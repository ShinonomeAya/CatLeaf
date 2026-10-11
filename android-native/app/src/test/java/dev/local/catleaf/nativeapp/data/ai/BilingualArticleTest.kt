package dev.local.catleaf.nativeapp.data.ai

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BilingualArticleTest {
    @Test fun title_is_a_separate_required_block_and_does_not_renumber_body_or_legacy_cache() {
        val body = BilingualArticle.fromHtml("<p>First</p><p>Second</p>")
        val article = BilingualArticle.fromHtml("<p>First</p><p>Second</p>", "Original title")
        assertEquals(BilingualArticle.TITLE_ID, article.blocks.first().id)
        assertEquals("Original title", article.blocks.first().text)
        assertEquals(body.blocks, article.blocks.drop(1))
        assertEquals(body.fingerprint, article.withoutTitle().fingerprint)
        assertNotEquals(body.fingerprint, article.fingerprint)
        assertNotEquals(article.fingerprint, BilingualArticle.fromHtml("<p>First</p><p>Second</p>", "Changed title").fingerprint)
        assertNull(article.parseTranslations(body.serializeTranslations(body.blocks.associate { it.id to "正文译文" })))
        val translated = article.blocks.associate { it.id to "标题或正文 <script>" }
        assertEquals(translated, article.parseTranslations(article.serializeTranslations(translated)))
        assertEquals(2, Jsoup.parseBodyFragment(article.withoutTitle().render(translated.filterKeys { it != BilingualArticle.TITLE_ID })).select(".catleaf-translation").size)
    }
    @Test fun inline_code_keeps_the_paragraph_together_and_original_markup_untouched() {
        val article = BilingualArticle.fromHtml("<p>Call <code>read()</code> for results.</p>")
        assertEquals(listOf("Call read() for results."), article.blocks.map { it.text })
        val rendered = Jsoup.parseBodyFragment(article.render(mapOf(article.blocks.single().id to "调用 read() 获取结果。")))
        assertEquals("read()", rendered.selectFirst("code")!!.text())
        assertEquals(1, rendered.select(".catleaf-translation").size)
    }
    @Test fun nested_blocks_images_links_and_code_keep_order_without_duplicate_text() {
        val source = "<div>Intro<br>Next<p>Read <a href='https://example.org'>this link</a>.</p>" +
            "<img src='https://example.org/a.jpg'><blockquote><p>Quote</p></blockquote>" +
            "<ul><li>Parent<ul><li>Child</li></ul></li></ul><table><tr><td>Cell</td></tr></table>" +
            "<pre><code>doNotTranslate()</code></pre><p>Repeated</p><p>Repeated</p></div>"
        val article = BilingualArticle.fromHtml(source)
        assertEquals(listOf("Intro", "Next", "Read this link.", "Quote", "Parent", "Child", "Cell", "Repeated", "Repeated"), article.blocks.map { it.text })
        assertEquals(article.blocks.size, article.blocks.map { it.id }.toSet().size)
        val rendered = Jsoup.parseBodyFragment(article.render(article.blocks.associate { it.id to "译文 ${it.text} <script>alert(1)</script>" }))
        assertEquals(1, rendered.select("img").size)
        assertEquals("https://example.org", rendered.selectFirst("a")!!.attr("href"))
        assertEquals("doNotTranslate()", rendered.selectFirst("pre")!!.text())
        assertEquals(0, rendered.select("script").size)
        assertEquals(article.blocks.size, rendered.select(".catleaf-translation").size)
        article.blocks.forEach { assertEquals("catleaf-translation", rendered.getElementById(it.id)!!.nextElementSibling()!!.className()) }
    }

    @Test fun paragraph_structure_is_part_of_cache_identity_but_image_url_is_not() {
        val one = BilingualArticle.fromHtml("<p>One Two</p><img src='https://example.org/a'>")
        val two = BilingualArticle.fromHtml("<p>One</p><p>Two</p>")
        assertNotEquals(one.fingerprint, two.fingerprint)
        assertEquals(one.fingerprint, BilingualArticle.fromHtml("<p>One Two</p><img src='https://appassets.androidplatform.net/offline/a'>").fingerprint)
        assertEquals(listOf("One", "Two"), BilingualArticle.fromText("One\n\nTwo").blocks.map { it.text })
    }

    @Test fun structured_translation_reorders_by_id_and_rejects_invalid_or_incomplete_results() {
        val article = BilingualArticle.fromHtml("<p>First</p><p>Second</p>")
        val valid = """{"segments":[{"id":"catleaf-b2","text":"第二段\\n新行"},{"id":"catleaf-b1","text":"第一段 🌿"}]}"""
        val parsed = article.parseTranslations(valid)!!
        assertEquals(listOf("catleaf-b1", "catleaf-b2"), parsed.keys.toList())
        assertEquals("第一段 🌿", parsed.values.first())
        assertEquals(parsed, article.parseTranslations(article.serializeTranslations(parsed)))
        listOf(
            "not json", valid + " junk",
            """{"segments":[{"id":"catleaf-b1","text":"一"}]}""",
            valid.replace("catleaf-b2", "catleaf-b1"),
            valid.replace("catleaf-b2", "catleaf-b3"),
            valid.replace("\"第一段 🌿\"", "null"),
            valid.replace("\"第一段 🌿\"", "3"),
            valid.replace("\"第一段 🌿\"", "\" \""),
        ).forEach { assertNull(it, article.parseTranslations(it)) }
    }
}
