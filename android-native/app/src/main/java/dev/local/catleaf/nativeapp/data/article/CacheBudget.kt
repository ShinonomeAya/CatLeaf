package dev.local.catleaf.nativeapp.data.article

/**
 * 普通缓存预算（契约 4.3）：**最多 2,000 篇或 100 MiB，先到先按最近访问淘汰**。
 * 纯函数、可注入小预算，使淘汰在单测里可复现；生产默认值有专测核对。
 *
 * 规则来源：
 * - 收藏正文（及其译文）独立保护，不进候选；**日摘要属普通缓存**，进候选；
 * - 当前正文避开（`keepLocalIds`，由调用方给出正在读的 localId 与本次写入集合）；
 * - 取消收藏后的文章回到普通预算（下次淘汰清得掉它）。
 *
 * ponytail: 字节数按 `article_content` 整行（content+summary+thumbnail）计，不是只按契约原文的
 * "UTF-8 正文"——更保守（更早开始淘汰），且不需要按列拆预算；真要贴契约再拆。
 */
object CacheBudget {

    const val MAX_ARTICLES = 2_000
    const val MAX_BYTES = 100L * 1024 * 1024

    /** 一条正文快照：localId、字节数、是否收藏、最近访问时间 */
    data class ContentRow(val localId: String, val bytes: Long, val starred: Boolean, val lastAccessedAt: Long)

    /** 一条 AI 结果：cacheKey、字节数、是否受保护（收藏文章的译文）、生成时间（当作访问时间排序） */
    data class AiRow(val cacheKey: String, val bytes: Long, val protectedItem: Boolean, val accessedAt: Long)

    /** 淘汰计划：只列要删的，永不列保护项 */
    data class Plan(val contentLocalIds: List<String>, val aiCacheKeys: List<String>) {
        val isEmpty: Boolean get() = contentLocalIds.isEmpty() && aiCacheKeys.isEmpty()
    }

    fun plan(
        contents: List<ContentRow>,
        ai: List<AiRow>,
        keepLocalIds: Set<String> = emptySet(),
        maxArticles: Int = MAX_ARTICLES,
        maxBytes: Long = MAX_BYTES,
    ): Plan {
        val contentCandidates = contents.filter { !it.starred && it.localId !in keepLocalIds }
        val aiCandidates = ai.filter { !it.protectedItem }

        val dropContent = mutableSetOf<String>()
        val dropAi = mutableSetOf<String>()

        // 篇数：普通快照（含被 keep 避开的当前正文——它只是"这次不删"，仍占预算名额）
        // 超过上限时，按最旧先删可删的
        var excess = contents.count { !it.starred } - maxArticles
        for (row in contentCandidates.sortedBy { it.lastAccessedAt }) {
            if (excess <= 0) break
            dropContent += row.localId
            excess--
        }

        // 字节：普通正文 + 普通 AI 结果合并计（收藏文本不计入普通预算）——统一按最近访问淘汰，
        // 每删一项重算一次，直到两者都回到上限内
        while (true) {
            val bytes = (contentCandidates.filter { it.localId !in dropContent }.sumOf { it.bytes }) +
                (aiCandidates.filter { it.cacheKey !in dropAi }.sumOf { it.bytes })
            if (bytes <= maxBytes) break
            // 取全局最旧的一项（内容用 lastAccessedAt，AI 用 createdAt）
            val oldestContent = contentCandidates.filter { it.localId !in dropContent }.minByOrNull { it.lastAccessedAt }
            val oldestAi = aiCandidates.filter { it.cacheKey !in dropAi }.minByOrNull { it.accessedAt }
            when {
                oldestContent == null && oldestAi == null -> break // 已无候选可删：如实留在超限，界面给统计
                oldestAi == null || (oldestContent != null && oldestContent.lastAccessedAt <= oldestAi.accessedAt) ->
                    dropContent += oldestContent!!.localId
                else -> dropAi += oldestAi.cacheKey
            }
        }

        return Plan(dropContent.toList(), dropAi.toList())
    }
}
