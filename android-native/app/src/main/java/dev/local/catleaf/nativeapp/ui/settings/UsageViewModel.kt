package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.LocalFeedStore
import dev.local.catleaf.nativeapp.data.importexport.CopyToLocal
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CacheCleanup
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 复制预览表（原型 31）的一侧计数：订阅 / 已下载文章 / 已读 / 收藏，均为加载时刻的真实行数 */
data class CopyCounts(val feeds: Int = 0, val articles: Int = 0, val read: Int = 0, val starred: Int = 0)

internal fun countsOf(feeds: Int, articles: List<CopyToLocal.SourceArticle>) = CopyCounts(
    feeds = feeds,
    articles = articles.size,
    read = articles.count { it.isRead },
    starred = articles.count { it.isStarred },
)

/**
 * 使用方式（原型 29–31；C06/C07/C08）：本机默认、实例可选、数据互相独立。
 * 复制只处理**已下载**数据：预览冻结 → 勾选 → 确认 → 单事务写本机；原实例不删，复制不自动切换。
 */
data class UsageUiState(
    val scopeKind: ScopeKind = ScopeKind.LOCAL,
    val instanceOrigin: String? = null,
    /** 复制预览（进入预览步骤时加载并冻结） */
    val sourceFeeds: List<CopyToLocal.TargetFeed> = emptyList(),
    val sourceArticles: List<CopyToLocal.SourceArticle> = emptyList(),
    val sourceCounts: CopyCounts = CopyCounts(),
    val targetCounts: CopyCounts = CopyCounts(),
    val selected: Set<String> = emptySet(),
    val includeOrphaned: Boolean = false,
    val loading: Boolean = false,
    /** 本次预览快照已冻结（ViewModel 跨旋转保留；进程重建后为 false，需重新冻结） */
    val previewReady: Boolean = false,
    val copying: Boolean = false,
    val result: String? = null,
    val error: String? = null,
) {
    /** 与 CopyToLocal.plan 的「孤立收藏组」同一口径：来源源未被选中（含已删源）的收藏 */
    val orphanedStarred: Int get() = sourceArticles.count { it.isStarred && it.feedId !in selected }

    val hasSelection: Boolean get() = selected.isNotEmpty() || (includeOrphaned && orphanedStarred > 0)
}

class UsageViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)
    private val scopeResolver = ActivityScopeResolver(settings, db)

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    /** 进入使用方式页：只刷新当前范围与实例 origin；已冻结的预览字段保留（进程恢复时可能正在加载） */
    fun load() {
        viewModelScope.launch {
            val scope = scopeResolver.resolve()
            val origin = if (scope.kind == ScopeKind.INSTANCE) db.instances().get(scope.key)?.origin else null
            _state.value = _state.value.copy(scopeKind = scope.kind, instanceOrigin = origin)
        }
    }

    /** 进入复制预览（原型 31）：重新冻结来源/目标快照，清掉上一回的结果；只读，不写任何表 */
    fun openPreview() {
        if (_state.value.copying) return
        // 同步置 loading，避免界面恢复逻辑在协程挂起前重复触发
        _state.value = _state.value.copy(loading = true, previewReady = false, result = null, error = null, includeOrphaned = false)
        viewModelScope.launch {
            val scope = scopeResolver.resolve()
            if (scope.kind != ScopeKind.INSTANCE) {
                _state.value = _state.value.copy(loading = false, error = "当前没有使用实例，无需复制")
                return@launch
            }
            loadCopyPreview(scope.key)
        }
    }

    /** 预览冻结：加载时刻的来源/目标快照；期间变更需重新进入（startCopy 再核对一次） */
    private suspend fun loadCopyPreview(instanceKey: String) {
        val sourceFeeds = db.feeds().list(instanceKey).map { CopyToLocal.TargetFeed(it.feedId, it.url, it.title, it.category) }
        val sourceArticles = db.articles().listByInstance(instanceKey).map { it.toSource() }
        val targetFeeds = db.feeds().list(LOCAL_SCOPE_KEY).size
        val targetArticles = db.articles().listByInstance(LOCAL_SCOPE_KEY).map { it.toSource() }
        val feedIds = sourceFeeds.map { it.feedId }.toSet()
        _state.value = _state.value.copy(
            loading = false,
            previewReady = true,
            sourceFeeds = sourceFeeds,
            sourceArticles = sourceArticles,
            sourceCounts = countsOf(sourceFeeds.size, sourceArticles),
            targetCounts = countsOf(targetFeeds, targetArticles),
            selected = feedIds,
        )
    }

    private suspend fun ArticleEntity.toSource(): CopyToLocal.SourceArticle {
        val c = db.articles().getContent(localId)
        return CopyToLocal.SourceArticle(
            localId = localId,
            feedId = feedId, guid = guid, link = link, title = title,
            feedTitle = feedTitle, feedUrl = feedUrl,
            isRead = isRead, isStarred = isStarred, starredAt = starredAt,
            content = c?.content, contentSource = c?.contentSource ?: "rss", thumbnail = c?.thumbnail,
        )
    }

    fun toggle(feedId: String) {
        val s = _state.value
        if (s.copying) return
        val next = if (feedId in s.selected) s.selected - feedId else s.selected + feedId
        _state.value = s.copy(selected = next)
    }

    fun toggleOrphaned() {
        val s = _state.value
        if (!s.copying) _state.value = s.copy(includeOrphaned = !s.includeOrphaned)
    }

    /** 确认复制：预检 → 单事务 → 结果。失败时本机回滚、原实例不动。 */
    fun startCopy() {
        val s = _state.value
        if (!s.hasSelection || s.copying) return
        val origin = s.instanceOrigin ?: return
        _state.value = s.copy(copying = true, error = null, result = null)
        viewModelScope.launch {
            val scope = scopeResolver.resolve()
            if (scope.kind != ScopeKind.INSTANCE || db.instances().get(scope.key)?.origin != origin) {
                _state.value = _state.value.copy(copying = false, error = "实例已切换，请重新进入本页")
                return@launch
            }
            val budget = CopyToLocal.Budget(
                maxFeeds = LocalFeedStore.MAX_FEEDS,
                currentFeeds = db.feeds().list(LOCAL_SCOPE_KEY).size,
                remainingBytes = CacheCleanup(db, getApplication()).remainingBudgetBytes(),
            )
            val plan = CopyToLocal.plan(
                _state.value.sourceFeeds, _state.value.sourceArticles,
                db.feeds().list(LOCAL_SCOPE_KEY).map { CopyToLocal.TargetFeed(it.feedId, it.url, it.title, it.category) },
                db.articles().listByInstance(LOCAL_SCOPE_KEY).map { it.toSource() },
                _state.value.selected, _state.value.includeOrphaned, budget,
            )
            plan.fold(
                onSuccess = { p ->
                    CopyToLocal.execute(db, LOCAL_SCOPE_KEY, p).fold(
                        onSuccess = { (feeds, articles, merges) ->
                            _state.value = _state.value.copy(
                                copying = false,
                                result = "复制完成：新增 $feeds 个订阅、$articles 篇文章、$merges 条状态合并" +
                                    if (p.bookmarkOnly > 0) "（其中 ${p.bookmarkOnly} 篇仅书签，没有可保存文本）" else "",
                            )
                        },
                        onFailure = { e ->
                            _state.value = _state.value.copy(copying = false, error = "复制失败（本机未写入）：${e.message}")
                        },
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(copying = false, error = e.message ?: "预检失败")
                },
            )
        }
    }

    /** 切换到本机范围（用户主动确认后；不自动切）。实例数据与凭据保留，可再连回 */
    fun switchToLocal(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            onDone()
        }
    }
}
