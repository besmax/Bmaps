package bes.max.bmaps.feature.viewer

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
        Text("Map viewer", style = MaterialTheme.typography.headlineLarge)
        Text("A place for your next adventure.", style = MaterialTheme.typography.titleMedium)
        Text("Your offline map will open here when viewing is available.")
        TextButton(onClick = onOpenLibrary) { Text("Go to library") }
    }
}
