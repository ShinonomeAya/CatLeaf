package dev.local.catleaf.nativeapp.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.update.UpdateCheck
import dev.local.catleaf.nativeapp.data.update.UpdateRelease
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Capture the production dialog with isolated result fixtures; no copied layout. */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class UpdateDialogScreenshotProbeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun capture_states_and_retry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = mutableStateOf<UpdateCheck>(UpdateCheck.Checking)
        var retries = 0
        var dismissals = 0
        rule.setContent {
            CatLeafTheme(darkTheme = InstrumentationRegistry.getArguments().getString("theme") == "dark") {
                Surface(Modifier.fillMaxSize()) {
                    UpdateDialog(state.value, "0.1.19", { dismissals++ }, { retries++; state.value = UpdateCheck.Checking })
                }
            }
        }
        for ((name, value) in listOf(
            "checking" to UpdateCheck.Checking,
            "unconfigured" to UpdateCheck.NotConfigured,
            "latest" to UpdateCheck.Latest,
            "available" to UpdateCheck.Available(UpdateRelease("0.1.20", 21, "https://example.org/download/", "新增阅读功能，修复图片加载与窄屏排版问题。\n保留已有订阅、收藏和离线副本。")),
            "failed" to UpdateCheck.Failed("请求超时"),
        )) {
            rule.runOnIdle { state.value = value }
            rule.waitForIdle()
            Thread.sleep(400)
            File(instrumentation.targetContext.getExternalFilesDir(null), "update-$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        rule.onNodeWithText("重试").performClick()
        rule.runOnIdle { assertEquals(1, retries); assertEquals(UpdateCheck.Checking, state.value) }
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }
}
