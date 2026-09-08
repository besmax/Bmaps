package bes.max.bmaps.feature.library

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
        Text("Your maps", style = MaterialTheme.typography.headlineLarge)
        Text("Keep your maps close, wherever you go.", style = MaterialTheme.typography.titleMedium)
        Text("Your saved offline maps will appear here.")
        Button(onClick = onBuildMap) { Text("Build a map") }
    }
}
