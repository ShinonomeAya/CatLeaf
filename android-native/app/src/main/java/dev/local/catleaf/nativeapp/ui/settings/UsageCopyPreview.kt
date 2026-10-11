package dev.local.catleaf.nativeapp.ui.settings

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.SecondaryButton

/**
 * 复制到本机（原型 31，UIR09）：独立预览步骤。表格为进入时冻结的真实计数（来源实例 / 本机），
 * 勾选订阅 → 可选带上孤立收藏 → 「确认复制」才写入；「取消」回使用方式页、不写任何数据。
 * 完成后给出结果与「完成」，是否切到本机由用户另行决定。
 */
@Composable
internal fun UsageCopyPreview(
    state: UsageUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleOrphaned: () -> Unit,
    onConfirm: () -> Unit,
    onSwitchToLocal: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        UsageTopBar(stringResource(R.string.usage_copy_title), onBack = { if (!state.copying) onBack() })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.usage_copy_headline), style = MaterialTheme.typography.headlineSmall)
            state.instanceOrigin?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                CountTable(state)
                FeedChoices(state, onToggle, onToggleOrphaned)
            }

            InfoNote(stringResource(R.string.usage_copy_hint))

            state.error?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }

            val result = state.result
            if (result != null) {
                Text(result, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                PrimaryButton(stringResource(R.string.usage_copy_done), onClick = onBack)
                SecondaryButton(stringResource(R.string.usage_switch_local), onClick = onSwitchToLocal)
            } else if (state.copying) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.usage_copying), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                PrimaryButton(
                    stringResource(R.string.usage_copy_action),
                    onClick = onConfirm,
                    enabled = state.hasSelection && !state.loading,
                )
                SecondaryButton(stringResource(R.string.action_cancel), onClick = onBack)
            }

            Text(
                stringResource(R.string.usage_copy_footer),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** 项目 × 来源/目标 计数表；每行整体朗读为「订阅，来源实例 12，本机 3」 */
@Composable
private fun CountTable(state: UsageUiState) {
    val source = stringResource(R.string.usage_copy_col_source)
    val target = stringResource(R.string.usage_copy_col_target)
    Surface(shape = ControlShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            TableRow(
                stringResource(R.string.usage_copy_col_item), source, target,
                header = true,
            )
            val rows = listOf(
                R.string.usage_copy_row_feeds to (state.sourceCounts.feeds to state.targetCounts.feeds),
                R.string.usage_copy_row_articles to (state.sourceCounts.articles to state.targetCounts.articles),
                R.string.usage_copy_row_read to (state.sourceCounts.read to state.targetCounts.read),
                R.string.usage_copy_row_starred to (state.sourceCounts.starred to state.targetCounts.starred),
            )
            rows.forEach { (label, counts) ->
                HorizontalDivider()
                val name = stringResource(label)
                TableRow(
                    name, counts.first.toString(), counts.second.toString(),
                    description = "$name，$source ${counts.first}，$target ${counts.second}",
                )
            }
        }
    }
}

@Composable
private fun TableRow(item: String, source: String, target: String, header: Boolean = false, description: String? = null) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .let { m -> description?.let { d -> m.semantics(mergeDescendants = true) { contentDescription = d } } ?: m },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item, style = style, color = color, modifier = Modifier.weight(1.4f))
        Text(source, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(target, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FeedChoices(state: UsageUiState, onToggle: (String) -> Unit, onToggleOrphaned: () -> Unit) {
    Column {
        Text(stringResource(R.string.usage_copy_select), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        if (state.sourceFeeds.isEmpty()) {
            Text(
                stringResource(R.string.usage_copy_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.sourceFeeds.forEach { feed ->
            CheckRow(
                text = feed.title.ifBlank { feed.url },
                checked = feed.feedId in state.selected,
                enabled = !state.copying,
                onToggle = { onToggle(feed.feedId) },
            )
        }
        if (state.orphanedStarred > 0) {
            CheckRow(
                text = stringResource(R.string.usage_copy_orphaned, state.orphanedStarred),
                checked = state.includeOrphaned,
                enabled = !state.copying,
                onToggle = onToggleOrphaned,
            )
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
