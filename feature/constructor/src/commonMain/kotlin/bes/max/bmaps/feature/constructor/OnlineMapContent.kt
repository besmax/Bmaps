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
fun ProviderCredentialsContent(identifier: String, onDismiss: () -> Unit) {
    val model = metroViewModel<ProviderCredentialsViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(identifier) { model.load(identifier) }
    LaunchedEffect(model) { model.events.collect { onDismiss() } }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text("Provider API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use a key from your provider account. After saving or removing it, retry the map.")
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.hasSavedCredential) Text("A key is saved securely.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.draft, model::edit, label = { Text(if (state.hasSavedCredential) "Replace API key" else "API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !state.saving && !state.loading)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { model.save(identifier, remove = true) }, enabled = !state.saving) { Text("Remove saved key") }
            }
        },
        confirmButton = { TextButton(onClick = { model.save(identifier) }, enabled = !state.saving) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Cancel") } },
    )
}
