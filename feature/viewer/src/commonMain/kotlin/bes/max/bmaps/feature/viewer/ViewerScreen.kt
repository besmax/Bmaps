package bes.max.bmaps.feature.viewer

import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ViewerScreen(onOpenLibrary: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize().padding(margin), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.map_viewer), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.a_place_for_your_next_adventure), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.your_offline_map_will_open_here_when_viewing_is_available))
            TextButton(onClick = onOpenLibrary) { Text(stringResource(Res.string.go_to_library)) }
        }
    }
}
