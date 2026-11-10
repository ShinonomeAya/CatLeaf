package dev.local.catleaf.nativeapp.data.importexport

import dev.local.catleaf.nativeapp.data.article.WebsiteRules
import org.junit.Assert.*
import org.junit.Test

class WebSourceStateTest {
    @Test fun rulesSurviveBackupAndOldV2DefaultsToRss() {
        val rule = WebsiteRules(items = "article", title = "h2", link = "a").toJson()
        val feed = StateFeed("https://example.com/news", "News", "", "html", rule)
        val doc = StateFile.read(StateFile.write("local", null, 1, listOf(feed), emptyList())).getOrThrow()
        assertEquals(feed, doc.feeds.single())
        val old = StateFile.read("""{"format":"catweb-state","version":2,"source":{"kind":"local"},"feeds":[{"url":"https://example.com/feed","title":"Old","category":""}]}""".toByteArray()).getOrThrow()
        assertEquals("rss", old.feeds.single().sourceType)
        assertNull(old.feeds.single().ruleJson)
    }

    @Test fun invalidRulesAreReportedBeforeAnyRestore() {
        val doc = StateFile.read("""{"format":"catweb-state","version":3,"source":{"kind":"local"},"feeds":[{"url":"https://example.com/news","sourceType":"html","rules":{"items":"["}}]}""".toByteArray()).getOrThrow()
        assertTrue(doc.feeds.isEmpty())
        assertEquals(1, doc.invalidItems.size)
    }
}
