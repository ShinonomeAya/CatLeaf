package dev.local.catleaf.nativeapp.data.rss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.ui.reader.ArticleBodyView
import dev.local.catleaf.nativeapp.ui.reader.ReaderAssets
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.jsoup.Jsoup

/** Read-only public source probe; run explicitly because upstream content and network can change. */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class FeedLiveXhtmlProbeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun ifanr_rss_emoji_renders_as_text_before_fulltext() {
        val feed = FeedParser.parse(PublicFetch().fetch("https://www.ifanr.com/feed").getOrThrow().bytes.decodeToString()).getOrThrow()
        val item = feed.items.first { (it.content ?: it.description).orEmpty().contains("s.w.org/images/core/emoji/") }
        val clean = ArticleSanitizer.sanitize(item.content ?: item.description, item.link)
        assertTrue(!clean.html.contains("s.w.org/images/core/emoji/"))
        val paragraphs = Jsoup.parseBodyFragment(clean.html).select("p").filter { it.text().isNotBlank() }.take(6).joinToString("") { it.outerHtml() }
        composeRule.setContent { ArticleBodyView(bodyHtml = paragraphs, articleUrl = item.link, onExternalUrl = {}) }
        Thread.sleep(2_000)
        screenshot("ifanr-rss-emoji")
    }

    @Test fun sspai_pngs_render_with_article_origin() {
        val source = "https://sspai.com/post/115166"
        val urls = listOf("b1a2c3652928b4c9a8a4fd14462ba959", "dbabfce4f3d91a825edaef1a9e1ac31e").map {
            "https://cdnfile.sspai.com/2026/09/30/$it.png?imageView2/2/w/1120/q/90/interlace/1/ignore-error/1"
        }
        urls.forEach { assertTrue(PublicFetch().fetch(it, PublicFetch.MAX_IMAGE_BYTES, source).getOrThrow().bytes.isNotEmpty()) }
        val body = urls.mapIndexed { i, url -> "<p>少数派原文图片 ${i + 1}</p><img src='${ReaderAssets.escapeHtml(url)}'>" }.joinToString("")
        composeRule.setContent { ArticleBodyView(bodyHtml = body, articleUrl = source, onExternalUrl = {}) }
        Thread.sleep(8_000)
        screenshot("sspai-origin-images")
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        target.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun gitlab_atom_xhtml_summary_reaches_sanitizer() {
        val url = "https://gitlab.com/gitlab-org/gitlab.atom"
        val fetched = PublicFetch().fetch(url).getOrThrow()
        val feed = FeedParser.parse(fetched.bytes.decodeToString()).getOrThrow()
        assertTrue(feed.items.isNotEmpty())
        val xhtml = feed.items.firstOrNull { it.description?.contains("<div") == true }
            ?: error("当前公开源没有可测试的 XHTML 摘要")
        val clean = ArticleSanitizer.sanitize(xhtml.description, xhtml.link)
        assertTrue(clean.textLength > 0)
        assertTrue(clean.html.contains("<p") || clean.html.contains("<a "))
        val needle = Jsoup.parseBodyFragment(clean.html).select("p, a")
            .map { it.text().trim() }.first { it.length >= 4 }.take(10)
        composeRule.setContent { ArticleBodyView(bodyHtml = clean.html, onExternalUrl = {}) }
        composeRule.waitUntil(10_000) {
            hasText(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, needle)
        }
    }

    private fun hasText(node: AccessibilityNodeInfo?, needle: String): Boolean =
        node != null && (node.text?.contains(needle) == true ||
            (0 until node.childCount).any { hasText(node.getChild(it), needle) })
}
