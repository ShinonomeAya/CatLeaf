package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.warningColor
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.local.CacheCleanup
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.StorageStats
import dev.local.catleaf.nativeapp.data.local.OfflinePackageEntity
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.currentScopeKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.Locale

/** 存储页状态：统计 + 一次性的清理回执 */
data class StorageUiState(
    val loading: Boolean = true,
    val stats: StorageStats? = null,
    val clearing: Boolean = false,
    /** null=无；否则为提示（成功写明清了什么，失败写明旧状态保留） */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val offlinePackages: List<OfflinePackageEntity> = emptyList(),
    val offlineTotalBytes: Long = 0,
    val offlineBusyId: String? = null,
    /** 原型 40 范围标签：「本机使用」或「自有实例 · 名称」；null=尚未读到 */
    val scopeLabel: String? = null,
)

/**
 * P07 存储分组（T18）：统计展示 + 「清普通缓存」。
 * 失败处理按契约：**清理失败保留旧统计数字并提示**，成功后才重新加载数字。
 * 导出的/恢复/重置入口归 T19/T20，这里不渲染未实现按钮。
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val cleanup = CacheCleanup(CatLeafDatabase.get(application), application)
    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)
    private val offlineStore = OfflinePackageStore(application, db)
    private var offlineJob: Job? = null
    private var retryScope: String? = null
    private var activeScopeKey: String? = null
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.snapshot.collect { snap ->
                val key = if (dev.local.catleaf.nativeapp.data.settings.ActivityScopeRules.decideKind(
                    snap.activeScopeKind, snap.currentInstanceKey,
                ) == dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE) snap.currentInstanceKey.orEmpty()
                else dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
                if (activeScopeKey != key) {
                    if (retryScope != null && retryScope != key) offlineJob?.cancel()
                    retryScope = null
                    activeScopeKey = key
                    _state.value = _state.value.copy(offlinePackages = emptyList(), offlineBusyId = null, loading = true)
                    load()
                    val label = if (key == dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY) {
                        application.getString(R.string.reading_local_scope)
                    } else {
                        // 只显示实例名称/来源地址，不涉及凭据
                        val instance = runCatching { db.instances().get(key) }.getOrNull()
                        application.getString(
                            R.string.offline_manager_scope_instance,
                            instance?.displayName?.ifBlank { null } ?: instance?.origin ?: "",
                        ).trimEnd(' ', '·')
                    }
                    if (activeScopeKey == key) _state.value = _state.value.copy(scopeLabel = label)
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val scope = settings.currentScopeKey()
            runCatching {
                Triple(cleanup.stats(), db.offline().list(scope), db.offline().totalBytes())
            }
                .onSuccess { (stats, packages, total) ->
                    if (settings.currentScopeKey() == scope) _state.value = _state.value.copy(
                        loading = false, stats = stats, offlinePackages = packages, offlineTotalBytes = total,
                    )
                }
                .onFailure {
                    if (settings.currentScopeKey() != scope) return@onFailure
                    _state.value = _state.value.copy(
                        loading = false,
                        notice = "读取存储统计失败：${it.message ?: "未知原因"}",
                        noticeIsError = true,
                    )
                }
        }
    }

    /** 确认后执行（P07：清理须确认，确认框列出保留项） */
    fun clearNormalCache() {
        if (_state.value.clearing) return
        _state.value = _state.value.copy(clearing = true, notice = null)
        viewModelScope.launch {
            runCatching { cleanup.clearNormalCache() }
                .onSuccess { cleared ->
                    // 写盘成功才更新数字（原型说明：成功写盘后更新数字，失败保持旧状态）
                    val refreshed = runCatching { cleanup.stats() }
                    _state.value = _state.value.copy(
                        clearing = false,
                        stats = refreshed.getOrNull() ?: _state.value.stats,
                        notice = if (refreshed.isSuccess) {
                            "已清理 $cleared 项普通缓存；收藏、状态、订阅与密钥未动"
                        } else {
                            "已清理 $cleared 项普通缓存，但重新统计失败，数字为清理前的估算"
                        },
                        noticeIsError = refreshed.isFailure,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        clearing = false,
                        notice = "清理失败，已保留全部现有数据：${error.message ?: "未知原因"}",
                        noticeIsError = true,
                    )
                }
        }
    }

    fun removeOffline(pkg: OfflinePackageEntity) {
        viewModelScope.launch {
            if (settings.currentScopeKey() != pkg.scopeKey) return@launch
            val result = offlineStore.remove(pkg.scopeKey, pkg.localId)
            if (settings.currentScopeKey() != pkg.scopeKey) return@launch
            _state.value = _state.value.copy(
                notice = if (result.isSuccess) "离线副本已移除；收藏和已读状态保留" else "移除失败：${result.exceptionOrNull()?.message ?: "原副本已保留"}",
                noticeIsError = result.isFailure,
            )
            load()
        }
    }

    fun retryOffline(pkg: OfflinePackageEntity) {
        if (_state.value.offlineBusyId != null) return
        offlineJob = viewModelScope.launch {
            val scope = pkg.scopeKey
            if (settings.currentScopeKey() != scope) return@launch
            retryScope = scope
            _state.value = _state.value.copy(offlineBusyId = pkg.localId, notice = null)
            val result = offlineStore.retryMissing(scope, pkg.localId)
            retryScope = null
            if (settings.currentScopeKey() != scope) return@launch
            _state.value = _state.value.copy(
                offlineBusyId = null,
                notice = result.fold(
                    { "图片已保存 ${it.imageSaved}/${it.imageTotal} 张" },
                    { "重试未完成：${it.message ?: "请检查网络和空间"}" },
                ), noticeIsError = result.isFailure,
            )
            load()
        }
    }

    fun cancelOffline() {
        offlineJob?.cancel()
        retryScope = null
        _state.value = _state.value.copy(offlineBusyId = null, notice = "已取消后续图片请求；已保存内容仍在")
        load()
    }
}

