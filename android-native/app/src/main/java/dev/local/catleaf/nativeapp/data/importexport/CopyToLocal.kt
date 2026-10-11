package dev.local.catleaf.nativeapp.data.importexport

import androidx.room.withTransaction
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import java.security.MessageDigest

/**
 * 复制已下载数据到本机（契约 8.4，L05/C08）：source=当前实例，target=本机。
 *
 * 规则：
 * - 只处理**选择范围内**已下载数据；删除源后的孤立收藏作为独立可选组；
 * - 规范化 feed URL 映射到目标 feedId；按源+GUID、其次 link 匹配；**新建目标 localId**；
 * - 保留目标自定义名称/分组；新源沿源分组；
 * - 已读/收藏 **OR 合并**（备份 false 永不取消本机 true）；目标收藏时间保留，否则沿源；
 * - 目标已有正文不被覆盖；目标缺正文才复制来源已缓存净化正文；仅书签如实标识；
 * - 不复制密钥/实例身份/AI 结果/读位（正文指纹不一致时目标保留原读位，结果中说明）；
 * - 预检查（数量/空间）失败停止并提供缩小选择，不静默少复制；
 * - 写入用**单个 Room 事务**，失败回滚本机、源实例不动；重复复制幂等。
 */
object CopyToLocal {

    /** 预览/复制的最小来源快照（从 DB 行抽取，便于 JVM 纯测） */
    data class SourceArticle(
        val localId: String,
        val feedId: String,
        val guid: String?,
        val link: String?,
        val title: String,
        val feedTitle: String,
        val feedUrl: String,
        val isRead: Boolean,
        val isStarred: Boolean,
        val starredAt: Long?,
        val content: String?,
        val contentSource: String,
        val thumbnail: String?,
    )

    data class TargetFeed(val feedId: String, val url: String, val title: String, val category: String)

    data class Plan(
        /** 需要新建的目标源（URL 规范化后不存在的） */
        val newFeeds: List<TargetFeed>,
        /** URL 已存在的目标源（保留目标名称/分组，不动） */
        val existingFeedUrls: Set<String>,
        /** 需要写入的文章行（新建目标 localId） */
        val articlesToInsert: List<ArticleRow>,
        val contentUpdates: List<ContentUpdate>,
        /** 已存在目标文章需要 OR 合并的状态（localId → read/starred/starredAt） */
        val stateMerges: List<StateMerge>,
        /** 仅书签（两侧都无可保存文本）计数 */
        val bookmarkOnly: Int,
        /** 正文复制字节数（预算预检查用） */
        val contentBytes: Long,
        /** 被排除的孤立收藏（来源源未选择但已收藏） */
        val orphanedStarredAvailable: Int,
    ) {
        val newArticleCount: Int get() = articlesToInsert.size
        val mergedCount: Int get() = stateMerges.size
    }

    data class ArticleRow(
        val localId: String,
        val feedId: String,
        val guid: String?,
        val link: String?,
        val title: String,
        val feedTitle: String,
        val feedUrl: String,
        val isRead: Boolean,
        val isStarred: Boolean,
        val starredAt: Long?,
        val content: String?,
        val contentSource: String,
        val thumbnail: String?,
    )

    data class StateMerge(val localId: String, val isRead: Boolean, val isStarred: Boolean, val starredAt: Long?)
    data class ContentUpdate(val localId: String, val content: String, val contentSource: String, val thumbnail: String?)

    data class Budget(val maxFeeds: Int, val currentFeeds: Int, val remainingBytes: Long)

