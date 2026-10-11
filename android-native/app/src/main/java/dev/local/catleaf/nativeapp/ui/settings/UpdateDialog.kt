package dev.local.catleaf.nativeapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R
import dev.local.catleaf.nativeapp.data.update.UpdateCheck
import dev.local.catleaf.nativeapp.ui.reader.ExternalBrowser

@Composable
internal fun UpdateDialog(state: UpdateCheck, currentVersion: String, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var browserError by remember(state) { mutableStateOf<String?>(null) }
    val release = (state as? UpdateCheck.Available)?.release
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (release == null) R.string.update_check else R.string.update_available)) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(when (state) {
                    UpdateCheck.Idle, UpdateCheck.Checking -> stringResource(R.string.update_checking)
                    UpdateCheck.NotConfigured -> stringResource(R.string.update_not_configured)
                    UpdateCheck.Latest -> stringResource(R.string.update_latest, currentVersion)
                    is UpdateCheck.Available -> stringResource(R.string.update_versions, currentVersion, state.release.version)
                    is UpdateCheck.Failed -> stringResource(R.string.update_failed, state.reason)
                })
                if (release != null) {
                    if (release.notes.isNotBlank()) Text(release.notes)
                    Text(stringResource(R.string.update_browser_hint), style = MaterialTheme.typography.bodySmall)
                }
                browserError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    release != null -> {
                        browserError = ExternalBrowser.open(context, release.downloadUrl)
                        if (browserError == null) onDismiss()
                    }
                    state is UpdateCheck.Failed -> onRetry()
                    else -> onDismiss()
                }
            }, enabled = state != UpdateCheck.Checking) {
                Text(stringResource(when {
                    release != null -> R.string.update_download
                    state is UpdateCheck.Failed -> R.string.feed_retry
                    else -> R.string.update_acknowledge
                }))
            }
        },
        dismissButton = {
            if (release != null || state is UpdateCheck.Failed || state == UpdateCheck.Checking) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
