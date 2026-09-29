/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.shell

import bmaps.feature.shell.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
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
import bes.max.bmaps.core.datastore.CoordinateFormat
import bes.max.bmaps.core.datastore.DisplayCoordinateSystem
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
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 360.dp)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.preferences), style = MaterialTheme.typography.headlineSmall)
            if (state.isLoading) {
                CircularProgressIndicator()
            } else if (state.error == PreferencesError.LOAD) {
                Text(stringResource(Res.string.your_preferences_could_not_be_loaded))
                TextButton(onClick = viewModel::loadPreferences) { Text(stringResource(Res.string.retry)) }
            } else {
                Text(stringResource(Res.string.appearance), style = MaterialTheme.typography.titleMedium)
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
                                ThemePreference.SYSTEM -> stringResource(Res.string.use_device_setting)
                                ThemePreference.LIGHT -> stringResource(Res.string.light)
                                ThemePreference.DARK -> stringResource(Res.string.dark)
                            })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = state.clusterMapObjects, enabled = !state.isSaving,
                        role = Role.Switch, onValueChange = viewModel::clusterMapObjects,
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.cluster_map_objects))
                        Text(stringResource(Res.string.cluster_map_objects_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.clusterMapObjects, onCheckedChange = null, enabled = !state.isSaving)
                }
                Text(stringResource(Res.string.default_coordinate_system), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    DisplayCoordinateSystem.entries.forEach { system ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = state.defaultCoordinateSystem == system.identifier, enabled = !state.isSaving,
                            role = Role.RadioButton, onClick = { viewModel.selectCoordinateSystem(system.identifier) },
                        ), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(state.defaultCoordinateSystem == system.identifier, onClick = null,
                                enabled = !state.isSaving)
                            Text(stringResource(when (system) {
                                DisplayCoordinateSystem.WGS_84 -> Res.string.wgs_84
                                DisplayCoordinateSystem.PULKOVO_1942 -> Res.string.pulkovo_1942
                                DisplayCoordinateSystem.PZ_90_11 -> Res.string.pz_90_11
                            }))
                        }
                    }
                }
                if (DisplayCoordinateSystem.fromIdentifier(state.defaultCoordinateSystem) != null) {
                    Text(stringResource(Res.string.coordinate_format), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        CoordinateFormat.entries.forEach { format ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = state.coordinateFormat == format, enabled = !state.isSaving,
                                role = Role.RadioButton, onClick = { viewModel.selectCoordinateFormat(format) },
                            ), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(state.coordinateFormat == format, onClick = null, enabled = !state.isSaving)
                                Text(stringResource(when (format) {
                                    CoordinateFormat.DECIMAL_DEGREES -> Res.string.coordinate_decimal
                                    CoordinateFormat.DEGREES_MINUTES -> Res.string.coordinate_minutes
                                    CoordinateFormat.DEGREES_MINUTES_SECONDS -> Res.string.coordinate_seconds
                                }))
                            }
                        }
                    }
                }
                if (state.error == PreferencesError.SAVE) {
                    Text(stringResource(Res.string.changes_could_not_be_saved_please_try_again), color = MaterialTheme.colorScheme.error)
                }
            }
            ProjectLicensingContent()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                Button(
                    modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Color.White),
                    onClick = viewModel::save,
                    enabled = !state.isLoading && !state.isSaving && state.error != PreferencesError.LOAD,
                ) { Text(if (state.isSaving) stringResource(Res.string.saving) else stringResource(Res.string.save)) }
            }
        }
    }
}
