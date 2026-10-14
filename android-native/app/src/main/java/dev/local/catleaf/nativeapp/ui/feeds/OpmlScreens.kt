package dev.local.catleaf.nativeapp.ui.feeds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.opml.Opml
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.ui.theme.warningColor

/**
 * P03 导入预览（原型 10）。
 * 有效且未重复的条目默认可勾选；已存在/无效的条目复选框禁用并显示原因
 * （原型要求：禁用项必须能读屏说明原因，不能照抄成可点）。
 */
@Composable
fun OpmlPreviewSurface(
    document: Opml.Document,
    selected: Set<Int>,
    importing: Boolean,
    progressDone: Int,
    progressTotal: Int,
    localOnly: Boolean,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    processed: Set<Int> = emptySet(),
) {
    val importable = document.entries.indices.count { document.entries[it].selectable && it !in processed }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.opml_preview_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleAll, enabled = !importing) { Text(stringResource(R.string.opml_select_all)) }
        }

        Text(
            stringResource(R.string.opml_preview_counts, document.entries.size, importable),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        // 目标范围（计划：预览需写明导入到哪里）；与订阅管理页同一 localOnly 判定
        Text(
            stringResource(if (localOnly) R.string.opml_target_local else R.string.opml_target_instance),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.opml_preview_merge_hint),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (importable > Opml.MAX_BATCH_ENTRIES) Text(
            "每批最多 ${Opml.MAX_BATCH_ENTRIES} 条，导入后可继续下一批。",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(document.entries) { index, entry ->
                // 整行一个复选框：标题/地址/跳过原因合并为它的无障碍名称（原先复选框单独成节点、没有名称，
                // 读屏只念「复选框」；VR6）。禁用行仍可聚焦读出原因
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = index in selected,
                            enabled = entry.selectable && index !in processed && !importing &&
                                (index in selected || selected.size < Opml.MAX_BATCH_ENTRIES),
                            role = Role.Checkbox,
                            onValueChange = { onToggle(index) },
                        )
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = index in selected,
                        onCheckedChange = null,
                        enabled = entry.selectable && index !in processed && !importing &&
                            (index in selected || selected.size < Opml.MAX_BATCH_ENTRIES),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            hostOf(entry.xmlUrl),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                index in processed -> "本次已处理"
                                entry.invalidReason != null -> "${entry.invalidReason}，将跳过"
                                entry.duplicateOf != null -> "${entry.duplicateOf}，将跳过"
                                entry.groupTooLong -> "分组名超过 ${Opml.MAX_GROUP_LENGTH} 字符，请调整后再导入"
                                entry.group.isNotBlank() -> entry.group
                                else -> "未分组"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Divider(modifier = Modifier.padding(start = 24.dp))
            }
        }

        Divider()
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "本批已选 ${selected.size} 个 · 剩余可选 ${importable - selected.size} 个",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onStart,
                enabled = selected.isNotEmpty() && !importing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.opml_importing, progressDone, progressTotal))
                } else {
                    Text(stringResource(R.string.opml_start_import, selected.size))
                }
            }
        }
    }
}

/**
 * P03 导入结果（原型 11-v2）。
 * 逐条显示真实结果；超时条目显示“结果待确认”并只对它重试（重试前先核对远端列表）。
 * 跳过的条目单独折叠查看，不掩盖已成功的部分。
 */
