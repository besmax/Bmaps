package bes.max.bmaps.feature.constructor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.providers.OfflineDownloadPermission
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapSaveSettingsContent(mapOwner: ViewModelStoreOwner, onDismiss: () -> Unit) {
    val map = metroViewModel<OnlineMapViewModel>(mapOwner)
    val area = metroViewModel<AreaSelectionViewModel>(mapOwner)
    val model = metroViewModel<MapSaveSettingsViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val selection by area.state.collectAsStateWithLifecycle()
    val source = map.state.value.selected
    LaunchedEffect(model, selection.acceptedBounds, source) {
        val bounds = selection.acceptedBounds ?: return@LaunchedEffect
        val limits = source?.provider?.configFor(source.style)?.levelLimits ?: return@LaunchedEffect
        model.initialize(bounds, ZoomRange(limits.levelMin, limits.levelMax ?: limits.levelMin), selection.settings)
    }
    val dismiss by rememberUpdatedState(onDismiss)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { area.retain(it); dismiss() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save map settings") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.name, model::name, label = { Text("Map name") }, singleLine = true)
                Text("Zoom levels", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableLevels.forEach { level ->
                        FilterChip(selected = level in state.selectedLevels, onClick = { model.toggle(level) }, label = { Text(level.toString()) })
                    }
                }
                Text("Estimated size: ${formatMegabytes(state.estimate?.estimatedPackageBytes)} MB")
                Text("${state.estimate?.tileCount ?: 0} tiles · rough estimate using 32 KB per tile plus storage overhead. Actual size varies.", style = MaterialTheme.typography.bodySmall)
                if ((state.estimate?.estimatedPackageBytes ?: 0) > 300_000_000L) Text("Estimate exceeds the 300 MB package limit. Choose a smaller area or fewer levels.", color = MaterialTheme.colorScheme.error)
                if (source?.provider?.capabilitiesFor(source.style)?.offlineDownload == OfflineDownloadPermission.PROHIBITED) {
                    Text("This source does not permit offline downloads.", color = MaterialTheme.colorScheme.error)
                }
                Text("Downloading is not available yet. Save settings keeps this draft for the current map session.", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = model::confirm) { Text("Save settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatMegabytes(bytes: Long?): String = bytes?.let {
    val tenths = it / 100_000
    "${tenths / 10}.${tenths % 10}"
} ?: "unavailable"
