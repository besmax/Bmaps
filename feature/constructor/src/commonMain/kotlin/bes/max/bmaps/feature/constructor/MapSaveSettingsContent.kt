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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.providers.*
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSaveSettingsContent(mapOwner: ViewModelStoreOwner, onStarted: () -> Unit, onDismiss: () -> Unit, onCredentials: (String) -> Unit) {
    val map = metroViewModel<OnlineMapViewModel>(mapOwner)
    val area = metroViewModel<AreaSelectionViewModel>(mapOwner)
    val model = metroViewModel<MapSaveSettingsViewModel>()
    val submission = metroViewModel<DownloadSubmissionViewModel>()
    val download by submission.state.collectAsStateWithLifecycle()
    val authorizeNotifications = rememberDownloadNotificationPermission()
    val state by model.state.collectAsStateWithLifecycle()
    val selection by area.state.collectAsStateWithLifecycle()
    val online by map.state.collectAsStateWithLifecycle()
    val source = online.selected
    LaunchedEffect(model, selection.acceptedBounds, source) {
        val bounds = selection.acceptedBounds ?: return@LaunchedEffect
        val choice = source ?: return@LaunchedEffect
        val limits = choice.provider.configFor(choice.style).levelLimits
        model.initialize(bounds, ZoomRange(limits.levelMin, limits.levelMax ?: limits.levelMin), selection.settings)
        submission.configure(choice)
    }
    val currentSource by rememberUpdatedState(source)
    val dismiss by rememberUpdatedState(onDismiss)
    val started by rememberUpdatedState(onStarted)
    val credentials by rememberUpdatedState(onCredentials)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { settings -> currentSource?.let { choice ->
                area.retain(settings)
                authorizeNotifications { submission.start(settings, choice) }
            } }
        }
    }
    LaunchedEffect(submission, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { submission.events.collect { event ->
            when (event) {
                DownloadSubmissionEvent.Started -> started()
                DownloadSubmissionEvent.ElevationCredentials -> credentials(OPENTOPOGRAPHY_CREDENTIAL)
            }
        } }
    }
    if (state.addingLayer) AlertDialog(
        onDismissRequest = { model.showLayerPicker(false) },
        title = { Text(stringResource(Res.string.layer_add)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            online.choices.forEach { choice ->
                TextButton(onClick = { source?.let { model.addLayer(choice, it) } }) {
                    Text("${choice.provider.name} · ${choice.style.name}")
                }
            }
        } },
        confirmButton = { TextButton(onClick = { model.showLayerPicker(false) }) { Text(stringResource(Res.string.cancel)) } },
    ) else AlertDialog(
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        onDismissRequest = { if (!download.busy) dismiss() },
        title = { Text(stringResource(Res.string.map_settings_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.name, model::name, enabled = !download.busy, label = { Text(stringResource(Res.string.map_name)) }, singleLine = true, shape = MaterialTheme.shapes.small)
                Text(stringResource(Res.string.zoom_levels), style = MaterialTheme.typography.titleSmall)
                if (state.availableLevels.isNotEmpty() && state.selectedLevels.isNotEmpty()) {
                    val minimum = state.selectedLevels.min()
                    val maximum = state.selectedLevels.max()
                    val zoomLabel = stringResource(Res.string.zoom_range, minimum, maximum)
                    Text(zoomLabel)
                    if (state.availableLevels.size > 1) RangeSlider(
                        value = minimum.toFloat()..maximum.toFloat(),
                        onValueChange = { model.selectZoomRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
                        valueRange = state.availableLevels.first().toFloat()..state.availableLevels.last().toFloat(),
                        steps = (state.availableLevels.size - 2).coerceAtLeast(0),
                        enabled = !download.busy,
                        modifier = Modifier.semantics { contentDescription = zoomLabel },
                    )
                }
                Text(stringResource(Res.string.layers_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.layers_hint), style = MaterialTheme.typography.bodySmall)
                source?.let { root ->
                    LayerVisibility("${root.provider.name} · ${root.style.name}", state.rootVisible, !download.busy) { visible ->
                        model.layerVisibility(null, visible)
                    }
                    root.provider.attributionFor(root.style).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                }
                state.layers.forEachIndexed { index, layer ->
                    LayerVisibility("${layer.choice.provider.name} · ${layer.choice.style.name}", layer.visible, !download.busy) { visible ->
                        model.layerVisibility(layer.id, visible)
                    }
                    layer.choice.provider.attributionFor(layer.choice.style).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { model.moveLayer(layer.id, -1) }, enabled = !download.busy && index > 0) { Text(stringResource(Res.string.layer_down)) }
                        TextButton(onClick = { model.moveLayer(layer.id, 1) }, enabled = !download.busy && index < state.layers.lastIndex) { Text(stringResource(Res.string.layer_up)) }
                        TextButton(onClick = { model.removeLayer(layer.id) }, enabled = !download.busy) { Text(stringResource(Res.string.layer_remove)) }
                    }
                }
                TextButton(onClick = { model.showLayerPicker(true) }, enabled = !download.busy && state.layers.size < 31) { Text(stringResource(Res.string.layer_add)) }
                Text(stringResource(Res.string.estimated_size_mb, formatMegabytes(state.estimate?.estimatedPackageBytes) ?: stringResource(Res.string.unavailable)),
                    style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(Res.string.tile_count_estimate, state.estimate?.tileCount ?: 0), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.elevation_title), style = MaterialTheme.typography.titleSmall)
                ElevationDataset.entries.forEach { dataset ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = state.elevationDataset == dataset,
                            onClick = { model.elevation(dataset) }, enabled = !download.busy)
                        TextButton(onClick = { model.elevation(dataset) }, enabled = !download.busy) {
                            Text(stringResource(elevationTitle(dataset)))
                        }
                    }
                    if (state.elevationDataset == dataset) elevationDescription(dataset)?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.elevationDataset != ElevationDataset.NONE) {
                    Text(stringResource(Res.string.elevation_download_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onCredentials(OPENTOPOGRAPHY_CREDENTIAL) }, enabled = !download.busy) {
                        Text(stringResource(Res.string.elevation_manage_key))
                    }
                }
                if ((state.estimate?.estimatedLargestLayerBytes ?: 0) > bes.max.bmaps.domain.mapbuilder.PackageSizePolicy.MAX_LAYER_BYTES) Text(stringResource(Res.string.package_size_limit_exceeded), color = MaterialTheme.colorScheme.error)
                if (source?.provider?.capabilitiesFor(source.style)?.offlineDownload == OfflineDownloadPermission.PROHIBITED) {
                    Text(stringResource(Res.string.offline_download_prohibited), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(Res.string.download_available_mb, formatMegabytes(download.availableBytes) ?: stringResource(Res.string.unavailable)))
                TextButton(onClick = submission::refreshCapacity, enabled = !download.busy) { Text(stringResource(Res.string.download_refresh_capacity)) }
                download.policyError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                download.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = model::confirm, enabled = !download.busy && download.policyError == null,
            modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Color.White)) {
            Text(stringResource(if (download.busy) Res.string.download_starting else Res.string.download_start)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !download.busy) { Text(stringResource(Res.string.cancel)) } },
    )
}

