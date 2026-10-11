package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 当前源的阅读列表数据（契约 P02、4.2 的刷新规则）。
 *
 * - **本地先展示**：列表永远来自 Room，网络只负责补充；
 * - **前台 15 分钟按需刷新**：超时才自动刷，下拉刷新不受此限制；
 * - **同源并发刷新去重**：同一源同时最多一个刷新在跑，第二个请求直接复用结果而不是再发一次；
 * - **抓取失败不清空**：失败只返回失败原因，本地数据一行不动；
 * - **历史分页用于补缺**：按 100 条一页，追加时不产生重复。
 */
class ArticleRepository(
    private val db: CatLeafDatabase,
    /**
     * 实例 API（契约 8.1/8.2）：只有实例范围且密钥可用时才有。
     * null = 本机范围或实例密钥暂不可读——列表/收藏照常读写本地，刷新与历史给明确失败，
     * 本机抓取能力由 [localFeedXml] 提供。
     */
    private val api: FeedApi?,
    private val instanceKey: String,
    /** 可注入的时钟，便于测试 15 分钟规则 */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 普通缓存预算（契约 4.3）：写入前腾空间；失败就**不写**，保留旧内容 */
    private val cleanup: dev.local.catleaf.nativeapp.data.local.CacheCleanup,
    /**
     * 本机范围的源抓取（契约 8.2）：直接公开抓取源 URL，不走实例。
     * 设置后 [api] 可为空——15 分钟/并发去重/预算/合并规则与本机完全一致。
     */
    private val localFeedXml: (suspend (FeedEntity) -> Result<String>)? = null,
) {

    companion object {
        const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        const val HISTORY_PAGE_SIZE = 100

        /** 列表优先展示的已加载页大小 */
        const val LIST_PAGE_SIZE = 200
    }

    sealed class RefreshOutcome {
        data class Refreshed(
            val inserted: Int,
            val unidentified: Int,
            val conflicts: Int,
            val historyNotSynced: Boolean = false,
        ) : RefreshOutcome()
        /** 距上次成功不足 15 分钟（或等待期间别人已经刷完），未发请求 */
        object SkippedFresh : RefreshOutcome()
        data class Failed(val error: Throwable) : RefreshOutcome()
    }

    data class HistoryOutcome(
        val appended: Int,
        val total: Int,
        val hasMore: Boolean,
        val error: Throwable? = null,
        val nextOffset: Int = 0,
    )

    val canFetchHistory: Boolean get() = api != null && localFeedXml == null

    // 每个源一把锁 + 最近一次成功刷新的时间：并发调用第二个会等第一个，然后按 15 分钟规则跳过
    private val locks = mutableMapOf<String, Mutex>()
    private val lastSuccess = mutableMapOf<String, Long>()

    private fun lockFor(feedId: String): Mutex = synchronized(locks) {
        locks.getOrPut(feedId) { Mutex() }
    }

    /** 本地已加载的条目（含本地日期、短摘要与封面），供筛选、计数与列表显示（T22 F09） */
    suspend fun loadLocal(feedId: String, limit: Int = LIST_PAGE_SIZE, offset: Int = 0): List<ArticleFilter.LoadedRow> =
        db.articles().listByFeedWithPreview(instanceKey, feedId, limit, offset)
            .map { ArticleFilter.toLoadedRow(it.article, it.previewRaw, it.thumbnail) }

    suspend fun localCount(feedId: String): Int = db.articles().countByFeed(instanceKey, feedId)

    /** Filter before paging, so a match beyond the first 200 rows remains reachable. */
    suspend fun loadAllSubscribed(criteria: ArticleFilter.Criteria, limit: Int): Pair<List<ArticleFilter.LoadedRow>, Int> {
        val date = when (val scope = criteria.scope) {
            ArticleFilter.DateScope.Today -> java.time.LocalDate.now()
            is ArticleFilter.DateScope.On -> java.time.LocalDate.parse(scope.localDate)
            else -> null
        }
        val mode = if (date != null) "on" else if (criteria.scope == ArticleFilter.DateScope.Unknown) "unknown" else "all"
        val zone = java.time.ZoneId.systemDefault()
        val start = date?.atStartOfDay(zone)?.toInstant()?.toString()
        val end = date?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toString()
        val dao = db.articles()
        val rows = dao.listAllSubscribedWithPreview(instanceKey, criteria.unreadOnly, mode, start, end, limit)
            .map { ArticleFilter.toLoadedRow(it.article, it.previewRaw, it.thumbnail) }
        return rows to dao.countAllSubscribed(instanceKey, criteria.unreadOnly, mode, start, end)
    }

    /** 是否到了该自动刷新的时间（前台按需刷新，不是后台轮询） */
    suspend fun isStale(feedId: String): Boolean {
        val last = lastSuccess[feedId] ?: return true
        return now() - last >= REFRESH_INTERVAL_MS
    }

    /**
     * 前台按需刷新：超过 15 分钟才真正发请求。
     * 并发调用同一源时第二个会等待并在拿到锁后发现自己已“新鲜”，于是跳过——**只发一次请求**。
     */
    suspend fun refreshIfStale(feed: FeedEntity, force: Boolean = false): RefreshOutcome {
        val requestedAt = now()
        return lockFor(feed.feedId).withLock {
            if (!force && !isStale(feed.feedId)) {
                return@withLock RefreshOutcome.SkippedFresh
            }
            // 等锁期间如果别人已经刷完（成功时间不早于本次请求发起），直接复用它的结果。
            // 只看 force 会让两个同时到达的手动刷新各抓一次——手动刷新只该免掉 15 分钟间隔，
            // 不该免掉同源并发去重（契约 P02：同源并发刷新去重）。
            if ((lastSuccess[feed.feedId] ?: 0L) >= requestedAt) {
                return@withLock RefreshOutcome.SkippedFresh
            }
            doRefresh(feed)
        }
    }

    /** 下拉刷新：忽略 15 分钟间隔，但仍受同源去重约束 */
    suspend fun refreshNow(feed: FeedEntity): RefreshOutcome = refreshIfStale(feed, force = true)

    private suspend fun doRefresh(feed: FeedEntity): RefreshOutcome {
        // 先为新写入腾空间；腾不动（Room 故障）时不合并抓取结果——契约 4.3
        // "为新写入腾空间失败时保留旧内容并提示"
        runCatching { cleanup.enforceBudget() }.onFailure { error ->
            return RefreshOutcome.Failed(CleanupFailure(error))
        }

        val xml = when {
            localFeedXml != null -> localFeedXml.invoke(feed)
            api != null -> api.fetchFeedXml(feed.feedId)
            else -> return RefreshOutcome.Failed(NoInstanceApi())
        }.getOrElse { error ->
            // 失败不清空本地：直接返回原因，什么都不写
            return RefreshOutcome.Failed(error)
        }
        val feedApi = api // 供历史写入（本机范围 localFeedXml != null 且 api 可能非空——按范围判定不发）

        val parsed = FeedParser.parse(xml, feed.url).getOrElse { error ->
            return RefreshOutcome.Failed(error)
        }

        val result = ArticleMerger(db.articles()).mergeRefresh(
            instanceKey = instanceKey,
            feedId = feed.feedId,
            feedTitle = parsed.title.ifBlank { feed.title },
            feedUrl = feed.url,
            items = parsed.items.map {
                ArticleMerger.NewArticle(
                    title = it.title,
                    guid = it.guid,
                    link = it.link,
                    publishedAt = it.publishedAt,
                    content = it.content,
                    description = it.description,
                    thumbnail = it.thumbnail,
                )
            },
        )

        lastSuccess[feed.feedId] = now()
        updateFeedState(feed)

        // 历史尽力写入（L07/契约 8.3）：失败不影响本地阅读，但要标未同步。
        var historyNotSynced = false
        if (api != null && localFeedXml == null && parsed.items.isNotEmpty()) {
            val upserts = parsed.items.map {
                dev.local.catleaf.nativeapp.data.network.HistoryUpsert(
                    title = it.title, guid = it.guid, link = it.link,
                    pubDate = it.publishedAt, description = it.description,
                    content = it.content, thumbnail = it.thumbnail,
                )
            }
            upserts.chunked(100).forEach { batch ->
                if (runCatching { feedApi.upsertHistory(feed.feedId, batch).getOrThrow() }.isFailure) {
                    historyNotSynced = true
                }
            }
        }

        return RefreshOutcome.Refreshed(result.inserted, result.skippedUnidentified, result.conflicts.size, historyNotSynced)
    }

    /**
     * 加载更多历史：每页 100 条，**只补缺**，不会覆盖当前 RSS 正文或用户状态。
     * 追加后返回 total 与是否还有更多，供界面显示“已显示当前加载内容”。
     */
    suspend fun loadMoreHistory(feed: FeedEntity, offset: Int): HistoryOutcome {
        // 本机范围没有远端历史可补：只翻本地已保存页（契约 8.2），不报错
        if (!canFetchHistory) {
            return HistoryOutcome(0, db.articles().countByFeed(instanceKey, feed.feedId), hasMore = false, error = null)
        }
        // 历史追加同样先腾空间；腾不动就不拉不发、不写（契约 4.3）
        runCatching { cleanup.enforceBudget() }.onFailure { error ->
            return HistoryOutcome(0, db.articles().countByFeed(instanceKey, feed.feedId), false, CleanupFailure(error))
        }
        val page = api!!.fetchHistory(feed.feedId, HISTORY_PAGE_SIZE, offset).getOrElse { error ->
            return HistoryOutcome(0, db.articles().countByFeed(instanceKey, feed.feedId), false, error)
        }

        val result = ArticleMerger(db.articles()).mergeHistory(
            instanceKey = instanceKey,
            feedId = feed.feedId,
            feedTitle = feed.title,
            feedUrl = feed.url,
            items = page.items.map {
                ArticleMerger.NewArticle(
                    title = it.title,
                    guid = it.guid,
                    link = it.link,
                    publishedAt = ArticleIdentity.normalizePublishedAt(it.pubDate),
                    content = it.content,
                    description = it.description,
                )
            },
        )

        val nextOffset = offset + page.items.size
        return HistoryOutcome(
            appended = result.inserted,
            total = page.total,
            // Count received rows in the server cursor, including duplicates and invalid identities.
            hasMore = page.items.isNotEmpty() && nextOffset < page.total,
            nextOffset = nextOffset,
        )
    }

    /** 刷新后同步源的最近成功时间与状态（供订阅管理页显示） */
    private suspend fun updateFeedState(feed: FeedEntity) {
        db.feeds().setStatus(
            instanceKey = instanceKey,
            feedId = feed.feedId,
            status = "ok",
            lastSuccessAt = now(),
        )
        // 注意：这里**不能**再 upsert 整行——传入的行带着旧 status/lastSuccessAt，
        // 会把上面刚写的状态覆盖回 pending（本机新增后的首次验证实测踩到）。
        // 源名以用户自定义/镜像为准（FeedStore 决定），解析标题不在此回写。
    }
}

