package dev.local.catleaf.nativeapp.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.article.ArticleFilter

/**
 * 实际用到的共用控件（UIR19，设计规范 §4/§5）：只统一按钮圆角/高度、日期单选、独立开关行与中性字标，
 * 不改全局 shapes——Switch 滑块、导航选中形状保持 M3 默认。
 */
val ControlShape = RoundedCornerShape(12.dp)

/** 主操作：森林绿实底、白字、12 dp 圆角、最小高 48 dp */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** 次要操作：可见描边（#879189 / #819187）、绿色文字，不与主按钮争同等权重 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.dp, interactiveOutline()),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 规范「输入框可见边界 / 可交互轮廓」：浅色 #879189，深色 #819187（按 App 实际主题判定） */
@Composable
fun interactiveOutline() =
    if (MaterialTheme.colorScheme.background == BackgroundDark) InteractiveOutlineDark else InteractiveOutlineLight

/** 规范「警告」：浅色 #8B5A16，深色 #E3B66F（部分完成、图片未齐等非错误的未完成状态） */
@Composable
fun warningColor() =
    if (MaterialTheme.colorScheme.background == BackgroundDark) WarningDark else WarningLight

/**
 * 日期单选组（UIR05，原型 32/35）：顺序固定 全部 → 今天 → 指定日期 → 未知日期。
 * 选中 = 浅绿底 + 深绿字 + 对勾（不只靠颜色）；宽度不足时仅日期组横滑，不缩字号。
 */
@Composable
fun DateScopeChips(
    scope: ArticleFilter.DateScope,
    pickedDateLabel: String?,
    onSelect: (ArticleFilter.DateScope) -> Unit,
    onPickDate: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 24.dp,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.size(horizontalPadding - 8.dp))
        ScopeChip(stringResource(R.string.reading_scope_all), scope is ArticleFilter.DateScope.All) {
            onSelect(ArticleFilter.DateScope.All)
        }
        ScopeChip(stringResource(R.string.reading_scope_today), scope is ArticleFilter.DateScope.Today) {
            onSelect(ArticleFilter.DateScope.Today)
        }
        // 指定日期：选中后显示实际日期，再点重开选择器
        ScopeChip(
            pickedDateLabel ?: stringResource(R.string.reading_scope_pick_date),
            scope is ArticleFilter.DateScope.On,
            onClick = onPickDate,
        )
        ScopeChip(stringResource(R.string.reading_scope_unknown), scope is ArticleFilter.DateScope.Unknown) {
            onSelect(ArticleFilter.DateScope.Unknown)
        }
        Spacer(Modifier.size(horizontalPadding - 8.dp))
    }
}

/** 单选标签（日期组、搜索来源组共用）：选中带对勾；[trailingIcon] 用于会打开选择面板的标签 */
@Composable
fun ScopeChip(label: String, selected: Boolean, trailingIcon: Int? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        },
        leadingIcon = if (selected) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
        trailingIcon = trailingIcon?.let { icon ->
            { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
        },
        shape = ControlShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = interactiveOutline(),
            selectedBorderColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp),
    )
}

/**
 * 独立开关行（UIR05）：整行 48 dp 可点、Role.Switch 语义；与日期组分开，不排进互斥标签。
 * [trailing] 放同卡片里的次要动作（阅读页「清除筛选」）。
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            // 点击由整行 toggleable 处理，Switch 本身不重复接收
            Switch(checked = checked, onCheckedChange = null)
        }
        trailing?.invoke(this)
    }
}

/** 中性字标（原型 24/26）：系统文字 + 浅绿底，不伪造官方站标 */
@Composable
fun Monogram(name: String, size: Dp = 40.dp) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = ControlShape, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                monogramOf(name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
        }
    }
}

/** 中文取首字；拉丁名取首个单词的前两个字母（The Verge → V，IT之家 → IT） */
fun monogramOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "·"
    val first = trimmed.first()
    if (!first.isLetterOrDigit() || first.code > 0x7F) return first.toString()
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val word = if (words.size > 1 && words.first().equals("the", ignoreCase = true)) words[1] else words.first()
    val latin = word.takeWhile { it.code <= 0x7F && it.isLetterOrDigit() }
    return when {
        latin.length >= 2 && latin.take(2).all { it.isUpperCase() || it.isDigit() } -> latin.take(2)
        latin.isNotEmpty() -> latin.first().uppercaseChar().toString()
        else -> first.toString()
    }
}
