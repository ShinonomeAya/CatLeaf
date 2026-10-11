package dev.local.catleaf.nativeapp.ui.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.catalog.Availability
import dev.local.catleaf.nativeapp.data.catalog.Catalog
import dev.local.catleaf.nativeapp.data.catalog.CatalogQuery
import dev.local.catleaf.nativeapp.data.catalog.availabilityCounts
import dev.local.catleaf.nativeapp.data.catalog.nonEmptyCategories
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.interactiveOutline

/**
 * 全部分类面板（原型 27，UIR07）：固定标题 + 关闭，二列等宽分类（当前项对勾 + 选中语义），
 * 下方独立的来源状态筛选与说明。短屏只滚内容，关闭始终可达；关闭/返回保留原筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(
    catalog: Catalog,
    query: CatalogQuery,
    onSelectCategory: (String) -> Unit,
    onToggleAvailability: (Availability) -> Unit,
    onDismiss: () -> Unit,
) {
    val categories = remember(catalog) { nonEmptyCategories(catalog) }
    val counts = remember(catalog, query.categoryId) { availabilityCounts(catalog, query.categoryId) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.discovery_all_categories),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.discovery_close))
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            ) {
                categories.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        pair.forEach { (id, name) ->
                            SheetCell(
                                label = name,
                                selected = query.categoryId == id,
                                modifier = Modifier.weight(1f),
                                role = Role.RadioButton,
                                onClick = { onSelectCategory(id) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    stringResource(R.string.discovery_category_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.discovery_availability), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Availability.entries.forEach { a ->
                        SheetCell(
                            label = stringResource(R.string.discovery_availability_chip, availabilityLabel(a), counts[a] ?: 0),
                            selected = query.availability == a,
                            modifier = Modifier.weight(1f),
                            role = Role.Checkbox,
                            onClick = { onToggleAvailability(a) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.discovery_availability_help),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 等宽选择单元：选中 = 浅绿底 + 对勾（不只靠颜色）；分类是单选，来源状态可再点取消 */
@Composable
private fun SheetCell(
    label: String,
    selected: Boolean,
    role: Role,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = if (role == Role.Checkbox) {
        Modifier.toggleable(value = selected, role = role, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = role, onClick = onClick)
    }
    Surface(
        shape = ControlShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, interactiveOutline()),
        modifier = modifier.heightIn(min = 48.dp).then(interaction),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
