package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.article.WebsiteRules
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.settings.ReadingPreferencesScreen
import dev.local.catleaf.nativeapp.ui.settings.SettingsViewModel
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Production surfaces with isolated sample form data; no subscription or AI request. */
@ManualProbe
class WebRulesVisualProbeTest {
    @get:Rule val rule = createComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val pagingLabel = app.getString(dev.local.catleaf.nativeapp.R.string.reading_prefs_tap_paging)
    private val dark = mutableStateOf(false)
    private val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "probe"
    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(350)
        File(app.getExternalFilesDir(null), "web-rules-$profile-$name.png").outputStream().use {
            checkNotNull(instrument.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun readingPreferenceSwitchSavesAndRemainsReachableInBothThemes() = runBlocking {
        val settings = SettingsStore(app)
        val previous = settings.current()
        settings.setTapToPage(false)
        lateinit var model: SettingsViewModel
        instrument.runOnMainSync { model = SettingsViewModel(app) }
        try {
            rule.setContent { CatLeafTheme(darkTheme = dark.value) { Surface(Modifier.fillMaxSize()) { ReadingPreferencesScreen(model, {}) } } }
            rule.onNodeWithText(pagingLabel).performScrollTo().performClick()
            rule.waitUntil(5000) { model.tapToPage.value }
            rule.waitUntil(5000) { runBlocking { settings.current().tapToPage } }
            assertTrue(settings.current().tapToPage)
            for (night in listOf(false, true)) {
                rule.runOnIdle { dark.value = night }
                rule.onNodeWithText(pagingLabel).assertIsDisplayed()
                capture("${if (night) "dark" else "light"}-preferences")
            }
        } finally {
            settings.setTapToPage(previous.tapToPage)
            instrument.runOnMainSync { model.viewModelScope.cancel() }
        }
    }

    @Test fun ruleFormsScrollToPreviewAndSaveWithoutClipping() {
        val state = mutableStateOf(FeedFormState(url = "https://example.com/news/", customTitle = "网页规则隔离样本"))
        var previews = 0
        var saves = 0
        rule.setContent { CatLeafTheme(darkTheme = dark.value) { Surface(Modifier.fillMaxSize()) {
            FeedFormSurface(state.value, null, emptyList(), true,
                onSourceType = { state.value = state.value.copy(sourceType = it) },
                onCustomRules = { state.value = state.value.copy(customRules = it) },
                onRules = { state.value = state.value.copy(rules = it, previewedKey = null) },
                onPreviewUrl = { state.value = state.value.copy(previewUrl = it) },
                onPreview = { previews++ }, onUrl = {}, onTitle = {}, onCategory = {},
                onSubmit = { saves++ }, onDismiss = {}, onDelete = {})
        } } }
        for (night in listOf(false, true)) {
            rule.runOnIdle { dark.value = night; state.value = state.value.copy(sourceType = "rss", customRules = true, rules = WebsiteRules(body = ".entry-content", exclude = ".ad"), previewUrl = "https://example.com/story") }
            rule.onNodeWithText("RSS / Atom").performScrollTo().assertIsDisplayed()
            capture("${if (night) "dark" else "light"}-rss-top")
            rule.onNodeWithText("预览正文").performScrollTo().performClick()
            capture("${if (night) "dark" else "light"}-rss-preview")
            rule.onNodeWithText("网页订阅", substring = false).performScrollTo().performClick()
            rule.runOnIdle { state.value = state.value.copy(rules = WebsiteRules(items = "article", title = "h2", link = "a", date = "time", summary = "p")) }
            rule.onNodeWithText("网页列表地址").performScrollTo().assertIsDisplayed()
            capture("${if (night) "dark" else "light"}-html-top")
            rule.onNodeWithText("条目内文章链接（CSS，必填）").performScrollTo().assertIsDisplayed()
            capture("${if (night) "dark" else "light"}-html-fields")
            rule.onNodeWithText("预览列表").performScrollTo().performClick()
            rule.runOnIdle { state.value = state.value.copy(previewedKey = state.value.configurationKey, previewMessage = "预览成功：2篇有效文章，示例：一篇可读文章。") }
            rule.onNodeWithText("添加并验证").performScrollTo().assertIsEnabled().performClick()
            capture("${if (night) "dark" else "light"}-html-save")
        }
        assertEquals(4, previews)
        assertEquals(2, saves)
    }
}
