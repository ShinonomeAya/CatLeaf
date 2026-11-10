package dev.local.catleaf.nativeapp.ui.reader

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TapPagingProbeTest {
    @get:Rule val rule = createComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun web(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) } else null
    private fun currentWeb(): WebView? {
        var result: WebView? = null
        instrument.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView?.let(::web) }
        return result
    }
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 80)) {
            val event = MotionEvent.obtain(now, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrument.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
    }
    private fun exact(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { exact(node.getChild(it), text) }
    }

    @Test fun actualTapsPageBothWaysWhileCenterDisabledAndLinkTapsKeepTheirBehavior() {
        val enabled = mutableStateOf(false)
        val external = AtomicReference<String>()
        val reported = java.util.concurrent.atomic.AtomicInteger(0)
        rule.setContent { CatLeafTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) {
            ArticleBodyView(
                bodyHtml = "<p><a href=\"https://example.com/source\">Open source</a></p>" + (1..100).joinToString("") { "<p>Paragraph $it. Readable text to test page turns and selection.</p>" },
                onExternalUrl = { external.set(it) }, tapToPage = enabled.value,
                onScrollPosition = { y, _ -> reported.set(y) },
            )
        } } }
        lateinit var view: WebView
        rule.waitUntil(15_000) {
            view = currentWeb() ?: return@waitUntil false
            var ready = false
            instrument.runOnMainSync { ready = view.progress == 100 && view.contentHeight > 1000 }
            ready
        }
        val position = IntArray(2)
        instrument.runOnMainSync { view.getLocationOnScreen(position) }
        fun y(): Int { var result = 0; instrument.runOnMainSync { result = view.scrollY }; return result }
        tap(position[0] + view.width * .9f, position[1] + view.height * .5f)
        Thread.sleep(500)
        assertEquals(0, y())
        rule.runOnIdle { enabled.value = true }
        tap(position[0] + view.width * .5f, position[1] + view.height * .5f)
        Thread.sleep(500)
        assertEquals(0, y())
        tap(position[0] + view.width * .9f, position[1] + view.height * .5f)
        rule.waitUntil(5000) { y() > 0 }
        val moved = y()
        rule.waitUntil(5000) { reported.get() == moved }
        Thread.sleep(1200)
        assertEquals(moved, y())
        tap(position[0] + view.width * .1f, position[1] + view.height * .5f)
        rule.waitUntil(5000) { y() == 0 }
        lateinit var link: AccessibilityNodeInfo
        rule.waitUntil(5000) { exact(instrument.uiAutomation.rootInActiveWindow, "Open source")?.also { link = it } != null }
        val bounds = Rect().also(link::getBoundsInScreen)
        tap(bounds.exactCenterX(), bounds.exactCenterY())
        rule.waitUntil(5000) { external.get() != null }
        Thread.sleep(500)
        assertEquals("https://example.com/source", external.get())
        assertEquals(0, y())
        instrument.runOnMainSync { assertFalse(view.settings.javaScriptEnabled) }
    }

    @Test fun legacyTextAndSummaryUseTheSameTapZonesWithoutAWebView() {
        rule.setContent { CatLeafTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) {
            PagedReaderColumn(true, 18) { repeat(100) { Text("Paragraph ${it + 1}") } }
        } } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { click(Offset(width * .9f, height * .5f)) }
        rule.waitForIdle()
        rule.onNodeWithText("Paragraph 1").assertIsNotDisplayed()
    }

    @Test fun shortAndBilingualDocumentsKeepClampsAndReloadedPaging() {
        val article = dev.local.catleaf.nativeapp.data.ai.BilingualArticle.fromHtml((1..50).joinToString("") { "<p>Original paragraph $it with readable text.</p>" })
        val html = mutableStateOf("<p>Short article</p>")
        val font = mutableStateOf(18)
        rule.setContent { CatLeafTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) {
            ArticleBodyView(bodyHtml = html.value, onExternalUrl = {}, fontSizeSp = font.value, tapToPage = true)
        } } }
        lateinit var view: TapPagingWebView
        var previousDocument = ""
        fun y(): Int { var result = 0; instrument.runOnMainSync { result = view.scrollY }; return result }
        fun ready() {
            rule.waitUntil(15_000) {
                view = currentWeb() as? TapPagingWebView ?: return@waitUntil false
                var loaded = false
                instrument.runOnMainSync {
                    val key = view.interactionKey()
                    loaded = key != previousDocument && view.pagingEnabled() && view.contentHeight > 0
                    if (loaded) previousDocument = key
                }
                loaded
            }
        }
        fun right() {
            val at = IntArray(2)
            instrument.runOnMainSync { view.getLocationOnScreen(at) }
            tap(at[0] + view.width * .9f, at[1] + view.height * .5f)
            Thread.sleep(500)
        }
        ready(); right(); assertEquals(0, y())
        rule.runOnIdle { html.value = article.render(article.blocks.associate { it.id to "对应中文译文，保留原文并继续阅读。" }) }
        ready(); right(); assertTrue(y() > 0)
        rule.runOnIdle { font.value = 22 }
        ready()
        val restored = y()
        right(); assertTrue(y() > restored)
        val moved = y()
        Thread.sleep(1300)
        assertEquals(moved, y())
    }
}