@Composable
fun StorageScreen(
    viewModel: StorageViewModel,
    stateVm: StateBackupViewModel,
    onBack: () -> Unit,
    startOfflineManager: Boolean = false,
    onOpenOfflineArticle: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backupState by stateVm.state.collectAsStateWithLifecycle()
    var showConfirm by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<OfflinePackageEntity?>(null) }
    var showOfflineManager by remember(startOfflineManager) { mutableStateOf(startOfflineManager) }
    val context = LocalContext.current
    // ViewModel 跟随 Activity 存活：每次进入存储页都重读，阅读器里刚保存/移除的离线副本才会出现在统计与列表中
    LaunchedEffect(Unit) { viewModel.load() }

    // 文件交互用系统能力（契约 §6）：导入 = OpenDocument 选文件；导出 = 临时目录 + 系统分享
    val pickStateFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            val name = runCatching {
                context.contentResolver.query(it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: it.lastPathSegment?.substringAfterLast('/') ?: "catleaf-state.json"
            stateVm.openFile(it, name)
        }
    }
    val shareState = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // 预览/结果两步在存储页内整屏替换；返回逐级退出（Done/Preview → 存储页 → 上一页）
    val restoring = backupState.phase != RestorePhase.Idle
    BackHandler(enabled = !restoring) {
        if (showOfflineManager) showOfflineManager = false else onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val phase = backupState.phase
            IconButton(
                onClick = {
                    if (phase is RestorePhase.Preview || phase is RestorePhase.Done) stateVm.cancelRestore()
                    else if (showOfflineManager) showOfflineManager = false else onBack()
                },
                enabled = phase !is RestorePhase.Running && phase !is RestorePhase.Parsing,
            ) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(
                if (showOfflineManager && !restoring) "离线内容" else stringResource(
                    when (backupState.phase) {
                        is RestorePhase.Preview -> R.string.restore_preview_title
                        is RestorePhase.Done -> R.string.restore_result_title
                        else -> R.string.storage_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (restoring) {
            Box(modifier = Modifier.weight(1f)) {
                RestoreFlow(stateVm, onExit = { stateVm.cancelRestore() })
            }
        } else Box(modifier = Modifier.weight(1f)) {
            if (showOfflineManager) {
                OfflineManagerContent(state, viewModel, onRemove = { removeTarget = it }, onOpenArticle = onOpenOfflineArticle)
            } else if (state.loading) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val stats = state.stats
                    if (stats == null) {
                        Text(
                            stringResource(R.string.storage_load_failed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.storage_used_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(formatSize(stats.databaseBytes), style = MaterialTheme.typography.headlineLarge)
                        // 数量带单位（UIR15）：之前「32.4 MB · 120」看不出 120 是什么
                        StatRow(
                            stringResource(R.string.storage_normal_cache),
                            stringResource(R.string.storage_size_articles, formatSize(stats.normalTotalBytes), stats.normalArticles),
                        )
                        StatRow(stringResource(R.string.storage_starred_text), formatSize(stats.starredTextBytes + stats.protectedAiBytes))
                        // 上限数字直接读生产常量，界面与判定不可能两说
                        Text(
                            stringResource(
                                R.string.storage_budget_note,
                                dev.local.catleaf.nativeapp.data.article.CacheBudget.MAX_ARTICLES,
                                (dev.local.catleaf.nativeapp.data.article.CacheBudget.MAX_BYTES / (1024 * 1024)).toInt(),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedDangerButton(
                        text = stringResource(R.string.storage_clear_action),
                        enabled = stats != null && !state.clearing,
                        onClick = { showConfirm = true },
                    )
                    Text(
                        stringResource(R.string.storage_clear_scope),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state.notice?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }

                    // UIR15（原型 14）：离线内容与数据分区改为平面图标行，不再是整块填色卡片
                    SectionHead(stringResource(R.string.storage_offline_head))
                    StorageRow(
                        icon = R.drawable.ic_download,
                        title = stringResource(R.string.storage_offline_entry),
                        hint = stringResource(
                            R.string.storage_offline_hint,
                            state.offlinePackages.size,
                            formatSize(state.offlinePackages.sumOf { it.bytes }),
                        ),
                        onClick = { showOfflineManager = true },
                    )

                    // —— 数据分区（T19）：状态导出 / 恢复。OPML 导出与收藏链接导出在订阅管理侧已有入口
                    SectionHead(stringResource(R.string.restore_data_head))
                    StorageRow(
                        icon = R.drawable.ic_upload,
                        title = stringResource(R.string.data_export_state),
                        hint = stringResource(R.string.data_export_state_hint),
                        onClick = { stateVm.exportState { intent -> shareState.launch(android.content.Intent.createChooser(intent, null)) } },
                    )
                    StorageRow(
                        icon = R.drawable.ic_file_text,
                        title = stringResource(R.string.data_restore_state),
                        hint = stringResource(R.string.data_restore_state_hint),
                        onClick = { pickStateFile.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                    backupState.exportMessage?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    backupState.notice?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                    // 隐私说明用中性信息样式（原型 14 底部信息条）
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.data_state_privacy),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.storage_confirm_title)) },
            text = { Text(stringResource(R.string.storage_confirm_body)) },
            confirmButton = {
                Button(onClick = { showConfirm = false; viewModel.clearNormalCache() }) {
                    Text(stringResource(R.string.storage_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    removeTarget?.let { pkg ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除这篇离线副本？") },
            text = { Text("只移除“${pkg.title}”的离线包。已读和收藏状态保留；若收藏只剩这份正文，会先保留文本。") },
            confirmButton = { Button(onClick = { removeTarget = null; viewModel.removeOffline(pkg) }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } },
        )
    }
}

/** 原型 40（UIR14）：当前使用方式、全部使用方式共享的容量卡、逐篇状态行 + ⋯ 菜单、页脚说明 */
@Composable
private fun OfflineManagerContent(
    state: StorageUiState,
    viewModel: StorageViewModel,
    onRemove: (OfflinePackageEntity) -> Unit,
    onOpenArticle: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.scopeLabel?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ControlShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, ControlShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_file_text),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.offline_manager_capacity,
                                formatSize(state.offlineTotalBytes),
                                "${OfflinePackageStore.MAX_TOTAL_BYTES / 1_048_576} MB",
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.offline_manager_capacity_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { (state.offlineTotalBytes.toFloat() / OfflinePackageStore.MAX_TOTAL_BYTES).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        R.string.offline_manager_scope_usage,
                        state.offlinePackages.size,
                        formatSize(state.offlinePackages.sumOf { it.bytes }),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.notice?.let {
            Text(it, color = if (state.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            state.offlinePackages.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(R.string.offline_manager_empty), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.offline_manager_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> Column {
                state.offlinePackages.forEachIndexed { index, pkg ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OfflinePackageRow(
                        pkg = pkg,
                        busy = state.offlineBusyId == pkg.localId,
                        onOpen = { onOpenArticle(pkg.localId) },
                        onRetry = { viewModel.retryOffline(pkg) },
                        onCancel = viewModel::cancelOffline,
                        onRemove = { onRemove(pkg) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.offline_manager_footer),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
    }
}

/** 单篇行：标题 + 状态图标/文字 · 大小；动作收进行尾 ⋯（只列当前状态真实可做的） */
@Composable
private fun OfflinePackageRow(
    pkg: OfflinePackageEntity,
    busy: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val saving = busy || pkg.status == "saving"
    val warn = pkg.status == "partial" || pkg.status == "interrupted"
    val tint = if (warn) warningColor() else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onOpen).padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pkg.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painterResource(if (warn) R.drawable.ic_circle_alert else R.drawable.ic_circle_check),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    offlineRowStatus(pkg, saving) + " · " + formatSize(pkg.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (warn) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pkg.feedTitle.isNotBlank()) {
                Text(
                    pkg.feedTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painterResource(R.drawable.ic_ellipsis),
                    contentDescription = stringResource(R.string.offline_manager_more, pkg.title),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (busy) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.offline_cancel_requests)) }, onClick = {
                        menu = false
                        onCancel()
                    })
                } else if (warn && pkg.selectedImages) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.offline_manager_retry)) }, onClick = {
                        menu = false
                        onRetry()
                    })
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.offline_manager_remove), color = MaterialTheme.colorScheme.error) },
                    enabled = !busy,
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

private fun offlineRowStatus(pkg: OfflinePackageEntity, saving: Boolean): String = when {
    saving -> "保存中 · ${pkg.imageSaved}/${pkg.imageTotal} 张图片"
    pkg.status == "complete" && pkg.imageTotal > 0 -> "所选内容已保存 · ${pkg.imageTotal} 张图片"
    pkg.status == "complete" -> "所选内容已保存"
    pkg.status == "text" -> "仅正文已保存"
    pkg.status == "partial" -> "图片未齐 · ${pkg.imageSaved}/${pkg.imageTotal} 张"
    pkg.status == "interrupted" -> "已中断 · ${pkg.imageSaved}/${pkg.imageTotal} 张"
    else -> "待核对"
}

@Composable
private fun SectionHead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** 平面入口行（与设置页同形）：图标 + 标题/说明 + 箭头，≥72dp，行间分隔线 */
@Composable
private fun StorageRow(icon: Int, title: String, hint: String, onClick: () -> Unit) {
    Column {
        Surface(
            onClick = onClick,
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) {
            Row(modifier = Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.size(8.dp))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlinedDangerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_trash),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * P07 空间估算的显示。小于 1 MiB 用 KB——否则收藏正文（几十 KB）会显示成「0.0 MB」，
 * 看着像没保存，违反"如实反馈空间"。设备/契约用二进制单位，标签沿用原型的 MB/KB 写法。
 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
