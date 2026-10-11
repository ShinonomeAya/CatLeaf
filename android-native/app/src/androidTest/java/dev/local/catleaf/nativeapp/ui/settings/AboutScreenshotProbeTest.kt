package dev.local.catleaf.nativeapp.ui.settings

import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.MainActivity
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Actual Activity touch/scroll captures, without a Compose test clock or user data fixtures. */
@ManualProbe
class AboutScreenshotProbeTest {
    @Test fun capture_about_documents() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val settings = SettingsStore(app)
        val old = settings.current()
        val oldFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun find(text: String): AccessibilityNodeInfo? {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.windows.forEach { it.root?.let(nodes::addLast) }
            instrumentation.uiAutomation.rootInActiveWindow?.let(nodes::addLast)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (!node.refresh()) continue
                if ((node.text?.toString() == text || node.contentDescription?.toString() == text) && node.isVisibleToUser) return node
                repeat(node.childCount) { node.getChild(it)?.let(nodes::addLast) }
            }
            return null
        }
        fun scroll() {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(nodes::addLast)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isScrollable && node.isVisibleToUser) {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(500)
                    return
                }
                repeat(node.childCount) { node.getChild(it)?.let(nodes::addLast) }
            }
        }
        fun touch(node: AccessibilityNodeInfo, holdMs: Long = 0) {
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val start = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                if (action == MotionEvent.ACTION_UP && holdMs > 0) Thread.sleep(holdMs)
                val x = if (holdMs > 0) bounds.left + 48f else bounds.exactCenterX()
                val y = if (holdMs > 0) bounds.top + 36f else bounds.exactCenterY()
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            Thread.sleep(600)
        }
        fun click(text: String) {
            Thread.sleep(600)
            repeat(8) { if (find(text) == null) scroll() }
            val label = find(text)
            var node = label
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("Control must be reachable: $text", node != null)
            touch(label!!)
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); Thread.sleep(600) }
        try {
            settings.setOnboardingDone(true)
            settings.setThemeMode(if (InstrumentationRegistry.getArguments().getString("theme") == "dark") SettingsStore.ThemeMode.DARK else SettingsStore.ThemeMode.LIGHT)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("设置")
                click("关于猫页")
                capture("about-home")
                for ((title, name) in listOf("项目来源" to "source", "开源许可（MIT）" to "license", "数据与隐私" to "privacy", "配置帮助" to "help", "依赖声明" to "dependencies")) {
                    click(title)
                    val firstHeading = when (name) {
                        "source" -> "认识猫页"
                        "license" -> "许可全文"
                        "privacy" -> "应用权限"
                        "help" -> "本机阅读"
                        else -> "使用的开源组件"
                    }
                    assertTrue("Document must open: $name", find(firstHeading) != null)
                    capture("about-$name")
                    if (name == "license") assertTrue(find(app.getString(R.string.about_license_body)) != null)
                    scroll()
                    capture("about-$name-scrolled")
                    assertTrue("Back remains visible while reading $name", find("返回") != null)
                    back()
                    assertTrue("Back returns to About", find("关于猫页") != null)
                }
                click("数据与隐私")
                touch(find(app.getString(R.string.about_permissions))!!, 800)
                capture("about-selection")
                assertTrue("Text can be selected and copied", find("复制") != null || find("Copy") != null)
                back()
                if (find("关于猫页") == null) back()
                assertTrue("Selection returns to About", find("关于猫页") != null)
                back()
                assertTrue("About returns to settings", find("设置") != null)
            }
        } finally {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = oldFlags }
            settings.setThemeMode(old.themeMode)
            settings.setOnboardingDone(old.onboardingDone)
        }
    }
}
