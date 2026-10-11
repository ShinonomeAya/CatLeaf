package dev.local.catleaf.nativeapp.ui.digest

import dev.local.catleaf.nativeapp.data.ai.DigestSummarizer
import dev.local.catleaf.nativeapp.data.article.DateNormalizer
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import java.util.TimeZone

/**
 * 摘要范围预览的纯逻辑（契约 5.3 / P05），可单测：
 * 候选集合、正文/仅摘要判定与 30 篇 / 30,000 字符上限都在这里算，ViewModel 只管状态搬运。
 */
object DigestScope {

    /** 一篇文章 + 已取得正文/摘要的纯文本（净化后；null = 没有） */
    data class Row(
        val localId: String,
        val title: String,
        val publishedAt: String?,
        val link: String?,
        val body: String?,
        val summary: String?,
    )

    data class Candidate(
        val localId: String,
        val title: String,
        /** 可交给系统浏览器的原文链接（与阅读器同一 `isSafePublicUrl` 判定）；无效即 null，不当链接用 */
        val link: String?,
        /** 实际发送的文本：正文优先，否则摘要字段 */
        val text: String,
        val hasBody: Boolean,
    )

    /**
     * 冻结范围：只取「本地日期 == 所选日期」的已加载文章。
     * 无有效日期的文章不加入（契约 5.3）；正文与摘要都没有的也不加入（无有效输入）。
     * 行序保持传入顺序（= 阅读列表的稳定顺序）。
     */
    fun candidates(rows: List<Row>, localDate: String?, zone: TimeZone = TimeZone.getDefault()): List<Candidate> {
        if (localDate == null) return emptyList()
        return rows.mapNotNull { row ->
            val date = DateNormalizer.localDateOf(row.publishedAt, zone) ?: return@mapNotNull null
            if (date != localDate) return@mapNotNull null
            val body = row.body?.trim().orEmpty()
            if (body.isNotEmpty()) {
                Candidate(row.localId, row.title, safeLink(row.link), body, hasBody = true)
            } else {
                val summary = row.summary?.trim().orEmpty()
                if (summary.isEmpty()) return@mapNotNull null
                Candidate(row.localId, row.title, safeLink(row.link), summary, hasBody = false)
            }
        }
    }

    private fun safeLink(link: String?): String? = link?.takeIf { EndpointPolicy.isSafePublicUrl(it) }

    data class Selection(
        val selected: List<Candidate>,
        val totalChars: Int,
        val overItems: Boolean,
        val overChars: Boolean,
    ) {
        /** 超限就禁用提交（界面同时给取消勾选的指引）——不后台截断、不静默丢文章 */
        val canSubmit: Boolean get() = selected.isNotEmpty() && !overItems && !overChars
    }

    /** 勾选集合按稳定顺序（候选表原序）排好并计数 */
    fun selection(candidates: List<Candidate>, selectedIds: Set<String>): Selection {
        val selected = candidates.filter { it.localId in selectedIds }
        val chars = selected.sumOf { it.text.length }
        return Selection(
            selected = selected,
            totalChars = chars,
            overItems = selected.size > DigestSummarizer.MAX_ITEMS,
            overChars = chars > DigestSummarizer.MAX_CHARS,
        )
    }
}
