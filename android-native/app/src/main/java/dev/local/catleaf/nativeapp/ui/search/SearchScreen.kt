package dev.local.catleaf.nativeapp.ui.search

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.SearchSnippet
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.currentScopeKey
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.DateScopeChips
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.ScopeChip
import dev.local.catleaf.nativeapp.ui.theme.ToggleRow
import dev.local.catleaf.nativeapp.ui.theme.interactiveOutline
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 文章搜索（Q01/原型 35，契约 9.1）：标题与纯文本摘要；本地查询、数据库分页 50/页、
 * 输入防抖 250ms、序号防旧结果回写；绑定活动范围（切范围旧结果失效）；
 * **不写任何阅读状态**——点开文章的已读变更由详情页负责，返回后只重查显示。
 */
data class SearchUiState(
    val query: String = "",
    val results: List<ArticleEntity> = emptyList(),
    val total: Int = 0,
    val searching: Boolean = false,
    val tooLong: Boolean = false,
    /** 空态区分：空词（未开始）/ 无结果 */
    val hasQueried: Boolean = false,
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val dateScope: ArticleFilter.DateScope = ArticleFilter.DateScope.All,
    val hasMore: Boolean = false,
    val feedId: String? = null,
    val feeds: List<FeedEntity> = emptyList(),
    val bodyMode: Boolean = false,
    val snippets: Map<String, SearchSnippet> = emptyMap(),
    val error: String? = null,
    /** 范围说明行（原型 35）：true=本机使用，false=自有实例，null=尚未读到 */
    val localScope: Boolean? = null,
) {
    /** 是否有筛选在起作用：决定空态是否给「清除筛选」 */
    val hasFilters: Boolean
        get() = feedId != null || dateScope != ArticleFilter.DateScope.All || unreadOnly || starredOnly
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)

    /** 冻结的范围键：搜索与结果只归属发起时的范围，切范围即失效 */
    private var scopeKey: String = ""
    private var debounceJob: Job? = null
    private var searchJob: Job? = null
    /** 请求序号：防抖期间的旧回包不覆盖新结果 */
    private var requestId = 0

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Caller supplies the visible reading filters; favorites starts with its own filter. */
    fun open(feedId: String?, dateScope: ArticleFilter.DateScope, unreadOnly: Boolean, starredOnly: Boolean) {
        invalidate()
        _state.value = SearchUiState(feedId = feedId, dateScope = dateScope, unreadOnly = unreadOnly, starredOnly = starredOnly)
    }

    private fun invalidate() {
        requestId++
        debounceJob?.cancel()
        searchJob?.cancel()
    }

    fun onQueryChange(value: String) {
        invalidate()
        _state.value = _state.value.copy(
            query = value, results = emptyList(), snippets = emptyMap(), total = 0,
            hasMore = false, hasQueried = false, searching = value.isNotBlank(), error = null,
        )
        if (value.isBlank()) return
        debounceJob = viewModelScope.launch {
            delay(250)
            searchNow()
        }
    }

    fun toggleUnread() {
        _state.value = _state.value.copy(unreadOnly = !_state.value.unreadOnly)
        searchNow()
    }

    fun toggleStarred() {
        _state.value = _state.value.copy(starredOnly = !_state.value.starredOnly)
        searchNow()
    }

    fun setDate(scope: ArticleFilter.DateScope) {
        _state.value = _state.value.copy(dateScope = scope)
        searchNow()
    }

    fun setFeed(feedId: String?) {
        _state.value = _state.value.copy(feedId = feedId)
        searchNow()
    }

    fun toggleBody() {
        _state.value = _state.value.copy(bodyMode = !_state.value.bodyMode)
        searchNow()
    }

    fun clearFilters() {
        _state.value = _state.value.copy(
            feedId = null, dateScope = ArticleFilter.DateScope.All,
            unreadOnly = false, starredOnly = false,
        )
        searchNow()
    }

    /** 范围切换后旧结果立即失效（契约 9.1） */
    fun onEnter() {
        viewModelScope.launch {
            val key = runCatching { settings.currentScopeKey() }.getOrElse {
                _state.value = _state.value.copy(searching = false, error = it.message ?: "无法读取使用方式")
                return@launch
            }
            if (scopeKey.isNotEmpty() && key != scopeKey) {
                invalidate()
                scopeKey = key
                _state.value = SearchUiState(query = _state.value.query)
            } else {
                scopeKey = key
            }
            val feeds = runCatching { db.feeds().list(key) }.getOrElse {
                _state.value = _state.value.copy(searching = false, error = it.message ?: "无法读取订阅")
                return@launch
            }
            _state.value = _state.value.copy(feeds = feeds, localScope = key == LOCAL_SCOPE_KEY)
            if (_state.value.query.isNotBlank()) {
                searchNow(_state.value.results.size.coerceAtLeast(PAGE_SIZE), keepVisible = true)
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.searching) return
        invalidate()
        val token = requestId
        searchJob = viewModelScope.launch { runSearch(token, offset = s.results.size, pageSize = PAGE_SIZE) }
    }

    fun submitQuery() = searchNow()

    private fun searchNow(pageSize: Int = PAGE_SIZE, keepVisible: Boolean = false) {
        invalidate()
        if (!keepVisible) {
            _state.value = _state.value.copy(results = emptyList(), snippets = emptyMap(), total = 0, hasMore = false)
        }
        val token = requestId
        searchJob = viewModelScope.launch { runSearch(token, offset = 0, pageSize = pageSize) }
    }

    private suspend fun runSearch(token: Int, offset: Int, pageSize: Int) {
        val s = _state.value
        val normalized = SearchQuery.normalize(s.query)
        val frozenScope = scopeKey
        val queryText = normalized.getOrNull()

        if (queryText == null) {
            if (token == requestId && frozenScope == scopeKey) {
                _state.value = if (normalized.isFailure) {
                    s.copy(tooLong = true, hasQueried = true, results = emptyList(), total = 0, hasMore = false, searching = false, error = null)
                } else {
                    s.copy(searching = false, hasQueried = false, results = emptyList(), snippets = emptyMap(), total = 0, hasMore = false, error = null)
                }
            }
            return
        }

        _state.value = s.copy(searching = true, tooLong = false, error = null)
        try {
            val pattern = SearchQuery.likePattern(queryText)
            val dates = dateBounds(s.dateScope)
            val rows = db.articles().searchTitles(
                instanceKey = frozenScope,
                pattern = pattern,
                feedId = s.feedId,
                bodyMode = if (s.bodyMode) 1 else 0,
                unreadOnly = if (s.unreadOnly) 1 else 0,
                starredOnly = if (s.starredOnly) 1 else 0,
                dateMode = dates.mode,
                dayStart = dates.start,
                dayEnd = dates.end,
                limit = pageSize,
                offset = offset,
            )
            if (token != requestId || frozenScope != scopeKey) return
            val total = db.articles().countSearch(
                instanceKey = frozenScope,
                pattern = pattern,
                feedId = s.feedId,
                bodyMode = if (s.bodyMode) 1 else 0,
                unreadOnly = if (s.unreadOnly) 1 else 0,
                starredOnly = if (s.starredOnly) 1 else 0,
                dateMode = dates.mode, dayStart = dates.start, dayEnd = dates.end,
            )
            if (token != requestId || frozenScope != scopeKey) return
            val snippets = if (rows.isEmpty()) emptyList() else db.articles().searchSnippets(
                rows.map { it.localId }, queryText.lowercase(), if (s.bodyMode) 1 else 0,
            )
            if (token != requestId || frozenScope != scopeKey) return
            val merged = if (offset == 0) rows else _state.value.results + rows
            _state.value = _state.value.copy(
                searching = false, hasQueried = true, results = merged,
                snippets = (if (offset == 0) emptyMap() else _state.value.snippets) + snippets.associateBy { it.localId },
                total = total, hasMore = merged.size < total,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (token == requestId && frozenScope == scopeKey) {
                _state.value = _state.value.copy(
                    searching = false, hasQueried = true, error = error.message ?: "搜索失败，请重试",
                )
            }
        }
    }

    private data class DateBounds(val mode: String, val start: String?, val end: String?)

    /** 日期筛选→SQL 参数：today/on 由设备本地时区算日界（与列表筛选同一时区语义） */
    private fun dateBounds(scope: ArticleFilter.DateScope): DateBounds = when (scope) {
        is ArticleFilter.DateScope.All -> DateBounds("all", null, null)
        is ArticleFilter.DateScope.Unknown -> DateBounds("unknown", null, null)
        is ArticleFilter.DateScope.Today ->
            localDay(java.time.LocalDate.now().toString()).let { DateBounds("on", it.first, it.second) }
        is ArticleFilter.DateScope.On ->
            localDay(scope.localDate).let { DateBounds("on", it.first, it.second) }
    }

    /** ISO 日期→UTC 时间界；publishedAt 存 ISO-8601 UTC 字符串（字典序=时间序） */
    private fun localDay(localDate: String): Pair<String, String> {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.parse(localDate)
        return day.atStartOfDay(zone).toInstant().toString() to
            day.plusDays(1).atStartOfDay(zone).toInstant().toString()
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    listState: LazyListState,
) {
    val state by viewModel.state.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    val queryFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { viewModel.onEnter() }
    // 系统返回与左上返回一致，原先会直接退出 App
    BackHandler(onBack = onBack)
    val editQuery = {
        queryFocus.requestFocus()
        keyboard?.show()
        Unit
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        // 原型 35（UIR13）：独立标题行；输入框带搜索图标与清空；标题与输入固定，筛选随结果一起滚动
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.search_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = {
                Text(
                    stringResource(R.string.search_hint_query),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (state.query.isEmpty()) null else {
                {
                    IconButton(onClick = {
                        viewModel.onQueryChange("")
                        editQuery()
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_x),
                            contentDescription = stringResource(R.string.search_clear_query),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            singleLine = true,
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = interactiveOutline()),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submitQuery() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(queryFocus),
        )
        state.localScope?.let { local ->
            Text(
                stringResource(if (local) R.string.search_scope_local else R.string.search_scope_instance),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "filters") { SearchFilters(state, viewModel, onPickDate = { showDatePicker = true }) }
            if (state.searching && state.results.isEmpty()) {
                item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (state.error != null) {
                item(key = "error") {
                    Column(Modifier.padding(24.dp)) {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::submitQuery) { Text(stringResource(R.string.search_retry)) }
                    }
                }
            }
            if (!state.searching && state.error == null && state.results.isEmpty()) {
                item(key = "empty") {
                    when {
                        // 空输入 / 超长 / 非空零结果 分开（PRD：清空关键词显示输入引导；错误不显示为零结果）
                        !state.hasQueried -> SearchHint(stringResource(R.string.search_hint_empty))
                        state.tooLong -> SearchHint(stringResource(R.string.search_too_long))
                        else -> NoResults(state.hasFilters, onClear = viewModel::clearFilters, onEditQuery = editQuery)
                    }
                }
            }
            if (state.results.isNotEmpty()) {
                item(key = "count") {
                    Text(
                        stringResource(R.string.search_found, state.total),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(state.results.size, key = { state.results[it].localId }) { i ->
                val article = state.results[i]
                SearchResultRow(article, state.snippets[article.localId], state.query) { onOpenArticle(article.localId) }
                HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "footer") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.searching && state.results.isNotEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (state.hasMore) {
                        TextButton(onClick = viewModel::loadMore, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.search_load_more, state.total))
                        }
                    }
                    if (state.bodyMode && state.results.isNotEmpty()) {
                        Text(
                            stringResource(R.string.search_body_footer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    if (showDatePicker) {
        val picker = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    picker.selectedDateMillis?.let { millis ->
                        viewModel.setDate(ArticleFilter.DateScope.On(
                            java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                        ))
                    }
                }) { Text(stringResource(R.string.digest_date_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.digest_date_cancel)) } },
        ) { DatePicker(state = picker) }
    }
}

/** 原型 35/36：来源单选 → 日期单选 → 未读/收藏开关卡 → 独立「搜索正文」卡（文件图标 + 开关前后不同说明） */
@Composable
private fun SearchFilters(state: SearchUiState, viewModel: SearchViewModel, onPickDate: () -> Unit) {
    var showSources by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            ScopeChip(stringResource(R.string.search_all_feeds), state.feedId == null) { viewModel.setFeed(null) }
            if (state.feeds.isNotEmpty()) {
                Box {
                    ScopeChip(
                        label = state.feeds.firstOrNull { it.feedId == state.feedId }?.title
                            ?: stringResource(R.string.search_pick_source),
                        selected = state.feedId != null,
                        trailingIcon = R.drawable.ic_chevron_down,
                    ) { showSources = true }
                    DropdownMenu(expanded = showSources, onDismissRequest = { showSources = false }) {
                        state.feeds.forEach { feed ->
                            DropdownMenuItem(
                                text = { Text(feed.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = if (feed.feedId == state.feedId) {
                                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null,
                                onClick = {
                                    showSources = false
                                    viewModel.setFeed(feed.feedId)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        val scope = state.dateScope
        DateScopeChips(
            scope = scope,
            pickedDateLabel = (scope as? ArticleFilter.DateScope.On)?.localDate,
            onSelect = viewModel::setDate,
            onPickDate = onPickDate,
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalPadding = 16.dp,
        )
        FilterCard {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow(stringResource(R.string.reading_unread_only), state.unreadOnly, viewModel::toggleUnread)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(stringResource(R.string.search_starred_only), state.starredOnly, viewModel::toggleStarred)
            }
        }
        FilterCard {
            Row(Modifier.padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(R.drawable.ic_file_text)
                Spacer(Modifier.size(12.dp))
                ToggleRow(
                    label = stringResource(R.string.search_body_mode),
                    checked = state.bodyMode,
                    onToggle = viewModel::toggleBody,
                    supporting = stringResource(if (state.bodyMode) R.string.search_body_on else R.string.search_body_off),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FilterCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ControlShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) { content() }
}

/** 40dp 浅绿图标块（原型 35 文档图标、正文开关文件图标） */
@Composable
private fun IconTile(icon: Int) {
    Box(
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, ControlShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 结果行（原型 35/36）：文档图标块、标题高亮、命中片段、来源 · 日期 · 未读、命中位置；仅真实正文命中才显示徽标 */
@Composable
private fun SearchResultRow(article: ArticleEntity, snippet: SearchSnippet?, query: String, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconTile(R.drawable.ic_file_text)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                highlightedSnippet(article.title, query, highlight),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 标题命中时片段就是标题本身，不重复显示
            if (snippet != null && snippet.location != "title" && snippet.snippet.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    highlightedSnippet(snippet.snippet, query, highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                (article.feedTitle.ifBlank { article.feedUrl }) + " · " +
                    (article.publishedAt?.let(::shortDate) ?: stringResource(R.string.search_unknown_date)) +
                    if (article.isRead) "" else " · ${stringResource(R.string.search_unread)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            snippet?.let {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (it.location == "body") {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                stringResource(R.string.search_body_hit),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        stringResource(R.string.search_match_location, snippetLocation(it.location), it.position),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp).size(20.dp),
        )
    }
}

@Composable
private fun SearchHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
    )
}

/** 原型 37：非空关键词零结果——搜索图标、当前条件说明、有筛选时给「清除筛选」，始终可「修改关键词」 */
@Composable
private fun NoResults(hasFilters: Boolean, onClear: () -> Unit, onEditQuery: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.search_no_results), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(if (hasFilters) R.string.search_no_results_filters else R.string.search_no_results_plain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        if (hasFilters) PrimaryButton(stringResource(R.string.reading_clear_filters), onClear)
        TextButton(onClick = onEditQuery, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.search_edit_query), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 列表日期（原型 35「09-12」）：按设备时区的本地日期；跨年才带年份 */
private fun shortDate(iso: String): String = runCatching {
    val day = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val pattern = if (day.year == java.time.LocalDate.now().year) "MM-dd" else "yyyy-MM-dd"
    day.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}.getOrElse { iso.take(10) }

private fun snippetLocation(location: String): String = when (location) {
    "title" -> "标题"
    "summary" -> "摘要"
    else -> "正文"
}

private fun highlightedSnippet(text: String, term: String, background: Color) = buildAnnotatedString {
    val word = term.trim()
    val index = if (word.isEmpty()) -1 else text.indexOf(word, ignoreCase = true)
    if (index < 0) append(text) else {
        append(text.take(index))
        withStyle(SpanStyle(background = background)) { append(text.substring(index, index + word.length)) }
        append(text.substring(index + word.length))
    }
}
