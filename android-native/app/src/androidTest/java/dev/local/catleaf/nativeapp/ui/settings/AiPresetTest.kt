package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPresetTest {
    @Test fun preset_never_reuses_another_providers_saved_key_or_saves_without_confirmation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val settings = SettingsStore(app)
        val credentials = CredentialStore(app)
        val previous = settings.current()
        val oldKey = credentials.ciphertext(CredentialStore.AI_KEY)
        val oldOrigin = credentials.ciphertext(CredentialStore.AI_KEY_ORIGIN)
        val store = ViewModelStore()
        try {
            settings.setAiConfig("https://another.example.org", "old-model")
            credentials.putAiKey("https://another.example.org", "NOT-A-REAL-PRESET-KEY")
            lateinit var vm: AiSettingsViewModel
            instrumentation.runOnMainSync { vm = AiSettingsViewModel(app); store.put("ai", vm) }
            withTimeout(5000) { vm.state.first { it.keySaved } }
            instrumentation.runOnMainSync { vm.useDeepSeek(); vm.testConnection(); vm.save() }
            assertEquals("https://api.deepseek.com", vm.state.value.baseUrl)
            assertEquals("deepseek-flash", vm.state.value.model)
            assertFalse(vm.state.value.keySaved)
            assertEquals("", vm.state.value.keyInput)
            assertTrue(vm.state.value.test is AiTestState.Failed)
            assertEquals("https://another.example.org", settings.current().aiBaseUrl)
            assertEquals("NOT-A-REAL-PRESET-KEY", credentials.getAiKey("https://another.example.org"))
            assertNull(credentials.getAiKey("https://api.deepseek.com"))

            // A partial prior save must not mark another origin's key as usable on reopening.
            credentials.putAiKey("https://api.deepseek.com", "NOT-A-REAL-MISMATCHED-KEY")
            lateinit var reopened: AiSettingsViewModel
            instrumentation.runOnMainSync { reopened = AiSettingsViewModel(app); store.put("reopened", reopened) }
            withTimeout(5000) { reopened.state.first { it.baseUrl == "https://another.example.org" } }
            assertFalse(reopened.state.value.keySaved)
            instrumentation.runOnMainSync { reopened.save() }
            assertEquals("请先填写 API Key", reopened.state.value.message)
            assertEquals("https://another.example.org", settings.current().aiBaseUrl)
            assertEquals("NOT-A-REAL-MISMATCHED-KEY", credentials.getAiKey("https://api.deepseek.com"))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            credentials.restoreCiphertext(CredentialStore.AI_KEY, oldKey)
            credentials.restoreCiphertext(CredentialStore.AI_KEY_ORIGIN, oldOrigin)
            settings.setAiConfig(previous.aiBaseUrl, previous.aiModel)
        }
    }
}
