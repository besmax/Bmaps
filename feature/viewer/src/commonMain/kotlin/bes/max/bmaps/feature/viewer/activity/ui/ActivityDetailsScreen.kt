/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.sharing.rememberNativeDocuments
import bes.max.bmaps.core.ui.components.MapIconButton
import bes.max.bmaps.core.ui.components.MapIcons
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.feature.viewer.activity.presentation.TrackMapLayer
import bes.max.bmaps.feature.viewer.activity.presentation.TrackState
import bes.max.bmaps.feature.viewer.activity.presentation.*
import bes.max.bmaps.feature.viewer.map.presentation.ViewerViewModel
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailsScreen(activityId: String, onBack: () -> Unit, packageId: PackageId? = null) {
    val review = metroViewModel<ReviewViewModel>()
    val state by review.state.collectAsStateWithLifecycle()
    val map = metroViewModel<ViewerViewModel>()
    val mapState by map.state.collectAsStateWithLifecycle()
    val camera by map.renderer.camera.collectAsStateWithLifecycle()
    val documents = rememberNativeDocuments({ it.dispose() }, review::nativeFailure)
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activityId) { review.open(activityId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(review, lifecycle, documents) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            review.events.collect { event -> when (event) {
                is ActivityReviewEffect.Share -> documents.share(event.document)
            } }
        }
    }
    LaunchedEffect(state.activityId, state.loading, state.packageId, packageId) {
        if (!state.loading && state.error == null) {
            val source = packageId ?: state.packageId?.let(::PackageId)
            if (source == null) map.openActivityMap() else map.open(source)
        }
    }
    LaunchedEffect(mapState.error) {
        if (mapState.error != null && !mapState.vectorOnly) map.openActivityMap()
    }
    LaunchedEffect(state.loading, state.recorded, state.planned, camera?.session) {
        if (!state.loading && state.error == null && camera != null) {
            map.fitCoordinates((state.recorded + state.planned).flatMap { line ->
                line.points.map { GeographicCoordinate(it.latitude, it.longitude) }
            })
        }
    }
    LaunchedEffect(state.selectedPosition) {
        state.selectedPosition?.let { map.centerLocation(GeographicCoordinate(it.latitude, it.longitude)) }
    }
    DisposableEffect(map) { onDispose { map.cancelLocationMove() } }
    val paths = remember(state.recorded, state.planned, mapState.annotationPyramid) {
        TrackMapLayer.paths(TrackState(planned = state.planned, recorded = state.recorded), mapState.annotationPyramid)
            .map { it.copy(clickable = it.id.startsWith("track:recorded:")) }
    }
    val markerLabel = stringResource(Res.string.history_selected_position)
    val markers = remember(state.selectedPosition, mapState.annotationPyramid, markerLabel) {
        state.selectedPosition?.let { coordinate ->
            mapState.annotationPyramid?.positionOf(GeographicCoordinate(coordinate.latitude, coordinate.longitude))?.let {
                listOf(MapMarker("review:selected", it, "place", Color(0xFF00A878), markerLabel, MapMarkerAnchor.CENTER, 10f))
            }
        }.orEmpty()
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(state.name.ifBlank { stringResource(Res.string.history_details) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(painterResource(MapIcons.back), stringResource(Res.string.viewer_back)) } },
            actions = {
                val actionsLabel = stringResource(Res.string.track_actions)
                Box {
                    IconButton({ review.actionsMenu(true) }, enabled = !state.loading && !state.busy && state.activityId != null,
                        modifier = Modifier.semantics { contentDescription = actionsLabel }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                    DropdownMenu(state.actionsMenu, { review.actionsMenu(false) }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.viewer_retry)) }, onClick = { review.actionsMenu(false); review.retry() })
                        if (state.editable) DropdownMenuItem(text = { Text(stringResource(Res.string.history_rename)) }, onClick = { review.actionsMenu(false); review.renameDialog(true) })
                        if (state.exportable) DropdownMenuItem(text = { Text(stringResource(Res.string.history_export)) }, onClick = { review.actionsMenu(false); review.exportOptions(true) })
                        if (state.editable) DropdownMenuItem(text = { Text(stringResource(Res.string.history_delete)) }, onClick = { review.actionsMenu(false); review.deleteDialog(true) })
                    }
                }
            })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (state.error != null) Column(Modifier.padding(16.dp)) {
                Text(stringResource(requireNotNull(state.error)), color = MaterialTheme.colorScheme.error)
                TextButton(review::retry) { Text(stringResource(Res.string.viewer_retry)) }
            } else {
                val mapContent: @Composable (Modifier) -> Unit = { modifier ->
                    ActivityDetailsMap(map.renderer, paths, markers, mapState.vectorOnly, mapState.error, mapState.tileWarning,
                        { id, point -> review.selectMap(id, point, mapState.annotationPyramid) }, map::cancelLocationMove,
                        { map.renderer.controller.zoomIn() }, { map.renderer.controller.zoomOut() },
                        { map.openActivityMap(force = true) }, modifier)
                }
                if (maxWidth > maxHeight && maxWidth >= 700.dp) Row(Modifier.fillMaxSize()) {
                    mapContent(Modifier.weight(1f).fillMaxHeight())
                    ActivityDetailsContent(state, review::series, review::selectTime, review::eventList, review::exportOptions,
                        Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp))
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    mapContent(Modifier.fillMaxWidth().height(300.dp))
                    ActivityDetailsContent(state, review::series, review::selectTime, review::eventList, review::exportOptions,
                        Modifier.fillMaxWidth().padding(16.dp))
                }
            }
        }
    }
    ActivityDetailsDialogs(state, review::draftName, review::rename, { review.renameDialog(false) }, review::delete, { review.deleteDialog(false) })
    ReviewDialogs(state, review::selectTime, review::eventList, review::export, { review.exportOptions(false) })
}