    /**
     * 纯计划（JVM 可测）。@param selectedSourceFeedIds 选择的数据源；
     * @param includeOrphanedStarred 是否带上「来源源未选择但已收藏」的孤立收藏组。
     */
    fun plan(
        sourceFeeds: List<TargetFeed>,
        sourceArticles: List<SourceArticle>,
        targetFeeds: List<TargetFeed>,
        targetArticles: List<SourceArticle>,
        selectedSourceFeedIds: Set<String>,
        includeOrphanedStarred: Boolean,
        budget: Budget,
    ): Result<Plan> {
        val urlOf = sourceFeeds.associate { it.feedId to (EndpointPolicy.normalizeFeedUrl(it.url) ?: it.url) }
        val targetByUrl = targetFeeds.associate { (EndpointPolicy.normalizeFeedUrl(it.url) ?: it.url) to it }

        val selected = selectedSourceFeedIds.filter { urlOf.containsKey(it) }.toSet()
        val newFeeds = mutableListOf<TargetFeed>()
        val existingUrls = mutableSetOf<String>()
        for (feedId in selected) {
            val url = urlOf[feedId]!!
            val target = targetByUrl[url]
            if (target == null) newFeeds += sourceFeeds.first { it.feedId == feedId } else existingUrls += url
        }
        if (budget.currentFeeds + newFeeds.size > budget.maxFeeds) {
            return Result.failure(IllegalArgumentException(
                "本机已有 ${budget.currentFeeds} 个订阅，复制将超出 $${budget.maxFeeds} 上限：请缩小选择范围",
            ))
        }

        // 目标文章索引：feedUrl→(guid→row, link→row)（与 ArticleMerger 同一匹配次序）
        val targetNormUrl = { u: String -> EndpointPolicy.normalizeFeedUrl(u) ?: u }
        val targetByGuid = mutableMapOf<Pair<String, String>, SourceArticle>()
        val targetByLink = mutableMapOf<Pair<String, String>, SourceArticle>()
        for (a in targetArticles) {
            val url = targetNormUrl(a.feedUrl)
            a.guid?.let { targetByGuid.putIfAbsent(url to it, a) }
            a.link?.let { targetByLink.putIfAbsent(url to it, a) }
        }

        val articlesToInsert = mutableListOf<ArticleRow>()
        val contentUpdates = mutableListOf<ContentUpdate>()
        val stateMerges = mutableListOf<StateMerge>()
        var bookmarkOnly = 0
        var contentBytes = 0L

        val inScope = sourceArticles.filter {
            it.feedId in selected || (includeOrphanedStarred && it.isStarred && it.feedId !in selected)
        }
        for (a in inScope) {
            // 匹配身份用「来源源的规范化 URL」（契约 8.4：先映射 feed URL，再按源+GUID/link 匹配）；
            // 孤立收藏（源已删、不在映射表）回退到文章行自带快照 URL
            val url = urlOf[a.feedId] ?: targetNormUrl(a.feedUrl)
            val existing = a.guid?.let { targetByGuid[url to it] } ?: a.link?.let { targetByLink[url to it] }
            if (existing != null) {
                if (existing.content.isNullOrBlank() && !a.content.isNullOrBlank()) {
                    contentUpdates += ContentUpdate(existing.localId, a.content, a.contentSource, a.thumbnail)
                    contentBytes += a.content.toByteArray(Charsets.UTF_8).size.toLong()
                }
                // 并集合并：备份 false 永不取消本机 true；目标收藏时间保留
                val mergedRead = a.isRead || existing.isRead
                val mergedStar = a.isStarred || existing.isStarred
                if (mergedRead != existing.isRead || mergedStar != existing.isStarred) {
                    stateMerges += StateMerge(existing.localId, mergedRead, mergedStar, existing.starredAt ?: a.starredAt)
                }
                continue
            }
            val localId = newLocalId(url, a.guid, a.link) ?: continue // 双缺身份：跳过（不进目标）
            val hasText = !a.content.isNullOrBlank()
            if (!hasText) bookmarkOnly++
            a.content?.let { contentBytes += it.toByteArray(Charsets.UTF_8).size.toLong() }
            articlesToInsert += ArticleRow(
                localId = localId,
                feedId = url,
                guid = a.guid,
                link = a.link,
                title = a.title,
                feedTitle = a.feedTitle,
                // 目标命名空间里 feedUrl = 映射后的规范 URL（与目标 feed 行一致），
                // 这样二次复制按「源+GUID」能命中已存在行，实现幂等
                feedUrl = url,
                isRead = a.isRead,
                isStarred = a.isStarred,
                starredAt = a.starredAt,
                content = a.content,
                contentSource = a.contentSource,
                thumbnail = a.thumbnail,
            )
        }
        if (contentBytes > budget.remainingBytes) {
            return Result.failure(IllegalArgumentException(
                "复制正文约 ${contentBytes / 1024} KB，超出本机剩余缓存预算：请缩小选择范围",
            ))
        }
        val orphaned = sourceArticles.count { it.isStarred && it.feedId !in selected }
        return Result.success(Plan(newFeeds, existingUrls, articlesToInsert, contentUpdates, stateMerges, bookmarkOnly, contentBytes, orphaned))
    }