/** 腾空间失败：不是网络问题，提示要说清"没有丢旧内容、只是这次没写入" */
class CleanupFailure(cause: Throwable) : Exception(cause)

/** 当前范围没有实例 API（本机范围，或实例密钥暂不可读）：缓存仍可读（契约 8.1） */
class NoInstanceApi : Exception("当前范围没有可用的实例服务")

/** 便于界面把失败原因说清楚：网络问题 vs 内容问题分开展示 */
fun describeRefreshError(error: Throwable): String = when (error) {
    is CleanupFailure -> "本地缓存清理失败，已保留原内容，本次抓取未写入（${error.cause?.message ?: "未知原因"}）"
    is NoInstanceApi -> "实例连接暂不可用，正在显示已保存内容"
    is EndpointError.Timeout -> "请求超时，正在显示已保存内容"
    is EndpointError.Unreachable -> "无法连接，正在显示已保存内容"
    // 本机范围走公开抓取：同样说明旧内容仍在，不直接露出底层异常文本
    is PublicFetch.FetchFailure.Unreachable -> "无法连接，正在显示已保存内容"
    is PublicFetch.FetchFailure.Timeout -> "请求超时，正在显示已保存内容"
    is PublicFetch.FetchFailure.Http -> "订阅地址返回 ${error.code}，正在显示已保存内容"
    is EndpointError.Unauthorized -> "密钥无效或已失效，本地内容仍可阅读"
    is EndpointError.Forbidden -> "后端不允许抓取该地址（403），本地内容仍可阅读"
    is EndpointError.NotConfigured -> "后端未配置密钥（503）"
    is FeedParser.FeedParseError.HtmlInsteadOfXml -> "该地址返回的是网页而非订阅内容"
    is FeedParser.FeedParseError.Malformed -> error.message ?: "订阅内容格式错误"
    is FeedParser.FeedParseError.NotAFeed -> "该地址不是 RSS/Atom 订阅"
    else -> error.message ?: "刷新失败，正在显示已保存内容"
}
