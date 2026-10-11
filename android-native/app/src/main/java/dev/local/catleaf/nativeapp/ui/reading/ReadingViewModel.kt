package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.data.article.ArticleRepository
import dev.local.catleaf.nativeapp.data.FeedStore
import dev.local.catleaf.nativeapp.data.article.describeRefreshError
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.currentScopeKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * P02 阅读列表状态。数据全部来自本地镜像，网络只负责补充（契约 P02：本地先展示）。
 */
data class ReadingUiState(
    val feeds: List<FeedEntity> = emptyList(),
    val currentFeedId: String? = null,
    val allFeeds: Boolean = false,
    val refreshCompleted: Int = 0,
    val refreshTotal: Int = 0,
    /** 已加载的文章（未筛选），筛选在界面层按 [criteria] 应用 */
    val loaded: List<ArticleFilter.LoadedRow> = emptyList(),
    /** 全部订阅中符合当前筛选的本地保存篇数，不受分页窗口限制。 */
    val matchingSavedCount: Int = 0,
    val criteria: ArticleFilter.Criteria = ArticleFilter.Criteria(),
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** 上次成功刷新的时间；null 表示本次会话还没成功刷新过 */
    val lastSuccessAt: Long? = null,
    /** 刷新失败的原因；与“有内容可读”并存，不遮挡列表 */
    val offlineNotice: String? = null,
    val hasMoreHistory: Boolean = false,
    val historyOffset: Int = 0,
    val historyExhausted: Boolean = false,
    val notConnected: Boolean = false,
    /** 本机范围（契约 8.2）：没有远端历史，页脚提示与实例范围不同 */
    val isLocalScope: Boolean = false,
    /** 源列表已从本地读过一次：之前不显示「还没有订阅」，避免启动时空态一闪（UIR03） */
    val feedsLoaded: Boolean = false,
) {
    /** 原型 25：确认本范围没有任何订阅 */
    val noFeeds: Boolean get() = feedsLoaded && feeds.isEmpty()

    val currentFeed: FeedEntity? get() = feeds.firstOrNull { it.feedId == currentFeedId }

    val visible: List<ArticleFilter.LoadedRow>
        get() = ArticleFilter.apply(loaded, criteria)

    val unreadCount: Int get() = ArticleFilter.unreadCount(loaded)

    val emptyReason: ArticleFilter.EmptyReason
        get() = ArticleFilter.emptyReason(loaded, criteria)
}

class ReadingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CatLeafDatabase.get(application)
    private val settings = SettingsStore(application)
    private val scopeResolver = ActivityScopeResolver(settings, db)

    private var repo: ArticleRepository? = null
    private var feedStore: FeedStore? = null
    /** 冻结的范围键（local:v1 或实例哈希）：列表数据与回包都归属它 */
    private var scopeKey: String? = null
    /** 与 repo 绑定的实例密钥（null=本机范围，或实例密钥暂不可读→只读缓存） */
    private var apiSecret: String? = null
    /** 本机范围的公开抓取通道（懒建，持有复用的 OkHttp client） */
    private var localFetcher: dev.local.catleaf.nativeapp.data.rss.LocalFeedFetcher? = null
    private var readingSession = 0L
    private var allRefreshJob: Job? = null

    private val _state = MutableStateFlow(ReadingUiState())
    val state: StateFlow<ReadingUiState> = _state.asStateFlow()

    private suspend fun ensureRepo(): ArticleRepository? {
        val scope = scopeResolver.resolve()
        if (scope.kind == ScopeKind.INSTANCE && !scope.instanceReady) {
            // 实例指针悬空：不创建空范围覆盖（契约 8.1），界面给出连接指引
            repo = null
            feedStore = null
            scopeKey = null
            apiSecret = null
            _state.value = ReadingUiState(notConnected = true)
            return null
        }
        val key = scope.key
        val secret = if (scope.kind == ScopeKind.INSTANCE) {
            runCatching { CredentialStore(getApplication()).get("instance_secret_$key") }.getOrNull()
        } else null
        if (key == scopeKey && secret == apiSecret) repo?.let { return it }
        scopeKey = key
        apiSecret = secret
        _state.value = ReadingUiState()
        val instance = if (scope.kind == ScopeKind.INSTANCE) db.instances().get(key) else null
        // 实例密钥暂不可读 → 无网络能力仍显示缓存（契约 8.1：密钥问题走修复提示，不建空范围）
        val api = if (instance != null && secret != null) FeedApi(instance.origin, secret) else null
        feedStore = api?.let { FeedStore(db, it, key) }
        // 本机范围：直接公开抓取源 URL（契约 8.2）；实例范围走 api。两者共用同一套
        // 15 分钟/并发去重/预算/合并规则（ArticleRepository）。
        val fetcherForScope = if (scope.kind == ScopeKind.LOCAL) {
            localFetcher ?: dev.local.catleaf.nativeapp.data.rss.LocalFeedFetcher().also { localFetcher = it }
        } else null
        val localXml: (suspend (FeedEntity) -> Result<String>)? =
            fetcherForScope?.let { f -> { feed: FeedEntity -> f.fetchXml(feed.url) } }
        return ArticleRepository(
            db = db,
            api = api,
            instanceKey = key,
            cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, getApplication()),
            localFeedXml = localXml,
        ).also {
            repo = it
            _state.value = _state.value.copy(isLocalScope = scope.kind == ScopeKind.LOCAL)
        }
    }

    /** 打开阅读页：先用本地镜像渲染，再按 15 分钟规则决定是否刷新当前源 */
    fun start() {
        allRefreshJob?.cancel()
        val session = ++readingSession
        viewModelScope.launch {
            val r = ensureRepo() ?: return@launch
            val key = scopeKey ?: return@launch
            // 本地镜像为空时先同步一次源列表：源可能是在别处（订阅管理/OPML/另一台设备）添加的，
            // 否则阅读页会误显示“还没有订阅”而看不到已有源。
            // 本机范围或密钥暂不可用时没有远端可同步（feedStore 为 null），直接显示本地内容。
            if (feedStore != null && db.feeds().list(key).isEmpty()) {
                runCatching { feedStore?.refresh() }
            }
            val feeds = db.feeds().list(key)
            val remembered = settings.current()
            if (repo !== r || session != readingSession) return@launch
            val all = remembered.lastReadingAllFeeds && remembered.lastReadingScopeKey == key
            val current = if (all) null else feeds.firstOrNull {
                it.feedId == remembered.lastFeedId && (remembered.lastReadingScopeKey == null || remembered.lastReadingScopeKey == key)
            } ?: feeds.firstOrNull()

            if (_state.value.currentFeedId != current?.feedId || _state.value.allFeeds != all) {
                _state.value = _state.value.copy(loaded = emptyList(), historyOffset = 0, historyExhausted = false)
            }
            _state.value = _state.value.copy(feeds = feeds, currentFeedId = current?.feedId, allFeeds = all, feedsLoaded = true, loadingMore = false, refreshing = false)
            if (all) {
                // Restore a saved later-page anchor, with one page of room for newer entries.
                val restoreCount = if (remembered.lastScrollAnchor != null) remembered.lastReadingLoadedCount + ArticleRepository.LIST_PAGE_SIZE else ArticleRepository.LIST_PAGE_SIZE
                loadAllLocal(r, session, maxOf(restoreCount, _state.value.loaded.size))
                launchAllRefresh(r, force = false, session)
                return@launch
            }
            if (current == null) return@launch

            loadLocal(r, current.feedId)
            // 前台按需刷新：超过 15 分钟才真正发请求；刷新期间旧列表继续可读
            refreshInternal(r, current, force = false, session = session)
        }
    }

    private suspend fun loadLocal(r: ArticleRepository, feedId: String) {
        val session = readingSession
        val limit = maxOf(ArticleRepository.LIST_PAGE_SIZE, _state.value.loaded.size)
        val loaded = r.loadLocal(feedId, limit)
        val total = r.localCount(feedId)
        if (repo === r && _state.value.currentFeedId == feedId && session == readingSession) {
            _state.value = _state.value.copy(
                loaded = loaded,
                hasMoreHistory = loaded.size < total || (r.canFetchHistory && !_state.value.historyExhausted),
            )
        }
    }

    private suspend fun loadAllLocal(r: ArticleRepository, session: Long, limit: Int = maxOf(ArticleRepository.LIST_PAGE_SIZE, _state.value.loaded.size), pageLoad: Boolean = false): Boolean {
        val criteria = _state.value.criteria
        val result = runCatching { r.loadAllSubscribed(criteria, limit) }
        result.exceptionOrNull()?.let { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (repo === r && session == readingSession && _state.value.allFeeds && criteria == _state.value.criteria) {
                _state.value = _state.value.copy(loadingMore = if (pageLoad) false else _state.value.loadingMore, offlineNotice = describeRefreshError(error))
            }
            return false
        }
        val (rows, total) = result.getOrThrow()
        if (repo === r && session == readingSession && _state.value.allFeeds && criteria == _state.value.criteria) {
            if (limit >= _state.value.loaded.size) {
                _state.value = _state.value.copy(loaded = rows, matchingSavedCount = total, hasMoreHistory = rows.size < total)
            }
            if (pageLoad) _state.value = _state.value.copy(loadingMore = false)
        }
        return true
    }

    private fun launchAllRefresh(r: ArticleRepository, force: Boolean, session: Long) {
        if (_state.value.refreshing) return
        val feeds = _state.value.feeds
        _state.value = _state.value.copy(refreshing = feeds.isNotEmpty(), offlineNotice = null, refreshCompleted = 0, refreshTotal = feeds.size)
        allRefreshJob = viewModelScope.launch {
            val failed = mutableListOf<String>()
            var succeeded = false
            feeds.forEachIndexed { index, feed ->
                if (repo !== r || session != readingSession || !_state.value.allFeeds) return@launch
                val result = if (force) r.refreshNow(feed) else r.refreshIfStale(feed)
                if (repo !== r || session != readingSession || !_state.value.allFeeds) return@launch
                if (result is ArticleRepository.RefreshOutcome.Failed) failed += feed.title else succeeded = true
                if (!loadAllLocal(r, session)) {
                    _state.value = _state.value.copy(refreshing = false)
                    return@launch
                }
                _state.value = _state.value.copy(refreshCompleted = index + 1)
            }
            _state.value = _state.value.copy(refreshing = false, lastSuccessAt = if (succeeded) System.currentTimeMillis() else _state.value.lastSuccessAt,
                offlineNotice = failed.takeIf { it.isNotEmpty() }?.let {
                    getApplication<Application>().getString(dev.local.catleaf.nativeapp.R.string.reading_all_refresh_failed, it.joinToString("、"))
                })
        }
    }

    fun stopAllRefresh() {
        allRefreshJob?.cancel()
        ++readingSession
        _state.value = _state.value.copy(refreshing = false, loadingMore = false)
    }

    fun selectAllFeeds() {
        allRefreshJob?.cancel()
        val session = ++readingSession
        viewModelScope.launch {
            val r = ensureRepo() ?: return@launch
            if (repo !== r || session != readingSession) return@launch
            _state.value = _state.value.copy(allFeeds = true, currentFeedId = null, loaded = emptyList(), refreshing = false,
                loadingMore = false, offlineNotice = null, lastSuccessAt = null, hasMoreHistory = false)
            settings.setLastReadingPosition(null, null, null, scopeKey, allFeeds = true)
            if (repo !== r || session != readingSession) return@launch
            if (!loadAllLocal(r, session)) return@launch
            if (repo !== r || session != readingSession) return@launch
            launchAllRefresh(r, force = false, session)
        }
    }

    private suspend fun refreshInternal(r: ArticleRepository, feed: FeedEntity, force: Boolean, session: Long) {
        if (repo !== r || _state.value.currentFeedId != feed.feedId || session != readingSession) return
        _state.value = _state.value.copy(refreshing = true, offlineNotice = null)
        val outcome = if (force) r.refreshNow(feed) else r.refreshIfStale(feed)
        if (repo !== r || _state.value.currentFeedId != feed.feedId || session != readingSession) return

        when (outcome) {
            is ArticleRepository.RefreshOutcome.Refreshed -> {
                _state.value = _state.value.copy(historyOffset = 0, historyExhausted = false)
                loadLocal(r, feed.feedId)
                if (session != readingSession || repo !== r) return
                _state.value = _state.value.copy(
                    refreshing = false,
                    lastSuccessAt = System.currentTimeMillis(),
                    offlineNotice = if (outcome.historyNotSynced) {
                        getApplication<android.app.Application>().getString(dev.local.catleaf.nativeapp.R.string.reading_history_not_synced)
                    } else null,
                )
            }
            ArticleRepository.RefreshOutcome.SkippedFresh ->
                _state.value = _state.value.copy(refreshing = false)
            is ArticleRepository.RefreshOutcome.Failed -> {
                // 失败不覆盖旧列表，只加一条不遮挡内容的提示
                loadLocal(r, feed.feedId)
                if (session != readingSession || repo !== r) return
                _state.value = _state.value.copy(
                    refreshing = false,
                    offlineNotice = describeRefreshError(outcome.error),
                )
            }
        }
    }

    /** 下拉刷新：不受 15 分钟限制 */
    fun refresh() {
        if (_state.value.refreshing) return
        val session = readingSession
        viewModelScope.launch {
            val r = ensureRepo() ?: return@launch
            if (_state.value.allFeeds) {
                launchAllRefresh(r, force = true, session)
                return@launch
            }
            val feed = _state.value.currentFeed ?: return@launch
            refreshInternal(r, feed, force = true, session = session)
        }
    }

    fun selectFeed(feedId: String) {
        allRefreshJob?.cancel()
        val session = ++readingSession
        viewModelScope.launch {
            val r = ensureRepo() ?: return@launch
            val feed = _state.value.feeds.firstOrNull { it.feedId == feedId } ?: return@launch
            val loaded = r.loadLocal(feedId)
            val total = r.localCount(feedId)
            if (repo !== r || session != readingSession) return@launch
            _state.value = _state.value.copy(
                currentFeedId = feedId,
                allFeeds = false,
                loaded = loaded,
                offlineNotice = null,
                lastSuccessAt = null,
                hasMoreHistory = loaded.size < total || r.canFetchHistory,
                historyOffset = 0,
                historyExhausted = false,
                loadingMore = false,
                refreshing = false,
                // 切换源保留筛选条件（原型 08：关闭后更新列表，不清空用户已选的筛选）
            )
            settings.setLastReadingPosition(feedId, null, null, scopeKey)
            refreshInternal(r, feed, force = false, session = session)
        }
    }

    fun setScope(scope: ArticleFilter.DateScope) {
        _state.value = _state.value.copy(criteria = _state.value.criteria.copy(scope = scope))
        reloadAllFilter()
    }

    fun toggleUnreadOnly() {
        val c = _state.value.criteria
        _state.value = _state.value.copy(criteria = c.copy(unreadOnly = !c.unreadOnly))
        reloadAllFilter()
    }

    /** 清除筛选（L06/C05）：日期回「全部」、未读开关关——空态提示里给的真实出路 */
    fun clearFilters() {
        _state.value = _state.value.copy(criteria = ArticleFilter.Criteria())
        reloadAllFilter()
    }

    private fun reloadAllFilter() {
        if (!_state.value.allFeeds) return
        val r = repo ?: return
        val session = readingSession
        _state.value = _state.value.copy(loaded = emptyList(), loadingMore = true)
        viewModelScope.launch { loadAllLocal(r, session, ArticleRepository.LIST_PAGE_SIZE, pageLoad = true) }
    }

    /**
     * 上次离开这一源时的列表位置（契约 §7：稳定文章 id + 偏移）。
     * 锚点属于别的源时返回 null（换源不带着上一个源的位置），由界面从头显示。
     */
    suspend fun savedScrollAnchor(): ScrollAnchor.ListAnchor? {
        val snapshot = settings.current()
        if (snapshot.lastReadingScopeKey != null && snapshot.lastReadingScopeKey != scopeKey) return null
        if (snapshot.lastReadingAllFeeds != _state.value.allFeeds || snapshot.lastFeedId != _state.value.currentFeedId) return null
        return ScrollAnchor.decodeList(snapshot.lastScrollAnchor)
    }

    /** 滚动停下时记住位置：记的是文章 id + 偏移，不是下标 */
    fun rememberScrollAnchor(localId: String, offset: Int) {
        val feedId = _state.value.currentFeedId
        val all = _state.value.allFeeds
        if (!all && feedId == null) return
        val key = scopeKey
        viewModelScope.launch {
            val currentKey = settings.currentScopeKey()
            if (currentKey != key || _state.value.currentFeedId != feedId || _state.value.allFeeds != all) return@launch
            settings.setLastReadingPosition(feedId, settings.current().lastFilter, ScrollAnchor.encodeList(localId, offset), key, all,
                if (all) _state.value.loaded.size else ArticleRepository.LIST_PAGE_SIZE)
        }
    }

    /** 加载更多历史：追加到本地，不产生重复（契约 P02：失败保留已有页） */
    fun loadMoreHistory() {
        val current = _state.value
        if (current.allFeeds) {
            if (current.loadingMore || !current.hasMoreHistory) return
            val r = repo ?: return
            val session = readingSession
            _state.value = current.copy(loadingMore = true)
            viewModelScope.launch { loadAllLocal(r, session, current.loaded.size + ArticleRepository.LIST_PAGE_SIZE, pageLoad = true) }
            return
        }
        val feed = current.currentFeed ?: return
        if (current.loadingMore || current.refreshing || !current.hasMoreHistory) return
        val session = readingSession
        _state.value = current.copy(loadingMore = true, offlineNotice = null)
        viewModelScope.launch {
            val r = ensureRepo() ?: return@launch
            if (feed.instanceKey != scopeKey || session != readingSession) return@launch
            runCatching {
                // Show saved pages first. The remote cursor is independent of the merged local count.
                val outcome = if (r.localCount(feed.feedId) > current.loaded.size) null
                    else r.loadMoreHistory(feed, offset = current.historyOffset)
                if (outcome?.error != null) throw outcome.error
                val reloaded = r.loadLocal(feed.feedId, current.loaded.size + ArticleRepository.LIST_PAGE_SIZE)
                val total = r.localCount(feed.feedId)
                if (repo !== r || session != readingSession || _state.value.currentFeedId != feed.feedId) return@launch
                val exhausted = outcome?.let { !it.hasMore } ?: current.historyExhausted
                _state.value = _state.value.copy(
                    loadingMore = false,
                    loaded = reloaded,
                    historyOffset = outcome?.nextOffset ?: current.historyOffset,
                    historyExhausted = exhausted,
                    hasMoreHistory = reloaded.size < total || (r.canFetchHistory && !exhausted),
                )
            }.onFailure { error ->
                if (repo === r && session == readingSession && _state.value.currentFeedId == feed.feedId) {
                    _state.value = _state.value.copy(loadingMore = false, offlineNotice = describeRefreshError(error))
                }
            }
        }
    }
}
