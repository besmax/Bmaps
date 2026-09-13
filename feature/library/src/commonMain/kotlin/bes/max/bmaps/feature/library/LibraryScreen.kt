package bes.max.bmaps.feature.library

import bmaps.feature.library.generated.resources.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

@Composable
fun LibraryScreen(onBuildMap: () -> Unit) {
    val model = metroViewModel<LibraryViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { snackbar.showSnackbar(getString(it)) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize().padding(horizontal = margin),
            contentPadding = PaddingValues(top = margin, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(Res.string.your_maps), style = MaterialTheme.typography.headlineLarge)
                Button(onClick = onBuildMap) { Text(stringResource(Res.string.build_a_map)) }
            }
            items(state.progress.filter { it.state in setOf(BuildJobState.RUNNING, BuildJobState.QUEUED, BuildJobState.FINALIZING) },
                key = { "progress-${it.jobId.value}" }) { progress ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.maps.find { it.id == progress.packageId }?.name ?: stringResource(Res.string.library_downloading))
                        Text(stringResource(when (progress.state) {
                            BuildJobState.QUEUED -> Res.string.library_queued
                            BuildJobState.FINALIZING -> Res.string.library_finalizing
                            else -> Res.string.library_downloading
                        }))
                        LinearProgressIndicator(progress = { if (progress.totalTiles == 0L) 0f else
                            (progress.completedTiles.toDouble() / progress.totalTiles).toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(Res.string.library_tile_progress, progress.completedTiles, progress.totalTiles))
                        Row {
                            TextButton(onClick = { model.pause(progress.packageId) }, enabled = progress.packageId !in state.busy) { Text(stringResource(Res.string.library_pause)) }
                            TextButton(onClick = { model.cancel(progress.packageId) }, enabled = progress.packageId !in state.busy) { Text(stringResource(Res.string.library_cancel)) }
                        }
                    }
                }
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { error -> item {
                Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::refresh) { Text(stringResource(Res.string.library_retry)) }
            } }
            if (!state.loading && state.maps.isEmpty()) item { Text(stringResource(Res.string.your_saved_offline_maps_will_appear_here)) }
            items(state.maps, key = { it.id.value }) { map ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(map.name, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(Res.string.library_size, megabytes(map.sizeBytes)))
                        Text(stringResource(if (map.hasElevationData) Res.string.library_elevation_present else Res.string.library_no_elevation))
                        if (map.state == PackageState.READY) Text(stringResource(Res.string.library_ready))
                        else if (map.state in setOf(PackageState.FAILED, PackageState.PAUSED)) {
                            Text(stringResource(Res.string.library_missing_warning, map.missingTiles), color = MaterialTheme.colorScheme.error)
                            state.progress.find { it.packageId == map.id }?.failure?.let { failure ->
                                Text(stringResource(when (failure) {
                                    PackageFailure.NetworkUnavailable -> Res.string.library_network_failed
                                    PackageFailure.AuthenticationRequired -> Res.string.library_credentials_required
                                    PackageFailure.ProviderDownloadNotAllowed -> Res.string.library_provider_blocked
                                    is PackageFailure.SizeLimitExceeded -> Res.string.library_size_limit
                                    is PackageFailure.InsufficientStorage -> Res.string.library_storage_full
                                    is PackageFailure.RateLimited -> Res.string.library_rate_limited
                                    PackageFailure.TileUnavailable -> Res.string.library_tiles_unavailable
                                    PackageFailure.Conflict -> Res.string.library_source_changed
                                    else -> Res.string.library_action_failed
                                }))
                            }
                            TextButton(onClick = { model.restore(map.id) }, enabled = map.id !in state.busy) { Text(stringResource(Res.string.library_restore)) }
                        } else if (map.state in setOf(PackageState.CORRUPT, PackageState.MISSING)) {
                            Text(stringResource(Res.string.library_damaged), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (state.hasMore) item { TextButton(onClick = model::more, enabled = !state.loading) { Text(stringResource(Res.string.library_more)) } }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun megabytes(bytes: Long): String = "${bytes / 1_000_000}.${bytes / 100_000 % 10}"
