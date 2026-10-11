package dev.local.catleaf.nativeapp.ui.feeds

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.warningColor

/**
 * 添加结果（原型 34，UIR18）：登记成功后的验证结论整屏显示。
 * 未验证通过用警告样式而非总成功；「重新验证」只对同一条再抓取，不新增源；
 * 「查看订阅」回管理列表，「继续阅读」回阅读页（页面说明 §P03-34）。
 */
@Composable
fun FeedAddResultSurface(
    result: FeedAddResult,
    onRetry: () -> Unit,
    onViewFeeds: () -> Unit,
    onContinueReading: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = !result.retrying, onBack = onBack)
    val ok = result.outcome == FeedAddOutcome.Verified
    val tint = if (ok) MaterialTheme.colorScheme.primary else warningColor()
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !result.retrying) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.feed_add_result_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(
                    when (result.outcome) {
                        FeedAddOutcome.Verified -> R.drawable.ic_circle_check
                        FeedAddOutcome.SavedOffline -> R.drawable.ic_wifi_off
                        FeedAddOutcome.VerifyFailed -> R.drawable.ic_circle_alert
                    },
                ),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(48.dp),
            )
            // 结论变化（重新验证后）要被读屏播报
            Text(
                stringResource(
                    when (result.outcome) {
                        FeedAddOutcome.Verified -> R.string.feed_add_result_verified
                        FeedAddOutcome.SavedOffline -> R.string.feed_add_result_offline
                        FeedAddOutcome.VerifyFailed -> R.string.feed_add_result_failed
                    },
                ),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            FeedSourceCard(title = result.title, url = result.url)

            Text(
                when (result.outcome) {
                    FeedAddOutcome.Verified -> stringResource(R.string.feed_add_result_verified_body)
                    FeedAddOutcome.SavedOffline -> stringResource(R.string.feed_add_result_offline_body)
                    FeedAddOutcome.VerifyFailed ->
                        stringResource(R.string.feed_add_result_failed_body, result.reason ?: "")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (ok) {
                Button(
                    onClick = onContinueReading,
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.feed_add_result_continue)) }
                OutlinedButton(
                    onClick = onViewFeeds,
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.feed_add_result_view_feeds)) }
            } else {
                Button(
                    onClick = onRetry,
                    enabled = !result.retrying,
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    // 与表单提交同一布局：进度圈固定在起点，文字居中
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (result.retrying) R.string.feed_add_result_retrying else R.string.feed_add_result_retry,
                            ),
                        )
                        if (result.retrying) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onViewFeeds,
                    enabled = !result.retrying,
                    shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.feed_add_result_view_feeds)) }
                TextButton(
                    onClick = onContinueReading,
                    enabled = !result.retrying,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.feed_add_result_continue), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun FeedSourceCard(title: String, url: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_file_text),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
