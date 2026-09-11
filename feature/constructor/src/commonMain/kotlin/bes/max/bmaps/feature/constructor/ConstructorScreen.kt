package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(margin), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.build_a_map), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.constructor_subtitle), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.choose_source_instructions))
            Box {
                OutlinedButton(onClick = { model.sourceMenu(true) }) { Text(state.selected?.let { stringResource(Res.string.map_source_label, it.provider.name, it.style.name) } ?: stringResource(Res.string.choose_map_source)) }
                DropdownMenu(state.sourceMenuExpanded, onDismissRequest = { model.sourceMenu(false) }) {
                    state.choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(stringResource(Res.string.map_source_label, choice.provider.name, choice.style.name)) }, onClick = { model.sourceMenu(false); model.select(choice) })
                    }
                }
            }
            state.selected?.let { choice ->
                TextButton(onClick = {
                    try { uri.openUri(choice.provider.capabilitiesFor(choice.style).policyUrl) }
                    catch (_: Exception) { model.linkFailed() }
                }) { Text(stringResource(Res.string.source_terms)) }
                choice.style.endpoint.credential?.let {
                    OutlinedButton(onClick = { onCredentials(it.key) }) { Text(stringResource(Res.string.manage_api_key)) }
                }
                choice.unavailableReason?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (choice.id.onlineMapAvailability() == OnlineMapAvailability.ACCOUNT_KEY_REQUIRED) {
                    Text(stringResource(Res.string.provider_account_instructions), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onOpenMap(choice.provider.id.value, choice.style.id.value) }, enabled = choice.unavailableReason == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Color.White)) { Text(stringResource(Res.string.open_map)) }
            }
            if (state.linkError) Text(stringResource(Res.string.link_unavailable), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onOpenLibrary) { Text(stringResource(Res.string.go_to_library)) }
        }
    }
}
