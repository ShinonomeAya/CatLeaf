package dev.local.catleaf.nativeapp.data.importexport

import dev.local.catleaf.nativeapp.data.article.ArticleIdentity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.opml.Opml

/** 要恢复的收藏但没有对应文章的行：以独立书签插入（不建正文行——"正文未备份"） */
data class BookmarkPlan(
    val localId: String,
    /** 映射不到已导入的源时为 [StateRestore.ORPHAN_FEED_ID] */
    val feedId: String,
    val title: String,
    val feedUrl: String,
    val feedTitle: String,
    val guid: String?,
    val link: String?,
    val isRead: Boolean,
)

/**
 * 本地写入计划（一次事务执行的东西；纯函数，JVM 可测）。
 * 恢复分两步：先远端逐源导入（[feedsToImport]），刷新后用最新的 localFeeds 再算本计划。
 */
data class RestorePlan(
    /** read 需从 false→true 的现有行 */
    val readUpdates: List<String>,
    /** starred 需从 false→true 的现有行 */
    val starUpdates: List<String>,
    /** 收藏但目标里没有的行：以独立书签新建 */
    val bookmarks: List<BookmarkPlan>,
    /** 只读不收藏、且目标里没有：不创建身份行（跳过） */
    val skippedUnknown: Int,
    /** 引用了未导入成功源的收藏：按孤立书签恢复（feedId 用空串，见 identity 说明） */
    val orphanBookmarks: Int,
) {
    val writes: Int get() = readUpdates.size + starUpdates.size + bookmarks.size
    val isEmpty: Boolean get() = writes == 0
}

/**
 * 合并规则（契约 §6）：
 * - **已读/收藏是并集**：任一侧 true 保留 true；备份的 false **永不**取消本机现有状态——
 *   所以"已有相反状态"（本机已收藏、备份未收藏）不会被清掉；
 * - 备份源按**规范化 URL** 映射到目标实例的现有源；映射不上的不写状态（只读跳过），
 *   但其中收藏按"已删除源/失败源"的孤立书签恢复（保留链接与标题）；
 * - localId 一律现算（`localIdFor(instanceKey, feedId, guid, link)`），
 *   **不复用备份里的任何 id**（跨实例约束）——因此第二次恢复算出的更新集为空：幂等。
 */
object StateRestore {

    /** 备份源里"目标还没有"的：交给 [dev.local.catleaf.nativeapp.data.opml.OpmlImporter] 逐源导入 */
    fun feedsToImport(doc: StateDocument, localFeeds: List<FeedEntity>): List<Opml.Entry> =
        doc.feeds.filter { backup ->
            localFeeds.none { EndpointPolicy.isSameFeedUrl(it.url, backup.url) }
        }.distinctBy { EndpointPolicy.normalizeFeedUrl(it.url) ?: it.url }
            .map { Opml.Entry(title = it.title, xmlUrl = it.url, group = it.category) }

    /** 预览计数：目标已有的订阅数（"已存在"），和订阅总数 */
    fun feedPreview(doc: StateDocument, localFeeds: List<FeedEntity>): Pair<Int, Int> {
        val existing = doc.feeds.count { backup -> localFeeds.any { EndpointPolicy.isSameFeedUrl(it.url, backup.url) } }
        return existing to doc.feeds.size
    }

    fun planLocal(
        doc: StateDocument,
        instanceKey: String,
        localFeeds: List<FeedEntity>,
        localArticles: List<ArticleEntity>,
    ): RestorePlan {
        val feedIdByUrl = localFeeds.associate { feed ->
            (EndpointPolicy.normalizeFeedUrl(feed.url) ?: feed.url) to feed.feedId
        }
        // 备份文章按 (源URL, guid)/(源URL, link) 查现有行——与 ArticleDao 的查找列同规则
        val byFeedGuid = localArticles.mapNotNull { a ->
            a.guid?.let { Triple(a.instanceKey, a.feedId, it) to a }
        }.toMap()
        val byFeedLink = localArticles.mapNotNull { a ->
            a.link?.let { Triple(a.instanceKey, a.feedId, it) to a }
        }.toMap()

        val readUpdates = mutableListOf<String>()
        val starUpdates = mutableListOf<String>()
        val bookmarks = mutableListOf<BookmarkPlan>()
        var skippedUnknown = 0
        var orphanBookmarks = 0

        for (entry in doc.articles) {
            if (!entry.isRead && !entry.isStarred) continue // 无状态可恢复
            val guid = ArticleIdentity.normalizeGuid(entry.guid)
            val link = ArticleIdentity.normalizeLink(entry.link)
            val normalizedFeed = EndpointPolicy.normalizeFeedUrl(entry.feedUrl)
            val feedId = normalizedFeed?.let { feedIdByUrl[it] }

            val existing = if (feedId != null) {
                (if (guid != null) byFeedGuid[Triple(instanceKey, feedId, guid)] else null)
                    ?: (if (link != null) byFeedLink[Triple(instanceKey, feedId, link)] else null)
            } else {
                null // 源没导入：不可能对上任一行，收藏走孤立书签
            }

            when {
                existing != null -> {
                    if (entry.isRead && !existing.isRead) readUpdates += existing.localId
                    if (entry.isStarred && !existing.isStarred) starUpdates += existing.localId
                }

                // 收藏但目标没有这行：独立书签（正文未备份，界面按"仅书签"显示）
                entry.isStarred -> {
                    // 同源孤立书签兜底：上次导入失败留下的 feedId="" 在源导入成功后
                    // 不该被再插一份；其他源碰巧相同的 guid 不能合并。
                    val globalMatch = guid?.let { g ->
                        localArticles.firstOrNull {
                            it.instanceKey == instanceKey && it.feedId == ORPHAN_FEED_ID && it.guid == g &&
                                EndpointPolicy.isSameFeedUrl(it.feedUrl, entry.feedUrl)
                        }
                    }
                    if (globalMatch != null) {
                        if (!globalMatch.isStarred) starUpdates += globalMatch.localId
                    } else {
                        val identityFeedId = feedId ?: ORPHAN_FEED_ID
                        val localId = ArticleIdentity.localIdFor(instanceKey, identityFeedId, guid, link)
                        if (localId == null) {
                            skippedUnknown++ // guid/link 都在解析阶段判过无效；这里只兜底
                        } else {
                            if (feedId == null) orphanBookmarks++
                            bookmarks += BookmarkPlan(
                                localId = localId,
                                feedId = identityFeedId,
                                title = entry.title,
                                feedUrl = entry.feedUrl,
                                feedTitle = entry.feedTitle,
                                guid = guid,
                                link = link,
                                isRead = entry.isRead,
                            )
                        }
                    }
                }

                else -> skippedUnknown++ // 只有已读、目标里没有：不凭空建未读过的身份行
            }
        }

        return RestorePlan(readUpdates, starUpdates, bookmarks, skippedUnknown, orphanBookmarks)
    }

    /** 孤立书签的 feedId 占位：不属于本实例任何源；guid/link 照存，之后源导入成功也不与真实行冲突 */
    const val ORPHAN_FEED_ID = ""
}
