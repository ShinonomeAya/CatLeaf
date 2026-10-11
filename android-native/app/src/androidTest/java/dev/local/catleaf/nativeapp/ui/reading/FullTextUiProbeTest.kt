package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit screenshot configuration on the dedicated RF emulator, preserving previous settings. */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class FullTextUiProbeTest {
    @Test fun configure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val settings = SettingsStore(app)
        val args = InstrumentationRegistry.getArguments()
        val memo = app.getSharedPreferences("rf-ui-probe", Context.MODE_PRIVATE)
        when (args.getString("phase")) {
            "config" -> {
                if (!memo.contains("theme")) {
                    val old = settings.current()
                    memo.edit().putString("theme", old.themeMode.name).putBoolean("onboarding", old.onboardingDone).commit()
                }
                settings.setThemeMode(SettingsStore.ThemeMode.valueOf(args.getString("theme")!!))
                settings.setOnboardingDone(true)
            }
            "favorite", "empty" -> {
                val starred = args.getString("phase") == "favorite"
                check(CatLeafDatabase.get(app).articles().setStarred("vr4-a-0", starred, if (starred) 1 else null, 1) == 1)
            }
            "cleanup" -> {
                settings.setThemeMode(SettingsStore.ThemeMode.valueOf(memo.getString("theme", "SYSTEM")!!))
                settings.setOnboardingDone(memo.getBoolean("onboarding", false))
                memo.edit().clear().commit()
            }
            else -> error("requires explicit config/favorite/empty/cleanup phase")
        }
        Unit
    }
}
