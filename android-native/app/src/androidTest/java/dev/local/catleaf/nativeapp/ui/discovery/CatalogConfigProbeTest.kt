package dev.local.catleaf.nativeapp.ui.discovery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.catalog.CatalogRepository
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import dev.local.catleaf.nativeapp.R
import org.junit.Rule
import org.junit.Test

@ManualProbe
class CatalogConfigProbeTest {
    @get:Rule val rule = createComposeRule()
    @Test fun configuration_help_is_reachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = CatalogRepository(context).load().needsConfig.first { it.feedUrl.startsWith("https://rsshub.app/") }
        rule.setContent { CatLeafTheme(darkTheme = InstrumentationRegistry.getArguments().getString("theme") == "dark") {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                DiscoveryDetailScreen(entry, "目录配置", false, {}, {})
            }
        } }
        fun screenshot(name: String) {
            rule.waitForIdle(); Thread.sleep(400)
            java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        screenshot("catalog-config-top")
        rule.onNodeWithText(context.getString(R.string.discovery_config_security)).performScrollTo()
        screenshot("catalog-config-help")
    }
}
