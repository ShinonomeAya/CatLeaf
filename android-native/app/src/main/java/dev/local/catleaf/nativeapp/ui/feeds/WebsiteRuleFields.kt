package dev.local.catleaf.nativeapp.ui.feeds

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.data.article.WebsiteRules
import dev.local.catleaf.nativeapp.ui.theme.LabeledField

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebsiteRuleFields(
    form: FeedFormState,
    onEnabled: (Boolean) -> Unit,
    onRules: (WebsiteRules) -> Unit,
    onPreviewUrl: (String) -> Unit,
    onPreview: (Boolean) -> Unit,
) {
    val enabled = !form.submitting && !form.previewing
    if (form.sourceType == "rss") {
        TextButton(onClick = { onEnabled(!form.customRules) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (form.customRules) "关闭自定义全文规则" else "高级：自定义全文规则")
        }
    } else {
        Text("把公开网页列表读成猫页订阅。每次只读取此列表页；需要登录或仅由JavaScript生成的内容暂不支持。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RuleField("条目选择器（CSS，必填）", form.rules.items, "如 article.blog-post-preview", enabled) { onRules(form.rules.copy(items = it)) }
        RuleField("条目内标题（CSS，必填）", form.rules.title, "如 h2", enabled) { onRules(form.rules.copy(title = it)) }
        RuleField("条目内文章链接（CSS，必填）", form.rules.link, "如 h2 a", enabled) { onRules(form.rules.copy(link = it)) }
        RuleField("条目内日期（CSS，可选）", form.rules.date, "如 time；优先读取 datetime", enabled) { onRules(form.rules.copy(date = it)) }
        RuleField("条目内摘要（CSS，可选）", form.rules.summary, "如 .description", enabled) { onRules(form.rules.copy(summary = it)) }
    }
    if (form.sourceType == "html" || form.customRules) {
        Text("全文提取", style = MaterialTheme.typography.titleSmall)
        RuleField(if (form.sourceType == "html") "正文选择器（CSS，可选）" else "正文选择器（CSS，必填）", form.rules.body, "如 #main-content", enabled) { onRules(form.rules.copy(body = it)) }
        RuleField("排除元素（CSS，可选）", form.rules.exclude, "如 .ad, .comments", enabled) { onRules(form.rules.copy(exclude = it)) }
        Text("规则只选择内容，结果仍会安全净化。正文未命中时提示并尝试自动提取；不会自动重写旧文章或请求AI。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (form.sourceType == "html") {
            OutlinedButton(onClick = { onPreview(false) }, enabled = enabled && form.urlValid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (form.previewing) "正在预览…" else "预览列表")
            }
        }
        if (form.sourceType == "rss" || form.rules.body.isNotBlank()) {
            LabeledField(label = "示例文章地址（HTTPS）", value = form.previewUrl, onValueChange = onPreviewUrl, enabled = enabled, placeholder = "粘贴该源的一篇文章地址")
            OutlinedButton(onClick = { onPreview(true) }, enabled = enabled && form.previewUrl.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (form.previewing) "正在预览…" else "预览正文")
            }
        }
        form.previewMessage?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape) {
                Text(it, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (form.previewedKey != form.configurationKey) {
            Text("先完成${if (form.sourceType == "html") "列表" else "正文"}预览再保存；修改配置后需重新预览。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebsiteSourceChoice(form: FeedFormState, onType: (String) -> Unit) {
    Text("订阅类型", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(form.sourceType == "rss", { onType("rss") }, { Text("RSS / Atom") }, enabled = !form.submitting && !form.previewing)
        FilterChip(form.sourceType == "html", { onType("html") }, { Text("网页订阅") }, enabled = !form.submitting && !form.previewing)
    }
}

@Composable
private fun RuleField(label: String, value: String, placeholder: String, enabled: Boolean, onValue: (String) -> Unit) {
    LabeledField(label = label, value = value, onValueChange = onValue, placeholder = placeholder, enabled = enabled)
}
