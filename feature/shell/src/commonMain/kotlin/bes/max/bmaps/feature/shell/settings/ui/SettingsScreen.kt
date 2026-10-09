/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.shell.settings.ui

import bes.max.bmaps.feature.shell.about.ui.ProjectLicensingContent
import bes.max.bmaps.feature.shell.settings.presentation.PreferenceSetting
import bes.max.bmaps.feature.shell.settings.presentation.PreferencesState
import bes.max.bmaps.feature.shell.settings.presentation.PreferencesViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.datastore.CoordinateFormat
import bes.max.bmaps.core.datastore.DisplayCoordinateSystem
import bes.max.bmaps.core.datastore.ThemePreference
import bmaps.feature.shell.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: PreferencesViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(Res.string.settings)) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.ic_back), stringResource(Res.string.back)) }
            })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                if (landscape) {
                    Row(Modifier.widthIn(max = 1200.dp).fillMaxSize().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsLoadState(state, viewModel::loadPreferences)
                            if (!state.isLoading && !state.loadFailed) AppearanceSettings(state, viewModel)
                            ProjectLicensingContent()
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                            if (!state.isLoading && !state.loadFailed) MapSettings(state, viewModel)
                        }
                    }
                } else {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingsLoadState(state, viewModel::loadPreferences)
                        if (!state.isLoading && !state.loadFailed) {
                            AppearanceSettings(state, viewModel)
                            MapSettings(state, viewModel)
                        }
                        ProjectLicensingContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsLoadState(state: PreferencesState, retry: () -> Unit) {
    if (state.isLoading) CircularProgressIndicator()
    if (state.loadFailed) {
        Text(stringResource(Res.string.your_preferences_could_not_be_loaded), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text(stringResource(Res.string.retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettings(state: PreferencesState, model: PreferencesViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.appearance), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemePreference.entries.forEachIndexed { index, theme ->
                    SegmentedButton(selected = state.selectedTheme == theme, modifier = Modifier.heightIn(min = 48.dp), icon = {},
                        onClick = { model.selectTheme(theme) }, enabled = PreferenceSetting.THEME !in state.saving,
                        shape = SegmentedButtonDefaults.itemShape(index, ThemePreference.entries.size)) {
                        Text(stringResource(when (theme) {
                            ThemePreference.SYSTEM -> Res.string.system
                            ThemePreference.LIGHT -> Res.string.light
                            ThemePreference.DARK -> Res.string.dark
                        }))
                    }
                }
            }
            SettingStatus(state, PreferenceSetting.THEME)
        }
    }
}

@Composable
private fun MapSettings(state: PreferencesState, model: PreferencesViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = state.clusterMapObjects, enabled = PreferenceSetting.CLUSTERING !in state.saving,
                role = Role.Switch, onValueChange = model::clusterMapObjects,
            ), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.cluster_map_objects))
                    Text(stringResource(Res.string.cluster_map_objects_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.clusterMapObjects, onCheckedChange = null, enabled = PreferenceSetting.CLUSTERING !in state.saving)
            }
            SettingStatus(state, PreferenceSetting.CLUSTERING)
            HorizontalDivider()
            Text(stringResource(Res.string.default_coordinate_system), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                DisplayCoordinateSystem.entries.forEach { system ->
                    SettingOption(selected = state.defaultCoordinateSystem == system.identifier,
                        enabled = PreferenceSetting.COORDINATE_SYSTEM !in state.saving,
                        onClick = { model.selectCoordinateSystem(system.identifier) }, label = stringResource(when (system) {
                            DisplayCoordinateSystem.WGS_84 -> Res.string.wgs_84
                            DisplayCoordinateSystem.PULKOVO_1942 -> Res.string.pulkovo_1942
                            DisplayCoordinateSystem.PZ_90_11 -> Res.string.pz_90_11
                        }))
                }
            }
            SettingStatus(state, PreferenceSetting.COORDINATE_SYSTEM)
            if (DisplayCoordinateSystem.fromIdentifier(state.defaultCoordinateSystem) != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.coordinate_format), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    CoordinateFormat.entries.forEach { format ->
                        SettingOption(selected = state.coordinateFormat == format,
                            enabled = PreferenceSetting.COORDINATE_FORMAT !in state.saving,
                            onClick = { model.selectCoordinateFormat(format) }, label = stringResource(when (format) {
                                CoordinateFormat.DECIMAL_DEGREES -> Res.string.coordinate_decimal
                                CoordinateFormat.DEGREES_MINUTES -> Res.string.coordinate_minutes
                                CoordinateFormat.DEGREES_MINUTES_SECONDS -> Res.string.coordinate_seconds
                            }))
                    }
                }
                SettingStatus(state, PreferenceSetting.COORDINATE_FORMAT)
            }
        }
    }
}

@Composable
private fun SettingOption(selected: Boolean, enabled: Boolean, onClick: () -> Unit, label: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun SettingStatus(state: PreferencesState, setting: PreferenceSetting) {
    if (setting in state.saving) Text(stringResource(Res.string.saving), style = MaterialTheme.typography.bodySmall)
    if (setting in state.saveErrors) Text(stringResource(Res.string.setting_save_failed), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
}
