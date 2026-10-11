package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleDao
import dev.local.catleaf.nativeapp.data.local.ArticleEntity

/**
 * 文章合并（契约 4.2）。
 *
 * 两条硬要求驱动这里的写法：
 * 1. **刷新和历史合并不覆盖已读/收藏与新正文**。`ArticleEntity` 的 upsert 是整行替换，
 *    所以必须先读回旧行、把状态字段带过去；否则每次刷新都会把已读/收藏清成默认值。
 * 2. **回包只能写入发起时的 namespace**。instanceKey 与 feedId 由调用方在发请求前捕获并传入，
 *    这里**不去读“当前实例”设置**——切换实例期间的旧回包因此无处可写错。
 */
class ArticleMerger(private val dao: ArticleDao) {

    companion object {
        /** 正文来自 RSS 的 content:encoded/content */
        const val SOURCE_RSS = "rss"

        /** 本次刷新没有正文，只有摘要：详情页据此显示“摘要（无正文）”并提供获取全文 */
        const val SOURCE_SUMMARY = "summary"

        /** 用户点击获取全文后由 ReaderViewModel 写入；词汇表集中在这里，避免各处字符串漂移 */
        const val SOURCE_EXTRACTED = "extracted"
    }

    /** 一次合并的结果，供界面显示“部分条目异常”“缺少标识未保存”等 */
    data class MergeResult(
        val inserted: Int,
        val updated: Int,
        val skippedUnidentified: Int,
        /** GUID 与 link 分别命中不同记录：保留两条、不自动合并删除 */
        val conflicts: List<String>,
        /** 正文来自 RSS 还是历史补缺，便于界面区分 */
        val filledFromHistory: Int = 0,
    )

    /**
     * 刷新合并：RSS 当前内容优先，保留用户状态。
     * 已存在记录**保持原 localId**（即使这次是用另一个键命中的）。
     */
    suspend fun mergeRefresh(
        instanceKey: String,
        feedId: String,
        feedTitle: String,
        feedUrl: String,
        items: List<NewArticle>,
        now: Long = System.currentTimeMillis(),
    ): MergeResult {
        var inserted = 0
        var updated = 0
        var unidentified = 0
        val conflicts = mutableListOf<String>()

        for (item in items) {
            val guid = ArticleIdentity.normalizeGuid(item.guid)
            val link = ArticleIdentity.normalizeLink(item.link)

            // 先按 GUID 再按 URL 匹配（契约 4.2 的顺序）
            val byGuid = guid?.let { dao.findByGuid(instanceKey, feedId, it) }
            val byLink = link?.let { dao.findByLink(instanceKey, feedId, it) }

            if (byGuid != null && byLink != null && byGuid.localId != byLink.localId) {
                // 两个键指向不同记录：记冲突、两条都保留、不合并删除
                conflicts += guid ?: link.orEmpty()
                continue
            }

            val existing = byGuid ?: byLink
            val localId = existing?.localId
                ?: ArticleIdentity.localIdFor(instanceKey, feedId, guid, link)
                ?: run {
                    unidentified++
                    continue
                }

            val row = ArticleEntity(
                localId = localId,
                instanceKey = instanceKey,
                feedId = feedId,
                guid = guid,
                link = link,
                title = item.title,
                publishedAt = item.publishedAt,
                feedTitle = feedTitle,
                feedUrl = feedUrl,
                // 用户状态全部继承旧值；新条目才是默认值
                isRead = existing?.isRead ?: false,
                isStarred = existing?.isStarred ?: false,
                starredAt = existing?.starredAt,
                stateUpdatedAt = existing?.stateUpdatedAt ?: now,
            )

            // 有正文写正文；只有摘要也要写这一行——否则摘要不会被保存，
            // 详情页会显示成“完全没有内容”，而不是“摘要 + 获取全文”（契约 P04）
            val content = if (item.content != null || item.description != null) {
                ArticleContentEntity(
                    localId = localId,
                    content = item.content,
                    summary = item.description,
                    thumbnail = item.thumbnail,
                    contentSource = if (item.content != null) SOURCE_RSS else SOURCE_SUMMARY,
                    contentHash = ArticleIdentity.contentFingerprint(item.content ?: item.description.orEmpty()),
                    // 搜索索引随正文同生命周期生成（Q01/契约 9.1）
                    searchText = ArticleFilter.summaryPreview(item.description ?: item.content, limit = 500),
                    searchBodyText = item.content?.let(SearchQuery::bodyText),
                    lastAccessedAt = now,
                )
            } else {
                null
            }

            dao.merge(row, content)
            if (existing == null) inserted++ else updated++
        }

        return MergeResult(inserted, updated, unidentified, conflicts)
    }

    /**
     * 历史合并：**只补缺**，绝不覆盖刚抓到的正文或用户状态（契约 4.2）。
     * 已存在（按 GUID 或 URL 命中）就跳过。
     */
    suspend fun mergeHistory(
        instanceKey: String,
        feedId: String,
        feedTitle: String,
        feedUrl: String,
        items: List<NewArticle>,
        now: Long = System.currentTimeMillis(),
    ): MergeResult {
        var inserted = 0
        var unidentified = 0
        var skipped = 0

        for (item in items) {
            val guid = ArticleIdentity.normalizeGuid(item.guid)
            val link = ArticleIdentity.normalizeLink(item.link)

            val exists = (guid?.let { dao.findByGuid(instanceKey, feedId, it) } != null) ||
                (link?.let { dao.findByLink(instanceKey, feedId, it) } != null)
            if (exists) {
                skipped++
                continue
            }

            val localId = ArticleIdentity.localIdFor(instanceKey, feedId, guid, link)
            if (localId == null) {
                unidentified++
                continue
            }

            val row = ArticleEntity(
                localId = localId,
                instanceKey = instanceKey,
                feedId = feedId,
                guid = guid,
                link = link,
                title = item.title,
                publishedAt = item.publishedAt,
                feedTitle = feedTitle,
                feedUrl = feedUrl,
                isRead = false,
                isStarred = false,
                starredAt = null,
                stateUpdatedAt = now,
            )
            // 历史只补缺：正文即使有也不写，等该源下次刷新时用当前 RSS 正文
            dao.merge(row, null)
            inserted++
        }

        return MergeResult(
            inserted = inserted,
            updated = 0,
            skippedUnidentified = unidentified,
            conflicts = emptyList(),
            filledFromHistory = skipped,
        )
    }

    /** 待合并的新条目：字段与解析结果一致，但独立于解析器，便于测试与历史 JSON 复用 */
    data class NewArticle(
        val title: String,
        val guid: String?,
        val link: String?,
        val publishedAt: String?,
        val content: String?,
        val description: String? = null,
        val thumbnail: String? = null,
    )
}
