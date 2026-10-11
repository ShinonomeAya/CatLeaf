package dev.local.catleaf.nativeapp.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.local.catleaf.nativeapp.R

@Composable
fun rootSidePadding() = if (LocalConfiguration.current.screenWidthDp <= 360) 20.dp else 24.dp

/** RootScaffold owns safe insets; actions never change the text block's origin. */
@Composable
fun RootHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = rootSidePadding()).padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.brand_latin), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
        }
        actions()
    }
}
