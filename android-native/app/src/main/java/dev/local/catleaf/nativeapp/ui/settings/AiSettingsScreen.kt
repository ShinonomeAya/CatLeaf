package dev.local.catleaf.nativeapp.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

/**
 * P07 的 AI 设置（原型 12）。一套 Chat Completions 配置：服务地址、模型 ID、API Key。
 *
 * 只呈现首版验收过的东西：没有协议选择、没有模型列表、没有自动重试。
 * 未配置也能正常阅读——这一页只是「按需翻译与摘要」的前置。
 */
@Composable
fun AiSettingsScreen(viewModel: AiSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_settings_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.ai_settings_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // UIR12：标签在上、框内示例占位、辅助说明在下（原型 12）
        TextButton(onClick = viewModel::useDeepSeek, enabled = !state.testing && !state.saving) { Text("填入DeepSeek配置") }
        LabeledField(
            label = stringResource(R.string.ai_base_url_label),
            value = state.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            enabled = !state.testing && !state.saving,
            placeholder = stringResource(R.string.ai_base_url_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )

        LabeledField(
            label = stringResource(R.string.ai_model_label),
            value = state.model,
            onValueChange = viewModel::onModelChange,
            enabled = !state.testing && !state.saving,
            placeholder = stringResource(R.string.ai_model_placeholder),
            supporting = stringResource(R.string.ai_model_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        LabeledField(
            label = stringResource(R.string.ai_key_label),
            value = state.keyInput,
            onValueChange = viewModel::onKeyChange,
            enabled = !state.testing && !state.saving,
            placeholder = stringResource(
                if (state.keySaved) R.string.ai_key_saved_placeholder else R.string.ai_key_hint,
            ),
            visualTransformation = if (state.keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = viewModel::toggleKeyVisible) {
                    Icon(
                        painterResource(if (state.keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = stringResource(
                            if (state.keyVisible) R.string.instance_secret_hide else R.string.instance_secret_show,
                        ),
                    )
                }
            },
            supporting = state.destinationHost?.let { stringResource(R.string.ai_send_to, it) }
                ?: stringResource(R.string.ai_key_local_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )

        TestStatusRow(state)

        SecondaryButton(
            text = stringResource(if (state.testing) R.string.ai_testing else R.string.ai_test_action),
            onClick = viewModel::testConnection,
            enabled = !state.testing && !state.saving,
        )
        Text(
            stringResource(R.string.ai_test_cost_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrimaryButton(
            text = stringResource(R.string.ai_save_action),
            onClick = viewModel::save,
            enabled = !state.saving && !state.testing,
        )

        TextButton(
            onClick = viewModel::delete,
            enabled = !state.testing && !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(R.string.ai_delete_action),
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelLarge,
                color = if (message.startsWith("已")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 测试状态行（原型 12）：未测试 / 进行中 / 成功回显 / 失败原因。
 * UIR12：未测试用中性信息图标，只有真实请求进行中才显示旋转进度，不用加载图形冒充忙碌。
 */
@Composable
private fun TestStatusRow(state: AiSettingsUiState) {
    val test = state.test
    val (iconRes, tint, text) = when (test) {
        AiTestState.Untested -> Triple(
            R.drawable.ic_info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.ai_test_untested),
        )

        AiTestState.Running -> Triple(
            null,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.ai_testing),
        )

        is AiTestState.Ok -> Triple(
            R.drawable.ic_circle_check,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.ai_test_ok, test.reply.take(40)),
        )

        is AiTestState.Failed -> Triple(
            R.drawable.ic_circle_alert,
            MaterialTheme.colorScheme.error,
            stringResource(R.string.ai_test_failed, test.reason),
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
            }
            Spacer(Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal))
        }
    }
}
