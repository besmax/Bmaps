package bes.max.bmaps.feature.library

import bmaps.feature.library.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(onBuildMap: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize().padding(margin), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.your_maps), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.keep_your_maps_close_wherever_you_go), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.your_saved_offline_maps_will_appear_here))
            Button(onClick = onBuildMap, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Color.White)) { Text(stringResource(Res.string.build_a_map)) }
        }
    }
}
