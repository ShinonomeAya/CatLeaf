package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FeedManagerDirectNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun direct_add_result_opens_manager_and_back_remains_reachable() = verify(FeedManagerStart.AddForm)
    @Test fun discovery_prefill_result_opens_manager_without_automatic_exit() = verify(FeedManagerStart.Prefilled)
    @Test fun ordinary_manager_result_still_opens_manager() = verify(null)

    private fun verify(start: FeedManagerStart?) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val settings = SettingsStore(app)
        val db = CatLeafDatabase.get(app)
        val previous = settings.current()
        val name = "隔离导航-${UUID.randomUUID()}"
        val url = "https://navigation-${UUID.randomUUID()}.invalid/feed"
        var backs = 0
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            val vm = FeedManagerViewModel(app)
            if (start != FeedManagerStart.AddForm) vm.openPrefilledAddForm(url, name, "")
            rule.setContent { CatLeafTheme { FeedManagerScreen(vm, onBack = { backs++ }, startAction = start) } }
            rule.waitUntil(5_000) { vm.form.value != null }
            rule.runOnIdle {
                vm.onUrlChange(url)
                vm.onTitleChange(name)
                vm.submitForm()
            }
            rule.waitUntil(25_000) { vm.addResult.value?.retrying == false }
            rule.runOnIdle { assertEquals("result must not exit while its title is read from Room", 0, backs) }
            rule.onNodeWithText("查看订阅").performClick()
            rule.onNodeWithText("订阅管理").assertIsDisplayed()
            rule.onNodeWithText(name).assertIsDisplayed()
            rule.runOnIdle { assertEquals(0, backs) }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            java.io.File(app.getExternalFilesDir(null), "navigation-${start?.name ?: "manager"}.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithContentDescription("返回").performClick()
            rule.runOnIdle { assertEquals(1, backs) }
            assertEquals(1, db.feeds().list(LOCAL_SCOPE_KEY).count { it.url == url })
        } finally {
            db.feeds().list(LOCAL_SCOPE_KEY).filter { it.url == url }.forEach { db.feeds().delete(LOCAL_SCOPE_KEY, it.feedId) }
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }
}
