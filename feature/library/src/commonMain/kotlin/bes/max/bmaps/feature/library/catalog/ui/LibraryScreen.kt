/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.sharing.rememberNativeDocuments
import bes.max.bmaps.domain.mapbuilder.BuildJobState
import bes.max.bmaps.domain.mapbuilder.BuildProgress
import bes.max.bmaps.domain.mapbuilder.MapAvatar
import bes.max.bmaps.domain.mapbuilder.PackageFailure
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.domain.mapbuilder.PackageState
import bes.max.bmaps.domain.mapbuilder.PackageSummary
import bes.max.bmaps.domain.providers.OPENTOPOGRAPHY_CREDENTIAL
import bes.max.bmaps.feature.library.catalog.presentation.LibraryFilter
import bes.max.bmaps.feature.library.catalog.presentation.LibraryState
import bes.max.bmaps.feature.library.catalog.presentation.LibraryViewModel
import bes.max.bmaps.feature.library.transfer.presentation.PackageTransferViewModel
import bes.max.bmaps.feature.library.transfer.presentation.TransferEvent
import bmaps.feature.library.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    onBuildMap: () -> Unit,
    onOpenMap: (PackageId) -> Unit,
    onCredentials: (String) -> Unit
) {
    val model = metroViewModel<LibraryViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val transfer = metroViewModel<PackageTransferViewModel>()
    val transferState by transfer.state.collectAsStateWithLifecycle()
    val documents = rememberNativeDocuments(transfer::importFile, transfer::nativeFailure)
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.refresh()
            model.events.collect { snackbar.showSnackbar(getString(it)) }
        }
    }
    LaunchedEffect(transfer, lifecycle, documents) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            transfer.events.collect { event ->
                when (event) {
                    is TransferEvent.Share -> documents.share(event.document)
                    is TransferEvent.Message -> snackbar.showSnackbar(getString(event.message))
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        LibraryList(
            state = state,
            transferBusy = transferState.busy,
            margin = margin,
            onImport = documents::pick,
            onCancelTransfer = transfer::cancel,
            onSearch = model::search,
            onFilter = model::filter,
            onPause = { model.pause(it) },
            onCancel = { model.cancel(it) },
            onRefresh = model::refresh,
            onOpenMap = onOpenMap,
            onDetails = model::details,
            onRestore = { model.restore(it) },
            onFavourite = { model.favourite(it) },
            onExport = { transfer.export(it) },
            onDelete = model::confirmDelete,
            onCredentials = { onCredentials(OPENTOPOGRAPHY_CREDENTIAL) },
            onMore = model::more,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        LibraryBuildButton(onBuildMap, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    state.details?.let { details ->
        val map = state.maps.firstOrNull { it.id == details.id } ?: details
        PackageDetailsDialog(
            map,
            map.id in state.busy,
            { model.avatar(map.id, it) },
            { model.details(null) })
    }
    state.deleteConfirmation?.let { id ->
        LibraryDeleteDialog(
            mapName = state.maps.firstOrNull { it.id == id }?.name.orEmpty(),
            onConfirm = model::deleteConfirmed,
            onDismiss = { model.confirmDelete(null) },
        )
    }
}

@Composable
private fun LibraryBuildButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = androidx.compose.ui.graphics.Color.White,
    ) {
        Text(stringResource(Res.string.build_a_map))
    }
}

@Composable
private fun LibraryList(
    state: LibraryState,
    transferBusy: Boolean,
    margin: Dp,
    onImport: () -> Unit,
    onCancelTransfer: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onPause: (PackageId) -> Unit,
    onCancel: (PackageId) -> Unit,
    onRefresh: () -> Unit,
    onOpenMap: (PackageId) -> Unit,
    onDetails: (PackageId) -> Unit,
    onRestore: (PackageId) -> Unit,
    onFavourite: (PackageSummary) -> Unit,
    onExport: (PackageId) -> Unit,
    onDelete: (PackageId) -> Unit,
    onCredentials: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = margin),
        contentPadding = PaddingValues(top = margin, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LibraryControls(
                state.search,
                state.filter,
                transferBusy,
                onImport,
                onCancelTransfer,
                onSearch,
                onFilter
            )
        }
        items(
            state.progress.filter {
                it.state in setOf(
                    BuildJobState.RUNNING,
                    BuildJobState.QUEUED,
                    BuildJobState.FINALIZING
                )
            },
            key = { "progress-${it.jobId.value}" },
        ) { progress ->
            LibraryDownloadItem(
                progress = progress,
                packageName = state.maps.find { it.id == progress.packageId }?.name,
                busy = progress.packageId in state.busy,
                onPause = { onPause(progress.packageId) },
                onCancel = { onCancel(progress.packageId) },
            )
        }
        if (state.loading) item { LibraryLoadingItem() }
        state.error?.let { error -> item { LibraryErrorItem(error, onRefresh) } }
        if (!state.loading && state.error == null && state.maps.isEmpty()) item {
            LibraryEmptyItem(state.search.isNotEmpty() || state.filter != LibraryFilter.ALL)
        }
        items(state.maps, key = { it.id.value }) { map ->
            LibraryMapItem(
                map = map,
                failure = state.progress.find { it.packageId == map.id }?.failure,
                busy = map.id in state.busy,
                transferBusy = transferBusy,
                onOpen = { onOpenMap(map.id) },
                onDetails = { onDetails(map.id) },
                onRestore = { onRestore(map.id) },
                onFavourite = { onFavourite(map) },
                onExport = { onExport(map.id) },
                onDelete = { onDelete(map.id) },
                onCredentials = onCredentials,
            )
        }
        if (state.hasMore) item { LibraryMoreItem(state.loading, onMore) }
    }
}

