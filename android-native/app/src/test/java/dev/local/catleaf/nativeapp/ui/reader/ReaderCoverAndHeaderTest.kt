package dev.local.catleaf.nativeapp.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T22 R1：封面选择与页首块的最小检查。
 * readerCoverUrl 钉住 F02 的三条规则：安全地址、正文含同图不重复插、脏数据不进页面。
 */
class ReaderCoverAndHeaderTest {

    @Test fun paragraph_jumps_only_stay_within_the_current_controlled_document() {
        val page = ReaderAssets.PAGE_URL + "?render=test"
        assertTrue(isLocalParagraphJump(page, "$page#catleaf-b1"))
        assertTrue(isLocalParagraphJump("$page#catleaf-b1", "$page#catleaf-title"))
        assertFalse(isLocalParagraphJump(page, ReaderAssets.PAGE_URL + "?render=other#catleaf-b1"))
        assertFalse(isLocalParagraphJump(page, "$page#catleaf-b0"))
        assertFalse(isLocalParagraphJump(page, "https://evil.example/#catleaf-b1"))
        assertFalse(isLocalParagraphJump("https://evil.example/", "https://evil.example/#catleaf-b1"))
    }

    @Test
    fun cover_requires_safe_https() {
        val url = "https://blog.example.org/img/cover.png"
        assertEquals("干净的 https 图片可用", url, readerCoverUrl(url))
        assertNull("非 https 丢弃", readerCoverUrl("http://blog.example.org/a.png"))
        assertNull("伪协议丢弃", readerCoverUrl("javascript:alert(1)"))
        assertNull("内网丢弃", readerCoverUrl("https://127.0.0.1/a.png"))
        assertNull("没有附件就没有封面", readerCoverUrl(null))
    }

    @Test
    fun header_escapes_external_text_and_keeps_markup_structure() {
        val title = "A \"quoted\" <script> title & more"
        val escaped = ReaderAssets.escapeHtml(title)
        assertFalse("尖括号与引号必须转义", escaped.contains("<script>"))
        assertTrue(escaped.contains("&lt;script&gt;") && escaped.contains("&quot;") && escaped.contains("&amp;"))

        val page = ReaderAssets.page(
            bodyHtml = "<p>正文</p>",
            headerHtml = "<img class=\"catleaf-cover\" src=\"https://a.example.org/c.png\" alt=\"\">" +
                "<h1 class=\"catleaf-title\">$escaped</h1>",
        )
        assertTrue("页首块在正文之前、同一 body 内", page.indexOf("catleaf-cover") < page.indexOf("catleaf-body"))
        assertTrue("正文仍在", page.contains("<p>正文</p>"))
        assertFalse("转义后的标题不会成为可执行脚本", page.contains("<script>"))
    }

    @Test
    fun page_without_header_has_no_header_element() {
        val page = ReaderAssets.page("<p>x</p>")
        assertFalse("不给没有页首的调用凭空造 header", page.contains("<header"))
    }

    @Test
    fun title_is_independent_token_and_body_lineheight_matches_spec() {
        // V03（独立验收）：文章主标题 28/36 是独立规格——用户正文字号 16..22 不得改变它；
        // 正文行距按设计规范 18/30 → 1.67，与译文/偏好预览同一约定。
        for (sp in listOf(16, 18, 20, 22)) {
            val css = ReaderAssets.cssFor(sp, dark = false)
            assertTrue("$sp 时标题仍 28px", css.contains(".catleaf-title { font-size: 28px; line-height: 36px;"))
            assertFalse("标题不随正文放大", css.contains("font-size: ${sp}px; line-height: 1.4"))
        }
        assertTrue("body 行距 1.67（≈30/18）", ReaderAssets.cssFor(18, false).contains("line-height: 1.67;"))
    }
}
