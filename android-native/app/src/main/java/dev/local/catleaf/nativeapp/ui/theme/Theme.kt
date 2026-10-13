package dev.local.catleaf.nativeapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = ForestGreen,
    onPrimary = SurfaceLight,
    secondary = ForestGreen,
    background = WarmWhite,
    onBackground = TextPrimaryLight,
    surface = WarmWhite,
    onSurface = TextPrimaryLight,
    surfaceVariant = AccentSoftLight,
    onSurfaceVariant = TextSecondaryLight,
    // 底部导航等 M3 组件的选中底色默认取 secondaryContainer；
    // 不覆盖会落到 Material 默认紫，与设计规范的“选中浅绿底、深绿字”不符。
    secondaryContainer = AccentSoftLight,
    onSecondaryContainer = ForestGreen,
    surfaceContainer = WarmWhite,
    // M3 容器族同样默认带紫味：底部面板取 surfaceContainerLow、对话框/日期控件取
    // surfaceContainerHigh（T22 F08）。只覆盖实际用到的档，浮层按规范用 #FFFFFF。
    surfaceContainerLow = SurfaceLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = DividerLight,
    // 日期选择器等以 primaryContainer 作选中/头部底：给选中浅绿，不留默认紫
    primaryContainer = AccentSoftLight,
    onPrimaryContainer = ForestGreen,
    outline = InteractiveOutlineLight,
    outlineVariant = DividerLight,
    error = ErrorLight,
)

private val DarkColors = darkColorScheme(
    primary = ForestGreenDark,
    onPrimary = OnAccentDark,
    secondary = ForestGreenDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = AccentSoftDark,
    onSurfaceVariant = TextSecondaryDark,
    secondaryContainer = AccentSoftDark,
    onSecondaryContainer = ForestGreenDark,
    surfaceContainer = SurfaceDark,
    // 同上（T22 F08）：深色底部面板/对话框不再残留 Material 默认紫，逐项映射为规范表面色
    surfaceContainerLow = SurfaceDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = DividerDark,
    primaryContainer = AccentSoftDark,
    onPrimaryContainer = ForestGreenDark,
    outline = InteractiveOutlineDark,
    outlineVariant = DividerDark,
    error = ErrorDark,
)

/**
 * 只提供首版需要的排版层级；字号按设计规范，正文 18sp 为默认。
 * 首版不引入自定义字体文件，使用系统无衬线字体与中文回退。
 */
val CatLeafTypography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    // 文章主标题 28/36/700（设计规范 §3）：译文/无正文视图的页首块用
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    // 列表标题 17/24/600（设计规范 §3）
    titleSmall = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
)

@Composable
fun CatLeafTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CatLeafTypography,
        content = content,
    )
}

/**
 * P07 阅读分组：主题选择 → 是否深色。SYSTEM 才跟系统——
 * 纯函数，单测钉住三档映射（设置页写哪个、这里就渲染哪个，不给两说留空间）。
 */
fun resolveDarkTheme(
    mode: dev.local.catleaf.nativeapp.data.settings.SettingsStore.ThemeMode,
    systemInDark: Boolean,
): Boolean = when (mode) {
    dev.local.catleaf.nativeapp.data.settings.SettingsStore.ThemeMode.LIGHT -> false
    dev.local.catleaf.nativeapp.data.settings.SettingsStore.ThemeMode.DARK -> true
    dev.local.catleaf.nativeapp.data.settings.SettingsStore.ThemeMode.SYSTEM -> systemInDark
}