internal fun formatMegabytes(bytes: Long?): String? = bytes?.let {
    val tenths = it / 100_000
    "${tenths / 10}.${tenths % 10}"
}

@Composable
private fun LayerVisibility(name: String, visible: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(visible, change, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = name })
    }
}

private fun elevationTitle(dataset: ElevationDataset) = when (dataset) {
    ElevationDataset.SRTM15Plus -> Res.string.elevation_srtm15
    ElevationDataset.NASADEM -> Res.string.elevation_nasadem
    ElevationDataset.COP30 -> Res.string.elevation_cop30
    ElevationDataset.COP90 -> Res.string.elevation_cop90
    ElevationDataset.EU_DTM -> Res.string.elevation_eu_dtm
    ElevationDataset.NONE -> Res.string.elevation_none
}

private fun elevationDescription(dataset: ElevationDataset) = when (dataset) {
    ElevationDataset.SRTM15Plus -> Res.string.elevation_srtm15_description
    ElevationDataset.NASADEM -> Res.string.elevation_nasadem_description
    ElevationDataset.COP30 -> Res.string.elevation_cop30_description
    ElevationDataset.COP90 -> Res.string.elevation_cop90_description
    ElevationDataset.EU_DTM -> Res.string.elevation_eu_dtm_description
    ElevationDataset.NONE -> null
}
