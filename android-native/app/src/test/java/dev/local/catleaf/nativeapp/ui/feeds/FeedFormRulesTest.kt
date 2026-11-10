package dev.local.catleaf.nativeapp.ui.feeds

import dev.local.catleaf.nativeapp.data.article.WebsiteRules
import org.junit.Assert.*
import org.junit.Test

class FeedFormRulesTest {
    @Test fun webSourceRequiresMatchingPreviewAndValidSelectors() {
        val form = FeedFormState(url = "https://example.com/news", sourceType = "html", rules = WebsiteRules(items = "article", title = "h2", link = "a"))
        assertFalse(form.canSubmit)
        assertTrue(form.copy(previewedKey = form.configurationKey).canSubmit)
        assertFalse(form.copy(url = "https://other.example/news", previewedKey = form.configurationKey).canSubmit)
        assertFalse(form.copy(rules = form.rules.copy(link = "["), previewedKey = form.configurationKey).canSubmit)
    }

    @Test fun rssKeepsExistingFlowUnlessCustomRulesAreEnabled() {
        val form = FeedFormState(url = "https://example.com/feed.xml")
        assertTrue(form.canSubmit)
        val rules = form.copy(customRules = true, rules = WebsiteRules(body = ".entry"), previewUrl = "https://example.com/article")
        assertFalse(rules.canSubmit)
        assertTrue(rules.copy(previewedKey = rules.configurationKey).canSubmit)
        assertFalse(rules.copy(previewUrl = "https://example.com/different", previewedKey = rules.configurationKey).canSubmit)
    }
}
