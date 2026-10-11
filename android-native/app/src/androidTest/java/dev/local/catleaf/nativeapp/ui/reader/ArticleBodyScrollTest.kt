package dev.local.catleaf.nativeapp.ui.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** W01：一次短拖动停下后也要回报最终位置，不能只回报节流窗口的首沿。 */
@RunWith(AndroidJUnit4::class)
class ArticleBodyScrollTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun short_drag_reports_last_position_after_idle() {
        val reports = Collections.synchronizedList(mutableListOf<Int>())
        composeRule.setContent {
            ArticleBodyView(
                bodyHtml = (1..100).joinToString("") { "<p>Paragraph $it. Long readable text repeated for scrolling.</p>" },
                onExternalUrl = {},
                onScrollPosition = { y, _ -> reports += y },
            )
        }
        composeRule.waitUntil(10_000) { composeRule.onRoot().fetchSemanticsNode().size.height > 0 }
        Thread.sleep(1_000) // WebView's local HTML must finish laying out before touch input.
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { reports.any { it > 0 } }
        val beforeIdle = reports.size
        composeRule.waitUntil(5_000) { reports.size > beforeIdle }
        assertTrue("短拖动末沿必须持久化", reports.last() > 0)
    }

    @Test fun atom_ancestor_namespace_reaches_visible_reader_text() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="http://www.w3.org/1999/xhtml">
              <entry><id>visible</id><content type="xhtml"><x:div><x:p>Visible ancestor XHTML paragraph</x:p></x:div></content></entry>
            </feed>
        """.trimIndent()
        val parsed = FeedParser.parse(xml).getOrThrow().items.single().content
        val html = ArticleSanitizer.sanitize(parsed, "https://example.org/visible").html
        composeRule.setContent { ArticleBodyView(bodyHtml = html, onExternalUrl = {}) }
        composeRule.waitUntil(10_000) {
            hasText(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, "Visible ancestor XHTML paragraph")
        }
    }

    private fun hasText(node: AccessibilityNodeInfo?, needle: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(needle) == true) return true
        return (0 until node.childCount).any { hasText(node.getChild(it), needle) }
    }

    @Test fun late_anchor_after_page_load_is_applied() {
        val anchor = mutableStateOf<ScrollAnchor.ReaderAnchor?>(null)
        val reports = Collections.synchronizedList(mutableListOf<Int>())
        composeRule.setContent {
            ArticleBodyView(
                bodyHtml = (1..100).joinToString("") { "<p>Paragraph $it. Long readable text repeated for scrolling.</p>" },
                onExternalUrl = {},
                scrollAnchor = anchor.value,
                onScrollPosition = { y, _ -> reports += y },
            )
        }
        Thread.sleep(1_000)
        composeRule.runOnUiThread { anchor.value = ScrollAnchor.ReaderAnchor("article", "hash", 500, 3000) }
        composeRule.waitUntil(5_000) { reports.any { it > 0 } }
    }

    @Test fun same_webview_bilingual_reload_and_toggle_restore_scroll_without_javascript() {
        val article = dev.local.catleaf.nativeapp.data.ai.BilingualArticle.fromHtml((1..60).joinToString("") {
            "<p>Paragraph $it. Readable original text of a reasonably similar length.</p>"
        })
        val bilingual = mutableStateOf(false)
        val reports = Collections.synchronizedList(mutableListOf<Int>())
        val translations = article.blocks.associate { it.id to "对应中文段落，保持足够长度以验证正文插入后阅读位置恢复，不启用脚本。" }
        composeRule.setContent { ArticleBodyView(
            bodyHtml = article.render(translations.takeIf { bilingual.value }),
            onExternalUrl = {},
            displayKey = if (bilingual.value) "bilingual" else "original",
            onScrollPosition = { y, _ -> reports += y },
        ) }
        Thread.sleep(1000)
        composeRule.onRoot().performTouchInput { swipeUp(durationMillis = 800); click(center) }
        composeRule.waitUntil(5000) { reports.any { it > 0 } }
        Thread.sleep(1200)
        val originalY = reports.last()
        composeRule.runOnUiThread { bilingual.value = true }
        composeRule.waitForIdle()
        Thread.sleep(2200)
        assertTrue("双语首插不能跳回顶部：reports=$reports", reports.last() > originalY)
        Thread.sleep(1200)
        composeRule.runOnUiThread { bilingual.value = false }
        composeRule.waitForIdle()
        Thread.sleep(1600)
        assertTrue("回原文不能跳回顶部或串用双语高度 original=$originalY reports=$reports", kotlin.math.abs(reports.last() - originalY) < 50)
    }
}