@Composable
private fun ActivityDetailsMap(renderer: RasterMapRenderer, paths: List<MapPath>, markers: List<MapMarker>,
    vectorOnly: Boolean, error: org.jetbrains.compose.resources.StringResource?, warning: org.jetbrains.compose.resources.StringResource?,
    onSelect: (String, MapPoint) -> Unit, onPan: () -> Unit, onZoomIn: () -> Unit, onZoomOut: () -> Unit,
    onFullPath: () -> Unit, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        RasterMap(renderer, Modifier.fillMaxSize(), markers, paths, onSelect, onPan = onPan) { marker ->
            CurrentLocationMarker(marker.label)
        }
        Column(Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 64.dp)) {
            Text(stringResource(Res.string.history_map_legend), style = MaterialTheme.typography.labelSmall)
            if (vectorOnly) Text(stringResource(Res.string.history_neutral_map), style = MaterialTheme.typography.labelSmall)
            (error ?: warning)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onFullPath) { Text(stringResource(Res.string.history_show_full_path)) }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapIconButton(onZoomIn, MapIcons.zoomIn, stringResource(Res.string.viewer_zoom_in))
            MapIconButton(onZoomOut, MapIcons.zoomOut, stringResource(Res.string.viewer_zoom_out))
        }
    }
}

@Composable
private fun ActivityDetailsContent(state: ReviewState, onSeries: (Int) -> Unit, onSelect: (Long, String?) -> Unit,
    onEvents: (Boolean) -> Unit, onExport: (Boolean) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.name.ifBlank { stringResource(Res.string.track_unnamed) }, style = MaterialTheme.typography.headlineSmall)
        state.profile?.let { Text(stringResource(it)) }
        Text(state.date, style = MaterialTheme.typography.bodySmall)
        state.status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        ReviewStatistics(state.distance, state.elapsed, state.recording, state.moving, state.interrupted, state.estimated)
        Text(stringResource(Res.string.history_average_speed, state.averageSpeed ?: "—"))
        Text(stringResource(Res.string.history_maximum_speed, state.maximumSpeed ?: "—"))
        ActivityHeartRateList(state.series)
        Text(stringResource(Res.string.history_telemetry), style = MaterialTheme.typography.titleLarge)
        ReviewTelemetry(state, onSeries, onSelect, onEvents, onExport)
    }
}

@Composable
private fun ActivityDetailsDialogs(state: ReviewState, onDraft: (String) -> Unit, onRename: () -> Unit,
    onCancelRename: () -> Unit, onDelete: () -> Unit, onCancelDelete: () -> Unit) {
    if (state.renaming) AlertDialog(onDismissRequest = { if (!state.busy) onCancelRename() },
        title = { Text(stringResource(Res.string.history_rename)) }, text = {
            Column {
                OutlinedTextField(state.draftName, onDraft, singleLine = true, enabled = !state.busy)
                state.actionError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onRename, enabled = !state.busy) { Text(stringResource(Res.string.history_save)) } },
        dismissButton = { TextButton(onCancelRename, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } })
    if (state.deleting) AlertDialog(onDismissRequest = { if (!state.busy) onCancelDelete() },
        title = { Text(stringResource(Res.string.history_delete)) }, text = {
            Column {
                Text(stringResource(Res.string.history_delete_activity_message))
                state.actionError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onDelete, enabled = !state.busy) { Text(stringResource(Res.string.history_delete)) } },
        dismissButton = { TextButton(onCancelDelete, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } })
}

@Composable
private fun ActivityHeartRateList(series: ImmutableList<ReviewSeries>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        series.forEach { item -> if (item.metric == Res.string.history_heart_rate) ActivityHeartRateItem(item) }
    }
}

@Composable
private fun ActivityHeartRateItem(item: ReviewSeries) {
    Column {
        Text(stringResource(Res.string.history_heart_rate), style = MaterialTheme.typography.titleSmall)
        Text(item.source, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.history_sample_statistics, item.minimum, item.mean, item.maximum, item.count.toString()),
            style = MaterialTheme.typography.bodySmall)
        Text(item.unit, style = MaterialTheme.typography.labelSmall)
    }
}
