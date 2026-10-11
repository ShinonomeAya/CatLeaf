package dev.local.catleaf.nativeapp.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.warningColor
import dev.local.catleaf.nativeapp.ui.theme.RootHeader
import dev.local.catleaf.nativeapp.ui.theme.rootSidePadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P06 收藏（原型 06）。按收藏时间倒序，每行标清「文本已保存」或「仅书签」，
 * 来源已删除的行照常可打开，并明确标出（契约 4.3：删源不移除收藏）。
 */
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onOpenArticle: (String) -> Unit,
    onOpenSearch: () -> Unit = {},
    /** 原型 06：空态「去阅读」切回阅读入口 */
    onGoReading: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次进入收藏页都重读本地：详情里取消收藏后回来要立刻反映
    LaunchedEffect(Unit) { viewModel.load() }

    // 收藏列表位置：锚点是「文章 id + 偏移」，收藏顺序变化后也不会认错行
    val listState = rememberLazyListState()
    val rows = state.rows
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(rows.map { it.localId }.take(1), restored) {
        if (restored || rows.isEmpty()) return@LaunchedEffect
        viewModel.savedScrollAnchor()?.let { anchor ->
            val index = ScrollAnchor.indexOfArticle(rows.map { it.localId }, anchor.localId)
            // 符号约定与阅读列表一致：保存的是负值，scrollToItem 要正值
            if (index != null) listState.scrollToItem(index, -anchor.offset)
        }
        restored = true
    }

    LaunchedEffect(listState, restored) {
        // 与阅读列表同理：先恢复，再开始记录，否则进入页面时的“顶部”会覆盖锚点
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) return@collect
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@collect
                val key = first.key as? String ?: return@collect
                // 页脚说明不是收藏行：它没有锚点意义，跳过
                if (key == "hint") return@collect
                viewModel.rememberScrollAnchor(key, first.offset)
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RootHeader(stringResource(R.string.tab_favorites)) {
            IconButton(onClick = onOpenSearch) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.search_hint_query))
            }
        }
        if (state.rows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.favorites_summary, state.rows.size, state.savedTextCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = rootSidePadding()),
            )
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.notConnected -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.favorites_not_connected),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error != null -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(24.dp))
                PrimaryButton(stringResource(R.string.opml_retry), onClick = viewModel::load)
            }

            state.rows.isEmpty() -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painterResource(if (MaterialTheme.colorScheme.background == dev.local.catleaf.nativeapp.ui.theme.BackgroundDark) R.drawable.illus_empty_reading_dark else R.drawable.illus_empty_reading_light),
                    contentDescription = null,
                    modifier = Modifier.size(144.dp),
                )
                Spacer(Modifier.size(24.dp))
                Text(stringResource(R.string.favorites_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.favorites_empty),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(24.dp))
                PrimaryButton(stringResource(R.string.favorites_go_reading), onClick = onGoReading)
            }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.localId }) { row ->
                    FavoriteItem(row, onClick = { onOpenArticle(row.localId) })
                    Divider(modifier = Modifier.padding(start = 24.dp))
                }
                item(key = "hint") {
                    Text(
                        text = stringResource(R.string.favorites_keep_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteItem(row: FavoriteRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (row.sourceDeleted) {
                    stringResource(R.string.favorites_source_deleted)
                } else {
                    row.feedTitle
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                // 图标 + 文字双重表达，不只靠颜色（设计规范）
                Icon(
                    painterResource(
                        if (row.hasText) R.drawable.ic_circle_check else R.drawable.ic_circle_alert,
                    ),
                    contentDescription = null,
                    // 仅书签不是错误：用警告色（设计规范「网络不可用」同族，原型 06）
                    tint = if (row.hasText) MaterialTheme.colorScheme.primary else warningColor(),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(
                        if (row.hasText) R.string.favorites_saved else R.string.favorites_bookmark_only,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.hasText) MaterialTheme.colorScheme.onSurfaceVariant else warningColor(),
                )
            }
            Text(
                text = formatStarredAt(row.starredAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 行尾已收藏标记（原型 06）；整行已可点，标记只作装饰，不重复播报
        Icon(
            painterResource(R.drawable.ic_bookmark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp).size(20.dp),
        )
    }
}

private fun formatStarredAt(starredAt: Long?): String {
    if (starredAt == null) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(starredAt))
}
