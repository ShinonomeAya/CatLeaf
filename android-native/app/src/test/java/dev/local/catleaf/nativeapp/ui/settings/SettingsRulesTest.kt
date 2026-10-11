package dev.local.catleaf.nativeapp.ui.settings

import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.reader.ReaderAssets
import dev.local.catleaf.nativeapp.ui.theme.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T20 设置收口的纯逻辑：
 * - 主题三档解析（P07：跟随系统/浅/深）——决定整 App 深色与否；
 * - 正文字号注入 CSS（P07：16/18/20/22）——设置选哪个，WebView 就是哪个，不双重缩放。
 */
class SettingsRulesTest {

    @Test
    fun theme_resolves_per_mode_not_just_system() {
        // SYSTEM 才跟系统；LIGHT/DARK 无视系统
        assertTrue("SYSTEM + 系统深色 → 深色", resolveDarkTheme(SettingsStore.ThemeMode.SYSTEM, systemInDark = true))
        assertFalse("SYSTEM + 系统浅色 → 浅色", resolveDarkTheme(SettingsStore.ThemeMode.SYSTEM, systemInDark = false))
        assertFalse("LIGHT 强制浅色（即便系统深色）", resolveDarkTheme(SettingsStore.ThemeMode.LIGHT, systemInDark = true))
        assertTrue("DARK 强制深色（即便系统浅色）", resolveDarkTheme(SettingsStore.ThemeMode.DARK, systemInDark = false))
    }

    @Test
    fun font_size_injects_css_and_clamps_out_of_range() {
        // 默认 18 保持设计规范基准字号
        assertTrue("默认 18px", ReaderAssets.cssFor(18, dark = false).contains("font-size: 18px;"))
        // 特大 22 生效且只出现一次（不叠加、不双重缩放）
        val css22 = ReaderAssets.cssFor(22, dark = false)
        assertTrue("含 22px", css22.contains("font-size: 22px;"))
        assertFalse("旧 18px 已被替换，不留两份", css22.contains("font-size: 18px;"))
        // 原断言数全 CSS 的 font-size:Npx，T22 F01 页首块引入固定 13px 的元信息规则后误伤；
        // 本意是防“基准字号被叠加两次”，所以只数 body 规则内的声明
        assertEquals(
            "body 基准声明只出现一次",
            1,
            Regex("body \\{[^}]*font-size: \\d+px").findAll(css22).count(),
        )
        // 越界夹到 16..22（SettingsStore 也夹，这里双保险：即使 VM 漏夹也不会渲染出病态字号）
        assertTrue("下限夹到 16", ReaderAssets.cssFor(9, dark = false).contains("font-size: 16px;"))
        assertTrue("上限夹到 22", ReaderAssets.cssFor(40, dark = false).contains("font-size: 22px;"))
    }

    @Test
    fun body_theme_follows_app_not_system() {
        // T22 真机发现：正文用 media query 会跟系统不跟 App——两档都必须显式注入且无 @media
        val dark = ReaderAssets.cssFor(18, dark = true)
        val light = ReaderAssets.cssFor(18, dark = false)
        assertFalse("不再依赖系统 prefers-color-scheme", dark.contains("@media"))
        assertTrue("深色用设计规范色值", dark.contains("#181D1A") && dark.contains("#E5E9E3"))
        assertTrue("浅色用设计规范色值", light.contains("#FAF9F6") && light.contains("#252B28"))
    }
}