@Composable
fun OpmlResultSurface(
    report: OpmlImporter.Report,
    importing: Boolean,
    localOnly: Boolean,
    onRetry: (OpmlImporter.ItemResult?) -> Unit,
    onDismiss: () -> Unit,
    onNextBatch: () -> Unit = {},
) {
    var showSkipped by remember { mutableStateOf(false) }
    val skipped = report.items.filter { it.outcome is OpmlImporter.Outcome.SkippedInvalid }
    val shown = report.items.filterNot { it.outcome is OpmlImporter.Outcome.SkippedInvalid || it.outcome is OpmlImporter.Outcome.Deferred }
    val retryable = report.retryable
    // 已登记但 RSS 暂不可读：导入本身完成，但不能用全绿“导入完成”掩盖验证结果（契约 8.2）
    val unverified = report.items.count { (it.outcome as? OpmlImporter.Outcome.Added)?.verified == false }
    val incomplete = retryable > 0 || report.remaining > 0

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.opml_result_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (incomplete) R.drawable.ic_circle_alert else R.drawable.ic_circle_check,
                ),
                contentDescription = null,
                // 部分完成是未完成而非错误：规范「警告」色（原型 11-v2 琥珀）
                tint = if (incomplete) warningColor() else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Text(
                stringResource(
                    when {
                        report.failed + report.pending > 0 || report.remaining > 0 -> R.string.opml_result_partial
                        unverified > 0 -> R.string.opml_result_added_unverified
                        else -> R.string.opml_result_all_added
                    },
                ),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                "已保存 ${report.added} 个 · 待重试 $retryable 个 · 跳过 ${skipped.size} 个" +
                    if (report.remaining > 0) " · 待导入 ${report.remaining} 个" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(shown) { _, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pending = item.outcome is OpmlImporter.Outcome.PendingConfirm
                    val failed = item.outcome is OpmlImporter.Outcome.Failed
                    val unverified = (item.outcome as? OpmlImporter.Outcome.Added)?.verified == false
                    Icon(
                        painter = painterResource(
                            if (failed || pending || unverified) R.drawable.ic_circle_alert else R.drawable.ic_circle_check,
                        ),
                        contentDescription = null,
                        // 被拒绝才用错误色；待确认/已登记未验证是未完成状态，用警告色
                        tint = when {
                            failed -> MaterialTheme.colorScheme.error
                            pending || unverified -> warningColor()
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            outcomeText(item.outcome, item.group, localOnly),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pending || failed || unverified) {
                        TextButton(onClick = { onRetry(item) }, enabled = !importing) { Text(stringResource(R.string.opml_retry)) }
                    }
                }
                Divider(modifier = Modifier.padding(start = 24.dp))
            }

            if (skipped.isNotEmpty()) {
                item(key = "skipped-toggle") {
                    Surface(
                        onClick = { showSkipped = !showSkipped },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_file_text), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(
                                stringResource(R.string.opml_view_skipped, skipped.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (showSkipped) {
                    itemsIndexed(skipped) { _, item ->
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${(item.outcome as? OpmlImporter.Outcome.SkippedInvalid)?.reason ?: "已跳过"} · ${item.xmlUrl.ifBlank { "无地址" }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Divider()
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (report.remaining > 0) Button(
                onClick = onNextBatch, enabled = !importing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("继续下一批（${minOf(Opml.MAX_BATCH_ENTRIES, report.remaining)}）") }
            // 没有可重试项时不放禁用的「重试失败项（0）」：原型 11-v2 只在有失败时给重试；
            // 360dp/1.3 倍下这块固定区原先挤掉大半列表（VR6）
            if (retryable > 0) {
                Text(
                    stringResource(R.string.opml_keep_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onRetry(null) },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(stringResource(R.string.opml_importing, 0, retryable))
                    } else {
                        Text(stringResource(R.string.opml_retry_failed, retryable))
                    }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.opml_back_to_manager))
                }
            } else {
                // 每页一个主操作：无可重试项时返回即主操作
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.opml_back_to_manager))
                }
            }
        }
    }
}

@Composable
private fun outcomeText(outcome: OpmlImporter.Outcome, group: String, localOnly: Boolean): String = when (outcome) {
    // 契约 8.2：登记结果与验证结果分开写；有分组时附上（原型 11-v2「已添加 · 分组」）
    is OpmlImporter.Outcome.Added -> listOfNotNull(
        stringResource(R.string.opml_outcome_added),
        group.takeIf { it.isNotBlank() },
        when {
            outcome.verified -> stringResource(R.string.opml_outcome_verified)
            else -> "验证未通过：${outcome.verifyReason ?: "未知原因"}"
        },
    ).joinToString(" · ")
    is OpmlImporter.Outcome.AlreadyExists -> outcome.reason
    is OpmlImporter.Outcome.PendingConfirm -> stringResource(R.string.opml_outcome_pending)
    is OpmlImporter.Outcome.Cancelled -> stringResource(R.string.opml_outcome_cancelled)
    is OpmlImporter.Outcome.Deferred -> "等待后续批次"
    is OpmlImporter.Outcome.Failed -> outcome.reason
    is OpmlImporter.Outcome.SkippedInvalid -> outcome.reason
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull() ?: url
