package dev.local.catleaf.nativeapp.ui.discovery

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.catalog.Availability
import dev.local.catleaf.nativeapp.data.catalog.Catalog
import dev.local.catleaf.nativeapp.data.catalog.CatalogEntry
import dev.local.catleaf.nativeapp.data.catalog.CatalogQuery
import dev.local.catleaf.nativeapp.data.catalog.CatalogRepository
import dev.local.catleaf.nativeapp.data.catalog.CatalogView
import dev.local.catleaf.nativeapp.data.catalog.filterCatalog
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.currentScopeKey
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.Monogram
import dev.local.catleaf.nativeapp.ui.theme.interactiveOutline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 发现订阅源（原型 26–28；C03/C04，UIR06–08）：目录全部本地（assets 静态清单），不发网络、不抓 favicon。
 * 默认「精选」按目录固定顺序；搜索、分类与来源状态组合过滤；已订阅按**活动范围**规范化 URL 判断。
 * 「添加」只进入现有添加表单（复用共同新增与验证），不直接写入。
 *
 * 查询条件与列表位置放在 ViewModel（Activity 作用域）：看完详情、从添加表单回来都停在原处。
 */
class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)
    private val repo = CatalogRepository(application)
    val catalog: Catalog = repo.load()

    private val _subscribedUrls = MutableStateFlow<Set<String>>(emptySet())
    val subscribedUrls: StateFlow<Set<String>> = _subscribedUrls.asStateFlow()

    private val _query = MutableStateFlow(CatalogQuery())
    val query: StateFlow<CatalogQuery> = _query.asStateFlow()

    /** 离开发现页时的列表位置（首个可见项与偏移） */
    var savedScroll: Pair<Int, Int> = 0 to 0

    private var session: Int = Int.MIN_VALUE

    /**
     * 每次从外部（阅读空态、管理页、引导）新打开发现页换一个 [token]：条件与滚位回到默认；
     * 看详情、从添加流程回来用同一 token，保留原搜索与位置。须在读取 [query] 之前调用。
     */
    fun enterSession(token: Int) {
        if (token == session) return
        session = token
        _query.value = CatalogQuery()
        savedScroll = 0 to 0
    }

    fun refreshSubscribed() {
        viewModelScope.launch {
            val key = settings.currentScopeKey()
            _subscribedUrls.value = db.feeds().list(key).map { EndpointPolicy.normalizeFeedUrl(it.url) ?: it.url }.toSet()
        }
    }

    fun isSubscribed(entry: CatalogEntry, urls: Set<String>): Boolean {
        val normalized = EndpointPolicy.normalizeFeedUrl(entry.feedUrl) ?: entry.feedUrl
        return normalized in urls
    }

    fun update(transform: (CatalogQuery) -> CatalogQuery) {
        val next = transform(_query.value)
        if (next != _query.value) {
            _query.value = next
            savedScroll = 0 to 0
        }
    }

    fun rows(query: CatalogQuery): List<CatalogEntry> = filterCatalog(catalog, query)

    fun categoryName(id: String?): String? = id?.let { c -> catalog.categories.firstOrNull { it.first == c }?.second ?: c }
}

