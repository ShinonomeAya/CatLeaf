package dev.local.catleaf.nativeapp.ui.onboarding

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.LocalFeedStore
import dev.local.catleaf.nativeapp.data.catalog.CatalogEntry
import dev.local.catleaf.nativeapp.data.catalog.CatalogRepository
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.CacheCleanup
import dev.local.catleaf.nativeapp.data.settings.ActivityScopeResolver
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.theme.Monogram
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.SecondaryButton
import dev.local.catleaf.nativeapp.ui.theme.interactiveOutline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首次引导（原型 23–24；C01/C04；UIR01/UIR02）：两步、可跳过，五源默认**未勾选**、未选时主按钮禁用。
 * 只对新用户出现——旧用户升级由 [ActivityScopeResolver] 补 onboardingDone（L01），不重走引导。
 *
 * 完成条件（页面说明 §10）：只有明确「跳过」，或本机已有订阅后点「完成并阅读」才落盘 onboardingDone；
 * 仅打开发现页、取消手动添加/OPML 不算完成，返回后仍停在引导。
 */
data class OnboardingUiState(
    val step: Int = 1,
    val selected: Set<String> = emptySet(),
    val adding: Boolean = false,
    val done: Boolean = false,
    /** 每源添加结果（key=catalog id）：null=进行中，true=已验证，false=已登记但暂未验证 */
    val results: Map<String, Boolean?> = emptyMap(),
    /** 当前本机范围已有订阅数（任一去路添加后 > 0，才出现「完成并阅读」） */
    val existingFeedCount: Int = 0,
) {
    val canConfirm: Boolean get() = selected.isNotEmpty() && !adding
    val canFinish: Boolean get() = existingFeedCount > 0 && !adding
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val db = CatLeafDatabase.get(application)
    private val scopeResolver = ActivityScopeResolver(settings, db)
    private val catalog = CatalogRepository(application).load()

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    val preferred get() = catalog.preferred.take(5)

    init {
        viewModelScope.launch {
            // 非本机范围（异常情形，如升级数据被手清）：不引导，直接放行
            if (scopeResolver.resolve().kind != ScopeKind.LOCAL) {
                settings.setOnboardingDone(true)
                _state.value = _state.value.copy(done = true)
            } else {
                refreshExisting()
            }
        }
    }

    /**
     * 从发现/手动添加/OPML 返回后重读本机订阅数；已有订阅就停在第 2 步，让「完成并阅读」可见。
     * 失败只保留原计数（引导仍可跳过），不吞掉取消。
     */
    fun refreshExisting() {
        viewModelScope.launch {
            val scope = scopeResolver.resolve()
            val count = runCatching { db.feeds().list(scope.key).size }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(_state.value.existingFeedCount)
            val s = _state.value
            _state.value = s.copy(existingFeedCount = count, step = if (count > 0) 2 else s.step)
        }
    }

    fun nextStep() {
        _state.value = _state.value.copy(step = 2)
    }

    fun backToWelcome() {
        if (_state.value.adding) return
        _state.value = _state.value.copy(step = 1)
    }

    fun toggle(id: String) {
        val s = _state.value
        // 已登记的源不再参与勾选，避免重复提交
        if (s.adding || s.results[id] != null) return
        val next = if (id in s.selected) s.selected - id else s.selected + id
        _state.value = s.copy(selected = next)
    }

    /** 跳过：不添加任何源，直接完成（始终有去路） */
    fun skip() {
        if (_state.value.adding) return
        markDone()
    }

    /** 本机已有订阅后「完成并阅读」 */
    fun finish() {
        if (!_state.value.canFinish) return
        markDone()
    }

    private fun markDone() {
        viewModelScope.launch {
            settings.setOnboardingDone(true)
            _state.value = _state.value.copy(done = true)
        }
    }

    /**
     * 确认：逐个登记所选源（本机 Room 先落盘，再公开抓取验证）；单源失败不影响其余，结果逐条显示。
     * 登记后不立即离开，由用户看过结果再点「完成并阅读」。
     */
    fun confirm() {
        val s = _state.value
        if (!s.canConfirm) return
        _state.value = s.copy(adding = true, results = s.results + s.selected.associateWith { null })
        viewModelScope.launch {
            val scope = scopeResolver.resolve()
            val store = LocalFeedStore(
                db = db,
                scopeKey = scope.key,
                cleanup = CacheCleanup(db, getApplication()),
            )
            for (id in s.selected) {
                val entry = catalog.entries.firstOrNull { it.id == id } ?: continue
                val ok = store.add(entry.feedUrl, "", entry.name).map { it.verified }.getOrDefault(false)
                _state.value = _state.value.copy(results = _state.value.results + (id to ok))
            }
            val count = runCatching { db.feeds().list(scope.key).size }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(_state.value.existingFeedCount)
            _state.value = _state.value.copy(adding = false, selected = emptySet(), existingFeedCount = count)
        }
    }
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = viewModel(),
    onDone: () -> Unit,
    onOpenDiscovery: () -> Unit = {},
    onManualAdd: () -> Unit = {},
    onImportOpml: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    // 子流程（发现/手动/OPML）返回时重新进入组合：重读订阅数
    LaunchedEffect(Unit) { viewModel.refreshExisting() }
    BackHandler(enabled = state.step == 2 && !state.adding, onBack = viewModel::backToWelcome)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        if (state.step == 1) {
            WelcomeStep(onStart = viewModel::nextStep, onImportOpml = onImportOpml, onSkip = viewModel::skip)
        } else {
            PickStep(
                state = state,
                entries = viewModel.preferred,
                onBack = viewModel::backToWelcome,
                onToggle = viewModel::toggle,
                onConfirm = viewModel::confirm,
                onSkip = viewModel::skip,
                onFinish = viewModel::finish,
                onOpenDiscovery = onOpenDiscovery,
                onManualAdd = onManualAdd,
            )
        }
        Spacer(Modifier.size(32.dp))
    }
}

