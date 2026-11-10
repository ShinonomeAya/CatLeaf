package dev.local.catleaf.nativeapp.data.article

import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.network.HttpClients
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, public pages only; no credentials, AI or application database writes. */
@ManualProbe
class WebsiteRulesLiveProbeTest {
    @Test fun selectedPublicListsAndArticlesUseProductionFetchAndSelectors() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("web_rules_live") == "true")
        val fetcher = PublicFetch(HttpClients.forPublicFeed())
        val samples = listOf(
            "https://www.ruanyifeng.com/blog/" to WebsiteRules(items = ".entry-asset, #homepage li.module-list-item", title = ".entry-title, a", link = ".entry-title a, a", body = ".entry-content", exclude = ".asset-footer, #comments, #comment-form, script, style"),
            "https://developer.mozilla.org/en-US/blog/" to WebsiteRules(items = "article.blog-post-preview", title = "h2", link = "h2 a", summary = ".blog-post-preview__description", body = ".blog-post-content__content"),
        )
        for ((url, rules) in samples) {
            val listResponse = fetcher.fetch(url).getOrThrow()
            val list = rules.extractList(FullTextExtractor.decodeHtml(listResponse.bytes, listResponse.contentType), listResponse.finalUrl ?: url)
            assertTrue(list.items.isNotEmpty())
            assertEquals(list.items.size, list.items.map { it.link }.distinct().size)
            val articleUrl = list.items.first().link!!
            val articleResponse = fetcher.fetch(articleUrl).getOrThrow()
            val body = rules.extractBody(FullTextExtractor.decodeHtml(articleResponse.bytes, articleResponse.contentType), articleResponse.finalUrl ?: articleUrl)
            assertTrue(body.textLength > 200)
            assertFalse(body.html.contains("<script"))
            println("WEB_RULE_LIVE url=$url articles=${list.items.size} invalid=${list.unidentifiedCount} listBytes=${listResponse.bytes.size} article=$articleUrl chars=${body.textLength} images=${body.imageCount}")
        }
    }
}
