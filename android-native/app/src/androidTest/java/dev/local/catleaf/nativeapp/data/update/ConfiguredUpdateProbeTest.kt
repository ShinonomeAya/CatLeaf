package dev.local.catleaf.nativeapp.data.update

import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredUpdateProbeTest {
    @ManualProbe
    @Test fun official_manifest_is_readable_with_production_fetch_policy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = context.getString(R.string.update_manifest_url)
        assertTrue("官方清单地址必须配置", url.isNotBlank())
        val checker = AppUpdates()
        assertTrue(checker.check(url, context.packageName, 1) is UpdateCheck.Available)
        assertEquals(UpdateCheck.Latest, checker.check(url, context.packageName, Long.MAX_VALUE))
    }
}
