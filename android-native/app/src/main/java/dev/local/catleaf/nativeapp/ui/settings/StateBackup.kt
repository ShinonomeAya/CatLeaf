package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.FeedStore
import dev.local.catleaf.nativeapp.data.LocalFeedStore
import dev.local.catleaf.nativeapp.data.importexport.StateDocument
import dev.local.catleaf.nativeapp.data.importexport.StateFeed
import dev.local.catleaf.nativeapp.data.importexport.StateFile
import dev.local.catleaf.nativeapp.data.importexport.StateLimitExceeded
import dev.local.catleaf.nativeapp.data.importexport.StateRestore
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.theme.warningColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地恢复的第一步结果（远端部分复用 OpmlImporter.Report） */
data class LocalOutcome(
    val readsApplied: Int = 0,
    val starsApplied: Int = 0,
    val bookmarksCreated: Int = 0,
    val orphanBookmarks: Int = 0,
    val skippedUnknown: Int = 0,
    /** 非 null = 本地整批未写入（事务回滚），远端新增的订阅保留 */
    val failureReason: String? = null,
    /** L05 本机目标：本次事务写入的源行数 */
    val feedsWritten: Int = 0,
)

sealed interface RestorePhase {
    object Idle : RestorePhase
    object Parsing : RestorePhase
    data class Preview(
        val fileName: String,
        val sourceOrigin: String,
        val targetOrigin: String,
        val targetKey: String,
        val sameInstance: Boolean,
        /** 目标是本机范围（L05）：文案写「本机」而不是「当前实例」 */
        val targetLocal: Boolean,
        val feedsTotal: Int,
        val feedsExisting: Int,
        val feedsNew: Int,
        val invalidItems: List<String>,
        val readRecords: Int,
        val starredRecords: Int,
        val migrateConfirmed: Boolean,
    ) : RestorePhase

    data class Running(val note: String) : RestorePhase
    /** localTarget = 本机范围单事务恢复：没有远端步骤，订阅段写「本机订阅」 */
    data class Done(
        val remote: OpmlImporter.Report?,
        val local: LocalOutcome,
        val localTarget: Boolean = false,
        /** 预览里列出的无效条目数：结果页照实写「已跳过 N 个」，不在结果里消失 */
        val invalidSkipped: Int = 0,
    ) : RestorePhase
}

data class StateUiState(
    val phase: RestorePhase = RestorePhase.Idle,
    val notice: String? = null,
    val exportMessage: String? = null,
)

/**
 * 状态导出与恢复（契约 §6、T19）。两步不是原子事务：
 * 先远端逐源导入（复用 [OpmlImporter]，URL 幂等），刷新本地镜像后再算合并计划、
 * 一个事务写本地状态；远端成功、本地失败时**两边结果分开报**，重试保留已添加的订阅。
 */
class StateBackupViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)

    private var document: StateDocument? = null
    private var retryPreview: RestorePhase.Preview? = null

    private val _state = MutableStateFlow(StateUiState())
    val state: StateFlow<StateUiState> = _state.asStateFlow()

    private class Deps(
        val instanceKey: String,
        val origin: String,
        val store: FeedStore,
        val importer: OpmlImporter,
    )

    private suspend fun deps(): Deps? {
        val key = settings.current().currentInstanceKey ?: return null
        val instance = db.instances().get(key) ?: return null
        val secret = runCatching {
            CredentialStore(getApplication()).get("instance_secret_$key")
        }.getOrNull() ?: return null
        val api = FeedApi(instance.origin, secret)
        val store = FeedStore(db, api, key)
        return Deps(key, instance.origin, store, OpmlImporter(store))
    }

    /**
     * 本机范围（契约 8.5）：v1 状态文件只有实例 origin 语义，本机导出要等 v2
     * （"不把 local:v1 伪装成 URL 写进 v1"）；v1→本机恢复属 L05 的 v2 工作。
     * 两个入口在此明确说明，不冒充成功也不静默禁用。
     */
    /** 当前活动范围（L05：本机与实例都可导出/恢复，v2 按 scope 写 source） */
    private suspend fun activeScope(): dev.local.catleaf.nativeapp.data.settings.ActivityScope =
        dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver(settings, db).resolve()

    /** 导出：当前实例的订阅 + 有状态（已读或收藏）的文章身份与状态；不含正文/AI/密钥 */
    fun exportState(onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            val scope = activeScope()
            // 实例范围才走远端镜像导出；本机范围直接读本机源行（Room 是权威）
            val feeds: List<StateFeed>
            val articlesForExport: List<dev.local.catleaf.nativeapp.data.local.ArticleEntity>
            val exportKind: String
            val exportOrigin: String?
            if (scope.kind == dev.local.catleaf.nativeapp.data.settings.ScopeKind.LOCAL) {
                val localStore = dev.local.catleaf.nativeapp.data.LocalFeedStore(
                    db, scope.key, dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, getApplication()),
                )
                feeds = localStore.local().map { StateFeed(it.url, it.title, it.category) }
                articlesForExport = db.articles().listByInstance(scope.key)
                exportKind = "local"
                exportOrigin = null
            } else {
                val d = deps()
                d ?: run {
                    _state.value = _state.value.copy(notice = "尚未连接实例，无法导出")
                    return@launch
                }
                feeds = d.store.local().map { StateFeed(it.url, it.title, it.category) }
                articlesForExport = db.articles().listByInstance(d.instanceKey)
                exportKind = "instance"
                exportOrigin = d.origin
            }
            val articles = articlesForExport
                .filter { it.isRead || it.isStarred }
                .map {
                    dev.local.catleaf.nativeapp.data.importexport.StateArticle(
                        feedUrl = it.feedUrl,
                        guid = it.guid,
                        link = it.link,
                        title = it.title,
                        feedTitle = it.feedTitle,
                        isRead = it.isRead,
                        isStarred = it.isStarred,
                    )
                }
            val bytes = runCatching { StateFile.write(exportKind, exportOrigin, System.currentTimeMillis(), feeds, articles) }
                .fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        _state.value = _state.value.copy(
                            notice = if (e is StateLimitExceeded) e.message else "导出失败：${e.message}",
                        )
                        return@launch
                    },
                )
            val intent = runCatching {
                val context = getApplication<Application>()
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val name = "catleaf-state-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"
                val file = File(dir, name)
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CatLeaf 状态备份")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.getOrElse {
                _state.value = _state.value.copy(notice = "导出失败：${it.message}")
                return@launch
            }
            _state.value = _state.value.copy(
                exportMessage = "已导出 ${feeds.size} 个订阅、${articles.size} 条阅读状态（仅身份与状态，不含正文、AI 结果或密钥）",
            )
            onIntent(intent)
        }
    }

    /** 选文件后：完整解析 + 校验在预览前（非法/超限在这里拒绝，不进入任何写入） */
    fun openFile(uri: Uri, displayName: String) {
        viewModelScope.launch {
            document = null
            retryPreview = null
            _state.value = _state.value.copy(phase = RestorePhase.Parsing, notice = null)
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size().toLong() + count > StateFile.MAX_BYTES) {
                                throw StateLimitExceeded("文件超过 10 MiB 上限，已拒绝")
                            }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }
            }.getOrElse {
                _state.value = _state.value.copy(phase = RestorePhase.Idle, notice = "无法读取所选文件：${it.message}")
                return@launch
            }
            if (bytes == null) {
                _state.value = _state.value.copy(phase = RestorePhase.Idle, notice = "无法读取所选文件")
                return@launch
            }
            StateFile.read(bytes).fold(
                onSuccess = { doc ->
                    document = doc
                    // L05：目标=活动范围。本机目标单事务写入；实例目标沿用两步（远端+本地）
                    val scope = activeScope()
                    val targetKey: String
                    val sameScope: Boolean
                    val targetLabel: String
                    val targetLocal = scope.kind == dev.local.catleaf.nativeapp.data.settings.ScopeKind.LOCAL
                    when (scope.kind) {
                        dev.local.catleaf.nativeapp.data.settings.ScopeKind.LOCAL -> {
                            targetKey = scope.key
                            sameScope = doc.sourceKind == "local"
                            targetLabel = "本机"
                        }
                        else -> {
                            val d = deps()
                            if (d == null) {
                                _state.value = _state.value.copy(phase = RestorePhase.Idle, notice = "尚未连接实例，无法预览恢复")
                                return@fold
                            }
                            targetKey = d.instanceKey
                            sameScope = doc.sourceKind == "instance" &&
                                EndpointPolicy.originOf(doc.sourceOrigin) == EndpointPolicy.originOf(d.origin)
                            targetLabel = d.origin
                        }
                    }
                    val localFeeds = db.feeds().list(targetKey)
                    val (existing, total) = StateRestore.feedPreview(doc, localFeeds)
                    _state.value = _state.value.copy(
                        phase = RestorePhase.Preview(
                            fileName = displayName,
                            sourceOrigin = doc.sourceOrigin.ifBlank { "本机" },
                            targetOrigin = targetLabel,
                            targetKey = targetKey,
                            sameInstance = sameScope,
                            targetLocal = targetLocal,
                            feedsTotal = total,
                            feedsExisting = existing,
                            feedsNew = StateRestore.feedsToImport(doc, localFeeds).size,
                            invalidItems = doc.invalidItems,
                            readRecords = doc.articles.count { it.isRead },
                            starredRecords = doc.articles.count { it.isStarred },
                            migrateConfirmed = false,
                        ),
                        notice = null,
                    )
                },
                onFailure = { e ->
                    document = null
                    _state.value = _state.value.copy(
                        phase = RestorePhase.Idle,
                        notice = e.message ?: "状态文件不可用",
                    )
                },
            )
        }
    }

    fun setMigrateConfirmed(confirmed: Boolean) {
        val p = _state.value.phase as? RestorePhase.Preview ?: return
        _state.value = _state.value.copy(phase = p.copy(migrateConfirmed = confirmed))
    }

    /**
     * 本机目标的恢复（L05/§8.5）：**单个本地事务**——源行直接写 Room（pending 待联网验证），
     * 再按并集合并已读/收藏与孤立书签；无远端步骤，没有部分成功的分叉。
     */
    private suspend fun restoreIntoLocal(preview: RestorePhase.Preview, doc: StateDocument, scopeKey: String) {
        retryPreview = preview
        _state.value = _state.value.copy(phase = RestorePhase.Running("正在写入本机…"))
        val outcome = runCatching {
            val existingFeeds = db.feeds().list(scopeKey)
            val feedsToWrite = StateRestore.feedsToImport(doc, existingFeeds).map { f ->
                FeedEntity(
                    instanceKey = scopeKey,
                    feedId = "f" + (java.util.UUID.randomUUID().toString().replace("-", "").take(20)),
                    url = f.xmlUrl, title = f.title, category = f.group,
                    status = "pending", lastSuccessAt = null,
                )
            }
            check(feedsToWrite.isEmpty() || existingFeeds.size + feedsToWrite.size <= LocalFeedStore.MAX_FEEDS) {
                "本机订阅超过 ${LocalFeedStore.MAX_FEEDS} 个上限，请先删除一些"
            }
            val plan = StateRestore.planLocal(doc, scopeKey, existingFeeds + feedsToWrite, db.articles().listByInstance(scopeKey))
            val now = System.currentTimeMillis()
            var reads = 0
            var stars = 0
            var marks = 0
            var feedsWritten = 0
            db.withTransaction {
                db.feeds().upsertAll(feedsToWrite)
                feedsWritten = feedsToWrite.size
                plan.readUpdates.forEach { if (db.articles().setRead(it, true, now) > 0) reads++ }
                plan.starUpdates.forEach { if (db.articles().setStarred(it, true, now, now) > 0) stars++ }
                plan.bookmarks.forEach { b ->
                    db.articles().upsertArticle(
                        ArticleEntity(
                            localId = b.localId, instanceKey = scopeKey, feedId = b.feedId,
                            guid = b.guid, link = b.link, title = b.title,
                            publishedAt = null, feedTitle = b.feedTitle, feedUrl = b.feedUrl,
                            isRead = b.isRead, isStarred = true, starredAt = now, stateUpdatedAt = now,
                        ),
                    )
                    marks++
                }
            }
            LocalOutcome(reads, stars, marks, plan.orphanBookmarks, plan.skippedUnknown, feedsWritten = feedsWritten)
        }.fold(
            onSuccess = { outcome ->
                document = null
                _state.value = _state.value.copy(
                    phase = RestorePhase.Done(
                        remote = null,
                        local = outcome,
                        localTarget = true,
                        invalidSkipped = preview.invalidItems.size,
                    ),
                    notice = null,
                )
            },
            onFailure = { e ->
                _state.value = _state.value.copy(phase = RestorePhase.Idle, notice = "本机恢复失败（未写入）：${e.message}")
            },
        )
    }

    fun cancelRestore() {
        document = null
        retryPreview = null
        _state.value = StateUiState()
    }

    /** 确认后执行；「重试恢复」同路径（远端 URL 幂等、本地并集幂等，第二次不重复） */
    fun startRestore() {
        val preview = when (_state.value.phase) {
            is RestorePhase.Preview -> _state.value.phase as RestorePhase.Preview
            is RestorePhase.Done -> retryPreview
            else -> null
        } ?: return
        val doc = document ?: return
        if (!preview.sameInstance && !preview.migrateConfirmed) {
            _state.value = _state.value.copy(notice = "跨实例迁移需要明确勾选确认")
            return
        }
        viewModelScope.launch {
            val scope = activeScope()
            if (scope.key != preview.targetKey) {
                _state.value = _state.value.copy(notice = "使用范围已切换，请重新选择文件")
                return@launch
            }
            if (scope.kind == dev.local.catleaf.nativeapp.data.settings.ScopeKind.LOCAL) {
                restoreIntoLocal(preview, doc, scope.key)
                return@launch
            }
            val d = deps() ?: run {
                _state.value = _state.value.copy(phase = RestorePhase.Idle, notice = "尚未连接实例")
                return@launch
            }
            if (d.origin != preview.targetOrigin) {
                _state.value = _state.value.copy(notice = "实例已切换，请重新选择文件")
                return@launch
            }
            retryPreview = preview
            // 步骤 1：远端逐源导入（缺失的源）。已存在的按 URL 记「已存在」，不覆写名称
            _state.value = _state.value.copy(phase = RestorePhase.Running("正在向实例导入缺少的订阅…"))
            val toImport = StateRestore.feedsToImport(doc, db.feeds().list(d.instanceKey))
            val remote = if (toImport.isEmpty()) null else d.importer.import(toImport)
            // 镜像刷新：让刚导入的源参与映射（失败时仍继续——远端结果已定，本地按现有源合并）
            d.store.refresh()

            // 步骤 2：本地状态事务写入
            _state.value = _state.value.copy(phase = RestorePhase.Running("正在写入本地阅读状态…"))
            val localFeeds = db.feeds().list(d.instanceKey)
            val plan = StateRestore.planLocal(doc, d.instanceKey, localFeeds, db.articles().listByInstance(d.instanceKey))
            val now = System.currentTimeMillis()
            val outcome = runCatching {
                var reads = 0
                var stars = 0
                var marks = 0
                db.withTransaction {
                    plan.readUpdates.forEach { if (db.articles().setRead(it, true, now) > 0) reads++ }
                    plan.starUpdates.forEach { if (db.articles().setStarred(it, true, now, now) > 0) stars++ }
                    plan.bookmarks.forEach { b ->
                        db.articles().upsertArticle(
                            ArticleEntity(
                                localId = b.localId,
                                instanceKey = d.instanceKey,
                                feedId = b.feedId,
                                guid = b.guid,
                                link = b.link,
                                title = b.title,
                                publishedAt = null, // 状态条目没有发布时间——显示"发布时间未知"，不猜
                                feedTitle = b.feedTitle,
                                feedUrl = b.feedUrl,
                                isRead = b.isRead,
                                isStarred = true,
                                starredAt = now,
                                stateUpdatedAt = now,
                            ),
                        )
                        marks++
                    }
                }
                LocalOutcome(reads, stars, marks, plan.orphanBookmarks, plan.skippedUnknown)
            }.getOrElse { e ->
                // 事务失败：本地整批未写入（回滚），远端新增的订阅保留——分开报告由结果页呈现
                LocalOutcome(failureReason = e.message ?: "本地写入失败（已回滚，未改动任何状态）")
            }
            _state.value = _state.value.copy(phase = RestorePhase.Done(remote, outcome, invalidSkipped = preview.invalidItems.size))
        }
    }
}

