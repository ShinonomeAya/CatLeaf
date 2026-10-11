package dev.local.catleaf.nativeapp.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 表单字段（UIR12，设计规范 §5「固定可见标签、示例占位、必要辅助说明」）：
 * 标签固定在输入框上方（13/20 加粗），框内只放示例占位；输入文字 16/24；辅助/错误在框下 13/20。
 * 只包一层 OutlinedTextField，不另建表单系统——校验、键盘、掩码仍由调用方决定。
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)) } },
            enabled = enabled,
            readOnly = readOnly,
            isError = isError,
            singleLine = true,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = interactiveOutline(),
                // 只读（编辑订阅的地址）用浅底表明不可改，文字仍保持可读对比
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            // 标签画在框外，输入框本身没有名称：读屏只会念「编辑框」，这里把可见标签交给它（VR6）
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = label },
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
