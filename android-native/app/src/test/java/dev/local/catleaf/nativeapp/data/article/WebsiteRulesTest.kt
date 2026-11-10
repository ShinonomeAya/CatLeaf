package dev.local.catleaf.nativeapp.data.article

import org.junit.Assert.*
import org.junit.Test

class WebsiteRulesTest {
    private val base = "https://example.com:8443/news/list/"

    @Test fun selectedBodyRetainsSectionsAndImagesButRemovesExcludedAndActiveContent() {
        val rule = WebsiteRules(body = ".entry", exclude = ".ad")
        val result = rule.extractBody("""<nav>Menu</nav><article class=entry><h2>Section</h2><p>Wanted text</p><div class=ad>Ad text</div><img src=../image.png><script>alert(1)</script></article>""", base)
        assertTrue(result.html.contains("Section"))
        assertTrue(result.html.contains("Wanted text"))
        assertTrue(result.html.contains("https://example.com:8443/news/image.png"))
        assertFalse(result.html.contains("Ad text"))
        assertFalse(result.html.contains("script"))
        assertFalse(result.html.contains("Menu"))
    }

    @Test fun listResolvesLinksDeduplicatesAndLeavesFullTextOnDemand() {
        val rules = WebsiteRules(items = "article", title = "h2", link = "a", date = "time", summary = ".summary")
        val feed = rules.extractList("""<title>News</title><article><h2>One</h2><a href=../one#top>Read</a><time datetime=2026-11-01T08:00:00Z></time><p class=summary>Preview</p></article><article><h2>Duplicate</h2><a href=../one#other>Read</a></article><article><h2>Unsafe</h2><a href=https://127.0.0.1/private>Read</a></article>""", base)
        assertEquals(1, feed.items.size)
        assertEquals("https://example.com:8443/news/one", feed.items.single().link)
        assertEquals("2026-11-01T08:00:00Z", feed.items.single().publishedAt)
        assertNull(feed.items.single().content)
        assertTrue(feed.items.single().description!!.contains("Preview"))
        assertEquals(1, feed.unidentifiedCount)
    }

    @Test fun jsonRoundTripAndValidationDoNotExecuteExpressions() {
        val rule = WebsiteRules(body = "#content", exclude = ".ad")
        assertEquals(rule, WebsiteRules.fromJson(rule.toJson()))
        rule.requireValid("rss")
        assertThrows(IllegalArgumentException::class.java) { WebsiteRules(body = "div:matches((a+)+)").requireValid("rss") }
        assertThrows(IllegalArgumentException::class.java) { WebsiteRules(body = "[").requireValid("rss") }
        assertThrows(IllegalArgumentException::class.java) { WebsiteRules.fromJson("""{"body":4}""") }
        assertThrows(IllegalArgumentException::class.java) { rule.requireValid("unknown") }
    }

    @Test fun missingContainersAndScriptOnlyPagesAreFailuresNotEmptySuccess() {
        val rule = WebsiteRules(body = ".entry", items = "article", title = "h2", link = "a")
        assertThrows(IllegalArgumentException::class.java) { rule.extractBody("<p>Other</p>", base) }
        assertThrows(IllegalArgumentException::class.java) { rule.extractList("<script>renderArticles()</script>", base) }
        assertThrows(IllegalArgumentException::class.java) { WebsiteRules(items = "article").requireValid("html") }
    }

    @Test fun escapedSelectorsAndExcessiveDomDepthAreRejectedBeforeSelection() {
        assertThrows(IllegalArgumentException::class.java) { WebsiteRules(body = "div:ma\\tches(a+)").requireValid("rss") }
        val nested = "<div>".repeat(300) + "<p>Text</p>" + "</div>".repeat(300)
        assertThrows(IllegalArgumentException::class.java) { WebsiteRules(body = "p").extractBody(nested, base) }
    }

    @Test fun fullTextExclusionsDoNotRemoveListingItems() {
        val rule = WebsiteRules(body = "main", exclude = ".related", items = "article", title = "h2", link = "a")
        val list = rule.extractList("<article class=related><h2>Listing story</h2><a href=/story>Read</a></article>", base)
        assertEquals("Listing story", list.items.single().title)
        val body = rule.extractBody("<main><p>Keep this</p><p class=related>Remove this</p></main>", base)
        assertFalse(body.plainText.contains("Remove this"))
    }
}
