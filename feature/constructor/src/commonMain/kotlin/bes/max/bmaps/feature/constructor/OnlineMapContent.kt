package bes.max.bmaps.feature.constructor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.mapengine.RasterMap
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
internal fun OnlineMapContent(onCredentials: (String) -> Unit, modifier: Modifier = Modifier) {
    val model = metroViewModel<OnlineMapViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    fun openLink(url: String) {
        try { uri.openUri(url); linkError = false } catch (_: Exception) { linkError = true }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(state.selected?.label ?: "Choose map source") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                state.choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = { expanded = false; model.select(choice) })
                }
            }
        }
        state.selected?.let { choice ->
            Row {
                choice.style.endpoint.credential?.takeIf { choice.unavailableReason == null }?.let { credential ->
                    TextButton(onClick = { onCredentials(credential.key) }) { Text("API key") }
                }
                TextButton(onClick = { openLink(choice.provider.capabilitiesFor(choice.style).policyUrl) }) { Text("Source terms") }
            }
        }
        if (linkError) Text("The link could not be opened.", color = MaterialTheme.colorScheme.error)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (state.selected?.unavailableReason == null) TextButton(onClick = model::retry) { Text("Retry") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            state.session?.let { session ->
                key(session.generation) {
                    RasterMap(session.config, session.layers, Modifier.fillMaxSize().testTag("online-map")) {
                        model.onEvent(session.generation, it)
                    }
                }
            }
        }
        state.selected?.takeIf { state.session != null }?.let { choice ->
            choice.provider.attributionFor(choice.style).forEach { credit ->
                TextButton(onClick = { openLink(credit.url) }, contentPadding = PaddingValues(0.dp)) {
                    Text(credit.text, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ProviderCredentialsContent(identifier: String, onDismiss: () -> Unit) {
    val model = metroViewModel<ProviderCredentialsViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.events.collect { onDismiss() } }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Provider API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use a key from your provider account. After saving or removing it, retry the map.")
                OutlinedTextField(state.draft, model::edit, label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !state.saving)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { model.save(identifier, remove = true) }, enabled = !state.saving) { Text("Remove saved key") }
            }
        },
        confirmButton = { TextButton(onClick = { model.save(identifier) }, enabled = !state.saving) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Cancel") } },
    )
}