@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onBack: () -> Unit,
    /** 进入现有添加表单（预填地址与名称，复用共同新增；需配置条目先在详情说明约束） */
    onPrefillAdd: (CatalogEntry) -> Unit,
    /** 原型 26 头部「手动添加 / 导入 OPML」；为 null 时不显示该入口 */
    onManualAdd: (() -> Unit)? = null,
    onImportOpml: (() -> Unit)? = null,
) {
    val query by viewModel.query.collectAsState()
    val subscribed by viewModel.subscribedUrls.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshSubscribed() }

    var detail by remember { mutableStateOf<CatalogEntry?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(viewModel.savedScroll.first, viewModel.savedScroll.second)
    DisposableEffect(listState) {
        onDispose { viewModel.savedScroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    // 系统返回：先关详情，再退出发现页；分类面板由面板自己先消费返回
    BackHandler { if (detail != null) detail = null else onBack() }

    val open = detail
    if (open != null) {
        DiscoveryDetailScreen(
            entry = open,
            categoryName = viewModel.categoryName(open.categoryId).orEmpty(),
            subscribed = viewModel.isSubscribed(open, subscribed),
            onBack = { detail = null },
            onAdd = {
                detail = null
                onPrefillAdd(open)
            },
        )
        return
    }

    val rows = remember(query, subscribed) {
        viewModel.rows(query).map { it to viewModel.isSubscribed(it, subscribed) }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        DiscoveryList(
            viewModel = viewModel,
            query = query,
            rows = rows,
            listState = listState,
            onManualAdd = onManualAdd,
            onImportOpml = onImportOpml,
            onOpenCategories = { showCategories = true },
            onOpenDetail = { detail = it },
            onAdd = { entry ->
                // 需配置源先看说明，不盲发请求；可直接订阅的进入同一添加表单
                if (entry.status == Catalog.STATUS_NEEDS_CONFIG) detail = entry else onPrefillAdd(entry)
            },
        )
    }

    if (showCategories) {
        CategorySheet(
            catalog = viewModel.catalog,
            query = query,
            onSelectCategory = { id ->
                viewModel.update { it.toggleCategory(id) }
                showCategories = false
            },
            onToggleAvailability = { a -> viewModel.update { it.toggleAvailability(a) } },
            onDismiss = { showCategories = false },
        )
    }
}

@Composable
private fun DiscoveryList(
    viewModel: DiscoveryViewModel,
    query: CatalogQuery,
    rows: List<Pair<CatalogEntry, Boolean>>,
    listState: LazyListState,
    onManualAdd: (() -> Unit)?,
    onImportOpml: (() -> Unit)?,
    onOpenCategories: () -> Unit,
    onOpenDetail: (CatalogEntry) -> Unit,
    onAdd: (CatalogEntry) -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            DiscoveryHeader(
                query = query,
                categoryLabel = viewModel.categoryName(query.categoryId),
                onQuery = { text -> viewModel.update { it.copy(text = text) } },
                onManualAdd = onManualAdd,
                onImportOpml = onImportOpml,
                onFeatured = { viewModel.update { it.featured() } },
                onAll = { viewModel.update { it.all() } },
                onOpenCategories = onOpenCategories,
            )
        }
        item(key = "count") {
            Text(
                countLine(query, viewModel.categoryName(query.categoryId), rows.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        stringResource(R.string.discovery_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (query.hasConditions) {
                        TextButton(onClick = { viewModel.update { it.cleared() } }) {
                            Text(stringResource(R.string.discovery_clear_conditions))
                        }
                    }
                }
            }
        }
        items(rows.size, key = { rows[it].first.id }) { i ->
            val (entry, isSubscribed) = rows[i]
            SourceRow(
                entry = entry,
                categoryName = viewModel.categoryName(entry.categoryId).orEmpty(),
                subscribed = isSubscribed,
                onOpen = { onOpenDetail(entry) },
                onAdd = { onAdd(entry) },
            )
            HorizontalDivider(Modifier.padding(start = 80.dp))
        }
    }
}

/** 原型 26 头部：短说明 → 搜索 → 手动添加/导入 OPML → 精选/全部/全部分类（首屏可达） */
@Composable
private fun DiscoveryHeader(
    query: CatalogQuery,
    categoryLabel: String?,
    onQuery: (String) -> Unit,
    onManualAdd: (() -> Unit)?,
    onImportOpml: (() -> Unit)?,
    onFeatured: () -> Unit,
    onAll: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            stringResource(R.string.discovery_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        // 本地过滤，不上传输入
        OutlinedTextField(
            value = query.text,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.discovery_search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = if (query.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.discovery_search_clear))
                    }
                }
            } else null,
            singleLine = true,
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = interactiveOutline()),
            modifier = Modifier.fillMaxWidth(),
        )
        if (onManualAdd != null || onImportOpml != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                onManualAdd?.let { HeaderAction(R.drawable.ic_plus, R.string.reading_manual_add, it) }
                onImportOpml?.let { HeaderAction(R.drawable.ic_upload, R.string.opml_import, it) }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            ViewTab(stringResource(R.string.discovery_featured), query.view == CatalogView.Featured, onFeatured)
            ViewTab(stringResource(R.string.discovery_all), query.view == CatalogView.All && query.categoryId == null, onAll)
            ViewTab(
                label = categoryLabel ?: stringResource(R.string.discovery_all_categories),
                selected = query.categoryId != null,
                onClick = onOpenCategories,
                trailingIcon = R.drawable.ic_chevron_down,
            )
        }
    }
}

@Composable
private fun HeaderAction(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ViewTab(label: String, selected: Boolean, onClick: () -> Unit, trailingIcon: Int? = null) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
        trailingIcon = trailingIcon?.let { { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp)) } },
        shape = ControlShape,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = interactiveOutline(),
            selectedBorderColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp),
    )
}

/** 一行目录条目（原型 26）：中性字标、名称、纯文本说明、分类/语言/来源状态，右侧添加或已订阅 */
@Composable
private fun SourceRow(
    entry: CatalogEntry,
    categoryName: String,
    subscribed: Boolean,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(entry.name)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                entry.plainDescription.ifBlank { hostLabel(entry) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metaLine(entry, categoryName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        if (subscribed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.discovery_subscribed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            val description = stringResource(R.string.discovery_add_named, entry.name)
            OutlinedButton(
                onClick = onAdd,
                shape = ControlShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, interactiveOutline()),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
            ) {
                Text(stringResource(R.string.discovery_add), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal fun hostLabel(entry: CatalogEntry): String =
    runCatching { java.net.URI(entry.feedUrl).host }.getOrNull() ?: entry.siteUrl

@Composable
internal fun languageLabel(code: String): String = when (code.lowercase()) {
    "zh" -> stringResource(R.string.discovery_lang_zh)
    "en" -> stringResource(R.string.discovery_lang_en)
    else -> code
}

@Composable
internal fun availabilityLabel(entry: CatalogEntry): String = stringResource(
    if (entry.status == Catalog.STATUS_NEEDS_CONFIG) R.string.discovery_needs_config else R.string.discovery_ready,
)

@Composable
internal fun availabilityLabel(value: Availability): String = stringResource(
    if (value == Availability.NeedsConfig) R.string.discovery_needs_config else R.string.discovery_ready,
)

@Composable
private fun metaLine(entry: CatalogEntry, categoryName: String): String =
    listOf(categoryName, languageLabel(entry.language), availabilityLabel(entry))
        .filter { it.isNotBlank() }
        .joinToString(" · ")

@Composable
private fun countLine(query: CatalogQuery, categoryName: String?, count: Int): String {
    val base = when {
        query.isSearching -> stringResource(R.string.discovery_count_search, count)
        query.view == CatalogView.Featured -> stringResource(R.string.discovery_count_featured, count)
        else -> stringResource(R.string.discovery_count_all, categoryName ?: stringResource(R.string.discovery_all), count)
    }
    val status = query.availability?.let { availabilityLabel(it) }
    return listOfNotNull(base, status).joinToString(" · ")
}
