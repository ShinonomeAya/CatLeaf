package dev.local.catleaf.nativeapp.ui.onboarding

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.MainActivity
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val settings by lazy { SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext) }
    private var previous: SettingsStore.Snapshot? = null

    @Before fun showFirstRunOnboarding() { runBlocking {
        previous = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setOnboardingDone(false)
        rule.activityRule.scenario.recreate()
    } }

    @After fun restoreSettings() { runBlocking {
        rule.activityRule.scenario.close()
        previous?.let {
            settings.setActiveScopeKind(it.activeScopeKind)
            settings.setOnboardingDone(it.onboardingDone)
            settings.setCurrentInstanceKey(it.currentInstanceKey)
            settings.setLastReadingPosition(it.lastFeedId, it.lastFilter, it.lastScrollAnchor)
        }
    } }

    @Test fun recommended_source_is_named_and_toggles_as_one_checkbox() {
        // 根导航异步判定是否显示引导（读 DataStore/Room）：先等欢迎页出现，避免冷启动慢时偶发找不到
        rule.waitUntil(10_000) { rule.onAllNodesWithText("开始阅读").fetchSemanticsNodes().isNotEmpty() }
        // 360dp/1.3 倍字号下「开始阅读」在首屏以下：先滚到可见再点，与真实操作一致
        rule.onNodeWithText("开始阅读").performScrollTo().performClick()
        val source = rule.onNodeWithContentDescription("少数派").performScrollTo()
        source.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        source.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        source.performClick()

        source.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        rule.onNodeWithText("订阅并开始（1）").assertIsEnabled()
    }
}
