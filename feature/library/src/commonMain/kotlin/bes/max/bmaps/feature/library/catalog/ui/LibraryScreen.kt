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
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.domain.mapbuilder.PackageState
import bes.max.bmaps.domain.providers.OPENTOPOGRAPHY_CREDENTIAL
import bes.max.bmaps.feature.library.catalog.presentation.LibraryMapUi
import bes.max.bmaps.feature.library.catalog.presentation.LibraryMapHeaderUi
import bes.max.bmaps.feature.library.catalog.presentation.LibraryDownloadUi
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
            onCancelTransfer = transfer::cancel,
            onSearch = model::search,
            onFilter = model::filter,
            onPause = { model.pause(it) },
            onCancel = { model.cancel(it) },
            onRefresh = model::refresh,
            onOpenMap = { onOpenMap(PackageId(it)) },
            onDetails = model::details,
            onRestore = { model.restore(it) },
            onFavourite = { model.favourite(it) },
            onExport = { transfer.export(PackageId(it)) },
            onDelete = model::confirmDelete,
            onCredentials = { onCredentials(OPENTOPOGRAPHY_CREDENTIAL) },
            onMore = model::more,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        LibraryActionFab(
            expanded = state.actionsExpanded,
            importEnabled = !transferState.busy,
            onExpandedChange = model::actionsExpanded,
            onBuild = { model.actionsExpanded(false); onBuildMap() },
            onImport = { model.actionsExpanded(false); documents.pick() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
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
private fun LibraryList(
    state: LibraryState,
    transferBusy: Boolean,
    margin: Dp,
    onCancelTransfer: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenMap: (String) -> Unit,
    onDetails: (String) -> Unit,
    onRestore: (String) -> Unit,
    onFavourite: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
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
            key = { "progress-${it.jobId}" },
        ) { progress ->
            val id = progress.packageId
            LibraryDownloadItem(
                progress = progress,
                packageName = state.maps.find { it.id == id }?.name,
                busy = id in state.busy,
                onPause = { onPause(id) },
                onCancel = { onCancel(id) },
            )
        }
        if (state.loading) item { LibraryLoadingItem() }
        state.error?.let { error -> item { LibraryErrorItem(error, onRefresh) } }
        if (!state.loading && state.error == null && state.maps.isEmpty()) item {
            LibraryEmptyItem(state.search.isNotEmpty() || state.filter != LibraryFilter.ALL)
        }
        items(state.maps, key = { it.id }) { map ->
            val id = map.id
            val progress = state.progress.find { it.packageId == id }
            LibraryMapItem(
                map = map,
                failureLabel = progress?.failureLabel,
                needsElevationCredentials = progress?.needsElevationCredentials == true,
                busy = id in state.busy,
                savingFavourite = id in state.savingFavourites,
                transferBusy = transferBusy,
                onOpen = { onOpenMap(id) },
                onDetails = { onDetails(id) },
                onRestore = { onRestore(id) },
                onFavourite = { onFavourite(id) },
                onExport = { onExport(id) },
                onDelete = { onDelete(id) },
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
    onCancelTransfer: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    Column {
        LibraryTransferProgress(transferBusy, onCancelTransfer)
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
private fun LibraryTransferProgress(busy: Boolean, onCancel: () -> Unit) {
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
    progress: LibraryDownloadUi,
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
    map: LibraryMapUi,
    failureLabel: StringResource?,
    needsElevationCredentials: Boolean,
    busy: Boolean,
    savingFavourite: Boolean,
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
            LibraryMapHeader(map.header)
            LibraryMapStatus(map.state)
            LibraryMapMetadata(map.sizeBytes, map.hasElevationData)
            LibraryMapRecovery(map.state, map.missingTiles, failureLabel, needsElevationCredentials, busy, onRestore, onCredentials)
            LibraryMapActions(map.favourite, map.state == PackageState.READY, busy, savingFavourite, transferBusy, onFavourite, onDetails, onExport, onDelete)
        }
    }
}

@Composable
private fun LibraryMapStatus(state: PackageState) {
    Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun LibraryMapMetadata(sizeBytes: Long, hasElevationData: Boolean) {
    Text(stringResource(Res.string.library_size, megabytes(sizeBytes)))
    Text(stringResource(if (hasElevationData) Res.string.library_elevation_present else Res.string.library_no_elevation))
}

@Composable
private fun LibraryMapHeader(header: LibraryMapHeaderUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            val avatar = header.avatar
            Icon(
                painterResource(avatarIcon(avatar)),
                stringResource(avatarLabel(avatar)),
                Modifier.padding(14.dp).size(28.dp),
            )
        }
        Text(
            header.name,
            Modifier.weight(1f).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun LibraryMapRecovery(
    state: PackageState,
    missingTiles: Long,
    failureLabel: StringResource?,
    needsElevationCredentials: Boolean,
    busy: Boolean,
    onRestore: () -> Unit,
    onCredentials: () -> Unit,
) {
    if (state in setOf(PackageState.FAILED, PackageState.PAUSED)) {
        Text(
            if (missingTiles == 0L) stringResource(Res.string.library_assets_incomplete)
            else stringResource(Res.string.library_missing_warning, missingTiles),
            color = MaterialTheme.colorScheme.error,
        )
        failureLabel?.let { Text(stringResource(it)) }
        if (needsElevationCredentials) {
            TextButton(onClick = onCredentials) { Text(stringResource(Res.string.library_elevation_replace_key)) }
        }
        TextButton(
            onClick = onRestore,
            enabled = !busy
        ) { Text(stringResource(Res.string.library_restore)) }
    } else if (state in setOf(PackageState.CORRUPT, PackageState.MISSING)) {
        Text(stringResource(Res.string.library_damaged), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LibraryMapActions(
    favourite: Boolean,
    ready: Boolean,
    busy: Boolean,
    savingFavourite: Boolean,
    transferBusy: Boolean,
    onFavourite: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        IconButton(onClick = onFavourite, enabled = !busy && !savingFavourite) {
            Icon(
                painter = painterResource(if (favourite) Res.drawable.ic_star else Res.drawable.ic_star_border),
                contentDescription = stringResource(if (favourite) Res.string.library_unfavourite else Res.string.library_favourite)
            )
        }

        IconButton(onClick = onDetails) {
            Icon(
                painter = painterResource(Res.drawable.ic_info),
                contentDescription = stringResource(Res.string.library_details)
            )
        }

        if (ready) {
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