/** 原型 23：品牌、三条价值、唯一主操作「开始阅读」，次要「导入 OPML」，明确「跳过」 */
@Composable
private fun WelcomeStep(onStart: () -> Unit, onImportOpml: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.size(40.dp))
        Icon(
            painterResource(R.drawable.ic_catleaf_mark),
            contentDescription = stringResource(R.string.brand_latin),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.brand_latin),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // 原型 23：拉丁名下方是中文品牌名「猫页」
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(24.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.size(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FeatureRow(R.drawable.ic_rss, R.string.onboarding_feature_subscribe, R.string.onboarding_feature_subscribe_hint)
        FeatureRow(R.drawable.ic_bookmark, R.string.onboarding_feature_offline, R.string.onboarding_feature_offline_hint)
        FeatureRow(R.drawable.ic_database, R.string.onboarding_feature_instance, R.string.onboarding_feature_instance_hint)
    }
    Spacer(Modifier.size(32.dp))
    PrimaryButton(stringResource(R.string.onboarding_start), onClick = onStart)
    Spacer(Modifier.size(12.dp))
    SecondaryButton(stringResource(R.string.onboarding_import_opml), onClick = onImportOpml)
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.onboarding_skip), style = MaterialTheme.typography.labelLarge)
    }
    Text(
        stringResource(R.string.onboarding_welcome_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FeatureRow(iconRes: Int, titleRes: Int, hintRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(hintRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 原型 24：五源（默认未勾选）→ 浏览更多 / 手动添加 → 订阅并开始（n）/ 暂时跳过；已有订阅后「完成并阅读」 */
@Composable
private fun PickStep(
    state: OnboardingUiState,
    entries: List<CatalogEntry>,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onOpenDiscovery: () -> Unit,
    onManualAdd: () -> Unit,
) {
    Spacer(Modifier.size(8.dp))
    IconButton(onClick = onBack, enabled = !state.adding, modifier = Modifier.padding(start = 0.dp)) {
        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
    }
    Spacer(Modifier.size(8.dp))
    Text(stringResource(R.string.onboarding_pick_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(4.dp))
    Text(
        stringResource(R.string.onboarding_pick_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(16.dp))
    entries.forEach { entry ->
        SourceRow(
            entry = entry,
            checked = entry.id in state.selected || state.results[entry.id] != null,
            result = state.results[entry.id],
            known = entry.id in state.results,
            adding = state.adding,
            onToggle = { onToggle(entry.id) },
        )
        HorizontalDivider()
    }
    LinkRow(R.drawable.ic_search, R.string.onboarding_browse_more, enabled = !state.adding, onClick = onOpenDiscovery)
    LinkRow(R.drawable.ic_plus, R.string.onboarding_manual_add, enabled = !state.adding, onClick = onManualAdd)
    Spacer(Modifier.size(16.dp))
    if (state.existingFeedCount > 0) {
        Text(
            stringResource(R.string.onboarding_added_count, state.existingFeedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
    }
    when {
        // 还有待提交的勾选：主操作是提交；已有订阅时「完成并阅读」作为次要去路
        state.selected.isNotEmpty() || state.existingFeedCount == 0 -> {
            PrimaryButton(
                stringResource(R.string.onboarding_confirm, state.selected.size),
                onClick = onConfirm,
                enabled = state.canConfirm,
            )
            if (state.canFinish) {
                TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onboarding_continue), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                TextButton(
                    onClick = onSkip,
                    enabled = !state.adding,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.onboarding_skip_late), style = MaterialTheme.typography.labelLarge) }
            }
        }
        else -> PrimaryButton(stringResource(R.string.onboarding_continue), onClick = onFinish, enabled = state.canFinish)
    }
}

/** 整行一个复选框（48 dp+ 目标）：名称即无障碍名称，勾选框在右，登记后显示逐源结果 */
@Composable
private fun SourceRow(
    entry: CatalogEntry,
    checked: Boolean,
    result: Boolean?,
    known: Boolean,
    adding: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = !adding && !known, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = entry.name }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(entry.name, size = 48.dp)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                entry.plainDescription.ifBlank { entry.siteUrl },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                // 窄屏/大字下截断要可见（原先直接切字，像是说明本身就写到一半）
                overflow = TextOverflow.Ellipsis,
            )
            if (known) {
                when (result) {
                    null -> Unit
                    true -> Text(
                        stringResource(R.string.onboarding_added_ok),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    false -> Text(
                        stringResource(R.string.onboarding_added_unverified),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (known && result == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                // 点击由整行处理；勾选框只显示状态，未选轮廓用可见边界色
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(uncheckedColor = interactiveOutline()),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(iconRes: Int, labelRes: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
