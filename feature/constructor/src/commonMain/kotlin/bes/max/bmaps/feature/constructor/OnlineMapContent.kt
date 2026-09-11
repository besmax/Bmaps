package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun ProviderCredentialsContent(identifier: String, onDismiss: () -> Unit) {
    val model = metroViewModel<ProviderCredentialsViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(identifier) { model.load(identifier) }
    val dismiss by rememberUpdatedState(onDismiss)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.events.collect { dismiss() } }
    }
    AlertDialog(
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(Res.string.provider_api_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.provider_key_instructions))
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.hasSavedCredential) Text(stringResource(Res.string.key_saved_securely), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.draft, model::edit, label = { Text(if (state.hasSavedCredential) stringResource(Res.string.replace_api_key) else stringResource(Res.string.api_key)) }, singleLine = true, shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(), enabled = !state.saving && !state.loading)
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { model.save(identifier, remove = true) }, enabled = !state.saving) { Text(stringResource(Res.string.remove_saved_key)) }
            }
        },
        confirmButton = { Button(onClick = { model.save(identifier) }, enabled = !state.saving,
            modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Color.White)) { Text(stringResource(Res.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(Res.string.cancel)) } },
    )
}
