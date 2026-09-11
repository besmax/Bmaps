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
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.map_settings_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.name, model::name, label = { Text(stringResource(Res.string.map_name)) }, singleLine = true, shape = MaterialTheme.shapes.small)
                Text(stringResource(Res.string.zoom_levels), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableLevels.forEach { level ->
                        FilterChip(selected = level in state.selectedLevels, onClick = { model.toggle(level) }, label = { Text(level.toString()) })
                    }
                }
                Text(stringResource(Res.string.estimated_size_mb, formatMegabytes(state.estimate?.estimatedPackageBytes) ?: stringResource(Res.string.unavailable)),
                    style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(Res.string.tile_count_estimate, state.estimate?.tileCount ?: 0), style = MaterialTheme.typography.bodySmall)
                if ((state.estimate?.estimatedPackageBytes ?: 0) > 300_000_000L) Text(stringResource(Res.string.package_size_limit_exceeded), color = MaterialTheme.colorScheme.error)
                if (source?.provider?.capabilitiesFor(source.style)?.offlineDownload == OfflineDownloadPermission.PROHIBITED) {
                    Text(stringResource(Res.string.offline_download_prohibited), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(Res.string.download_draft_notice), style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = model::confirm, modifier = Modifier.heightIn(min = 48.dp), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Color.White)) { Text(stringResource(Res.string.save_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

internal fun formatMegabytes(bytes: Long?): String? = bytes?.let {
    val tenths = it / 100_000
    "${tenths / 10}.${tenths % 10}"
}
