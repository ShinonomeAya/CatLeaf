package dev.local.catleaf.nativeapp.ui.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.LabeledField
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.SecondaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实例连接页，按进入意图区分（UIR10）：
 * - 首配修复（P01）：品牌区、说明、字段、主按钮与「还没有实例？」帮助，无返回；
 * - 连接自有实例（原型 30）：从本机使用进入，「连接并切换」+「取消」，成功才切换，取消不改使用方式；
 * - 实例管理（原型 17）：正在使用实例时，状态卡显示当前实例与上次连接，同址「更新密钥」、换址「连接并切换」。
 * 失败时错误紧贴表单显示且不清空输入；已存密钥从不回显。
 */
@Composable
fun InstanceSetupScreen(
    viewModel: InstanceSetupViewModel,
    /** 从使用方式进入时显示返回与取消；首配（P01）没有可回的地方 */
    showBack: Boolean = false,
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 从使用方式进入（showBack）时系统返回回到上一页且不改使用方式；首配保持平台默认。
    if (showBack) {
        androidx.activity.compose.BackHandler(onBack = onBack)
    }

    val title = stringResource(
        when (state.intent) {
            InstanceIntent.FirstRun -> R.string.instance_setup_title
            InstanceIntent.Connect -> R.string.instance_connect_title
            InstanceIntent.Manage -> R.string.instance_manage_title
        },
    )

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (showBack) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (showBack) 8.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!showBack) {
                BrandHeader()
                Text(title, style = MaterialTheme.typography.headlineLarge)
            }
            Text(
                stringResource(
                    when (state.intent) {
                        InstanceIntent.FirstRun -> R.string.instance_setup_subtitle
                        InstanceIntent.Connect -> R.string.instance_connect_subtitle
                        InstanceIntent.Manage -> R.string.instance_manage_subtitle
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.managing) ManageStatusCard(state)

            InstanceFields(state, viewModel)

            PrimaryAction(state, onClick = viewModel::connect)
            if (showBack) {
                SecondaryButton(stringResource(R.string.action_cancel), onClick = onBack, enabled = !state.connecting)
            }

            state.errorMessage?.let { message ->
                // 错误就地显示，输入保持不变（页面说明 P01）
                Text(message, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }

            if (!state.connecting && state.errorMessage == null) {
                Text(
                    stringResource(
                        if (state.intent == InstanceIntent.FirstRun) R.string.instance_after_connect_hint
                        else R.string.instance_connect_keep_hint,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.managing) {
                HorizontalDivider()
                Text(stringResource(R.string.instance_no_instance_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.instance_no_instance_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.instance_ai_later),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_catleaf_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(stringResource(R.string.brand_latin), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 管理模式状态卡（原型 17）：当前实例、上次连接成功时间、换密钥与换实例分别会发生什么 */
@Composable
private fun ManageStatusCard(state: InstanceSetupUiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.instance_current_origin_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.managingFrom ?: stringResource(R.string.instance_current_none), style = MaterialTheme.typography.labelLarge)
            state.lastConnectedAt?.takeIf { it > 0 }?.let { at ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_circle_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(R.string.instance_last_connected, formatConnectedAt(at)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(if (state.rotatingKey) R.string.instance_manage_rotate_hint else R.string.instance_manage_switch_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** UIR12：标签在上、框内示例占位、辅助说明在下，与订阅/AI 表单同一字段样式 */
@Composable
private fun InstanceFields(state: InstanceSetupUiState, viewModel: InstanceSetupViewModel) {
    LabeledField(
        label = stringResource(R.string.instance_address_label),
        value = state.origin,
        onValueChange = viewModel::onOriginChange,
        placeholder = stringResource(R.string.instance_address_hint),
        enabled = !state.connecting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    LabeledField(
        label = stringResource(R.string.instance_secret_label),
        value = state.secret,
        onValueChange = viewModel::onSecretChange,
        enabled = !state.connecting,
        visualTransformation = if (state.secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = viewModel::toggleSecretVisible) {
                Icon(
                    painter = painterResource(if (state.secretVisible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = stringResource(
                        if (state.secretVisible) R.string.instance_secret_hide else R.string.instance_secret_show,
                    ),
                )
            }
        },
        supporting = stringResource(R.string.instance_secret_helper),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    )
}

/** 主提交：首配「连接并保存」；连接/换址「连接并切换」；同址「更新密钥」。进行中显示进度 */
@Composable
private fun PrimaryAction(state: InstanceSetupUiState, onClick: () -> Unit) {
    if (state.connecting) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.instance_connecting), style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    val label = stringResource(
        when {
            state.intent == InstanceIntent.FirstRun -> R.string.instance_connect_action
            state.rotatingKey -> R.string.instance_rotate_action
            else -> R.string.instance_connect_switch_action
        },
    )
    PrimaryButton(label, onClick = onClick, enabled = state.canSubmit)
}

private fun formatConnectedAt(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
