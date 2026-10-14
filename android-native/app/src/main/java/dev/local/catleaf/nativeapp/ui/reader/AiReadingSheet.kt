package dev.local.catleaf.nativeapp.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.ai.ArticleAssistant
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.ui.theme.ControlShape

internal const val AI_READING_TITLE = "AI解读"

/** 原型02局部增量：任务和关闭固定，内容独立滚动，模型文本只用Compose Text。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiReadingSheet(
    reader: ReaderUiState,
    onMode: (ArticleAssistant.Mode) -> Unit,
    onQuestion: (String) -> Unit,
    onParagraph: (String) -> Unit,
    onGenerate: (Boolean) -> Unit,
    onSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ai = reader.aiReading
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = remember(windowSize.width) { windowSize.height }
    val article = remember(reader.bodyHtml, reader.title) { BilingualArticle.fromHtml(reader.bodyHtml.orEmpty(), reader.title) }
    var choosing by remember(reader.bodyHash) { mutableStateOf(ai.paragraphId == null) }
    val selected = article.blocks.firstOrNull { it.id == ai.paragraphId }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = reader.bodyFontSizeSp.sp, lineHeight = (reader.bodyFontSizeSp * 1.67).sp)
    val listState = rememberLazyListState()
    LaunchedEffect(ai.mode) { listState.scrollToItem(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        val density = LocalDensity.current
        val imeHeight = WindowInsets.ime.getBottom(density)
        val availableHeight = (windowHeight - imeHeight).coerceAtLeast(0).toFloat()
        val desiredHeight = availableHeight * if (ai.answer == null) .52f else .78f
        val sheetHeight = with(density) {
            (if (imeHeight > 0) desiredHeight.coerceAtLeast(360.dp.toPx()).coerceAtMost(availableHeight) else desiredHeight).toDp()
        }
        Column(Modifier.fillMaxWidth().height(sheetHeight).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AI_READING_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_x), contentDescription = "关闭$AI_READING_TITLE") }
            }
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArticleAssistant.Mode.entries.forEach { mode -> FilterChip(
                    selected = ai.mode == mode, onClick = { onMode(mode) }, enabled = !ai.busy,
                    label = { Text(taskLabel(mode)) },
                ) }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val kind = when (reader.source) {
                        ContentSource.Rss -> "RSS正文"
                        ContentSource.Extracted -> "提取全文"
                        ContentSource.Offline -> "离线副本"
                        ContentSource.Summary -> "仅摘要，尚未获取全文"
                        ContentSource.None -> "没有可分析的正文"
                    }
                    Text("当前文章 · $kind", style = MaterialTheme.typography.titleSmall)
                    Text("文章 ${article.blocks.sumOf { it.text.length }} 字符 · 模型 ${ai.model.ifBlank { "尚未配置" }}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("接收方 ${EndpointPolicy.originOf(ai.baseUrl)?.removePrefix("https://") ?: "尚未配置"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("新请求会发送标题和文字段落，可能产生费用；缓存无需重复请求。",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ai.notice?.let { notice -> item { Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
                if (ai.selection.isNotBlank()) item {
                    Text("已选原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(ai.selection, style = MaterialTheme.typography.bodyMedium)
                }
                if (ai.mode == ArticleAssistant.Mode.Question) item {
                    OutlinedTextField(value = ai.question, onValueChange = onQuestion, enabled = !ai.busy,
                        label = { Text("你想了解什么？") }, supportingText = { Text("${ai.question.length}/${ArticleAssistant.MAX_QUESTION_CHARS} · 只依据当前文章") },
                        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                }
                if (ai.mode == ArticleAssistant.Mode.Explain) {
                    item {
                        Text("选择原文段落", style = MaterialTheme.typography.titleMedium)
                        if (selected != null && !choosing) {
                            if (ai.selection.isBlank()) Text(selected.text, style = bodyStyle)
                            TextButton(onClick = { choosing = true }, enabled = !ai.busy) { Text("换一段") }
                        }
                    }
                    if (selected == null || choosing) items(article.blocks, key = { it.id }) { block ->
                        OutlinedButton(onClick = { onParagraph(block.id); choosing = false }, enabled = !ai.busy,
                            shape = ControlShape,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(sourceLabel(block.id), style = MaterialTheme.typography.labelMedium)
                                Text(block.text.take(160), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                ai.answer?.let { answer ->
                    item {
                        HorizontalDivider()
                        Text(if (ai.cached) "$AI_READING_TITLE · 已缓存" else "$AI_READING_TITLE · 刚刚生成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (answer.notInArticle) Text("本文未提供", style = MaterialTheme.typography.titleMedium)
                        Text(answer.text, style = bodyStyle)
                        Citations(answer.sourceIds, onSource)
                    }
                    items(answer.points) { point ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(point.text, style = bodyStyle)
                            Citations(point.sourceIds, onSource)
                            HorizontalDivider()
                        }
                    }
                    if (answer.background.isNotBlank()) item {
                        Text("背景解释（不是本文事实）", style = MaterialTheme.typography.titleMedium)
                        Text(answer.background, style = bodyStyle)
                    }
                    item { Text("AI可能有误，展开依据可回到当前原文核对。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            val enabled = reader.hasText && !ai.busy && when (ai.mode) {
                ArticleAssistant.Mode.Overview -> true
                ArticleAssistant.Mode.Question -> ai.question.isNotBlank()
                ArticleAssistant.Mode.Explain -> selected != null && !choosing
            }
            Button(onClick = { onGenerate(ai.answer != null) }, enabled = enabled,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                if (ai.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("正在处理")
                } else Text(if (ai.answer != null) "重新生成（再次请求）" else when (ai.mode) {
                    ArticleAssistant.Mode.Overview -> "生成概览"
                    ArticleAssistant.Mode.Explain -> "解释这一段"
                    ArticleAssistant.Mode.Question -> "提问"
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Citations(ids: List<String>, onSource: (String) -> Unit) {
    if (ids.isEmpty()) return
    var expanded by remember(ids) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("${if (expanded) "收起" else "查看"}依据（${ids.size}处）", style = MaterialTheme.typography.labelMedium)
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(if (expanded) 180f else 0f))
        }
        if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ids.forEach { id ->
                TextButton(onClick = { onSource(id) }, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = "查看${sourceLabel(id)}" }) {
                    Text(if (id == BilingualArticle.TITLE_ID) "标题" else "第${id.removePrefix("catleaf-b")}段",
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun sourceLabel(id: String) = if (id == BilingualArticle.TITLE_ID) "原文标题" else "原文段落${id.removePrefix("catleaf-b") }"
private fun taskLabel(mode: ArticleAssistant.Mode) = when (mode) {
    ArticleAssistant.Mode.Overview -> "概览"
    ArticleAssistant.Mode.Explain -> "解释段落"
    ArticleAssistant.Mode.Question -> "问本文"
}
