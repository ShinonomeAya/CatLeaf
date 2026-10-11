package dev.local.catleaf.nativeapp.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VR6 截图用（原型 23/24）：只在隔离模拟器上显式运行（默认被 ManualProbe 排除）。
 * phase=seed 只把「引导已处理」置回 false 并切本机范围，让下次冷启动显示欢迎页；不删任何数据、不写密钥。
 * 原值记入探针私有文件；phase=cleanup 恢复原值（与 OnboardingAccessibilityTest 同一做法）。
 */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class OnboardingScreenshotProbeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val settings = SettingsStore(app)
    private val memo = app.getSharedPreferences("vr6-onboarding-probe", Context.MODE_PRIVATE)

    @Test fun seedOrCleanup() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        check(phase == "seed" || phase == "cleanup") { "requires explicit seed/cleanup phase" }
        if (phase == "seed") {
            if (!memo.getBoolean("seeded", false)) {
                val prev = settings.current()
                memo.edit()
                    .putBoolean("seeded", true)
                    .putBoolean("done", prev.onboardingDone)
                    .putString("kind", prev.activeScopeKind)
                    .commit()
            }
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            settings.setOnboardingDone(false)
        } else if (memo.getBoolean("seeded", false)) {
            settings.setOnboardingDone(memo.getBoolean("done", true))
            settings.setActiveScopeKind(memo.getString("kind", null))
            memo.edit().clear().commit()
        }
    }
}
