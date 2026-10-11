package dev.local.catleaf.nativeapp.data.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 普通设置检查：默认值、字号夹取、持久化，以及设置里不出现密钥。
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        store = SettingsStore(context)
        runBlocking { store.clear() }
    }

    @Test
    fun defaults_match_design_spec() = runBlocking {
        val s = store.current()
        assertEquals(SettingsStore.ThemeMode.SYSTEM, s.themeMode)
        assertEquals("正文字号默认 18（设计规范）", 18, s.bodyFontSizeSp)
        assertEquals(null, s.currentInstanceKey)
    }

    @Test
    fun font_size_is_clamped_to_supported_range() = runBlocking {
        store.setBodyFontSize(22)
        assertEquals(22, store.current().bodyFontSizeSp)
        store.setBodyFontSize(100)
        assertEquals("超上限应夹到 22", 22, store.current().bodyFontSizeSp)
        store.setBodyFontSize(1)
        assertEquals("超下限应夹到 16", 16, store.current().bodyFontSizeSp)
    }

    @Test
    fun values_persist_and_clear_removes_settings_only() = runBlocking {
        store.setThemeMode(SettingsStore.ThemeMode.DARK)
        store.setCurrentInstanceKey("inst-a")
        store.setLastReadingPosition("feed-1", "unread", "anchor-7")

        val s = store.current()
        assertEquals(SettingsStore.ThemeMode.DARK, s.themeMode)
        assertEquals("inst-a", s.currentInstanceKey)
        assertEquals("anchor-7", s.lastScrollAnchor)

        store.clear()
        assertEquals("清设置后回到默认主题", SettingsStore.ThemeMode.SYSTEM, store.current().themeMode)
        assertEquals(null, store.current().currentInstanceKey)
    }

    @Test
    fun settings_file_contains_no_credential_like_value() = runBlocking {
        store.setThemeMode(SettingsStore.ThemeMode.LIGHT)
        store.setCurrentInstanceKey("inst-a")

        val file = File(store.storagePath())
        assertEquals(true, file.exists())
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertEquals("普通设置里不应有密钥形态的字符串", false, text.contains("secret", ignoreCase = true))
        assertEquals(false, text.contains("sk-"))
        assertEquals("应能读到刚写入的实例标识", true, text.contains("inst-a"))
    }
}