@Composable
private fun LibraryLoadingItem() {
    CircularProgressIndicator()
}

@Composable
private fun LibraryErrorItem(error: StringResource, onRetry: () -> Unit) {
    Column {
        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.library_retry)) }
    }
}

@Composable
private fun LibraryEmptyItem(filtered: Boolean) {
    Text(stringResource(if (filtered) Res.string.library_no_matches else Res.string.your_saved_offline_maps_will_appear_here))
}

@Composable
private fun LibraryMoreItem(loading: Boolean, onMore: () -> Unit) {
    TextButton(
        onClick = onMore,
        enabled = !loading
    ) { Text(stringResource(Res.string.library_more)) }
}

@Composable
private fun LibraryControls(
    search: String,
    selectedFilter: LibraryFilter,
    transferBusy: Boolean,
    onImport: () -> Unit,
    onCancelTransfer: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    Column {
        LibraryImportControls(transferBusy, onImport, onCancelTransfer)
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text(stringResource(Res.string.library_search)) },
            modifier = Modifier.fillMaxWidth(),
        )
        LibraryFilters(selectedFilter, onFilter)
    }
}

@Composable
private fun LibraryImportControls(busy: Boolean, onImport: () -> Unit, onCancel: () -> Unit) {
    TextButton(
        onClick = onImport,
        enabled = !busy
    ) { Text(stringResource(Res.string.transfer_import)) }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(Res.string.transfer_working))
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.library_cancel)) }
    }
}

@Composable
private fun LibraryFilters(selectedFilter: LibraryFilter, onFilter: (LibraryFilter) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryFilter.entries.forEach { filter ->
            LibraryFilterItem(filter, selectedFilter == filter) { onFilter(filter) }
        }
    }
}

@Composable
private fun LibraryFilterItem(filter: LibraryFilter, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                stringResource(
                    when (filter) {
                        LibraryFilter.ALL -> Res.string.library_all
                        LibraryFilter.FAVOURITES -> Res.string.library_favourites
                        LibraryFilter.READY -> Res.string.library_ready
                        LibraryFilter.INCOMPLETE -> Res.string.library_incomplete
                    }
                )
            )
        },
    )
}

@Composable
private fun LibraryDownloadItem(
    progress: BuildProgress,
    packageName: String?,
    busy: Boolean,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(packageName ?: stringResource(Res.string.library_downloading))
            Text(
                stringResource(
                    when (progress.state) {
                        BuildJobState.QUEUED -> Res.string.library_queued
                        BuildJobState.FINALIZING -> Res.string.library_finalizing
                        else -> if (progress.missingTiles == 0L) Res.string.library_finishing_assets else Res.string.library_downloading
                    }
                )
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.totalTiles == 0L) 0f
                    else (progress.completedTiles.toDouble() / progress.totalTiles).toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    Res.string.library_tile_progress,
                    progress.completedTiles,
                    progress.totalTiles
                )
            )
            Row {
                TextButton(
                    onClick = onPause,
                    enabled = !busy
                ) { Text(stringResource(Res.string.library_pause)) }
                TextButton(
                    onClick = onCancel,
                    enabled = !busy
                ) { Text(stringResource(Res.string.library_cancel)) }
            }
        }
    }
}

