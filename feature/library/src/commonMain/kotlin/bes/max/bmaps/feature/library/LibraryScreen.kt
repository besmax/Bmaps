package bes.max.bmaps.feature.library

import bmaps.feature.library.generated.resources.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

@Composable
fun LibraryScreen(onBuildMap: () -> Unit, onOpenMap: (PackageId) -> Unit) {
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
                OutlinedTextField(value = state.search, onValueChange = model::search, singleLine = true,
                    label = { Text(stringResource(Res.string.library_search)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryFilter.entries.forEach { filter ->
                        FilterChip(selected = state.filter == filter, onClick = { model.filter(filter) }, label = {
                            Text(stringResource(when (filter) {
                                LibraryFilter.ALL -> Res.string.library_all
                                LibraryFilter.FAVOURITES -> Res.string.library_favourites
                                LibraryFilter.READY -> Res.string.library_ready
                                LibraryFilter.INCOMPLETE -> Res.string.library_incomplete
                            }))
                        })
                    }
                }
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
            if (!state.loading && state.error == null && state.maps.isEmpty()) item {
                Text(stringResource(if (state.search.isNotEmpty() || state.filter != LibraryFilter.ALL)
                    Res.string.library_no_matches else Res.string.your_saved_offline_maps_will_appear_here))
            }
            items(state.maps, key = { it.id.value }) { map ->
                OutlinedCard(onClick = { if (map.state == PackageState.READY) onOpenMap(map.id) else model.details(map.id) }, enabled = map.id !in state.busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                                val avatar = MapAvatar.entries.firstOrNull { it.storageKey == map.avatarKey } ?: MapAvatar.MAP
                                Icon(painterResource(avatarIcon(avatar)), stringResource(avatarLabel(avatar)), Modifier.padding(14.dp).size(28.dp))
                            }
                            Text(map.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        Text(stringResource(stateLabel(map.state)), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(Res.string.library_size, megabytes(map.sizeBytes)))
                        Text(stringResource(if (map.hasElevationData) Res.string.library_elevation_present else Res.string.library_no_elevation))
                        if (map.state in setOf(PackageState.FAILED, PackageState.PAUSED)) {
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
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { model.favourite(map) }, enabled = map.id !in state.busy) {
                                Text(stringResource(if (map.favourite) Res.string.library_unfavourite else Res.string.library_favourite))
                            }
                            TextButton(onClick = { model.details(map.id) }) { Text(stringResource(Res.string.library_details)) }
                            TextButton(onClick = { model.confirmDelete(map.id) }, enabled = map.id !in state.busy) {
                                Text(stringResource(Res.string.library_delete))
                            }
                        }
                    }
                }
            }
            if (state.hasMore) item { TextButton(onClick = model::more, enabled = !state.loading) { Text(stringResource(Res.string.library_more)) } }
        }
        ExtendedFloatingActionButton(onClick = onBuildMap,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = androidx.compose.ui.graphics.Color.White) {
            Text(stringResource(Res.string.build_a_map))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    state.maps.firstOrNull { it.id == state.details }?.let { map ->
        AlertDialog(onDismissRequest = { model.details(null) }, title = { Text(map.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(stateLabel(map.state)))
                Text(stringResource(Res.string.library_size, megabytes(map.sizeBytes)))
                Text(stringResource(if (map.hasElevationData) Res.string.library_elevation_present else Res.string.library_no_elevation))
                map.bounds?.let { bounds -> Text(stringResource(Res.string.library_bounds,
                    bounds.south.toString(), bounds.west.toString(), bounds.north.toString(), bounds.east.toString())) }
                if (map.zoomLevels.isNotEmpty()) Text(stringResource(Res.string.library_levels, map.zoomLevels.sorted().joinToString()))
                Text(stringResource(Res.string.library_tile_progress, map.downloadedTiles, map.totalTiles))
                Text(stringResource(Res.string.library_avatar), style = MaterialTheme.typography.titleSmall)
                MapAvatar.entries.chunked(2).forEach { avatars ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        avatars.forEach { avatar ->
                            FilterChip(selected = map.avatarKey == avatar.storageKey, enabled = map.id !in state.busy,
                                onClick = { model.avatar(map.id, avatar) },
                                leadingIcon = { Icon(painterResource(avatarIcon(avatar)), null, Modifier.size(20.dp)) }, label = { Text(stringResource(avatarLabel(avatar))) })
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { model.details(null) }) { Text(stringResource(Res.string.library_done)) } })
    }
    state.deleteConfirmation?.let { id ->
        AlertDialog(onDismissRequest = { model.confirmDelete(null) },
            title = { Text(stringResource(Res.string.library_delete_title)) },
            text = { Text(stringResource(Res.string.library_delete_message, state.maps.firstOrNull { it.id == id }?.name.orEmpty())) },
            confirmButton = { TextButton(onClick = model::deleteConfirmed) { Text(stringResource(Res.string.library_delete)) } },
            dismissButton = { TextButton(onClick = { model.confirmDelete(null) }) { Text(stringResource(Res.string.library_keep)) } })
    }
}

private fun stateLabel(state: PackageState) = when (state) {
    PackageState.READY -> Res.string.library_ready
    PackageState.BUILDING -> Res.string.library_downloading
    PackageState.FINALIZING -> Res.string.library_finalizing
    PackageState.PAUSED -> Res.string.library_paused
    PackageState.FAILED -> Res.string.library_failed
    PackageState.DELETING -> Res.string.library_deleting
    PackageState.CORRUPT, PackageState.MISSING -> Res.string.library_damaged
}

private fun megabytes(bytes: Long): String = "${bytes / 1_000_000}.${bytes / 100_000 % 10}"

private fun avatarLabel(avatar: MapAvatar) = when (avatar) {
    MapAvatar.MAP -> Res.string.avatar_map
    MapAvatar.MOUNTAIN -> Res.string.avatar_mountain
    MapAvatar.FOREST -> Res.string.avatar_forest
    MapAvatar.WATER -> Res.string.avatar_water
    MapAvatar.CITY -> Res.string.avatar_city
    MapAvatar.CAMP -> Res.string.avatar_camp
}

private fun avatarIcon(avatar: MapAvatar) = when (avatar) {
    MapAvatar.MAP -> Res.drawable.avatar_map
    MapAvatar.MOUNTAIN -> Res.drawable.avatar_mountain
    MapAvatar.FOREST -> Res.drawable.avatar_forest
    MapAvatar.WATER -> Res.drawable.avatar_water
    MapAvatar.CITY -> Res.drawable.avatar_city
    MapAvatar.CAMP -> Res.drawable.avatar_camp
}
