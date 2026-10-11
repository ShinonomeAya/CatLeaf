package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.MainActivity
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.*
import dev.local.catleaf.nativeapp.data.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Actual Activity/Choreographer captures; avoids the Compose test-clock observer race. */
@ManualProbe
class AllSubscriptionsScreenshotProbeTest {
    @Test fun capture_actual_reading_panels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = CatLeafDatabase.get(app)
        val settings = SettingsStore(app)
        val old = settings.current()
        val scope = "all-screenshot-${UUID.randomUUID()}"
        fun find(label: String): AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            val nodes = ArrayDeque<AccessibilityNodeInfo>(); nodes.add(root)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                repeat(node.childCount) { node.getChild(it)?.let(nodes::addLast) }
            }
            return null
        }
        fun click(label: String) {
            val deadline = System.currentTimeMillis() + 10_000
            var node = find(label)
            while (node == null && System.currentTimeMillis() < deadline) { Thread.sleep(100); node = find(label) }
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("Native control must be reachable: $label", node != null)
            val bounds = Rect(); node!!.getBoundsInScreen(bounds)
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            Thread.sleep(600)
        }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            java.io.File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun scrollSourceList(action: Int) {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(nodes::addLast)
            var node: AccessibilityNodeInfo? = null
            while (nodes.isNotEmpty()) {
                val candidate = nodes.removeFirst()
                if (candidate.isScrollable && candidate.isVisibleToUser) { node = candidate; break }
                repeat(candidate.childCount) { candidate.getChild(it)?.let(nodes::addLast) }
            }
            assertTrue("Source list scroll must be reachable", node != null)
            assertTrue(node!!.performAction(action))
            Thread.sleep(600)
        }
        try {
            db.instances().upsert(InstanceEntity(scope, "https://capture.invalid", "隔离截图", 1))
            (1..20).forEach { i ->
                val f = "s%02d".format(i)
                db.feeds().upsert(FeedEntity(scope, f, "https://capture.invalid/$f", "隔离订阅 %02d".format(i), "分组 ${('A'.code + (i - 1) / 8).toChar()}", "ok", 1))
                db.articles().upsertArticle(ArticleEntity("$scope-$f", scope, f, f, null, "隔离文章$f", null,
                    "隔离订阅$f", "https://capture.invalid/$f", stateUpdatedAt = 1))
            }
            repeat(430) { i ->
                val f = "s%02d".format(i % 20 + 1)
                db.articles().upsertArticle(ArticleEntity("$scope-extra-$i", scope, f, "extra-$i", null, "隔离分页文章$i", null,
                    "隔离订阅$f", "https://capture.invalid/$f", stateUpdatedAt = 1))
            }
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            settings.setCurrentInstanceKey(scope)
            settings.setOnboardingDone(true)
            settings.setThemeMode(if (InstrumentationRegistry.getArguments().getString("theme") == "dark") SettingsStore.ThemeMode.DARK else SettingsStore.ThemeMode.LIGHT)
            settings.setLastReadingPosition(null, null, null, scope, allFeeds = true)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("选择订阅")
                screenshot("all-source-sheet")
                scrollSourceList(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                screenshot("all-source-sheet-scrolled")
                scrollSourceList(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                screenshot("all-source-sheet-top")
                click("全部订阅")
                screenshot("all-reading")
                repeat(5) {
                    if (find("显示 200 / 450 篇")?.isVisibleToUser != true) scrollSourceList(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                assertTrue("Saved total must be visible", find("显示 200 / 450 篇")?.isVisibleToUser == true)
                screenshot("all-reading-count")
                repeat(5) {
                    if (find("选择订阅")?.isVisibleToUser != true) scrollSourceList(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                click("选择订阅")
                click("隔离订阅 01")
                click("选择订阅")
                screenshot("selected-source-sheet")
                click("取消")
                click("全部日期")
                screenshot("all-date-sheet")
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey = ?", arrayOf(scope))
            db.feeds().deleteAll(scope)
            db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(scope))
            settings.setThemeMode(old.themeMode)
            settings.setOnboardingDone(old.onboardingDone)
            settings.setCurrentInstanceKey(old.currentInstanceKey)
            settings.setActiveScopeKind(old.activeScopeKind)
            settings.setLastReadingPosition(old.lastFeedId, old.lastFilter, old.lastScrollAnchor, old.lastReadingScopeKey, old.lastReadingAllFeeds, old.lastReadingLoadedCount)
        }
    }
}
