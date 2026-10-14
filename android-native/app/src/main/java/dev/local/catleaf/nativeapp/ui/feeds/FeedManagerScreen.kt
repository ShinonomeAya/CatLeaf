package dev.local.catleaf.nativeapp.ui.feeds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.opml.Opml
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.LabeledField
import dev.local.catleaf.nativeapp.ui.theme.Monogram

/**
 * P03 订阅管理，按原型 03 的布局：返回 + 标题 + 右上“添加”，
 * 副标题计数，分组标题（可折叠），源行（缩略标识 + 名称 + 状态行 + 失败时“重试”），
 * 删除走统一确认框并说明影响。
 * 底部“订阅迁移（OPML）”属 T09，本任务不渲染未实现的入口。
 */
/** 订阅管理的直接入口；null = 正常打开管理列表 */
enum class FeedManagerStart { AddForm, OpmlPicker, Prefilled }

private val OPML_MIME_TYPES = arrayOf("text/xml", "text/x-opml", "application/xml", "*/*")

@Composable
fun FeedManagerScreen(
    viewModel: FeedManagerViewModel,
    onBack: () -> Unit,
    opmlViewModel: OpmlViewModel? = null,
    /** L04：进入发现订阅源页 */
    onOpenDiscovery: (() -> Unit)? = null,
    /**
     * 单一流程入口（UIR02/UIR03）：引导页与阅读空态直接进入添加表单或 OPML 选择，
     * 不先落到管理列表；该流程结束（提交成功、取消、导入结果关闭、选择器取消）即 [onBack] 回调用方。
     */
    startAction: FeedManagerStart? = null,
    /** 添加结果页「继续阅读」（原型 34）：回阅读页；null 时同 [onBack] */
    onContinueReading: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val categories by viewModel.knownCategories.collectAsStateWithLifecycle()
    val addResult by viewModel.addResult.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val opmlState by (opmlViewModel?.state ?: remember { MutableStateFlow(OpmlUiState()) })
        .collectAsStateWithLifecycle()

    // 返回顺序：先关 OPML 预览/结果，再退订阅管理（对话框由 Dialog 自己处理返回）
    val opmlSurfaceShown = opmlState.document != null || opmlState.report != null
    BackHandler {
        if (opmlSurfaceShown) opmlViewModel?.dismiss() else onBack()
    }

    // 单一流程：记录流程是否真正出现过，出现后又回到空闲才返回，避免首帧误退
    val direct = startAction != null
    var started by rememberSaveable { mutableStateOf(false) }
    var sawFlow by rememberSaveable { mutableStateOf(false) }
    // 结果页点「查看订阅」：用户明确要留在管理列表，单一流程不再自动返回
    var stayInManager by rememberSaveable { mutableStateOf(false) }
    val flowActive = form != null || addResult != null || opmlSurfaceShown ||
        opmlState.parsing || opmlState.errorMessage != null
    LaunchedEffect(flowActive, started, stayInManager) {
        if (!direct || !started || stayInManager) return@LaunchedEffect
        // 表单与结果是两个流；切换时以当前值确认结束，避免收集顺序产生瞬时空闲。
        val activeNow = viewModel.form.value != null || viewModel.addResult.value != null ||
            opmlSurfaceShown || opmlState.parsing || opmlState.errorMessage != null
        if (activeNow) sawFlow = true else if (sawFlow) onBack()
    }

    // OPML 预览/结果是整屏页面（原型 10 / 11-v2），必须在管理页之前分支，
    // 否则两者会同时发射、叠在一起（先前就是这么叠出重影的）
    opmlState.document?.takeIf { opmlState.report == null }?.let { doc ->
        OpmlPreviewSurface(
            document = doc,
            selected = opmlState.selected,
            importing = opmlState.importing,
            progressDone = opmlState.progressDone,
            progressTotal = opmlState.progressTotal,
            localOnly = state.localOnly,
            onToggle = { opmlViewModel?.toggle(it) },
            onToggleAll = { opmlViewModel?.selectAll() },
            onStart = { opmlViewModel?.startImport() },
            onDismiss = { opmlViewModel?.dismiss() },
            processed = opmlState.processed,
        )
        return
    }
    opmlState.report?.let { report ->
        OpmlResultSurface(
            report = report,
            importing = opmlState.importing,
            localOnly = state.localOnly,
            onRetry = { opmlViewModel?.retryUnresolved(it) },
            onNextBatch = { opmlViewModel?.nextBatch() },
            onDismiss = { opmlViewModel?.dismiss() },
        )
        return
    }

    // 系统文件选择：用平台能力，不引入文件选择大库（契约 §6）
    val pickOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        when {
            uri != null -> opmlViewModel?.loadDocument(uri)
            // 直接进入的 OPML 选择被取消：回到调用方，不停在管理列表
            direct -> onBack()
        }
    }

    LaunchedEffect(startAction) {
        if (started) return@LaunchedEffect
        when (startAction) {
            FeedManagerStart.AddForm -> viewModel.openAddForm()
            FeedManagerStart.OpmlPicker -> pickOpml.launch(OPML_MIME_TYPES)
            // 预填表单由调用方在依赖就绪后打开
            FeedManagerStart.Prefilled, null -> Unit
        }
        started = true
    }

    // 导出走系统分享/保存，不申请全盘存储权限
    val shareOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { opmlViewModel?.reportExportShared(state.feeds.size) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    var pendingDelete by remember { mutableStateOf<FeedEntity?>(null) }

    // 添加结果（原型 34）：登记成功后整屏显示验证结论，关闭前不落回列表或调用方
    addResult?.let { r ->
        FeedAddResultSurface(
            result = r,
            onRetry = viewModel::retryAdded,
            onViewFeeds = {
                stayInManager = true
                viewModel.closeAddResult()
            },
            onContinueReading = {
                viewModel.closeAddResult()
                (onContinueReading ?: onBack)()
            },
            onBack = viewModel::closeAddResult,
        )
        return
    }

    // 订阅表单是整屏二级页（T22 F15）：打开时**替换**管理列表。它不再是自带遮罩窗口的
    // AlertDialog，若与管理页在同一容器顺序渲染会互相穿透（真机复测抓到）。
    form?.let { f ->
        // 编辑态的状态行取真实订阅行（原型 22「上次抓取成功」），不在表单里硬写“正常”
        val editingFeed = if (f.editing) state.feeds.firstOrNull { it.feedId == f.feedId } else null
        FeedFormSurface(
            form = f,
            editingFeed = editingFeed,
            knownCategories = categories,
            onUrl = viewModel::onUrlChange,
            onTitle = viewModel::onTitleChange,
            onCategory = viewModel::onCategoryChange,
            onSubmit = viewModel::submitForm,
            onDismiss = viewModel::closeForm,
            onDelete = { editingFeed?.let { pendingDelete = it } },
        )
        pendingDelete?.let { feed ->
            DeleteFeedDialog(
                feed = feed,
                localOnly = state.localOnly,
                onConfirm = {
                    pendingDelete = null
                    viewModel.closeForm()
                    viewModel.delete(feed)
                },
                onDismiss = { pendingDelete = null },
            )
        }
        return
    }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }

    // 二级页整屏渲染（无根导航），必须自己避让系统栏，否则标题会被状态栏压住
    // 单一流程不显示管理列表（表单/OPML 之外只留对话框），避免进出时闪出管理页
    if (!direct || stayInManager) Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.feed_manager_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::openAddForm) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.feed_manager_add))
            }
        }

        if (state.localOnly) {
            // 本机范围说明（契约 8.2）：订阅保存在这台设备上
            Text(
                stringResource(R.string.feed_manager_local_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        Text(
            stringResource(R.string.feed_manager_counts, state.feeds.size, state.groupCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        state.errorMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        if (state.loading && state.feeds.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Column
        }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.feeds.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        Text(
                            stringResource(
                                if (state.localOnly) R.string.feed_manager_empty_local
                                else R.string.feed_manager_empty,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }

            state.grouped().forEach { (category, feeds) ->
                val isCollapsed = category in collapsed
                item(key = "group-$category") {
                    // 分组标题可折叠（原型 03）：整行 48dp 点击区，折叠状态有读屏说明
                    val toggleLabel = stringResource(
                        if (isCollapsed) R.string.feed_group_expand else R.string.feed_group_collapse,
                        category,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = toggleLabel) {
                                collapsed = if (isCollapsed) collapsed - category else collapsed + category
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(category, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            feeds.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                        Icon(
                            painterResource(if (isCollapsed) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (!isCollapsed) items(feeds, key = { it.feedId }) { feed ->
                    FeedRow(
                        feed = feed,
                        busy = state.busyFeedId == feed.feedId,
                        onRetry = { viewModel.retry(feed) },
                        onEdit = { viewModel.openEditForm(feed) },
                        onDelete = { pendingDelete = feed },
                    )
                    Divider(modifier = Modifier.padding(start = 24.dp))
                }
            }

            if (onOpenDiscovery != null) {
                item(key = "discovery-entry") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenDiscovery)
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.discovery_entry_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Divider(modifier = Modifier.padding(start = 24.dp))
                }
            }

            if (opmlViewModel != null) {
                item(key = "migration-title") {
                    Text(
                        stringResource(R.string.opml_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
                item(key = "opml-import") {
                    MigrationRow(
                        iconRes = R.drawable.ic_file_text,
                        title = stringResource(R.string.opml_import),
                        hint = stringResource(R.string.opml_import_hint),
                    ) { pickOpml.launch(OPML_MIME_TYPES) }
                }
                item(key = "opml-export") {
                    MigrationRow(
                        iconRes = R.drawable.ic_upload,
                        title = stringResource(R.string.opml_export),
                        hint = stringResource(R.string.opml_export_hint),
                    ) {
                        val intent = opmlViewModel.buildExportIntent(
                            state.feeds.map {
                                Opml.ExportFeed(title = it.title, xmlUrl = it.url, group = it.category)
                            },
                        )
                        if (intent != null) shareOpml.launch(Intent.createChooser(intent, null))
                    }
                }
                item(key = "opml-privacy") {
                    Text(
                        stringResource(R.string.opml_export_privacy),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    opmlState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { opmlViewModel?.dismiss() },
            title = { Text(stringResource(R.string.opml_import)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { opmlViewModel?.dismiss() }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    opmlState.exportMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            opmlViewModel?.clearExportMessage()
        }
    }

    pendingDelete?.let { feed ->
        DeleteFeedDialog(
            feed = feed,
            localOnly = state.localOnly,
            onConfirm = {
                pendingDelete = null
                viewModel.delete(feed)
            },
            onDismiss = { pendingDelete = null },
        )
    }

}

/** 删除统一确认：列表菜单与编辑页底部共用，先说明影响再执行 */
@Composable
private fun DeleteFeedDialog(feed: FeedEntity, localOnly: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_delete_title, feed.title)) },
        // 本机模式没有云端：说明按当前使用方式区分（VR3 实测本机模式仍写「云端订阅」）
        text = { Text(stringResource(if (localOnly) R.string.feed_delete_body_local else R.string.feed_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete_feed), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun MigrationRow(
    iconRes: Int,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FeedRow(
    feed: FeedEntity,
    busy: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    actionsEnabled: Boolean = true,
) {
    val failed = feed.status == dev.local.catleaf.nativeapp.data.FeedStore.STATUS_FAILED
    var menuOpen by remember { mutableStateOf(false) }
    // 管理行 ≥72dp（设计规范 §5）；删除收进「更多」菜单并二次确认，不再每行常驻垃圾桶（UIR11）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(feed.title, size = 44.dp)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                feed.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 失败用真实图标 + 文字（不再用字符 ⚠，编辑页状态行也不重复出现两个警示符）
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
                    text = statusLine(feed),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (failed && actionsEnabled) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.feed_retry))
            }
        }
        if (actionsEnabled) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painterResource(R.drawable.ic_ellipsis),
                        contentDescription = stringResource(R.string.feed_more_actions, feed.title),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feed_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    if (failed) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feed_retry)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                onRetry()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete_feed), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_trash),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLine(feed: FeedEntity): String = when (feed.status) {
    dev.local.catleaf.nativeapp.data.FeedStore.STATUS_FAILED -> stringResource(R.string.feed_status_failed)
    dev.local.catleaf.nativeapp.data.FeedStore.STATUS_PENDING -> stringResource(R.string.feed_status_pending)
    else -> feed.lastSuccessAt?.let {
        stringResource(R.string.feed_status_updated, formatTimestamp(it))
    } ?: stringResource(R.string.feed_status_ok)
}

private fun formatTimestamp(millis: Long): String {
    val formatter = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(millis))
}

/**
 * 添加/编辑表单（T22 F15：原型 09/22 的独立二级页，不再用窄对话框）。
 * 编辑时 **URL 只读** 并说明换地址要新建源（原型 22）；已有分组做成可选 chip，
 * 新分组直接在下方的输入框里写。业务与校验全部保持 `FeedManagerViewModel.form` 那套，
 * 这里只换组织方式。safeDrawingPadding 含键盘 inset + 可滚动，键盘弹出仍能滚到提交按钮。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FeedFormSurface(
    form: FeedFormState,
    editingFeed: FeedEntity?,
    knownCategories: List<String>,
    onUrl: (String) -> Unit,
    onTitle: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(enabled = !form.submitting, onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, enabled = !form.submitting) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(if (form.editing) R.string.feed_edit_title else R.string.feed_add_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        // 引导说明（V05：原型 09 的页级引导层级）
        Text(
            stringResource(if (form.editing) R.string.feed_edit_lead else R.string.feed_add_lead),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // UIR12：标签固定在框上方，框内只放示例；编辑时地址只读并用锁标出原因（原型 22）
        LabeledField(
            label = stringResource(R.string.feed_url_label),
            value = form.url,
            onValueChange = onUrl,
            placeholder = stringResource(R.string.feed_url_placeholder),
            supporting = stringResource(if (form.editing) R.string.feed_url_readonly_hint else R.string.feed_url_hint),
            enabled = !form.editing && !form.submitting,
            // 与实例/AI 地址框一致：URL 键盘、下一项（VR3 实测此处仍是普通文字键盘）
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            ),
            leadingIcon = if (form.editing) {
                { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(20.dp)) }
            } else {
                null
            },
        )
        LabeledField(
            label = stringResource(R.string.feed_name_label),
            value = form.customTitle,
            onValueChange = onTitle,
            supporting = stringResource(R.string.feed_name_hint),
            enabled = !form.submitting,
        )

        if (knownCategories.isNotEmpty()) {
            Text(
                stringResource(R.string.feed_category_existing_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                knownCategories.forEach { category ->
                    androidx.compose.material3.FilterChip(
                        selected = form.category == category,
                        enabled = !form.submitting,
                        onClick = {
                            // 点已选中的 chip 取消分组（回到未分组），否则切到该分组
                            onCategory(if (form.category == category) "" else category)
                        },
                        label = { Text(category, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        LabeledField(
            label = stringResource(R.string.feed_category_label),
            value = form.category,
            onValueChange = onCategory,
            supporting = stringResource(R.string.feed_category_hint),
            enabled = !form.submitting,
        )

        // 编辑页状态取真实订阅行（原型 22），没有数据就不显示，不写死“正常”
        editingFeed?.let { FeedStatusRow(it) }

        // 校验错误紧贴表单下方、不清空输入（设计规范 §5）
        form.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }

        // V05：同一页只有一个全宽主操作；取消是次操作放其下，键盘弹出随滚动可达
        Button(
            onClick = onSubmit,
            enabled = form.canSubmit,
            shape = ControlShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            // L06（C05）：进度圈放在**固定起点**，文字始终居中——锚点不随 spinner 移动
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(if (form.editing) R.string.action_save else R.string.feed_add_action))
                if (form.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 16.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        TextButton(
            onClick = onDismiss,
            enabled = !form.submitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.primary)
        }

        // 危险操作放页底（设计规范 §5 / 原型 22）：描边红色，点后仍走统一确认框
        if (editingFeed != null) {
            Divider()
            OutlinedButton(
                onClick = onDelete,
                enabled = !form.submitting,
                shape = ControlShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_delete_feed), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.feed_delete_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedStatusRow(feed: FeedEntity) {
    val failed = feed.status == dev.local.catleaf.nativeapp.data.FeedStore.STATUS_FAILED
    val ok = feed.status == dev.local.catleaf.nativeapp.data.FeedStore.STATUS_OK
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.feed_status_label),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(
                        when {
                            failed -> R.drawable.ic_circle_alert
                            ok -> R.drawable.ic_circle_check
                            else -> R.drawable.ic_info
                        },
                    ),
                    contentDescription = null,
                    tint = when {
                        failed -> MaterialTheme.colorScheme.error
                        ok -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    statusLine(feed),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