    /** 目标 localId 全现算（不复用来源 id）：url|guid|link 的稳定哈希，幂等复制的键 */
    private fun newLocalId(feedUrl: String, guid: String?, link: String?): String? {
        val key = guid?.takeIf { it.isNotBlank() } ?: link?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest("$feedUrl|$key".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** 单事务写入（失败整体回滚，源实例不动）；返回实际写入计数 */
    suspend fun execute(
        db: CatLeafDatabase,
        targetScopeKey: String,
        plan: Plan,
    ): Result<Triple<Int, Int, Int>> = runCatching {
        db.withTransaction {
            db.feeds().upsertAll(plan.newFeeds.map { f ->
                FeedEntity(
                    instanceKey = targetScopeKey, feedId = f.url, url = f.url,
                    title = f.title, category = f.category,
                    status = "pending", lastSuccessAt = null,
                )
            })
            for (row in plan.articlesToInsert) {
                val existed = db.articles().get(row.localId) != null
                if (existed) continue // 幂等：并发/重复复制的第二落点
                db.articles().upsertArticle(
                    dev.local.catleaf.nativeapp.data.local.ArticleEntity(
                        localId = row.localId, instanceKey = targetScopeKey, feedId = row.feedId,
                        guid = row.guid, link = row.link, title = row.title,
                        publishedAt = null, feedTitle = row.feedTitle, feedUrl = row.feedUrl,
                        isRead = row.isRead, isStarred = row.isStarred, starredAt = row.starredAt,
                        stateUpdatedAt = System.currentTimeMillis(),
                    ),
                )
                if (row.content != null) {
                    // 目标缺正文才复制（计划层保证）；来源快照的 contentSource/缩略图随行走
                    db.articles().upsertContent(
                        ArticleContentEntity(
                            localId = row.localId,
                            content = row.content,
                            summary = null,
                            thumbnail = row.thumbnail,
                            contentSource = row.contentSource,
                            contentHash = dev.local.catleaf.nativeapp.data.article.ArticleIdentity
                                .contentFingerprint(row.content),
                            searchText = dev.local.catleaf.nativeapp.data.article.ArticleFilter
                                .summaryPreview(row.content, limit = 500),
                            searchBodyText = dev.local.catleaf.nativeapp.data.article.SearchQuery.bodyText(row.content),
                            lastAccessedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            for (row in plan.contentUpdates) {
                if (!db.articles().getContent(row.localId)?.content.isNullOrBlank()) continue
                db.articles().upsertContent(ArticleContentEntity(
                    localId = row.localId, content = row.content, summary = null,
                    thumbnail = row.thumbnail, contentSource = row.contentSource,
                    contentHash = dev.local.catleaf.nativeapp.data.article.ArticleIdentity.contentFingerprint(row.content),
                    searchText = dev.local.catleaf.nativeapp.data.article.ArticleFilter.summaryPreview(row.content, limit = 500),
                    searchBodyText = dev.local.catleaf.nativeapp.data.article.SearchQuery.bodyText(row.content),
                    lastAccessedAt = System.currentTimeMillis(),
                ))
            }
            for (m in plan.stateMerges) {
                db.articles().setRead(m.localId, m.isRead, System.currentTimeMillis())
                if (m.isStarred) db.articles().setStarred(m.localId, true, m.starredAt, System.currentTimeMillis())
            }
            Triple(plan.newFeeds.size, plan.newArticleCount, plan.mergedCount)
        }
    }
}
