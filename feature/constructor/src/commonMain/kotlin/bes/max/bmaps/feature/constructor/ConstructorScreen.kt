package bes.max.bmaps.feature.constructor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ConstructorScreen(onOpenLibrary: () -> Unit, onCredentials: (String) -> Unit = {}, showFixture: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Build a map", style = MaterialTheme.typography.headlineLarge)
        Text("Choose a place. Take it offline.", style = MaterialTheme.typography.titleMedium)
        if (showFixture) {
            Text("Sample map · offline preview", style = MaterialTheme.typography.labelMedium)
            FixtureMap(Modifier.weight(1f).fillMaxWidth().testTag("sample-map"))
        } else {
            Text("Online preview · downloads are not available yet", style = MaterialTheme.typography.labelMedium)
            OnlineMapContent(onCredentials, Modifier.weight(1f).fillMaxWidth())
        }
        TextButton(onClick = onOpenLibrary) { Text("Go to library") }
    }
}
