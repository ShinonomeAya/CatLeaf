package dev.local.catleaf.nativeapp.ui.digest

import dev.local.catleaf.nativeapp.data.ai.DigestSummarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 范围冻结与上限计算（契约 5.3 / P05 / A09 的纯逻辑部分）。 */
class DigestScopeTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun row(localId: String, publishedAt: String?, body: String? = null, summary: String? = null, link: String? = null) =
        DigestScope.Row(localId, "标题-$localId", publishedAt, link, body, summary)

    private val rows = listOf(
        row("a", "2026-09-22T01:00:00Z", body = "正文A"),
        row("b", "2026-09-22T10:00:00Z", summary = "摘要B"),
        row("c", "2026-09-21T10:00:00Z", body = "正文C"), // 别的日期
        row("d", null, body = "正文D"), // 无有效日期：不加入（契约 5.3）
        row("e", "2026-09-22T12:00:00Z"), // 既无正文也无摘要：不算候选
        row("f", "2026-09-22T15:30:00Z", body = "正文F"), // 上海时间 23:30：同一天但 UTC 下会挪日
    )

    @Test
    fun candidates_only_the_selected_local_day_with_text_keep_stable_order() {
        val candidates = DigestScope.candidates(rows, "2026-09-22", zone)
        assertEquals("该日候选应为 a、b、f（按行序）", listOf("a", "b", "f"), candidates.map { it.localId })
        assertEquals("a 用正文", true, candidates[0].hasBody)
        assertEquals("a 的文本", "正文A", candidates[0].text)
        assertEquals("b 仅摘要", false, candidates[1].hasBody)
        assertEquals("b 的文本", "摘要B", candidates[1].text)
        assertEquals("f 上海时间 23:30 仍是本地 09-22", "正文F", candidates[2].text)
    }

    @Test
    fun date_shifts_with_timezone() {
        // a 是 09-22T01:00Z：纽约（UTC-4）下属于 09-21。同一份数据不同本地时区结果不同，
        // 所以时区必须进缓存键（契约 5.3）
        val ny = DigestScope.candidates(rows, "2026-09-21", TimeZone.getTimeZone("America/New_York"))
        assertEquals("纽约时区下 09-21 含 a 与 c（行序不变）", listOf("a", "c"), ny.map { it.localId })
    }

    @Test
    fun no_date_no_candidates() {
        assertTrue("未选日期不得给出候选", DigestScope.candidates(rows, null, zone).isEmpty())
    }

    @Test
    fun links_follow_the_reader_external_gate() {
        // 与阅读器「原网页」同一判定（isSafePublicUrl）：只接受 http/https、无用户信息；
        // 摘要页不会自己抓取这个链接，所以不要求内网检查，但 javascript: 之类必须丢掉
        val candidates = DigestScope.candidates(
            listOf(
                row("x", "2026-09-22T01:00:00Z", body = "正文", link = "javascript:alert(1)"),
                row("y", "2026-09-22T01:00:00Z", body = "正文", link = "https://example.com/a"),
            ),
            "2026-09-22",
            zone,
        )
        assertNull("非 http(s) 协议不算链接", candidates[0].link)
        assertEquals("HTTPS 链接保留", "https://example.com/a", candidates[1].link)
    }

    @Test
    fun limits_are_explicit_never_silent() {
        val big = (1..31).map { row("id$it", "2026-09-22T01:00:00Z", body = "字".repeat(10)) }
        val candidates = DigestScope.candidates(big, "2026-09-22", zone)
        assertEquals(31, candidates.size)

        val all = DigestScope.selection(candidates, candidates.map { it.localId }.toSet())
        assertTrue("31 篇必须判为超篇数上限", all.overItems)
        assertFalse("31×10 字未超字符上限", all.overChars)
        assertFalse("超上限就不能提交（界面要取消勾选，不后台截断）", all.canSubmit)
        assertEquals("计数仍是全部勾选", 31, all.selected.size)

        val exactly30 = DigestScope.selection(candidates, candidates.take(DigestSummarizer.MAX_ITEMS).map { it.localId }.toSet())
        assertEquals("30 篇是允许的边界", 30, exactly30.selected.size)
        assertFalse(exactly30.overItems)
        assertTrue(exactly30.canSubmit)
    }

    @Test
    fun char_limit_boundary_is_exact() {
        val rows = listOf(
            row("p1", "2026-09-22T01:00:00Z", body = "字".repeat(DigestSummarizer.MAX_CHARS)),
            row("p2", "2026-09-22T02:00:00Z", body = "字"),
        )
        val candidates = DigestScope.candidates(rows, "2026-09-22", zone)
        val both = DigestScope.selection(candidates, setOf("p1", "p2"))
        assertEquals(30_001, both.totalChars)
        assertTrue("30,001 字符即超限", both.overChars)
        assertFalse(both.canSubmit)

        val first = DigestScope.selection(candidates, setOf("p1"))
        assertEquals("恰好 30,000 不超限", 30_000, first.totalChars)
        assertFalse("恰好到上限不判超限", first.overChars)
        assertTrue("恰好到上限可以提交", first.canSubmit)
    }

    @Test
    fun empty_selection_cannot_submit() {
        val candidates = DigestScope.candidates(rows, "2026-09-22", zone)
        val none = DigestScope.selection(candidates, emptySet())
        assertEquals(0, none.selected.size)
        assertFalse("无有效输入不能提交", none.canSubmit)
    }
}
