package dev.local.catleaf.nativeapp.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.Role
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.SecondaryButton
import dev.local.catleaf.nativeapp.ui.theme.warningColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.catleaf.nativeapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P04 文章详情（原型 02）。T12 做了安全阅读与全文兜底，T13 加上已读与收藏。
 *
 * **滚动结构（T22 F01）**：只有紧凑工具栏固定；标题、元信息、封面与正文是**同一滚动容器**——
 * 原文与段落双语共用 WebView，页首由本地转义注入；旧版整篇缓存仍用 Compose 纯文本读取。
 * 长标题滚出视口后不再驻留，也不占阅读高度。
 *
 * 工具栏：返回 + 源名 + 已读/未读切换（操作必须常驻可达）；有译文时下方固定一行原文/译文切换。
 * 正文始终来自本地已存内容；只有点「获取全文」才发请求，失败保留原正文。
 */
@Composable
fun ReaderScreen(
    localId: String,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    /** 工具栏「Aa」进阅读偏好页（T22 F13，原型 02→16）：返回后停在原文章与原滚动位置 */
    onOpenPreferences: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 打开外链失败的提示（例如没有浏览器）；与提取失败提示分开展示
    var actionError by remember { mutableStateOf<String?>(null) }
    val noLinkHint = stringResource(R.string.reader_no_link_hint)
    val undoLabel = stringResource(R.string.action_undo)
    val snackbarHostState = remember { SnackbarHostState() }
    var showMore by remember { mutableStateOf(false) }
    var showOfflineConfirm by remember { mutableStateOf(false) }
    var showOfflineResult by remember { mutableStateOf(false) }
    var includeOfflineImages by remember { mutableStateOf(true) }
    val favoriteFocusRequester = remember { FocusRequester() }
    // Only this locally generated link can dispatch the manual action; article/model links cannot guess it.
    val retranslateUrl = remember(localId) { "https://${ReaderAssets.DOMAIN}/reader/retranslate?token=" + java.util.UUID.randomUUID() }
    // 分享 = 标题+链接的纯文本走系统选择器（P04 动作之一；不申请任何权限）
    val shareLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(localId) { viewModel.open(localId) }

    // 详情是整屏二级页：系统返回键与左上角返回同一行为，不能直接退出 App（契约 P04/设计规范 §6）
    BackHandler(enabled = !showOfflineConfirm && !showOfflineResult, onBack = onBack)

    // 收藏/未读的回执走 Snackbar：取消收藏可短时撤销（P04）
    val feedback = state.feedback
    LaunchedEffect(feedback) {
        if (feedback == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = feedback.text,
            actionLabel = if (feedback.action == FeedbackAction.UndoUnstar) undoLabel else null,
            withDismissAction = false,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoUnstar()
        viewModel.consumeFeedback()
    }

    if (showOfflineConfirm) {
        OfflineSaveConfirmScreen(
            state = state,
            includeImages = includeOfflineImages,
            onToggleImages = { includeOfflineImages = it },
            onBack = { showOfflineConfirm = false },
            // 38 无正文分支：回文章页并按用户这次点击获取全文（离线时该按钮不出现）
            onFetchFullText = {
                showOfflineConfirm = false
                viewModel.fetchFullText()
            },
            onSave = {
                viewModel.saveOffline(includeOfflineImages)
                showOfflineConfirm = false
                showOfflineResult = true
            },
        )
        return
    }
    if (showOfflineResult) {
        OfflineSaveResultScreen(
            state = state,
            onBack = { showOfflineResult = false },
            onCancel = viewModel::cancelOffline,
            onRetry = viewModel::retryOfflineImages,
            onOpenStorage = onOpenStorage,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 二级页整屏：内容避让系统栏（状态栏/导航栏/手势区）
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = state.feedTitle.ifBlank { stringResource(R.string.reader_unknown_source) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 已读/未读留在固定工具栏：页首随正文滚动（F01）后操作仍要常驻可达
                if (!state.loading && !state.missing) {
                    // UIR16：紧凑栏文字动作保持 48 dp 触摸高度
                    TextButton(
                        onClick = { if (state.isRead) viewModel.setUnread() else viewModel.setRead() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (state.isRead) R.string.reader_mark_unread else R.string.reader_mark_read,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Aa：不用退出文章去设置里调字号（T22 F13；原型 02→16）
                    TextButton(
                        onClick = onOpenPreferences,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                    ) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box {
                        // UIR16：「更多」用交付的 ellipsis 图标（48 dp IconButton），可访问名称仍为「更多」
                        IconButton(
                            onClick = { showMore = true },
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Tab || event.key == Key.DirectionDown)
                                ) {
                                    favoriteFocusRequester.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_ellipsis),
                                contentDescription = stringResource(R.string.reader_more),
                            )
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("保存离线") },
                                onClick = { showMore = false; showOfflineConfirm = true },
                            )
                            if (state.offlineSnapshot?.record?.status in setOf("partial", "interrupted")) DropdownMenuItem(
                                text = { Text("重试未保存图片") },
                                onClick = { showMore = false; showOfflineResult = true; viewModel.retryOfflineImages() },
                            )
                            if (state.savingOffline) DropdownMenuItem(
                                text = { Text("取消离线保存") },
                                onClick = { showMore = false; viewModel.cancelOffline() },
                            )
                        }
                    }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.missing -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.reader_missing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    state.offlineSnapshot?.let { saved ->
                        Text(
                            text = when (saved.record.status) {
                                "complete" -> "离线副本 · ${saved.record.imageSaved}/${saved.record.imageTotal} 张图片"
                                "text" -> "仅正文已保存"
                                "interrupted" -> "离线保存已中断 · 已存内容仍可读"
                                else -> "正文已保存 · 图片 ${saved.record.imageSaved}/${saved.record.imageTotal} 张"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    // 离线横幅（T22 V02）：说清在读已存内容、哪些动作要联网——禁用网络动作但不禁阅读
                    if (!state.isOnline) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_wifi_off),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                stringResource(R.string.reader_offline_banner),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 原文/译文切换是操作，固定在工具栏下方；标题与元信息随正文滚动（T22 F01）
                    if (state.canToggleTranslation) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !state.showTranslation,
                                    onClick = viewModel::showOriginal,
                                    label = { Text(stringResource(R.string.ai_tab_original)) },
                                )
                                FilterChip(
                                    selected = state.showTranslation,
                                    onClick = viewModel::showTranslation,
                                    label = { Text(stringResource(if (state.translationSegments == null) R.string.ai_tab_legacy_translation else R.string.ai_tab_translation)) },
                                )
                            }
                        }
                    }

                    val notice = actionError ?: state.notice
                    if (notice != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_circle_alert),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        val translation = state.translation
                        if (state.showTranslation && state.translationSegments == null && !translation.isNullOrBlank()) {
                            // 译文是纯文本：不渲染任何 HTML，也不给模型返回内容执行的机会。
                            // 页首块放滚动列顶部：与译文一起滚出视口（T22 F01）
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                TranslationStatus(state) { viewModel.translate(force = true) }
                                ReaderHeaderCompose(state)
                                SelectionContainer {
                                    Text(
                                        text = translation,
                                        // 译文用与正文同一用户字号与行距策略（T22 F18）：body 18/30 的比例
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = state.bodyFontSizeSp.sp,
                                            lineHeight = (state.bodyFontSizeSp * 30 / 18).sp,
                                        ),
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        } else if (state.bodyHtml != null) {
                            val article = remember(state.bodyHtml) { BilingualArticle.fromHtml(state.bodyHtml!!) }
                            val displayedBody = remember(article, state.showTranslation, state.translationSegments) {
                                article.render(state.translationSegments?.filterKeys { it != BilingualArticle.TITLE_ID }.takeIf { state.showTranslation })
                            }
                            // 页首块（封面/标题/元信息/书签提示）注入本地模板：与正文同一滚动容器（F01/F02）
                            key(localId) { ArticleBodyView(
                                articleUrl = state.link,
                                bodyHtml = displayedBody,
                                displayKey = state.bodyHash + if (state.showTranslation && state.translationSegments != null) ":bilingual" else ":original",
                                headerHtml = readerHeaderHtml(state, retranslateUrl),
                                onExternalUrl = { url ->
                                    if (url == retranslateUrl) {
                                        val current = viewModel.state.value
                                        if (dev.local.catleaf.nativeapp.data.local.CurrentBody.localId == localId &&
                                            current.bodyHash == state.bodyHash && !current.loading &&
                                            current.canToggleTranslation && !current.translating && current.isOnline) {
                                            viewModel.translate(force = true)
                                        }
                                    } else actionError = ExternalBrowser.open(context, url)
                                },
                                scrollAnchor = state.scrollAnchor,
                                onScrollPosition = { y, height -> viewModel.rememberScrollPosition(localId, y, height) },
                                fontSizeSp = state.bodyFontSizeSp,
                                // 正文跟 App 生效的调色板（P07 强制深色时不跟系统）：背景亮度即答案
                                darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
                                offlineSnapshot = state.offlineSnapshot,
                            ) }
                        } else {
                            // 没有任何可显示文本时也不能给空白页：说明情况并留原网页入口。
                            // 标题/书签说明仍要显示：放在可滚动的页首块里
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                ReaderHeaderCompose(state)
                                Text(
                                    text = stringResource(R.string.reader_no_content),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }

                    ActionsBar(
                        state = state,
                        favoriteFocusRequester = favoriteFocusRequester,
                        onToggleStar = {
                            actionError = null
                            viewModel.toggleStar()
                        },
                        onTranslate = {
                            actionError = null
                            viewModel.translate()
                        },
                        onExtract = {
                            actionError = null
                            viewModel.fetchFullText()
                        },
                        onShare = {
                            val link = state.link ?: return@ActionsBar
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, "${state.title}\n$link")
                            shareLauncher.launch(android.content.Intent.createChooser(send, null))
                        },
                        onOpenOriginal = {
                            val link = state.link
                            actionError = if (link == null) noLinkHint else ExternalBrowser.open(context, link)
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
        )
    }

}

/** 原型 38（UIR14）：单篇离线确认——预览、保存内容（正文固定、图片可选）、与收藏/缓存的关系、限额说明 */
@Composable
private fun OfflineSaveConfirmScreen(
    state: ReaderUiState,
    includeImages: Boolean,
    onToggleImages: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFetchFullText: () -> Unit,
    onSave: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OfflineTopBar(stringResource(R.string.offline_confirm_title), onBack)
        OfflineCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    feedDateLine(state.feedTitle, state.publishedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.bodyPreview.isNotBlank()) {
                    Text(
                        state.bodyPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (state.bookmarkOnly) {
            // 没有已取得正文：说明原因并给出路（回文章页由用户自己点获取全文，不在后台偷偷抓）
            OfflineCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OfflineInfoLine(
                        R.drawable.ic_circle_alert,
                        warningColor(),
                        stringResource(R.string.offline_no_body_title),
                        stringResource(if (state.isOnline) R.string.offline_no_body_hint else R.string.offline_no_body_offline),
                    )
                    if (state.isOnline) PrimaryButton(stringResource(R.string.offline_fetch_full_text), onFetchFullText)
                }
            }
        } else {
            Text(stringResource(R.string.offline_content_head), style = MaterialTheme.typography.labelLarge)
            OfflineCard {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    // 正文必存：勾选且不可取消，只作说明
                    OfflineCheckRow(
                        label = stringResource(R.string.offline_body_label),
                        supporting = stringResource(R.string.offline_body_chars, state.translatableChars),
                        checked = true,
                        enabled = false,
                        onToggle = {},
                    )
                    OfflineCheckRow(
                        label = stringResource(R.string.offline_images_label),
                        supporting = stringResource(R.string.offline_images_hint),
                        checked = includeImages,
                        enabled = !state.savingOffline,
                        onToggle = { onToggleImages(!includeImages) },
                    )
                    Text(
                        stringResource(R.string.offline_body_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                }
            }
        }
        OfflineCard {
            Box(Modifier.padding(16.dp)) {
                OfflineInfoLine(
                    R.drawable.ic_info,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.offline_relation_title),
                    stringResource(R.string.offline_relation_body),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.offline_save_action), onSave, enabled = !state.bookmarkOnly && !state.savingOffline)
        SecondaryButton(stringResource(R.string.offline_cancel), onBack)
        Text(
            stringResource(R.string.offline_limits_footer),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

/** 原型 39（UIR14）：保存结果——状态图标、结论、正文/图片/占用明细，按实际状态给重试或取消 */
@Composable
private fun OfflineSaveResultScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val saved = state.offlineSnapshot?.record
    val failed = state.notice?.startsWith("离线保存未完成") == true || state.notice?.startsWith("重试未完成") == true
    val cancelled = state.notice?.startsWith("已取消") == true
    val headline = when {
        state.savingOffline -> "正在保存离线内容"
        failed && saved != null -> "更新未完成，旧副本仍可读"
        saved == null -> "未成功保存"
        saved.status == "complete" -> "所选内容已保存"
        saved.status == "text" -> "仅正文已保存"
        saved.status == "interrupted" -> "保存已中断"
        else -> "正文已保存，图片未齐"
    }
    val missing = saved?.let { if (it.selectedImages) (it.imageTotal - it.imageSaved).coerceAtLeast(0) else 0 } ?: 0
    val ok = saved != null && !failed && (saved.status == "complete" || saved.status == "text")
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OfflineTopBar(stringResource(R.string.offline_result_title), onBack)
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tint = when {
                state.savingOffline || ok -> MaterialTheme.colorScheme.primary
                saved == null -> MaterialTheme.colorScheme.error
                else -> warningColor()
            }
            Box(
                Modifier.size(64.dp).background(tint.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (state.savingOffline) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        painterResource(if (ok) R.drawable.ic_circle_check else R.drawable.ic_circle_alert),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Text(headline, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(state.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                feedDateLine(state.feedTitle, state.publishedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        saved?.let {
            OfflineCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ResultRow(R.drawable.ic_circle_check, MaterialTheme.colorScheme.primary,
                        stringResource(R.string.offline_row_body), stringResource(R.string.offline_row_saved))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (it.selectedImages && it.imageTotal == 0) {
                        // 勾选了图片但正文里没有图片：不显示「0 / 0 张」
                        ResultRow(R.drawable.ic_info, MaterialTheme.colorScheme.onSurfaceVariant,
                            stringResource(R.string.offline_row_images), stringResource(R.string.offline_row_images_none))
                    } else if (it.selectedImages) {
                        ResultRow(
                            if (missing > 0) R.drawable.ic_circle_alert else R.drawable.ic_circle_check,
                            if (missing > 0) warningColor() else MaterialTheme.colorScheme.primary,
                            stringResource(R.string.offline_row_images),
                            stringResource(R.string.offline_row_images_count, it.imageSaved, it.imageTotal),
                            supporting = if (missing > 0) stringResource(R.string.offline_row_images_missing, missing) else null,
                        )
                    } else {
                        ResultRow(R.drawable.ic_info, MaterialTheme.colorScheme.onSurfaceVariant,
                            stringResource(R.string.offline_row_images), stringResource(R.string.offline_row_images_skipped))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ResultRow(R.drawable.ic_database, MaterialTheme.colorScheme.onSurfaceVariant,
                        stringResource(R.string.offline_row_size), dev.local.catleaf.nativeapp.ui.settings.formatSize(it.bytes))
                }
            }
        }
        // 结论已由标题与明细表达；只有失败/取消这类额外信息才单独显示
        if (failed || cancelled) {
            Text(
                state.notice.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            state.savingOffline -> PrimaryButton(stringResource(R.string.offline_cancel_requests), onCancel)
            missing > 0 -> PrimaryButton(stringResource(R.string.offline_retry_images), onRetry)
        }
        SecondaryButton(stringResource(R.string.offline_start_reading), onBack)
        TextButton(
            onClick = onOpenStorage,
            enabled = !state.savingOffline,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.offline_manage), style = MaterialTheme.typography.labelLarge) }
        // 只有部分保存/失败/中断时才说明「已保存的部分会保留」；完整保存时这句没有意义
        if (saved != null && !ok && !state.savingOffline) {
            Text(
                stringResource(R.string.offline_kept_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun OfflineTopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 0.dp)) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun OfflineCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ControlShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun OfflineInfoLine(icon: Int, tint: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(body, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 48dp 复选行：整行可点（Role.Checkbox），复选框本身不重复接收点击 */
@Composable
private fun OfflineCheckRow(label: String, supporting: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultRow(icon: Int, tint: androidx.compose.ui.graphics.Color, label: String, value: String, supporting: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.labelMedium, color = warningColor())
            }
        }
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun feedDateLine(feedTitle: String, publishedAt: String?): String =
    listOf(feedTitle, publishedLabel(publishedAt)).filter { it.isNotBlank() }.joinToString(" · ")

/** 内容来源标签：三种来源必须能区分（验收①），不是装饰 */
@androidx.annotation.StringRes
private fun sourceLabelRes(source: ContentSource): Int = when (source) {
    ContentSource.Offline -> R.string.reader_source_offline
    ContentSource.Rss -> R.string.reader_source_rss
    ContentSource.Extracted -> R.string.reader_source_extracted
    ContentSource.Summary -> R.string.reader_source_summary
    ContentSource.None -> R.string.reader_source_none
}

/** 书签态说明：明确区分「只存了摘要」与「只存了链接」（P04/P06） */
@Composable
private fun bookmarkHintText(state: ReaderUiState): String? = if (!state.bookmarkOnly) {
    null
} else {
    stringResource(
        if (state.summarySaved) R.string.reader_bookmark_summary_hint else R.string.reader_bookmark_link_only_hint,
    )
}

@Composable
private fun translationStatusText(state: ReaderUiState): String =
    stringResource(if (state.translationCached) R.string.ai_label_cached else R.string.ai_label_fresh)

@Composable
private fun translationHintText(state: ReaderUiState): String = stringResource(when {
    state.translationSegments == null -> R.string.ai_legacy_translation_hint
    state.title.isNotBlank() && BilingualArticle.TITLE_ID !in state.translationSegments -> R.string.ai_body_cache_hint
    else -> R.string.ai_retranslate_hint
})

@Composable
private fun TranslationStatus(state: ReaderUiState, onRetranslate: () -> Unit) {
    if (!state.canToggleTranslation) return
    Column(Modifier.padding(horizontal = 20.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = translationStatusText(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            TextButton(
                onClick = onRetranslate,
                // 重新翻译是新请求：离线禁用（禁用原因见离线横幅，T22 V02）
                enabled = !state.translating && state.isOnline,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.ai_retranslate),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            translationHintText(state),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 元信息行：发布时间 · 来源 · 字数 · 作者（合并一行，T22 F01 随正文滚动） */
@Composable
private fun readerMetaLine(state: ReaderUiState): String = buildList {
    add(publishedLabel(state.publishedAt))
    add(stringResource(sourceLabelRes(state.source)))
    if (state.translatableChars > 0) add(stringResource(R.string.reader_char_count, state.translatableChars))
    state.byline?.let { add(it) }
}.joinToString("  ·  ")

/**
 * 页首块的 WebView 版（T22 F01/F02；V03 顺序按原型 02：标题 → 元信息 → 封面 → 提示 → 正文）。
 * 全部经 [ReaderAssets.escapeHtml] 转义后由本地模板注入——外部文本绝不原样拼进 HTML。
 */
@Composable
private fun readerHeaderHtml(state: ReaderUiState, retranslateUrl: String): String {
    val meta = readerMetaLine(state)
    val hint = bookmarkHintText(state)
    val status = translationStatusText(state)
    val action = stringResource(R.string.ai_retranslate)
    val fee = translationHintText(state)
    return buildString {
        if (state.canToggleTranslation) {
            append("<div class=\"catleaf-translation-controls\"><div class=\"catleaf-translation-status\">")
                .append(ReaderAssets.escapeHtml(status))
            if (!state.translating && state.isOnline) {
                append("<a class=\"catleaf-retranslate\" role=\"button\" href=\"")
                    .append(ReaderAssets.escapeHtml(retranslateUrl)).append("\">")
                    .append(ReaderAssets.escapeHtml(action)).append("</a>")
            } else append("<span class=\"catleaf-retranslate catleaf-meta\" aria-disabled=\"true\">")
                .append(ReaderAssets.escapeHtml(action)).append("</span>")
            append("</div><div class=\"catleaf-meta\">").append(ReaderAssets.escapeHtml(fee)).append("</div></div>")
        }
        append("<h1 class=\"catleaf-title\">").append(ReaderAssets.escapeHtml(state.title)).append("</h1>")
        if (state.showTranslation) state.translationSegments?.get(BilingualArticle.TITLE_ID)?.let {
            append("<div class=\"catleaf-translation catleaf-title-translation\">")
                .append(ReaderAssets.escapeHtml(it)).append("</div>")
        }
        append("<div class=\"catleaf-meta\">").append(ReaderAssets.escapeHtml(meta)).append("</div>")
        state.coverUrl?.let {
            append("<img class=\"catleaf-cover\" src=\"").append(ReaderAssets.escapeHtml(it)).append("\" alt=\"\">")
        }
        if (hint != null) append("<div class=\"catleaf-hint\">").append(ReaderAssets.escapeHtml(hint)).append("</div>")
    }
}

/** 页首块的 Compose 版：译文/无正文两种滚动视图用（文章主标题 28/36，元信息 13/20） */
@Composable
private fun ReaderHeaderCompose(state: ReaderUiState) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = readerMetaLine(state),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        bookmarkHintText(state)?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 底部操作栏（原型 02）：收藏 / 获取全文（按需）/ 原网页 */
@Composable
private fun ActionsBar(
    state: ReaderUiState,
    favoriteFocusRequester: FocusRequester,
    onToggleStar: () -> Unit,
    onTranslate: () -> Unit,
    onExtract: () -> Unit,
    onShare: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(
                iconRes = if (state.isStarred) R.drawable.ic_nav_favorites_active else R.drawable.ic_nav_favorites,
                labelRes = if (state.isStarred) R.string.reader_starred else R.string.reader_star,
                enabled = true,
                tinted = state.isStarred,
                onClick = onToggleStar,
                modifier = Modifier.weight(1f).focusRequester(favoriteFocusRequester),
            )

            if (state.translatableChars > 0) {
                BarAction(
                    iconRes = R.drawable.ic_languages,
                    labelRes = if (state.translating) R.string.ai_translating else R.string.ai_translate,
                    // 离线：已有译文可切换（无网络），没有译文才禁用（T22 V02）
                    enabled = !state.translating && (state.isOnline || state.canToggleTranslation),
                    tinted = state.canToggleTranslation,
                    onClick = onTranslate,
                    modifier = Modifier.weight(1f),
                )
            }

            // 已经有提取全文就不再重复提供；没有原文链接时也不显示（不能凭空合成地址）
            if (state.source != ContentSource.Extracted && state.link != null) {
                BarAction(
                    iconRes = R.drawable.ic_file_text,
                    labelRes = if (state.extracting) {
                        R.string.reader_getting_full_text_short
                    } else {
                        R.string.reader_get_full_text
                    },
                    // 获取全文必须联网；禁用原因见离线横幅（T22 V02）
                    enabled = !state.extracting && state.isOnline,
                    tinted = false,
                    onClick = onExtract,
                    modifier = Modifier.weight(1f),
                )
            }

            // 分享只在有已验证链接时出现（不能分享合成地址；没链接时「原网页」已给出禁用原因）
            if (state.link != null) {
                BarAction(
                    iconRes = R.drawable.ic_upload,
                    labelRes = R.string.reader_share,
                    enabled = true,
                    tinted = false,
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
            }

            BarAction(
                iconRes = R.drawable.ic_external_link,
                labelRes = R.string.reader_open_original,
                enabled = state.link != null,
                tinted = false,
                onClick = onOpenOriginal,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.link == null) {
            Text(
                text = stringResource(R.string.reader_no_link_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
}

/** 底部栏里的一个动作：图标 + 文字（原型 02 的写法），禁用时整块变灰但仍占位 */
@Composable
private fun BarAction(
    iconRes: Int,
    labelRes: Int,
    enabled: Boolean,
    tinted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        tinted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 设备本地时间显示；没有有效日期显示“发布时间未知”，不猜、不留空 */
@Composable
private fun publishedLabel(publishedAt: String?): String {
    if (publishedAt == null) return stringResource(R.string.reader_published_unknown)
    val millis = runCatching { java.time.Instant.parse(publishedAt).toEpochMilli() }.getOrNull()
        ?: return stringResource(R.string.reader_published_unknown)
    return SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
}
