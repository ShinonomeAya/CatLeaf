package dev.local.catleaf.nativeapp.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.ui.theme.warningColor
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import dev.local.catleaf.nativeapp.ui.theme.ScopeChip
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.SecondaryButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import dev.local.catleaf.nativeapp.ui.theme.RootHeader
import dev.local.catleaf.nativeapp.ui.theme.rootSidePadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P02 阅读列表，按原型 01（正常）、20（空态）、21（离线）、08（源选择面板）实现。
 *
 * 关键规则：
 * - 列表永远来自本地，刷新期间旧内容继续可读；
 * - 离线/失败用**不遮挡内容**的提示条，并显示“上次成功更新”而不是假的实时时间；
 * - 空态按真实原因区分（未加载 / 该日无内容 / 已加载范围内无未读）；
 * - 未读数只统计已加载文章，并在页脚标清范围。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    viewModel: ReadingViewModel,
    onOpenArticle: (String) -> Unit,
    onOpenDigest: (feedId: String?, date: String?) -> Unit,
    /** 订阅面板的「管理订阅」按钮（T22 F04）：关闭面板后进入管理页，返回原阅读位置 */
    onOpenManageSubscriptions: () -> Unit,
    /** 阅读空态进入发现订阅源（L04，原型 25） */
    onOpenDiscovery: () -> Unit = {},
    /** 无订阅空态直接进入添加表单 / OPML 选择，结束回阅读页（UIR03，原型 25） */
    onOpenManualAdd: () -> Unit = {},
    onOpenOpmlImport: () -> Unit = {},
    /** 头部搜索（Q01/原型 35） */
    onOpenSearch: (String?, ArticleFilter.DateScope, Boolean) -> Unit = { _, _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSourcePanel by remember { mutableStateOf(false) }
    var showDateScopePanel by remember { mutableStateOf(false) }
    // 指定日期筛选（T22 F05）：Material 日期选择器与摘要页同一组件
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start() }

    if (state.notConnected) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.reading_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 列表位置：离开再回来（以及进程恢复）要回到原处，锚点是「文章 id + 偏移」而不是下标
    val listState = rememberLazyListState()
    val articles = state.visible
    var restored by remember(state.currentFeedId, state.allFeeds) { mutableStateOf(false) }

    LaunchedEffect(articles.map { it.entity.localId }.take(1), restored) {
        if (restored || articles.isEmpty()) return@LaunchedEffect
        val anchor = viewModel.savedScrollAnchor()
        if (anchor != null) {
            val index = ScrollAnchor.indexOfArticle(articles.map { it.entity.localId }, anchor.localId)
            if (index != null) restoreListAnchor(listState, index, anchor)
            else listState.scrollToItem(0)
        } else listState.scrollToItem(0)
        restored = true
    }

    LaunchedEffect(listState, restored) {
        // 滚动停下时记一次；键就是文章 id，界面据此在下次进入时定位。
        // 必须等恢复动作做完再开始记：进入页面时列表先停在顶部，
        // 那次 isScrollInProgress=false 会把“顶部”当成用户位置写回去，把锚点冲掉。
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) return@collect
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && isArticleKey(it.key as String) }
                    ?: return@collect
                viewModel.rememberScrollAnchor(first.key as String, first.offset)
            }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column {
                    val config = LocalConfiguration.current
                    val compactActions = config.screenWidthDp <= 360 || config.fontScale > 1.2f
                    var showMore by remember { mutableStateOf(false) }
                    RootHeader(stringResource(R.string.tab_reading)) {
                        // 日摘要入口固定放在头部、首屏可见（T22 F10）：
                        // 之前它在横滑筛选栏末端，正常操作根本发现不了。带当前筛选日期进入范围预览，不自动调用 AI。
                        if (state.currentFeedId != null || state.allFeeds && !state.noFeeds) {
                            val digestDate = when (val s = state.criteria.scope) {
                                is ArticleFilter.DateScope.Today ->
                                    dev.local.catleaf.nativeapp.data.article.DateNormalizer.localDateOf(
                                        java.time.Instant.now().toString(),
                                    )
                                is ArticleFilter.DateScope.On -> s.localDate
                                else -> null
                            }
                            val openDigest = { if (state.allFeeds) showSourcePanel = true else onOpenDigest(state.currentFeedId, digestDate) }
                            if (compactActions) IconButton(onClick = openDigest) {
                                Icon(painterResource(R.drawable.ic_calendar_days), contentDescription = stringResource(R.string.reading_daily_digest))
                            } else TextButton(onClick = openDigest) {
                                Text(stringResource(R.string.reading_daily_digest))
                            }
                        }
                        // 原型 25：无订阅时没有可搜或可刷新的内容，头部只留标题
                        if (!state.noFeeds) IconButton(onClick = {
                            onOpenSearch(state.currentFeedId, state.criteria.scope, state.criteria.unreadOnly)
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_search),
                                contentDescription = stringResource(R.string.search_hint_query),
                            )
                        }
                        if (!state.noFeeds && compactActions) Box {
                            IconButton(onClick = { showMore = true }) {
                                Icon(painterResource(R.drawable.ic_ellipsis), contentDescription = stringResource(R.string.reader_more))
                            }
                            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reading_refresh)) },
                                    onClick = { showMore = false; viewModel.refresh() },
                                )
                            }
                        }
                        if (!state.noFeeds && !compactActions) IconButton(onClick = viewModel::refresh) {
                            Icon(
                                painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.reading_refresh),
                            )
                        }
                    }

                    // 当前源 + 展开源选择面板（原型 01/08）；无订阅时不显示，避免与空态标题重复（原型 25）
                    if (!state.noFeeds) Surface(
                        onClick = { showSourcePanel = true },
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        // 触摸区至少 48 dp（设计规范 §4）：这行文字本身只有 26 dp 高
                        modifier = Modifier.padding(horizontal = rootSidePadding()).heightIn(min = 48.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.allFeeds) stringResource(R.string.reading_all_subscriptions) else state.currentFeed?.title ?: stringResource(R.string.reading_no_source),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(6.dp))
                            Icon(
                                painterResource(R.drawable.ic_chevron_down),
                                contentDescription = stringResource(R.string.reading_choose_source),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // 离线/失败的提示条：说明原因，不遮挡内容（原型 21）
            state.offlineNotice?.let { notice ->
                item(key = "notice") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            // 设计规范：网络不可用/刷新失败用警告色 + 图标 + 文字，不用错误红（旧内容仍可读）
                            .background(warningColor().copy(alpha = 0.12f), MaterialTheme.shapes.medium)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_wifi_off),
                            contentDescription = null,
                            tint = warningColor(),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(notice, style = MaterialTheme.typography.labelLarge, color = warningColor())
                    }
                }
            }

            item(key = "status") {
                Text(
                    text = if (state.noFeeds) {
                        if (state.isLocalScope) stringResource(R.string.reading_local_scope) else ""
                    } else {
                        statusLine(state)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                if (state.allFeeds && state.refreshing) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reading_all_refresh_progress, state.refreshCompleted, state.refreshTotal), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = viewModel::stopAllRefresh) { Text(stringResource(R.string.reading_stop_refresh)) }
                }
            }

            // 无订阅时不显示筛选与页脚（原型 25）；此时没有文章，不影响锚点恢复
            if (!state.noFeeds) item(key = "filters") {
                // 2026-10-09 选定C：日期与未读独立组合，日期选项在面板中展开。
                val scope = state.criteria.scope
                Column(Modifier.padding(horizontal = rootSidePadding(), vertical = 4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScopeChip(
                            label = dateFilterLabel(scope),
                            selected = scope != ArticleFilter.DateScope.All,
                            trailingIcon = R.drawable.ic_chevron_down,
                        ) { showDateScopePanel = true }
                        ScopeChip(
                            label = stringResource(R.string.reading_unread_only),
                            selected = state.criteria.unreadOnly,
                            onClick = viewModel::toggleUnreadOnly,
                        )
                    }
                    val filtered = scope != ArticleFilter.DateScope.All || state.criteria.unreadOnly
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.allFeeds) stringResource(R.string.reading_all_visible_count, state.visible.size, state.matchingSavedCount)
                            else stringResource(R.string.reading_visible_count, state.visible.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (filtered) TextButton(onClick = viewModel::clearFilters) {
                            Text(stringResource(R.string.reading_clear_filters), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            val visible = state.visible
            if (state.noFeeds) {
                item(key = "empty") {
                    NoFeedsEmpty(
                        onOpenDiscovery = onOpenDiscovery,
                        onOpenManualAdd = onOpenManualAdd,
                        onOpenOpmlImport = onOpenOpmlImport,
                    )
                }
            } else if (visible.isEmpty() && state.feedsLoaded) {
                item(key = "empty") {
                    // 有源但当前源/筛选下没内容：按真实原因说明，筛选在起作用时给「清除筛选」
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (state.allFeeds && state.criteria != ArticleFilter.Criteria()) stringResource(R.string.reading_all_empty_match) else emptyText(state.emptyReason),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        val filtered = state.criteria.scope != ArticleFilter.DateScope.All || state.criteria.unreadOnly
                        if (filtered && state.emptyReason != ArticleFilter.EmptyReason.NoContentLoaded) {
                            Spacer(Modifier.size(8.dp))
                            TextButton(onClick = viewModel::clearFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.reading_clear_filters))
                            }
                        }
                    }
                }
            }

            items(visible, key = { it.entity.localId }) { row ->
                ArticleRow(row, onClick = { onOpenArticle(row.entity.localId) }, showSource = state.allFeeds)
                Divider(modifier = Modifier.padding(start = 24.dp))
            }

            if (!state.noFeeds) item(key = "footer") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 标清范围：这里的篇数与未读数都只针对“已加载”内容（契约 P02）
                    Text(
                        text = stringResource(R.string.reading_loaded_summary, visible.size, visible.count { !it.entity.isRead }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if ((state.isLocalScope || state.allFeeds) && visible.isNotEmpty() && !state.hasMoreHistory) {
                        // 本机范围没有远端历史可补（契约 8.2）
                        Text(
                            text = stringResource(if (state.allFeeds) R.string.reading_all_history_end else R.string.reading_local_history_end),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.hasMoreHistory) {
                        OutlinedButton(onClick = viewModel::loadMoreHistory, enabled = !state.loadingMore && (state.allFeeds || !state.refreshing)) {
                            Text(
                                stringResource(
                                    if (state.loadingMore) R.string.reading_loading_more else R.string.reading_load_more,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSourcePanel) {
        SourcePanel(
            state = state,
            onSelectAll = { showSourcePanel = false; viewModel.selectAllFeeds() },
            onSelect = { feedId ->
                showSourcePanel = false
                viewModel.selectFeed(feedId)
            },
            onDismiss = { showSourcePanel = false },
            onManage = {
                // T22 F04：之前这个按钮只关面板，点了像没反应。现在关面板并进管理页；
                // 返回时列表按既有锚点（文章 id + 偏移）回到原位置
                showSourcePanel = false
                onOpenManageSubscriptions()
            },
        )
    }

    if (showDateScopePanel) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val maxListHeight = with(LocalDensity.current) {
            (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.55f).toDp()
        }
        val scope = state.criteria.scope
        val selected = when (scope) {
            ArticleFilter.DateScope.All -> 0
            ArticleFilter.DateScope.Today -> 1
            is ArticleFilter.DateScope.On -> 2
            ArticleFilter.DateScope.Unknown -> 3
        }
        ModalBottomSheet(onDismissRequest = { showDateScopePanel = false }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reading_choose_date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showDateScopePanel = false }) {
                        Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.action_cancel))
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = maxListHeight).verticalScroll(rememberScrollState()).selectableGroup().padding(top = 12.dp, bottom = 16.dp)) {
                    listOf(R.string.filter_state_all, R.string.reading_scope_today, R.string.reading_scope_pick_date, R.string.reading_scope_unknown).forEachIndexed { index, label ->
                        Surface(
                            color = if (selected == index) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
                            modifier = Modifier.fillMaxWidth().selectable(selected = selected == index, role = Role.RadioButton) {
                                showDateScopePanel = false
                                when (index) {
                                    0 -> viewModel.setScope(ArticleFilter.DateScope.All)
                                    1 -> viewModel.setScope(ArticleFilter.DateScope.Today)
                                    2 -> showDatePicker = true
                                    3 -> viewModel.setScope(ArticleFilter.DateScope.Unknown)
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painterResource(if (index == 3) R.drawable.ic_circle_question_mark else R.drawable.ic_calendar_days),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.size(12.dp))
                                Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.size(8.dp))
                                if (selected == index) Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = (state.criteria.scope as? ArticleFilter.DateScope.On)?.localDate?.let {
            java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        })
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        pickerState.selectedDateMillis?.let { millis ->
                            // M3 选择器返回 UTC 午夜的毫秒：按 UTC 取日期，不受设备偏移二次挪动（与摘要页同一换算）
                            viewModel.setScope(
                                ArticleFilter.DateScope.On(
                                    java.time.Instant.ofEpochMilli(millis)
                                        .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                                ),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.digest_date_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.digest_date_cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }
}

/** 原型 25（UIR03）：插画 + 标题 + 说明，主操作「发现订阅源」，次要「手动添加」，文字「导入 OPML」 */
@Composable
private fun NoFeedsEmpty(onOpenDiscovery: () -> Unit, onOpenManualAdd: () -> Unit, onOpenOpmlImport: () -> Unit) {
    // 按 App 实际主题（不是系统主题）选插画
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(if (dark) R.drawable.illus_empty_reading_dark else R.drawable.illus_empty_reading_light),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(0.5f).aspectRatio(240f / 200f),
        )
        Spacer(Modifier.size(24.dp))
        Text(
            stringResource(R.string.reading_no_feeds),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.reading_no_feeds_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(24.dp))
        PrimaryButton(stringResource(R.string.discovery_title), onClick = onOpenDiscovery)
        Spacer(Modifier.size(12.dp))
        SecondaryButton(stringResource(R.string.reading_manual_add), onClick = onOpenManualAdd)
        Spacer(Modifier.size(4.dp))
        TextButton(onClick = onOpenOpmlImport, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.opml_import), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun dateFilterLabel(scope: ArticleFilter.DateScope): String = when (scope) {
        is ArticleFilter.DateScope.All -> stringResource(R.string.filter_state_all)
        is ArticleFilter.DateScope.Today -> stringResource(R.string.reading_scope_today)
        is ArticleFilter.DateScope.On -> scopeDateLabel(scope.localDate)
        is ArticleFilter.DateScope.Unknown -> stringResource(R.string.reading_scope_unknown)
}

/** 筛选 chip 上显示选定日期（M月d日）；解析失败原样显示，不猜 */
private fun scopeDateLabel(localDate: String): String {
    val d = runCatching { java.time.LocalDate.parse(localDate) }.getOrNull() ?: return localDate
    return "${d.monthValue}月${d.dayOfMonth}日"
}

/** 列表里的文章项键形如 `g:`/`l:`（见 ArticleIdentity.localIdFor），用它区分表头等固定项 */
private fun isArticleKey(key: String): Boolean = key.startsWith("g:") || key.startsWith("l:")

/**
 * 把「文章下标」换算成 LazyColumn 下标并恢复位置。
 * 表头/提示条/状态/筛选在主流程里排在文章之前，这里先按 3 + 提示条 估算，
 * 再按可见键校正一次（提示条出现或消失会让前导条目数变化）。
 */
private suspend fun restoreListAnchor(
    listState: LazyListState,
    articleIndex: Int,
    anchor: ScrollAnchor.ListAnchor,
) {
    val guess = articleIndex + 3
    // scrollToItem 的 scrollOffset 与 LazyListItemInfo.offset **符号相反**：
    // 保存下来的是「首项顶部露出视野多少（负值）」，而 scrollToItem 要求正值表示向上推出视野
    val scrollOffset = -anchor.offset
    listState.scrollToItem(guess, scrollOffset)
    // 前导条目（提示条）数量可能变化：用可见键校正一次
    val actual = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.localId }?.index ?: return
    val delta = actual - guess
    if (delta != 0) listState.scrollToItem(guess + delta, scrollOffset)
}

/** 状态行：在线显示“已获取 N 篇 · 更新于 HH:mm”，离线显示上次成功更新（原型 21） */
@Composable
private fun statusLine(state: ReadingUiState): String {
    val count = if (state.allFeeds) state.matchingSavedCount else state.loaded.size
    val time = state.lastSuccessAt ?: state.currentFeed?.lastSuccessAt
    return if (state.offlineNotice != null) {
        if (time != null) {
            stringResource(R.string.reading_last_success, formatTime(time), count)
        } else {
            stringResource(R.string.reading_offline_no_time, count)
        }
    } else {
        if (time != null) {
            stringResource(R.string.reading_fetched_updated, count, formatTime(time))
        } else {
            stringResource(R.string.reading_fetched, count)
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun emptyText(reason: ArticleFilter.EmptyReason): String = when (reason) {
    ArticleFilter.EmptyReason.NoContentLoaded -> stringResource(R.string.reading_empty_no_content)
    ArticleFilter.EmptyReason.NoUnreadInLoaded -> stringResource(R.string.reading_empty_no_unread)
    ArticleFilter.EmptyReason.NoTodayInLoaded -> stringResource(R.string.reading_empty_no_today)
    ArticleFilter.EmptyReason.NoContentOnDate -> stringResource(R.string.reading_empty_no_date)
    ArticleFilter.EmptyReason.NoUnknownDate -> stringResource(R.string.reading_empty_no_unknown)
    ArticleFilter.EmptyReason.NoMatchForFilter -> stringResource(R.string.reading_empty_no_match)
    ArticleFilter.EmptyReason.None -> ""
}

/**
 * 列表行（原型 01；T22 F09）：标题 + 短摘要 + 合并的一行元信息，缩略图靠右。
 * 摘要与封面复用刷新时已保存的数据（不逐条抓全文）；无图自然不留空框。
 */
@Composable
private fun ArticleRow(row: ArticleFilter.LoadedRow, onClick: () -> Unit, showSource: Boolean = false) {
    val article = row.entity
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 未读用圆点 + “未读”文字双重表达，不只依赖颜色（设计规范：状态不只靠颜色）
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(
                    color = if (article.isRead) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            row.summary?.let {
                Spacer(Modifier.size(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(6.dp))
            // 元信息合并为一行（时间 · 已读状态 · 收藏）：此前各占一行，与原型 01 不符
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildList {
                        if (showSource) add(article.feedTitle)
                        article.publishedAt?.let { add(formatTime(java.time.Instant.parse(it).toEpochMilli())) }
                        add(stringResource(if (article.isRead) R.string.reading_read else R.string.reading_unread))
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (article.isStarred) {
                    Spacer(Modifier.size(8.dp))
                    Icon(
                        painterResource(R.drawable.ic_nav_favorites_active),
                        contentDescription = stringResource(R.string.reading_starred),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        row.thumbnail?.let { url ->
            Spacer(Modifier.size(12.dp))
            ArticleThumbnail(url = url, articleUrl = row.entity.link)
        }
    }
}

/**
 * 列表缩略图（1:1、圆角 10 dp，设计规范 §4）：
 * 只走公开抓取通道（仅 HTTPS、逐跳校验、限额、无凭据，与正文图片同一策略），
 * 取不到就什么都不显示——文字保持对齐，不留破图或空框。
 * V08：按目标尺寸采样解码（56 dp 的行不整张解 2000px 大图），
 * 加 6 MB 内存 LRU——快速来回滚动/重组不对已见过的行重发请求。
 */
@Composable
private fun ArticleThumbnail(url: String, articleUrl: String?) {
    val targetPx = with(LocalDensity.current) { 56.dp.toPx() }.roundToInt()
    var bitmap by remember(url) { mutableStateOf(thumbnailCache.get(url)) }
    LaunchedEffect(url, articleUrl) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                dev.local.catleaf.nativeapp.data.network.PublicFetch()
                    .fetch(url, dev.local.catleaf.nativeapp.data.network.PublicFetch.MAX_IMAGE_BYTES, articleUrl)
                    .getOrNull()
                    ?.let { fetched ->
                        decodeSampledThumbnail(fetched.bytes, targetPx)?.also { thumbnailCache.put(url, it) }
                    }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
        )
    }
}

/** 6 MB 上限的缩略图内存缓存（V08）；sizeOf 按 RGBA 估算 */
private val thumbnailCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int = value.width * value.height * 4
}

/**
 * 两段式解码（V08）：先 bounds 取尺寸，选 2 的幂采样，只解到目标边长附近。
 * BitmapFactory 的 inSampleSize 向下取 2 的幂，结果是 [target, 2×target) 区间——够小且不糊。
 */
internal fun decodeSampledThumbnail(bytes: ByteArray, targetPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    return android.graphics.BitmapFactory
        .decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

/**
 * 源选择面板（原型 08，UIR11）：按分组列出源并显示组内数量，每行显示真实抓取状态；
 * 打开时滚到当前阅读源；列表单独滚动，「管理订阅」固定在面板底部，源再多也不会被挤出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePanel(
    state: ReadingUiState,
    onSelectAll: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val groups = remember(state.feeds) {
        state.feeds.groupBy { it.category.ifBlank { dev.local.catleaf.nativeapp.ui.feeds.FeedManagerUiState.UNGROUPED } }
            .toList()
    }
    // 第0项为全部订阅；随后每组1个标题+组内行。
    val currentIndex = remember(groups, state.currentFeedId) {
        var index = 1
        var found = -1
        for ((_, feeds) in groups) {
            index += 1
            val pos = feeds.indexOfFirst { it.feedId == state.currentFeedId }
            if (pos >= 0) {
                found = index + pos
                break
            }
            index += feeds.size
        }
        found
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        // 让当前源露出上一行的上下文，不贴在列表顶边
        if (currentIndex > 0) listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
    }
    // 列表高度上限按屏高取（约 55%），为标题与底部按钮留出位置；内容少时按内容收缩（T22 F07）
    val windowHeightPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val maxListHeight = with(androidx.compose.ui.platform.LocalDensity.current) { (windowHeightPx * 0.55f).toDp() }

    // 直接完全展开：半展开时底部「管理订阅」会落在屏外，固定按钮失去意义（VR3 实测）
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reading_choose_source),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.action_cancel))
                }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight)) {
                item(key = "all-subscriptions") {
                    Surface(onClick = onSelectAll, color = if (state.allFeeds) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                        shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape, modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_rss), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                            }
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.reading_all_subscriptions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (state.allFeeds) Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.reading_current_source), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                groups.forEach { (group, feeds) ->
                    item(key = "g-$group") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                feeds.size.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(feeds, key = { it.feedId }) { feed ->
                        SourceRow(
                            feed = feed,
                            selected = feed.feedId == state.currentFeedId,
                            onClick = { onSelect(feed.feedId) },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onManage,
                shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.reading_manage_subscriptions))
            }
        }
    }
}

@Composable
private fun SourceRow(feed: FeedEntity, selected: Boolean, onClick: () -> Unit) {
    val failed = feed.status == dev.local.catleaf.nativeapp.data.FeedStore.STATUS_FAILED
    // 状态只来自真实订阅行：失败 / 上次更新时间；两者都没有就不写副标题
    val status = when {
        failed -> stringResource(R.string.reading_source_failed)
        feed.lastSuccessAt != null -> stringResource(
            R.string.feed_status_updated,
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(feed.lastSuccessAt)),
        )
        else -> null
    }
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // 水平内边距补齐（T22 F06）：之前图标紧贴卡片左缘
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dev.local.catleaf.nativeapp.ui.theme.Monogram(feed.title, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(feed.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (failed) {
                            Icon(
                                painterResource(R.drawable.ic_circle_alert),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            if (selected) {
                // 选中态：图标 + 无障碍文字说明（状态不只靠颜色，但不再挤占文字区，T22 F06）
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.reading_current_source),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