@Composable
private fun LibraryMapItem(
    map: PackageSummary,
    failure: PackageFailure?,
    busy: Boolean,
    transferBusy: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onRestore: () -> Unit,
    onFavourite: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCredentials: () -> Unit,
) {
    OutlinedCard(
        onClick = { if (map.state == PackageState.READY) onOpen() else onDetails() },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryMapHeader(map)
            Text(
                stringResource(stateLabel(map.state)),
                style = MaterialTheme.typography.labelMedium
            )
            Text(stringResource(Res.string.library_size, megabytes(map.sizeBytes)))
            Text(stringResource(if (map.hasElevationData) Res.string.library_elevation_present else Res.string.library_no_elevation))
            LibraryMapRecovery(map, failure, busy, onRestore, onCredentials)
            LibraryMapActions(map, busy, transferBusy, onFavourite, onDetails, onExport, onDelete)
        }
    }
}

@Composable
private fun LibraryMapHeader(map: PackageSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            val avatar =
                MapAvatar.entries.firstOrNull { it.storageKey == map.avatarKey } ?: MapAvatar.MAP
            Icon(
                painterResource(avatarIcon(avatar)),
                stringResource(avatarLabel(avatar)),
                Modifier.padding(14.dp).size(28.dp),
            )
        }
        Text(
            map.name,
            Modifier.weight(1f).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun LibraryMapRecovery(
    map: PackageSummary,
    failure: PackageFailure?,
    busy: Boolean,
    onRestore: () -> Unit,
    onCredentials: () -> Unit,
) {
    if (map.state in setOf(PackageState.FAILED, PackageState.PAUSED)) {
        Text(
            if (map.missingTiles == 0L) stringResource(Res.string.library_assets_incomplete)
            else stringResource(Res.string.library_missing_warning, map.missingTiles),
            color = MaterialTheme.colorScheme.error,
        )
        failure?.let { Text(stringResource(failureLabel(it))) }
        if (failure in setOf(
                PackageFailure.ElevationCredentialsRequired,
                PackageFailure.ElevationAccessDenied
            )
        ) {
            TextButton(onClick = onCredentials) { Text(stringResource(Res.string.library_elevation_replace_key)) }
        }
        TextButton(
            onClick = onRestore,
            enabled = !busy
        ) { Text(stringResource(Res.string.library_restore)) }
    } else if (map.state in setOf(PackageState.CORRUPT, PackageState.MISSING)) {
        Text(stringResource(Res.string.library_damaged), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LibraryMapActions(
    map: PackageSummary,
    busy: Boolean,
    transferBusy: Boolean,
    onFavourite: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        IconButton(onClick = onFavourite, enabled = !busy) {
            Icon(
                painter = painterResource(if (map.favourite) Res.drawable.ic_star else Res.drawable.ic_star_border),
                contentDescription = stringResource(if (map.favourite) Res.string.library_unfavourite else Res.string.library_favourite)
            )
        }

        IconButton(onClick = onDetails) {
            Icon(
                painter = painterResource(Res.drawable.ic_info),
                contentDescription = stringResource(Res.string.library_details)
            )
        }

        if (map.state == PackageState.READY) {
            IconButton(onClick = onExport, enabled = !transferBusy && !busy) {
                Icon(
                    painter = painterResource(Res.drawable.ic_share),
                    contentDescription =stringResource(Res.string.transfer_share)
                )
            }
        }

        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(
                painter = painterResource(Res.drawable.ic_delete),
                contentDescription = stringResource(Res.string.library_delete)
            )
        }
    }
}

@Composable
private fun LibraryDeleteDialog(mapName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_delete_title)) },
        text = { Text(stringResource(Res.string.library_delete_message, mapName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.library_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_keep)) }
        },
    )
}
