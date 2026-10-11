package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import java.util.TimeZone

/**
 * 阅读列表的筛选与计数（契约 P02）。纯逻辑，可单测。
 *
 * 两条契约要求驱动这里的写法：
 * - **只筛选已经加载的文章**：日期筛选不触发新的网络请求，也不假装服务器没有更多内容；
 * - **未读数只统计本地实际已加载文章**，界面必须标清范围，不能拿历史条数冒充未读数。
 */
object ArticleFilter {

    /** 列表上方的日期筛选；“全部已获取”是默认（PRD 明确：默认不是“今天”） */
    sealed class DateScope {
        object All : DateScope()
        object Today : DateScope()
        data class On(val localDate: String) : DateScope()
        /** 未知日期：publishedAt 为 null 的条目单独成一档，不混进任何一天 */
        object Unknown : DateScope()
    }

    data class Criteria(
        val scope: DateScope = DateScope.All,
        val unreadOnly: Boolean = false,
    )

    /** 一条已加载文章 + 本地日期 + 列表摘要与封面（T22 F09；null 表示没有，不占位） */
    data class LoadedRow(
        val entity: ArticleEntity,
        val localDate: String?,
        /** 纯文本短摘要：由 previewRaw 剥标签得到，列表显示两行 */
        val summary: String? = null,
        /** 封面地址（解析时已核验 https 无凭据）；有图才显示缩略图 */
        val thumbnail: String? = null,
    )

    fun toLoadedRow(
        entity: ArticleEntity,
        previewRaw: String? = null,
        thumbnail: String? = null,
        zone: TimeZone = TimeZone.getDefault(),
    ): LoadedRow = LoadedRow(
        entity,
        DateNormalizer.localDateOf(entity.publishedAt, zone),
        summaryPreview(previewRaw, title = entity.title),
        thumbnail?.takeIf { it.isNotBlank() }?.let { ArticleSanitizer.wechatImageUrl(it) ?: it },
    )

    /**
     * 列表预览文本（T22 F09）：去标签 → 解常见实体 → 压空白 → 截断。
     * 只是显示层的纯文本提取，不是正文渲染——正文仍必须走唯一净化入口。
     */
    fun summaryPreview(rawHtml: String?, limit: Int = 120, title: String? = null): String? {
        val repaired = rawHtml?.replace(Regex("<[^>]*$"), " ")
            ?.replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { match ->
                val digits = match.groupValues[1]
                val cp = if (digits.startsWith('x')) digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
                if (cp == null || cp < 0x20 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) "&amp;${match.value.drop(1)}" else match.value
            }
        val html = ArticleSanitizer.sanitize(repaired, null, listOfNotNull(title)).html
        val doc = org.jsoup.Jsoup.parseBodyFragment(html)
        val text = doc.text().trim()
        if (text.isEmpty()) return null
        return if (text.length <= limit) text else text.take(limit).trimEnd() + "…"
    }

    fun apply(rows: List<LoadedRow>, criteria: Criteria): List<LoadedRow> = rows.filter { row ->
        val matchesDate = when (val scope = criteria.scope) {
            is DateScope.All -> true
            // “今天”按设备本地时区判定，不用 UTC 日期
            is DateScope.Today -> row.localDate == DateNormalizer.localDateOf(
                java.time.Instant.now().toString(),
                TimeZone.getDefault(),
            )
            is DateScope.On -> row.localDate == scope.localDate
            is DateScope.Unknown -> row.localDate == null
        }
        matchesDate && (!criteria.unreadOnly || !row.entity.isRead)
    }

    /** 未读数：只数当前已加载的条目，界面须同时说明这一范围 */
    fun unreadCount(rows: List<LoadedRow>): Int = rows.count { !it.entity.isRead }

    /** 当前筛选下“为什么是空的”——空态必须说明真实原因，不能都写“还没有订阅” */
    fun emptyReason(rows: List<LoadedRow>, criteria: Criteria): EmptyReason = when {
        rows.isEmpty() -> EmptyReason.NoContentLoaded
        apply(rows, criteria).isNotEmpty() -> EmptyReason.None
        criteria.unreadOnly && criteria.scope is DateScope.All ->
            EmptyReason.NoUnreadInLoaded
        criteria.scope is DateScope.Unknown -> EmptyReason.NoUnknownDate
        criteria.scope is DateScope.Today -> EmptyReason.NoTodayInLoaded
        criteria.scope is DateScope.On -> EmptyReason.NoContentOnDate
        else -> EmptyReason.NoMatchForFilter
    }

    enum class EmptyReason {
        /** 该源一条都没加载到（还没刷新过或刷新失败且无缓存） */
        None,
        NoContentLoaded,
        /** 已加载范围内没有未读——不是“服务器没有新内容” */
        NoUnreadInLoaded,
        NoTodayInLoaded,
        NoContentOnDate,
        NoUnknownDate,
        NoMatchForFilter,
    }
}
