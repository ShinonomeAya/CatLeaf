package dev.local.catleaf.nativeapp.ui.digest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.ai.DigestSummarizer
import dev.local.catleaf.nativeapp.ui.reader.ExternalBrowser
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * P05 日摘要（原型 13 = 范围预览、原型 04 = 结果），整屏二级页、两步同页。
 *
 * 界面必须如实表达契约的三条硬规则：
 * - 范围只基于**已加载**内容，页面上写明“可能不包含该日全部文章”；
 * - 超限只提示、只让用户取消勾选，按钮禁用——绝不后台截断；
 * - “结果不完整”用错误色横幅与正常成功清楚区分（该内容未写成功缓存）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(
    feedId: String,
    seedDate: String?,
    viewModel: DigestViewModel,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    /** UIR17：未配置 AI 时从提示卡直达 AI 设置；返回后重新进入本页会重读配置 */
    onOpenAiSettings: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    // 来源跳转的兜底失败原因（与生成提示分开，只在结果页出现）
    var pageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedId, seedDate) { viewModel.open(feedId, seedDate) }
    BackHandler(onBack = onBack)

    fun handleSource(no: Int) {
        viewModel.resolveSource(no) { target ->
            when (target) {
                is SourceAction.OpenReader -> onOpenArticle(target.localId)
                is SourceAction.OpenExternal -> pageError = ExternalBrowser.open(context, target.url)
                is SourceAction.Unavailable -> pageError = target.reason
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(if (state.step == DigestStep.Scope) R.string.digest_scope_title else R.string.digest_page_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.step == DigestStep.Scope -> ScopePage(
                    state = state,
                    onPickDate = { showPicker = true },
                    onToggle = viewModel::toggleSelected,
                    onGenerate = { viewModel.generate(force = false) },
                    onOpenAiSettings = onOpenAiSettings,
                )
                else -> ResultPage(
                    state = state,
                    pageError = pageError,
                    onSourceTap = ::handleSource,
                    onViewScope = { pageError = null; viewModel.showScope() },
                    onRegenerate = { pageError = null; viewModel.generate(force = true) },
                )
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPicker = false
                        pickerState.selectedDateMillis?.let { millis ->
                            // M3 选择器返回 UTC 午夜的毫秒：按 UTC 取日期，不受设备偏移二次挪动
                            val local = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            viewModel.setDate(local)
                        }
                    },
                ) { Text(stringResource(R.string.digest_date_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.digest_date_cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun ScopePage(
    state: DigestUiState,
    onPickDate: () -> Unit,
    onToggle: (String) -> Unit,
    onGenerate: () -> Unit,
    onOpenAiSettings: (() -> Unit)?,
) {
    val scopeNote = stringResource(R.string.digest_partial_scope)
    Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        item(key = "head") {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_file_text), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        state.feedTitle.ifBlank { stringResource(R.string.reader_unknown_source) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.size(8.dp))
                // UIR17：日期是明确的选择控件——日历图标 + 日期 + 下拉箭头，整块 ≥56dp（设计规范 §4/§5）
                val pickLabel = stringResource(R.string.digest_choose_date)
                Surface(
                    onClick = onPickDate,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = dev.local.catleaf.nativeapp.ui.theme.ControlShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, dev.local.catleaf.nativeapp.ui.theme.interactiveOutline()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_calendar_days),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                state.localDate?.let { dateLabel(it) } ?: pickLabel,
                                style = MaterialTheme.typography.titleLarge,
                                color = if (state.localDate == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(R.string.digest_timezone, state.zoneLabel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_chevron_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(scopeNote, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.localDate == null) {
                    // 未选日期是正常的起始状态，不是错误：中性引导（生成按钮本身在无日期时不可用）
                    Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            painterResource(R.drawable.ic_info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.digest_need_date),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.localDate != null) {
            item(key = "loaded") {
                Text(
                    text = stringResource(
                        if (state.items.isEmpty()) R.string.digest_loaded_none else R.string.digest_loaded_count,
                        state.items.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(state.items, key = { it.localId }) { item ->
                CandidateRow(item, onToggle)
                Divider(modifier = Modifier.padding(start = 24.dp))
            }
            item(key = "selected") {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.digest_selected_count, state.selectedCount, state.selectedChars),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.digest_limits,
                            DigestSummarizer.MAX_ITEMS,
                            DigestSummarizer.MAX_CHARS,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.overItems || state.overChars) {
                        Text(
                            stringResource(R.string.digest_over_limit),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    ScopeFooter(state, onGenerate, onOpenAiSettings)
    }
}

@Composable
private fun CandidateRow(item: DigestItemView, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(item.localId) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = item.selected, onCheckedChange = { onToggle(item.localId) })
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(
                    if (item.hasBody) R.string.digest_item_body else R.string.digest_item_summary_only,
                    item.chars,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScopeFooter(state: DigestUiState, onGenerate: () -> Unit, onOpenAiSettings: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.newContentNotice) {
                Text(
                    stringResource(R.string.digest_new_content),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, dev.local.catleaf.nativeapp.ui.theme.ControlShape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // UIR17：未配置 = 信息语义（不是刷新/错误）；已配置 = 发往哪台 AI 的说明
                Icon(
                    painterResource(if (state.aiHost == null) R.drawable.ic_info else R.drawable.ic_cpu),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (state.aiHost == null) {
                        Text(stringResource(R.string.digest_ai_missing), style = MaterialTheme.typography.labelMedium)
                        if (onOpenAiSettings != null) {
                            TextButton(
                                onClick = onOpenAiSettings,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.digest_open_ai_settings))
                            }
                        }
                    } else {
                        Text(stringResource(R.string.digest_send_to, state.aiHost), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.digest_model, state.aiModel.orEmpty()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.digest_send_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onGenerate,
                enabled = state.canGenerate && state.aiHost != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.generating) R.string.digest_generating else R.string.digest_generate))
            }
        }
    }
}

@Composable
private fun ResultPage(
    state: DigestUiState,
    pageError: String?,
    onSourceTap: (Int) -> Unit,
    onViewScope: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val result = state.result
    if (result == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        item(key = "head") {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    state.feedTitle.ifBlank { stringResource(R.string.reader_unknown_source) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                state.localDate?.let {
                    Text(dateLabel(it), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    stringResource(R.string.digest_meta_line, result.sources.size, timeLabel(result.generatedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(if (result.fromCache) R.string.digest_label_cached else R.string.digest_label_fresh),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.digest_partial_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // 重新生成失败：旧结果继续显示（不被覆盖），但要如实说明这次没成功
        state.notice?.let { reason ->
            item(key = "regen-failed") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_circle_alert),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(reason, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 不完整横幅：与正常成功清楚区分（该内容未写成功缓存）
        result.incompleteReason?.let { reason ->
            item(key = "incomplete") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_circle_alert),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "$reason${stringResource(R.string.digest_incomplete_suffix)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        item(key = "title") {
            Text(
                result.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        item(key = "overview") {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.digest_overview), style = MaterialTheme.typography.titleMedium)
                Text(result.overview, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item(key = "points-head") {
            Text(
                stringResource(R.string.digest_points),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(result.points, key = { i, _ -> "p$i" }) { index, point ->
            PointRow(index = index, point = point, onSourceTap = onSourceTap)
            Divider(modifier = Modifier.padding(start = 24.dp))
        }

        item(key = "sources-head") {
            Text(
                stringResource(R.string.digest_sources),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        items(result.sources, key = { "s${it.no}" }) { source ->
            SourceRow(source) { onSourceTap(source.no) }
        }

        item(key = "notice") {
            Text(
                stringResource(R.string.digest_ai_notice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        pageError?.let {
            item(key = "page-error") {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        // 费用提示单独一行（原型 04）：按钮只写动作，不在按钮里折行
        Text(
            stringResource(R.string.digest_regenerate_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onViewScope, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.digest_view_scope))
            }
            Button(
                onClick = onRegenerate,
                enabled = !state.generating,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.generating) R.string.digest_generating else R.string.digest_regenerate))
            }
        }
        }
    }
    }
}

@Composable
private fun PointRow(index: Int, point: DigestPointView, onSourceTap: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            String.format(Locale.US, "%02d", index + 1),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(point.text, style = MaterialTheme.typography.bodyLarge)
            if (point.sourceIds.isEmpty()) {
                // 无效编号不成链接：只标待核对（整份结果也已标不完整）
                Text(
                    stringResource(R.string.digest_point_no_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    point.sourceIds.forEach { no ->
                        Text(
                            text = "[$no]",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onSourceTap(no) }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: DigestSourceView, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Text(
                source.no.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            source.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** 冻结表与编号验证在数据层（DigestSummarizer）；界面只渲染验证后的结果 */
private fun dateLabel(localDate: String): String {
    val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return localDate
    return "${date.monthValue}月${date.dayOfMonth}日"
}

private fun timeLabel(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
