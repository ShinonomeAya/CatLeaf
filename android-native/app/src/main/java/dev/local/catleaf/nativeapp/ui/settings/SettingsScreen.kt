package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.RootHeader
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.ActivityScopeRules
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.update.AppUpdates
import dev.local.catleaf.nativeapp.data.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页收口（P07；T20）。
 *
 * 「写内存 + 写 DataStore」两份的原因（T16 实测坑）：DataStore 写后立即读会拿到旧值，
 * 而主题/字号要求**即时预览**——所以 VM 里内存值直接驱动 UI，快照回流只是兜底。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val db = CatLeafDatabase.get(application)

    private val _themeMode = MutableStateFlow(SettingsStore.ThemeMode.SYSTEM)
    val themeMode: StateFlow<SettingsStore.ThemeMode> = _themeMode.asStateFlow()

    private val _bodyFontSize = MutableStateFlow(SettingsStore.DEFAULT_FONT_SIZE_SP)
    val bodyFontSize: StateFlow<Int> = _bodyFontSize.asStateFlow()

    /** 当前实例 origin 展示（不含密钥——P07 明确不显示完整已存密钥，这里连一段都不显示） */
    private val _currentOrigin = MutableStateFlow<String?>(null)
    val currentOrigin: StateFlow<String?> = _currentOrigin.asStateFlow()

    /** 每次「重置此设备数据」+1；根导航据此回到 P01 连接页 */
    private val _resetTick = MutableStateFlow(0)
    val resetTick: StateFlow<Int> = _resetTick.asStateFlow()

    private val _updateCheck = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val updateCheck: StateFlow<UpdateCheck> = _updateCheck.asStateFlow()

    fun checkForUpdates() {
        if (_updateCheck.value == UpdateCheck.Checking) return
        _updateCheck.value = UpdateCheck.Checking
        viewModelScope.launch {
            val app = getApplication<Application>()
            val installedCode = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            _updateCheck.value = withContext(Dispatchers.IO) {
                AppUpdates().check(app.getString(R.string.update_manifest_url), app.packageName, installedCode)
            }
        }
    }

    init {
        viewModelScope.launch {
            settings.snapshot.collect { snap ->
                // 冷启动时恢复已保存的主题/字号；运行中的即时预览由 setX 先写内存，
                // 这里回流的是同一个值，不会闪回旧档
                _themeMode.value = snap.themeMode
                _bodyFontSize.value = snap.bodyFontSizeSp
                // 仅实例范围生效时显示 origin；本机范围下保留的实例指针不算「当前使用」
                val kind = ActivityScopeRules.decideKind(snap.activeScopeKind, snap.currentInstanceKey)
                _currentOrigin.value = if (snap.currentInstanceKey == null || kind != SCOPE_KIND_INSTANCE) {
                    null
                } else {
                    runCatching { db.instances().get(snap.currentInstanceKey)?.origin }.getOrNull()
                }
            }
        }
    }

    /** 阅读偏好页的保存反馈（UIR04）：离开页面后下次进入从 Idle 开始 */
    private val _saveStatus = MutableStateFlow(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    fun clearSaveStatus() {
        _saveStatus.value = SaveStatus.Idle
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        val previous = _themeMode.value
        if (previous == mode) return
        viewModelScope.launch {
            _saveStatus.value = saveWithRollback(previous, mode, { _themeMode.value = it }) { settings.setThemeMode(it) }
        }
    }

    fun setBodyFontSize(sp: Int) {
        val next = sp.coerceIn(SettingsStore.MIN_FONT_SIZE_SP, SettingsStore.MAX_FONT_SIZE_SP)
        val previous = _bodyFontSize.value
        if (previous == next) return
        viewModelScope.launch {
            _saveStatus.value = saveWithRollback(previous, next, { _bodyFontSize.value = it }) { settings.setBodyFontSize(it) }
        }
    }

    /**
     * 重置此设备数据（P07 重置分组）：删全部本机实例、密钥、订阅镜像、文章与缓存；
     * **不碰云端订阅**（这里没有任何网络调用）。二次确认在界面上完成。
     */
    fun resetDeviceData() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            OfflinePackageStore.clearForDeviceReset(app)
            runCatching { CredentialStore(app).clearAll() }
            runCatching { settings.clear() }
            CatLeafDatabase.resetForDeviceReset(app)
            _themeMode.value = SettingsStore.ThemeMode.SYSTEM
            _bodyFontSize.value = SettingsStore.DEFAULT_FONT_SIZE_SP
            _currentOrigin.value = null
            _resetTick.value = _resetTick.value + 1
        }
    }
}

