package bes.max.bmaps.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.datastore.ThemePreference
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun PreferencesContent(onDismiss: () -> Unit, viewModel: PreferencesViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { event ->
                when (event) {
                    PreferencesEvent.Saved -> dismiss()
                }
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.widthIn(max = 420.dp)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Preferences", style = MaterialTheme.typography.headlineSmall)
            if (state.isLoading) {
                CircularProgressIndicator()
            } else if (state.error == PreferencesError.LOAD) {
                Text("Your preferences could not be loaded.")
                TextButton(onClick = viewModel::loadPreferences) { Text("Retry") }
            } else {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    ThemePreference.entries.forEach { theme ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = state.selectedTheme == theme,
                                enabled = !state.isSaving,
                                role = Role.RadioButton,
                                onClick = { viewModel.selectTheme(theme) },
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = state.selectedTheme == theme, onClick = null, enabled = !state.isSaving)
                            Text(when (theme) {
                                ThemePreference.SYSTEM -> "Use device setting"
                                ThemePreference.LIGHT -> "Light"
                                ThemePreference.DARK -> "Dark"
                            })
                        }
                    }
                }
                Text("Default coordinate system", style = MaterialTheme.typography.titleMedium)
                Text(if (state.defaultCoordinateSystem == "EPSG:4326") "WGS 84" else state.defaultCoordinateSystem)
                if (state.error == PreferencesError.SAVE) {
                    Text("Changes could not be saved. Please try again.", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isLoading && !state.isSaving && state.error != PreferencesError.LOAD,
                ) { Text(if (state.isSaving) "Saving…" else "Save") }
            }
        }
    }
}