/** P07 恢复流：预览（原型 15）→ 执行 → 结果（原型 19）。整屏替换存储内容，返回逐级退出 */
@Composable
fun RestoreFlow(viewModel: StateBackupViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val phase = state.phase) {
        RestorePhase.Idle -> { /* 由外层存储页渲染 */ }
        RestorePhase.Parsing -> RunningBody(stringResource(R.string.restore_parsing))
        is RestorePhase.Running -> {
            BackHandler(enabled = false) { }
            RunningBody(phase.note)
        }
        is RestorePhase.Preview -> {
            BackHandler(onBack = onExit)
            PreviewBody(phase, onToggleMigrate = viewModel::setMigrateConfirmed, onConfirm = viewModel::startRestore, onCancel = onExit)
        }
        is RestorePhase.Done -> {
            BackHandler { viewModel.cancelRestore(); onExit() }
            ResultBody(phase, onRetry = viewModel::startRestore, onBack = { viewModel.cancelRestore(); onExit() })
        }
    }
    state.notice?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RunningBody(note: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text(note, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewBody(
    preview: RestorePhase.Preview,
    onToggleMigrate: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var showInvalid by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_file_text), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Text(preview.fileName, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.restore_origin_head), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.restore_from, preview.sourceOrigin.ifBlank { "?" }), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        Text(
            if (preview.targetLocal) stringResource(R.string.restore_to_local) else stringResource(R.string.restore_to, preview.targetOrigin),
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.restore_merge_head), style = MaterialTheme.typography.titleMedium)
        RestoreStatRow(stringResource(R.string.restore_feeds), stringResource(R.string.restore_unit_feeds, preview.feedsTotal))
        RestoreStatRow(stringResource(R.string.restore_feeds_new), stringResource(R.string.restore_unit_feeds, preview.feedsNew))
        RestoreStatRow(stringResource(R.string.restore_feeds_exists), stringResource(R.string.restore_unit_feeds, preview.feedsExisting))
        if (preview.invalidItems.isNotEmpty()) {
            RestoreStatRow(
                label = stringResource(R.string.restore_invalid),
                value = stringResource(R.string.restore_unit_items, preview.invalidItems.size),
                accent = true,
                onClick = { showInvalid = !showInvalid },
            )
            if (showInvalid) {
                preview.invalidItems.forEach {
                    Text("· $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        RestoreStatRow(stringResource(R.string.restore_reads), stringResource(R.string.restore_unit_records, preview.readRecords))
        RestoreStatRow(stringResource(R.string.restore_stars), stringResource(R.string.restore_unit_records, preview.starredRecords))
        Text(
            stringResource(R.string.restore_union_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        InfoBox(stringResource(R.string.restore_no_content_note), iconRes = R.drawable.ic_info)
        if (!preview.sameInstance) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.restore_cross_warning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onToggleMigrate(!preview.migrateConfirmed) },
            ) {
                Checkbox(checked = preview.migrateConfirmed, onCheckedChange = onToggleMigrate)
                Text(stringResource(R.string.restore_cross_confirm), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.size(12.dp))
        Button(
            onClick = onConfirm,
            enabled = preview.sameInstance || preview.migrateConfirmed,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.restore_merge_action)) }
        Text(
            stringResource(if (preview.targetLocal) R.string.restore_adds_feeds_note_local else R.string.restore_adds_feeds_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun ResultBody(done: RestorePhase.Done, onRetry: () -> Unit, onBack: () -> Unit) {
    val remote = done.remote
    val local = done.local
    val localFailed = local.failureReason != null
    val pending = remote?.needsRetry == true
    val incomplete = localFailed || pending
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.size(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (incomplete) R.drawable.ic_circle_alert else R.drawable.ic_circle_check),
                contentDescription = null,
                // 部分完成用警示色（设计规范 warning），不用错误红，也不用成功绿
                tint = if (incomplete) warningColor() else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            stringResource(if (localFailed || pending) R.string.restore_partial_title else R.string.restore_done_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            stringResource(if (localFailed) R.string.restore_local_not_written else R.string.restore_local_written),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(24.dp))

        // 远端与本地分开报（契约 §6：不能一句"恢复失败"掩盖部分已完成）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (remote == null || (remote.failed == 0 && remote.pending == 0)) R.drawable.ic_circle_check else R.drawable.ic_circle_alert),
                contentDescription = null,
                tint = if (remote == null || (remote.failed == 0 && remote.pending == 0)) MaterialTheme.colorScheme.primary else warningColor(),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(if (done.localTarget) R.string.restore_local_feeds_head else R.string.restore_remote_head),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            when {
                remote != null -> stringResource(R.string.restore_remote_line, remote.added, remote.exists, remote.failed + remote.pending)
                done.localTarget && local.feedsWritten > 0 -> stringResource(R.string.restore_local_feeds_line, local.feedsWritten)
                else -> stringResource(R.string.restore_remote_none)
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 30.dp, top = 2.dp),
        )
        remote?.items?.filter { it.outcome is OpmlImporter.Outcome.Failed || it.outcome is OpmlImporter.Outcome.PendingConfirm }?.forEach { item ->
            Text(
                "· ${item.title}：${(item.outcome as? OpmlImporter.Outcome.Failed)?.reason ?: stringResource(R.string.restore_pending_hint)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp),
            )
        }
        Spacer(Modifier.size(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (localFailed) R.drawable.ic_circle_alert else R.drawable.ic_circle_check),
                contentDescription = null,
                tint = if (localFailed) warningColor() else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.restore_local_head), style = MaterialTheme.typography.titleMedium)
        }
        if (localFailed) {
            Text(
                stringResource(R.string.restore_local_failed_line, local.failureReason!!),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        } else {
            Text(
                stringResource(R.string.restore_local_line, local.readsApplied, local.starsApplied, local.bookmarksCreated) +
                    if (local.orphanBookmarks > 0) stringResource(R.string.restore_orphan_note, local.orphanBookmarks) else "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        }
        if (done.invalidSkipped > 0) {
            Text(
                stringResource(R.string.restore_invalid_skipped, done.invalidSkipped),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        // 只在未完成时说明重试语义；全部完成时这句话会误导
        if (incomplete) {
            Text(
                stringResource(R.string.restore_retry_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        if (incomplete) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.restore_retry_action))
            }
            Spacer(Modifier.size(8.dp))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.restore_back_action))
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun RestoreStatRow(label: String, value: String, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    val rowModifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).let {
        if (onClick != null) it.clickable(onClick = onClick) else it
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (accent) warningColor() else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (accent) "$value · ${stringResource(R.string.restore_invalid_view)}" else value,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) warningColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun InfoBox(text: String, iconRes: Int = R.drawable.ic_circle_check) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
