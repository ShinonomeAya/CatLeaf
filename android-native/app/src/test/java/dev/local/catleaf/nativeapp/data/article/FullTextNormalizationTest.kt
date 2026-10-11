package dev.local.catleaf.nativeapp.data.article

import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

class FullTextNormalizationTest {
    private val url = "https://example.org/story"
    private val cover = "https://example.org/cover.png"

    @Test fun only_matching_leading_title_is_removed_and_captions_and_sections_survive() {
        val raw = "<div><figure><img src='$cover'><figcaption>图注</figcaption></figure>" +
            "<h1>A &amp;　 B</h1><p>正文</p><h2>正常章节</h2><img src='$cover'><h1>A &amp; B</h1></div>"
        val body = ArticleSanitizer.sanitize(raw, url, listOf("A & B"), cover)
        val doc = org.jsoup.Jsoup.parseBodyFragment(body.html)
        assertEquals(1, doc.select("h1").size)
        assertEquals(1, doc.select("img").size)
        assertEquals("图注", doc.select("figcaption").text())
        assertEquals("正常章节", doc.select("h2").text())
        assertFalse(body.plainText.startsWith("A & B"))
    }

    @Test fun different_or_later_title_and_middle_image_are_preserved() {
        for (raw in listOf("<h1>其他标题</h1><p>正文</p>", "<p>正文</p><h1>标题</h1><img src='$cover'>")) {
            val doc = org.jsoup.Jsoup.parseBodyFragment(ArticleSanitizer.sanitize(raw, url, listOf("标题"), cover).html)
            assertEquals(1, doc.select("h1").size)
        }
        assertEquals(1, org.jsoup.Jsoup.parseBodyFragment(ArticleSanitizer.sanitize("<p>正文</p><img src='$cover'>", url, coverUrl = cover).html).select("img").size)
        assertTrue(ArticleSanitizer.sanitize("<h1>标题</h1>", url, listOf("标题")).isEmpty)
    }

    @Test fun cached_list_preview_uses_same_title_rule_and_repairs_cut_markup() {
        assertEquals("正文", ArticleFilter.summaryPreview("<h1>标题</h1><p>正文</p><p style=\"cut", title = "标题"))
        assertEquals("可靠 RSS 摘要", ArticleFilter.summaryPreview("<p>可靠 RSS 摘要</p>", title = "标题"))
    }

    @Test fun explicit_cover_metadata_is_resolved_and_validated_in_priority_order() {
        assertEquals(cover, FullTextExtractor.coverFromHtml("<meta property='og:image' content='/cover.png'><meta name='twitter:image' content='/other.png'>", url))
        assertEquals(cover, FullTextExtractor.coverFromHtml("<meta property='og:image' content='https://127.0.0.1/a'><meta name='twitter:image' content='$cover'>", url))
        assertNull(FullTextExtractor.coverFromHtml("<img src='$cover'><meta property='og:image' content='http://example.org/a'>", url))
    }

    @Test fun captured_ifanr_page_loses_duplicate_title_but_keeps_sections_and_author() = kotlinx.coroutines.runBlocking {
        val html = javaClass.getResource("/fulltext/ifanr-1682644.html")!!.readText()
        val title = "ChatGPT 一夜抄完半个 AI 圈，现场演示频频翻车"
        val articleUrl = "https://www.ifanr.com/1682644"
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(html.toResponseBody()).build()
        }.build()
        val outcome = FullTextExtractor(null, dev.local.catleaf.nativeapp.data.network.PublicFetch(client))
            .extract(articleUrl, title) as FullTextExtractor.Outcome.Extracted
        val body = org.jsoup.Jsoup.parseBodyFragment(outcome.html)
        assertEquals(0, body.select("h1").size)
        assertTrue(body.select("h3").size > 0)
        assertTrue(body.select("img").size > 20)
        assertEquals("莫崇宇", outcome.byline)
        assertEquals(FullTextExtractor.coverFromHtml(html, articleUrl), outcome.thumbnail)
        assertNotNull(outcome.thumbnail)
    }
}
