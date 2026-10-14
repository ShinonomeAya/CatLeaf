package dev.local.catleaf.nativeapp.ui.reader

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual touchscreen selection and system popup; no classifier call, JS, bridge, clipboard or provider request. */
@ManualProbe
class ArticleSelectionNativeProbeTest {
    @get:Rule val rule = createComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val word = "CatLeafselection"

    @Test fun long_press_controlled_webview_opens_native_ai_action_and_delivers_actual_selection() {
        val automation = instrument.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.setServiceInfo(automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        })
        try {
            val arrived = CountDownLatch(1)
            val selected = AtomicReference<String>()
            val calls = AtomicInteger(0)
            val onMain = AtomicBoolean(false)
            rule.setContent {
                CatLeafTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) {
                    ArticleBodyView(
                        bodyHtml = BilingualArticle.fromHtml("<p>$word</p><p>Native selection probe.</p>").render(),
                        onExternalUrl = { fail("Local text has no external link") },
                        fontSizeSp = 22,
                        onAiSelection = {
                            selected.set(it)
                            calls.incrementAndGet()
                            onMain.set(Looper.myLooper() == Looper.getMainLooper())
                            arrived.countDown()
                        },
                    )
                } }
            }
            lateinit var web: WebView
            rule.waitUntil(10_000) {
                var ready = false
                instrument.runOnMainSync {
                    val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                    activity?.window?.decorView?.let(::findWebView)?.let {
                        web = it
                        ready = it.progress == 100 && it.contentHeight > 0 && it.title == ReaderAssets.PAGE_TITLE
                    }
                }
                ready
            }
            val drawn = CountDownLatch(1)
            instrument.runOnMainSync {
                assertFalse(web.settings.javaScriptEnabled)
                assertTrue(web.url.orEmpty().startsWith(ReaderAssets.PAGE_URL + "?render="))
                web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { drawn.countDown() }
                })
            }
            assertTrue("Local WebView did not draw", drawn.await(5, TimeUnit.SECONDS))
            val textNode = awaitNode(5000) { it.text?.toString() == word }
            assertNotNull("Rendered text is unavailable to native accessibility", textNode)
            val bounds = Rect()
            textNode!!.getBoundsInScreen(bounds)
            textNode.recycle()
            assertTrue("Text bounds are empty", bounds.width() > 0 && bounds.height() > 0)
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrument.uiAutomation.injectInputEvent(down, true))
            down.recycle()
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 300)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrument.uiAutomation.injectInputEvent(up, true))
            up.recycle()

            capture("article-selection-native-menu.png")
            captureTree("article-selection-native-menu-tree.txt")
            var action = awaitNode(3000, ::isAiAction)
            if (action == null) {
                val more = awaitNode(3000) {
                    it.contentDescription?.toString() in setOf("More options", "更多选项", "更多")
                }
                assertNotNull("Native selection popup has neither AI action nor overflow", more)
                assertTrue(clickAndRecycle(more!!))
                capture("article-selection-native-overflow.png")
                captureTree("article-selection-native-overflow-tree.txt")
                action = awaitNode(5000, ::isAiAction)
            }
            assertNotNull("Actual native selection popup has no AI action", action)
            capture("article-selection-native-action.png")
            assertTrue(clickAndRecycle(action!!))
            assertTrue("Private selection callback did not arrive", arrived.await(5, TimeUnit.SECONDS))
            assertEquals(word, selected.get())
            assertEquals(1, calls.get())
            assertTrue(onMain.get())
            instrument.runOnMainSync { assertFalse(web.settings.javaScriptEnabled) }
            println("ARTICLE_SELECTION_NATIVE callback=${selected.get()} count=${calls.get()} main=${onMain.get()} javascript=false")
        } finally {
            automation.setServiceInfo(automation.serviceInfo.apply { flags = previousFlags })
        }
    }

    private fun findWebView(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) } else null

    private fun isAiAction(node: AccessibilityNodeInfo): Boolean =
        node.text?.toString() in setOf("AI解读", "AI解读所选文字") ||
            node.contentDescription?.toString() in setOf("AI解读", "AI解读所选文字")

    private fun clickAndRecycle(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                return clicked
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return false
    }

    private fun awaitNode(timeout: Long, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (match(node)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val result = visit(child)
                if (result !== child) child.recycle()
                if (result != null) return result
            }
            return null
        }
        val end = SystemClock.uptimeMillis() + timeout
        do {
            val roots = roots()
            var found: AccessibilityNodeInfo? = null
            for (root in roots) {
                if (found != null) { root.recycle(); continue }
                found = visit(root)
                if (found !== root) root.recycle()
            }
            if (found != null) return found
            Thread.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        return null
    }

    private fun roots(): List<AccessibilityNodeInfo> = buildList {
        instrument.uiAutomation.windows.forEach { it.root?.let(::add) }
        instrument.uiAutomation.rootInActiveWindow?.let(::add)
    }

    private fun captureTree(name: String) {
        val text = StringBuilder()
        fun append(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also(node::getBoundsInScreen)
            text.appendLine("${"  ".repeat(depth)}${node.className} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable} bounds=$bounds")
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                append(child, depth + 1)
                child.recycle()
            }
        }
        roots().forEachIndexed { index, root ->
            text.appendLine("WINDOW_ROOT $index")
            append(root, 0)
            root.recycle()
        }
        File(instrument.targetContext.getExternalFilesDir(null), name).writeText(text.toString())
        println("ARTICLE_SELECTION_NATIVE_TREE\n$text")
    }

    private fun capture(name: String) {
        val context = instrument.targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(instrument.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
