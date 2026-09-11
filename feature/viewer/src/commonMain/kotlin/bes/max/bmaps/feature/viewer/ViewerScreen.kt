package bes.max.bmaps.feature.viewer

import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ViewerScreen(onOpenLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.map_viewer), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.a_place_for_your_next_adventure), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.your_offline_map_will_open_here_when_viewing_is_available))
        TextButton(onClick = onOpenLibrary) { Text(stringResource(Res.string.go_to_library)) }
    }
}
