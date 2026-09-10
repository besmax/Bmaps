package bes.max.bmaps.feature.constructor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.providers.OnlineMapAvailability
import bes.max.bmaps.domain.providers.onlineMapAvailability
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun ConstructorScreen(onOpenLibrary: () -> Unit, onCredentials: (String) -> Unit, onOpenMap: (String, String) -> Unit) {
    val model = metroViewModel<OnlineMapViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Build a map", style = MaterialTheme.typography.headlineLarge)
        Text("Choose a place. Take it offline.", style = MaterialTheme.typography.titleMedium)
        Text("Choose a source, then open the map to select your area.")
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(state.selected?.label ?: "Choose map source") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                state.choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = { expanded = false; model.select(choice) })
                }
            }
        }
        state.selected?.let { choice ->
            TextButton(onClick = {
                try { uri.openUri(choice.provider.capabilitiesFor(choice.style).policyUrl) }
                catch (_: Exception) { error = "The source terms could not be opened." }
            }) { Text("Source terms") }
            choice.style.endpoint.credential?.let {
                OutlinedButton(onClick = { onCredentials(it.key) }) { Text("Manage API key") }
            }
            choice.unavailableReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (choice.id.onlineMapAvailability() == OnlineMapAvailability.ACCOUNT_KEY_REQUIRED) {
                Text("Use an API key from an account with access to this source.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onOpenMap(choice.provider.id.value, choice.style.id.value) }, enabled = choice.unavailableReason == null) { Text("Open map") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onOpenLibrary) { Text("Go to library") }
    }
}
