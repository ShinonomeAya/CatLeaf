package dev.local.catleaf.nativeapp.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures real Material switches; no copied token assertions. */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class SwitchStatesProbeTest {
    @get:Rule val rule = createComposeRule()
    @Test fun captureFourStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dark = InstrumentationRegistry.getArguments().getString("theme") == "DARK"
        rule.setContent {
            CatLeafTheme(darkTheme = dark) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).statusBarsPadding()) {
                        Text("共享开关四态", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        for (enabled in listOf(true, false)) for (checked in listOf(true, false)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${if (enabled) "可用" else "禁用"} · ${if (checked) "开" else "关"}", Modifier.weight(1f))
                                Switch(checked = checked, enabled = enabled, onCheckedChange = {})
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("共享开关四态").assertIsDisplayed()
        Thread.sleep(400) // Wait for the first window buffer, not only Compose's idle state.
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "rf-switch-states.png")
        target.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
