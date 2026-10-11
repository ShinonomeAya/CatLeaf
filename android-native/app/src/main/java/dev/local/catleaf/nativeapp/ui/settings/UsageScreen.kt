package dev.local.catleaf.nativeapp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.settings.ScopeKind
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.interactiveOutline

/**
 * 使用方式（原型 29，UIR09）：当前使用（本机 / 自有实例两张单选卡）→ 连接实例的三项好处 →
 * 能力边界说明 → 本地帮助。实例范围多一个「复制已下载内容到本机」入口，进入独立预览步骤（原型 31，
 * [UsageCopyPreview]），取消回到本页、不写任何数据。系统返回：预览 → 本页 → 上一页。
 *
 * 本机范围点「自有实例」走连接流程（原型 30，成功才切换）；实例范围点它进实例管理（原型 17）。
 * 从实例切回本机需要确认，且不复制内容。
 */
@Composable
fun UsageScreen(
    viewModel: UsageViewModel,
    onBack: () -> Unit,
    onConnectInstance: () -> Unit,
    onManageInstance: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsState()
    var previewing by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf<HelpTopic?>(null) }
    var confirmSwitch by rememberSaveable { mutableStateOf(false) }

    // 进程恢复后仍停在预览：重新冻结一次快照（旋转时 ViewModel 保留，不重复加载）
    LaunchedEffect(previewing, state.scopeKind) {
        if (previewing && state.scopeKind == ScopeKind.INSTANCE && !state.previewReady && !state.loading) {
            viewModel.openPreview()
        }
    }

    BackHandler { if (previewing) { if (!state.copying) previewing = false } else onBack() }

    if (previewing && state.scopeKind == ScopeKind.INSTANCE) {
        UsageCopyPreview(
            state = state,
            onBack = { previewing = false },
            onToggle = viewModel::toggle,
            onToggleOrphaned = viewModel::toggleOrphaned,
            onConfirm = viewModel::startCopy,
            onSwitchToLocal = { confirmSwitch = true },
        )
    } else {
        UsageOverview(
            state = state,
            onBack = onBack,
            onSelectLocal = { if (state.scopeKind == ScopeKind.INSTANCE) confirmSwitch = true },
            onSelectInstance = { if (state.scopeKind == ScopeKind.LOCAL) onConnectInstance() else onManageInstance() },
            onOpenPreview = {
                previewing = true
                viewModel.openPreview()
            },
            onHelp = { help = it },
        )
    }

    help?.let { topic ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(stringResource(topic.title)) },
            text = { Text(stringResource(topic.body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { help = null }) { Text(stringResource(R.string.usage_copy_done)) } },
        )
    }

    if (confirmSwitch) {
        AlertDialog(
            onDismissRequest = { confirmSwitch = false },
            title = { Text(stringResource(R.string.usage_switch_confirm_title)) },
            text = { Text(stringResource(R.string.usage_switch_confirm_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitch = false
                    previewing = false
                    viewModel.switchToLocal(onBack)
                }) { Text(stringResource(R.string.usage_switch_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSwitch = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 本地帮助主题（文案依据 docs/14），不外链、不联网 */
enum class HelpTopic(val title: Int, val body: Int) {
    Connect(R.string.usage_help_connect, R.string.usage_help_connect_body),
    Backup(R.string.usage_help_backup, R.string.usage_help_backup_body),
}

@Composable
private fun UsageOverview(
    state: UsageUiState,
    onBack: () -> Unit,
    onSelectLocal: () -> Unit,
    onSelectInstance: () -> Unit,
    onOpenPreview: () -> Unit,
    onHelp: (HelpTopic) -> Unit,
) {
    val usingInstance = state.scopeKind == ScopeKind.INSTANCE
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        UsageTopBar(stringResource(R.string.usage_title), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.usage_current), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(12.dp))
            ModeCard(
                title = stringResource(R.string.usage_local_title),
                body = stringResource(R.string.usage_local_body),
                selected = !usingInstance,
                onClick = onSelectLocal,
            )
            Spacer(Modifier.size(12.dp))
            val instanceBody = state.instanceOrigin?.let { stringResource(R.string.usage_instance_active, it) }
                ?: stringResource(R.string.usage_instance_body)
            val instanceAction = if (usingInstance) {
                stringResource(R.string.usage_instance_manage_named, state.instanceOrigin.orEmpty())
            } else {
                stringResource(R.string.usage_instance_connect_named)
            }
            ModeCard(
                title = stringResource(R.string.usage_instance_title),
                body = instanceBody,
                selected = usingInstance,
                onClick = onSelectInstance,
                chevronDescription = instanceAction,
            )

            if (usingInstance) {
                Spacer(Modifier.size(12.dp))
                LinkRow(
                    iconRes = R.drawable.ic_database,
                    title = stringResource(R.string.usage_copy_entry),
                    body = stringResource(R.string.usage_copy_entry_body),
                    onClick = onOpenPreview,
                )
            }

            Spacer(Modifier.size(24.dp))
            Text(stringResource(R.string.usage_benefits_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            BenefitRow(R.drawable.ic_rss, R.string.usage_benefit_catalog, R.string.usage_benefit_catalog_body)
            BenefitRow(R.drawable.ic_file_text, R.string.usage_benefit_snapshot, R.string.usage_benefit_snapshot_body)
            BenefitRow(R.drawable.ic_refresh, R.string.usage_benefit_assist, R.string.usage_benefit_assist_body)

            Spacer(Modifier.size(12.dp))
            InfoNote(stringResource(R.string.usage_limit_note))

            Spacer(Modifier.size(16.dp))
            HorizontalDivider()
            LinkRow(R.drawable.ic_info, stringResource(R.string.usage_help_connect), null) { onHelp(HelpTopic.Connect) }
            HorizontalDivider()
            LinkRow(R.drawable.ic_database, stringResource(R.string.usage_help_backup), null) { onHelp(HelpTopic.Backup) }
            HorizontalDivider()
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun UsageTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

/** 单选卡：选中 = 浅绿底 + 选中圆点（不只靠颜色）；实例卡带 › 表示会进入下一页 */
@Composable
private fun ModeCard(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    chevronDescription: String? = null,
) {
    Surface(
        shape = ControlShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, interactiveOutline()),
        modifier = Modifier
            .fillMaxWidth()
            // 标题与说明照常朗读；实例卡的去向（连接 / 管理）作为点击动作标签
            .semantics { this.selected = selected }
            .clickable(onClickLabel = chevronDescription, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 语义由整卡 selectable 提供，圆点不单独可点
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (chevronDescription != null) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(iconRes: Int, titleRes: Int, bodyRes: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = ControlShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun InfoNote(text: String) {
    Surface(shape = ControlShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkRow(iconRes: Int, title: String, body: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
