package bes.max.bmaps.feature.library

import bmaps.feature.library.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(onBuildMap: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.your_maps), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.keep_your_maps_close_wherever_you_go), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.your_saved_offline_maps_will_appear_here))
        Button(onClick = onBuildMap) { Text(stringResource(Res.string.build_a_map)) }
    }
}