/**
 * 设置分组（P07）：阅读 / AI / 实例 / 存储 / 关于 + 危险区「重置」。
 *
 * T22 F12 结构收口（对照原型 07/设计规范 §3-4）：
 * - 根标题 30/38（headlineLarge），与其他根页同一层级；
 * - 分组平面行 + 前导图标 + 行间分隔线——入口不再是整块绿色卡片（颜色强调只留给主操作/选中态）；
 * - 主题/字号从根页移入独立的「阅读偏好」页（T22 F13：Reader 的 Aa 与这里共用同一页）。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenReadingPrefs: () -> Unit,
    onOpenFeedManager: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    /** L05：使用方式页（本机默认/实例可选/复制到本机） */
    onOpenUsage: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.bodyFontSize.collectAsStateWithLifecycle()
    val currentOrigin by viewModel.currentOrigin.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 根页安全区已由 RootScaffold 的 innerPadding 计算一次；这里再 safeDrawingPadding
            // 会重复扣除状态栏，标题被顶下约一层（T22 F11）。二级页（如 AboutScreen）仍各自保留。
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RootHeader(stringResource(R.string.settings_title))

        // UIR15（原型 07）：阅读与内容 / 连接与服务 / 本机数据 / 关于，四组平面行
        SettingsGroup(stringResource(R.string.settings_group_content)) {
            TextRow(
                icon = R.drawable.ic_rss,
                title = stringResource(R.string.feed_manager_entry),
                hint = stringResource(R.string.feed_manager_entry_hint),
                onClick = onOpenFeedManager,
            )
            TextRow(
                icon = R.drawable.ic_type,
                title = stringResource(R.string.settings_reading_prefs_entry),
                hint = stringResource(
                    R.string.settings_reading_prefs_hint,
                    stringResource(
                        when (themeMode) {
                            SettingsStore.ThemeMode.SYSTEM -> R.string.theme_system
                            SettingsStore.ThemeMode.LIGHT -> R.string.theme_light
                            SettingsStore.ThemeMode.DARK -> R.string.theme_dark
                        },
                    ),
                    fontSize,
                ),
                onClick = onOpenReadingPrefs,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_services)) {
            // UIR09：单一入口进使用方式页（29），副标题显示当前使用方式；连接/管理从 29 进入
            TextRow(
                icon = R.drawable.ic_link,
                title = stringResource(R.string.usage_title),
                hint = currentOrigin?.let { stringResource(R.string.settings_usage_instance, it) }
                    ?: stringResource(R.string.settings_usage_local),
                onClick = onOpenUsage,
            )
            TextRow(
                icon = R.drawable.ic_cpu,
                title = stringResource(R.string.ai_settings_entry),
                hint = stringResource(R.string.ai_settings_entry_hint_empty),
                onClick = onOpenAiSettings,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_device)) {
            TextRow(
                icon = R.drawable.ic_database,
                title = stringResource(R.string.storage_entry),
                hint = stringResource(R.string.storage_entry_hint_full),
                onClick = onOpenStorage,
            )
        }
        SettingsGroup(stringResource(R.string.settings_about_group)) {
            TextRow(
                icon = R.drawable.ic_refresh,
                title = stringResource(R.string.update_check),
                hint = stringResource(R.string.about_version, appVersion()),
                onClick = {
                    showUpdateDialog = true
                    viewModel.checkForUpdates()
                },
            )
            TextRow(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.about_entry),
                hint = stringResource(R.string.about_entry_hint),
                trailing = appVersion(),
                onClick = onOpenAbout,
            )
        }

        // —— 危险操作（P07：单独分组 + 二次确认 + 说明不删云端）
        TextButton(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.reset_entry), color = MaterialTheme.colorScheme.error)
        }
    }

    if (showUpdateDialog) {
        UpdateDialog(updateCheck, appVersion(), { showUpdateDialog = false }, viewModel::checkForUpdates)
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetDeviceData()
                    },
                ) {
                    Text(stringResource(R.string.reset_confirm_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 分组：小号灰色组名在上（原型 07），行本身平铺，不包卡片 */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

/** 真实安装版本（packageManager），不写死 */
@Composable
private fun appVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
}

/**
 * 平面入口行（T22 F12）：前导图标 + 标题/说明 + 尾部箭头 + 行分隔线，
 * 触摸区最少 72 dp（设计规范 §4「普通管理行」）；不再是整块填色卡片。
 */
@Composable
private fun TextRow(icon: Int, title: String, hint: String, onClick: () -> Unit, trailing: String? = null) {
    Column {
        Surface(
            onClick = onClick,
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) {
            Row(modifier = Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trailing != null) {
                    Spacer(Modifier.size(8.dp))
                    Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.size(8.dp))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 阅读偏好页（T22 F13；原型 16；UIR04）：主题三段、正文字号四段，改动即时生效并给样张预览，
 * 写盘成功才显示「已保存，立即生效」，失败恢复原设置并如实提示。
 * 设置根页与阅读器工具栏的「Aa」都进这一页（同一个 SettingsViewModel）。
 */
@Composable
fun ReadingPreferencesScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.bodyFontSize.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    BackHandlerLike(onBack)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.clearSaveStatus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.settings_reading_prefs_entry), style = MaterialTheme.typography.titleLarge)
        }

        SectionLabel(stringResource(R.string.settings_theme))
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.theme_system) to SettingsStore.ThemeMode.SYSTEM,
                stringResource(R.string.theme_light) to SettingsStore.ThemeMode.LIGHT,
                stringResource(R.string.theme_dark) to SettingsStore.ThemeMode.DARK,
            ),
            selected = themeMode,
            onSelect = viewModel::setThemeMode,
        )

        Spacer(Modifier.size(24.dp))
        SectionLabel(stringResource(R.string.settings_font_size))
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.font_small) to 16,
                stringResource(R.string.font_standard) to 18,
                stringResource(R.string.font_large) to 20,
                stringResource(R.string.font_xlarge) to 22,
            ),
            selected = fontSize,
            onSelect = viewModel::setBodyFontSize,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.reading_prefs_font_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(24.dp))
        SectionLabel(stringResource(R.string.reading_prefs_preview_label))
        // 即时样张：排版说明文字（不是示例文章），按当前正文字号与 30/18 行高比例渲染
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ControlShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.reading_prefs_preview_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = (fontSize + 2).sp,
                        lineHeight = ((fontSize + 2) * 28 / 20).sp,
                    ),
                )
                val body = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 30 / 18).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.reading_prefs_preview), style = body)
                Text(stringResource(R.string.reading_prefs_preview_2), style = body)
            }
        }

        Spacer(Modifier.size(16.dp))
        SaveStatusRow(saveStatus)
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 等分分段选择（原型 16）：选中 = 浅绿底 + 深绿字 + 对勾，单选语义。
 * 360 dp 或系统字体 >1.15 倍时四档放不下，改为两列网格（不缩字、不截断）。
 */
