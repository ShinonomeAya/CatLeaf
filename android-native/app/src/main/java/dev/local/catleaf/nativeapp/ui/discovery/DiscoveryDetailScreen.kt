package dev.local.catleaf.nativeapp.ui.discovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.catalog.Catalog
import dev.local.catleaf.nativeapp.data.catalog.CatalogEntry
import dev.local.catleaf.nativeapp.ui.reader.ExternalBrowser
import dev.local.catleaf.nativeapp.ui.theme.ControlShape
import dev.local.catleaf.nativeapp.ui.theme.Monogram
import dev.local.catleaf.nativeapp.ui.theme.PrimaryButton
import dev.local.catleaf.nativeapp.ui.theme.SecondaryButton

/**
 * 目录源详情（原型 28，UIR08）：整屏替换发现列表，返回回到原搜索/滚位。
 * 先给用户理解的信息（身份、说明、地址、可用性），核查时间与收录来源放次层。
 * 「继续添加」进入同一添加表单（分组在表单中确认），不引入第二套订阅存储；已订阅不可重复添加。
 */
@Composable
internal fun DiscoveryDetailScreen(
    entry: CatalogEntry,
    categoryName: String,
    subscribed: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember(entry.id) { mutableStateOf(false) }
    var openError by remember(entry.id) { mutableStateOf<String?>(null) }
    val needsConfig = entry.status == Catalog.STATUS_NEEDS_CONFIG

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.discovery_detail_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Monogram(entry.name, size = 56.dp)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOf(categoryName, languageLabel(entry.language)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.plainDescription.isNotBlank()) {
                Text(entry.plainDescription, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.size(8.dp))
            Surface(shape = ControlShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    InfoRow(R.drawable.ic_rss, stringResource(R.string.discovery_detail_feed), entry.feedUrl) {
                        val label = stringResource(if (copied) R.string.discovery_copied else R.string.discovery_copy)
                        val description = stringResource(R.string.discovery_copy_named, stringResource(R.string.discovery_detail_feed))
                        TextButton(
                            onClick = { copied = copyToClipboard(context, entry.feedUrl) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                        ) { Text(label) }
                    }
                    if (entry.siteUrl.isNotBlank()) {
                        HorizontalDivider(Modifier.padding(start = 52.dp))
                        InfoRow(R.drawable.ic_external_link, stringResource(R.string.discovery_detail_site), entry.siteUrl)
                    }
                    HorizontalDivider(Modifier.padding(start = 52.dp))
                    InfoRow(
                        iconRes = if (needsConfig) R.drawable.ic_info else R.drawable.ic_circle_check,
                        title = stringResource(R.string.discovery_detail_availability),
                        value = availabilityLabel(entry) + " · " + stringResource(
                            if (entry.sourceType == "third_party") R.string.discovery_type_third_party else R.string.discovery_type_origin,
                        ),
                    )
                }
            }
            Text(
                stringResource(if (needsConfig) R.string.discovery_needs_config_hint else R.string.discovery_ready_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            entry.note?.let { note ->
                Text(
                    stringResource(R.string.discovery_detail_note, note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (needsConfig) {
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.discovery_config_title), style = MaterialTheme.typography.titleMedium)
                val host = runCatching { java.net.URI(entry.feedUrl).host.orEmpty().lowercase() }.getOrDefault("")
                val rsshub = host == "rsshub.app" || host.endsWith(".rsshub.app") || host.startsWith("rsshub.")
                val hosted = host == "plink.anyfeeder.com" || host == "feeds.feedburner.com" || host == "feeds2.feedburner.com"
                Text(stringResource(if (rsshub) R.string.discovery_config_rsshub else if (hosted) R.string.discovery_config_hosted else R.string.discovery_config_unknown),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.discovery_config_steps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.discovery_config_login), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.discovery_config_security), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (rsshub) TextButton(onClick = { openError = ExternalBrowser.open(context, "https://docs.rsshub.app/guide/") }) {
                    Text(stringResource(R.string.discovery_config_docs))
                }
            }
            if (entry.checkedAt.isNotBlank()) {
                Text(
                    checkedLine(entry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!subscribed) {
                Spacer(Modifier.size(20.dp))
                Text(stringResource(R.string.discovery_detail_group_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.discovery_detail_group_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            openError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (subscribed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.discovery_already_subscribed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                PrimaryButton(stringResource(R.string.discovery_add_action), onClick = onAdd)
            }
            if (entry.siteUrl.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                SecondaryButton(
                    stringResource(R.string.discovery_view_site),
                    onClick = { openError = ExternalBrowser.open(context, entry.siteUrl) },
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    iconRes: Int,
    title: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            // 长 URL 自然换行，不撑破右侧按钮
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun checkedLine(entry: CatalogEntry): String {
    val from = when {
        entry.provenance == "preferred-five" -> stringResource(R.string.discovery_provenance_preferred)
        entry.provenance.startsWith("top-rss-list") -> stringResource(R.string.discovery_provenance_list)
        else -> null
    }
    return if (from == null) {
        stringResource(R.string.discovery_detail_checked, entry.checkedAt)
    } else {
        stringResource(R.string.discovery_detail_checked_from, entry.checkedAt, from)
    }
}

/** 系统剪贴板（平台能力）；只复制公开的订阅地址，不涉及凭据 */
private fun copyToClipboard(context: Context, text: String): Boolean {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
    manager.setPrimaryClip(ClipData.newPlainText("RSS", text))
    return true
}