@Composable
private fun <T> SegmentedChoice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tight = options.size > 3 && (density.fontScale > 1.15f || maxWidth < 380.dp - 48.dp)
        val rows = if (tight) options.chunked(2) else listOf(options)
        Surface(
            shape = ControlShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.selectableGroup().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (label, value) ->
                            SegmentOption(label, value == selected, Modifier.weight(1f)) { onSelect(value) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
        ) {
            if (selected) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** 真实保存结果：成功「已保存，立即生效」；失败「保存失败，已恢复原设置」——不预先展示成功 */
@Composable
private fun SaveStatusRow(status: SaveStatus) {
    val (icon, text, color) = when (status) {
        SaveStatus.Idle -> return
        SaveStatus.Saved -> Triple(R.drawable.ic_circle_check, R.string.reading_prefs_saved, MaterialTheme.colorScheme.primary)
        SaveStatus.Failed -> Triple(R.drawable.ic_circle_alert, R.string.reading_prefs_save_failed, MaterialTheme.colorScheme.error)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * 关于页（P07；原型 18）：品牌 Logo + 名称 + 版本，然后分区说明（构建来源 / 权限 / 上游许可 / 依赖）。
 *
 * T22 F14：之前只有「猫页」文字与整段说明、没有品牌 Logo。这里复用已交付的 A 版
 * `ic_catleaf_mark`（森林绿），按规范分区组织，MIT 全文与版权逐字保留。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val version = appVersion()
    val context = androidx.compose.ui.platform.LocalContext.current
    val dependencyLicenses = remember(context) {
        context.assets.open("licenses/THIRD-PARTY.txt").bufferedReader().use { it.readText() }
    }
    // 信息详情（V04）：系统返回先关详情再出关于页。
    var openDoc by remember { mutableStateOf<AboutEntry?>(null) }
    openDoc?.let { entry ->
        AboutDocScreen(entry = entry, onBack = { openDoc = null })
        return
    }
    BackHandlerLike(onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleLarge)
        }

        // 品牌区：正式 Logo（歪头小叶猫 A 版）+ 英文名 + 中文名 + 版本（设计规范 §1）
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // A 版「歪头小叶猫」原生转换（几何保留 evenOdd 透明眼嘴与独立叶片），按主题着色
            Icon(
                painter = painterResource(R.drawable.ic_catleaf_mark),
                contentDescription = stringResource(R.string.brand_latin),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Text(stringResource(R.string.brand_latin), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // V04：入口行与分节详情承接，许可全文逐字保留。
        listOf(
            AboutEntry(
                R.drawable.ic_file_text,
                stringResource(R.string.about_head_source),
                stringResource(R.string.about_hint_source),
                listOf(
                    AboutSection(R.string.about_source_intro_head, stringResource(R.string.about_source_intro)),
                    AboutSection(R.string.about_source_build_head, stringResource(R.string.about_build_source)),
                ),
            ),
            AboutEntry(
                R.drawable.ic_code_xml,
                stringResource(R.string.about_license_head),
                stringResource(R.string.about_hint_license),
                listOf(AboutSection(R.string.about_license_full_head, stringResource(R.string.about_license_body), license = true)),
            ),
            AboutEntry(
                R.drawable.ic_lock,
                stringResource(R.string.about_head_privacy),
                stringResource(R.string.about_hint_privacy),
                listOf(
                    AboutSection(R.string.about_permissions_head, stringResource(R.string.about_permissions)),
                    AboutSection(R.string.about_privacy_data_head, stringResource(R.string.about_privacy_body)),
                ),
            ),
            AboutEntry(
                R.drawable.ic_circle_question_mark,
                stringResource(R.string.about_head_help),
                stringResource(R.string.about_hint_help),
                listOf(
                    AboutSection(R.string.about_help_local_head, stringResource(R.string.about_help_local)),
                    AboutSection(R.string.about_help_instance_head, stringResource(R.string.about_help_instance)),
                    AboutSection(R.string.about_help_feeds_head, stringResource(R.string.about_help_feeds)),
                    AboutSection(R.string.about_help_ai_head, stringResource(R.string.about_help_ai)),
                    AboutSection(R.string.about_help_offline_head, stringResource(R.string.about_help_offline)),
                ),
            ),
            AboutEntry(
                R.drawable.ic_folder,
                stringResource(R.string.about_head_dependencies),
                stringResource(R.string.about_hint_dependencies),
                listOf(
                    AboutSection(R.string.about_dependencies_overview_head, stringResource(R.string.about_dependencies)),
                    AboutSection(R.string.about_dependencies_full_head, dependencyLicenses, license = true),
                ),
            ),
        ).forEach { entry ->
            TextRow(
                icon = entry.icon,
                title = entry.title,
                hint = entry.hint,
                onClick = { openDoc = entry },
            )
        }

    }
}

private data class AboutEntry(val icon: Int, val title: String, val hint: String, val sections: List<AboutSection>)
private data class AboutSection(val heading: Int, val text: String, val license: Boolean = false)

/** 分节说明（原型18增量）：长文滚动时保留返回入口，许可原文可选择复制。 */
@Composable
private fun AboutDocScreen(entry: AboutEntry, onBack: () -> Unit) {
    BackHandlerLike(onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(entry.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = ControlShape) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(entry.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text(entry.hint, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            entry.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(section.heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (section.license) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ControlShape) {
                        SelectionContainer {
                            Text(section.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp), modifier = Modifier.fillMaxWidth().padding(16.dp))
                        }
                    } else SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            section.text.split("\n\n").forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}

/** 关于/设置都是二级页：系统返回与左上角一致（同 ReaderScreen 的做法） */
@Composable
private fun BackHandlerLike(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
