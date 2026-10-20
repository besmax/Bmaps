/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.map.ui

import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationEditorViewModel
import bes.max.bmaps.feature.viewer.annotations.presentation.MapObjectsViewModel
import bes.max.bmaps.feature.viewer.annotations.presentation.MapObjectsEvent
import bes.max.bmaps.feature.viewer.annotations.ui.MapObjectsActions
import bes.max.bmaps.feature.viewer.annotations.ui.MapObjectsImportDialog
import bes.max.bmaps.feature.viewer.annotations.ui.MapRouteActionDialogs
import bes.max.bmaps.feature.viewer.elevation.presentation.ElevationProgressViewModel
import bes.max.bmaps.feature.viewer.location.ui.locationMessage
import bes.max.bmaps.feature.viewer.position.presentation.MapPositionViewModel
import bes.max.bmaps.feature.viewer.position.ui.MapCrosshair
import bes.max.bmaps.feature.viewer.map.presentation.ViewerViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import bes.max.bmaps.core.mapengine.MapMarker
import bes.max.bmaps.feature.viewer.activity.presentation.ReviewViewModel
import bes.max.bmaps.feature.viewer.activity.presentation.ActivityReviewEffect
import bes.max.bmaps.feature.viewer.activity.ui.ReviewPanel
import bes.max.bmaps.feature.viewer.activity.ui.ReviewDialogs
import bes.max.bmaps.core.ui.components.MapIconButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import bes.max.bmaps.core.sharing.rememberNativeDocuments
import bes.max.bmaps.feature.viewer.activity.presentation.*
import bes.max.bmaps.feature.viewer.activity.ui.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import bes.max.bmaps.core.location.*
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.mapengine.WebMercator
import bes.max.bmaps.core.mapengine.currentLocationOverlays
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.mapengine.positionOf
import bes.max.bmaps.core.mapengine.coordinateAt
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun ViewerScreen(
    packageId: PackageId?,
    onBack: () -> Unit,
    calloutAnimated: Boolean = true,
    autoDismiss: Boolean = true,
    activityId: String? = null,
    onHistory: () -> Unit = {},
    onReview: (String) -> Unit = {},
    trackScreen: Boolean = false,
    onTrack: (String?) -> Unit = {},
) {
    val model = metroViewModel<ViewerViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val relief = metroViewModel<ElevationProgressViewModel>()
    val reliefProgress by relief.state.collectAsStateWithLifecycle()
    LaunchedEffect(packageId) { packageId?.let(relief::open) }
    LaunchedEffect(reliefProgress.job?.token, reliefProgress.job?.state, state.manifest) {
        val job = reliefProgress.job
        if (job?.state == bes.max.bmaps.domain.mapbuilder.ElevationGenerationState.COMPLETED &&
            state.manifest != null && state.manifest?.layers?.none { it.tiles.relativePath == "layers/elevation-relief-${job.token}.mbtiles" } == true) model.refreshRelief()
    }
    val position = metroViewModel<MapPositionViewModel>()
    val positionState by position.state.collectAsStateWithLifecycle()
    val annotations = metroViewModel<AnnotationEditorViewModel>()
    val annotationState by annotations.state.collectAsStateWithLifecycle()
    val camera by model.renderer.camera.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val track = metroViewModel<TrackViewModel>()
    val trackState by track.state.collectAsStateWithLifecycle()
    val resolvedPackageId = packageId ?: trackState.packageId?.takeIf { trackScreen }?.let(::PackageId)
    LaunchedEffect(resolvedPackageId) { track.context(resolvedPackageId?.value) }
    LaunchedEffect(trackScreen) { if (trackScreen) track.enterTrackScreen() }
    val review = metroViewModel<ReviewViewModel>()
    val reviewState by review.state.collectAsStateWithLifecycle()
    val reviewDocuments = rememberNativeDocuments({ it.dispose() }, review::nativeFailure)
    LaunchedEffect(activityId) { activityId?.let(review::open) }
    val objects = metroViewModel<MapObjectsViewModel>()
    val objectState by objects.state.collectAsStateWithLifecycle()
    LaunchedEffect(resolvedPackageId) { objects.context(resolvedPackageId); objects.refresh() }
    LaunchedEffect(annotationState.panel) { if (annotationState.panel) objects.refresh() }
    val documents = rememberNativeDocuments(objects::pick, objects::nativeFailure)
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val trackActions = remember(track, annotations, documents, model, onHistory) {
        TrackActions(track::toggle, track::collapse, track::prepareFree, { track.collapse(); annotations.panel(true) },
            track::selectRoute, track::loadMoreRoutes, documents::pick,
            track::profile, track::reverse, track::segment, track::edge,
            track::start, track::pauseResume, track::finish, track::confirmFinish, track::menu,
            track::returnAlongRecording, track::followPlanned, track::correct, track::applyCorrection, track::toggleAlerts,
            track::followPosition, track::inspect, { model.cancelLocationMove(); track.beginStartPick() }, track::markControlPoint, track::recover, onHistory, track::details)
    }
    val canFollow = activityId == null && trackState.metrics == null && !trackState.busy && annotationState.draft == null
    val objectActions = MapObjectsActions({ annotations.panel(false); documents.pick() },
        { annotations.panel(false); objects.beginImport() }, objects::text,
        objects::prepareText, objects::confirmImport, objects::closeImport, objects::cancel,
        objects::more, objects::refresh, objects::select,
        if (canFollow) ({ id ->
            annotations.panel(false); annotations.dismissSelection(); objects.callout(false); track.selectRoute(id)
        }) else null,
        if (canFollow) ({ id ->
            annotationState.source.firstOrNull { it.id == id }?.let { route ->
                annotations.panel(false); objects.clearSelection(); track.followAnnotation(route); annotations.dismissSelection()
            }
        }) else null)
    LaunchedEffect(objects, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            objects.events.collect { event ->
                when (event) {
                    MapObjectsEvent.Imported -> { annotations.retry(); annotations.panel(true); track.refreshRoutes() }
                    is MapObjectsEvent.Selected -> { annotations.panel(false); annotations.dismissSelection() }
                }
            }
        }
    }
    LaunchedEffect(objectState.selection?.id, objectState.fitSelection, camera?.session) {
        if (objectState.fitSelection) objectState.selection?.lines?.let { lines ->
            if (model.fitCoordinates(lines.flatMap { line -> line.points.map { GeographicCoordinate(it.latitude, it.longitude) } })) objects.fitted()
        }
    }
    LaunchedEffect(annotationState.activeObjectId) {
        if (annotationState.activeObjectId != null) objects.clearSelection()
    }
    LaunchedEffect(track, haptics, lifecycle, onReview, onTrack) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            track.events.collectLatest { event ->
                when (event) {
                    is TrackEvent.Started -> if (!trackScreen) onTrack(event.packageId)
                    is TrackEvent.Completed -> onReview(event.activityId)
                    is TrackEvent.Message -> {
                        if (event.haptic) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!model.state.value.immersive) {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(getString(event.text))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(track, state.annotationPyramid) { track.mapCoverage(state.annotationPyramid) }
    LaunchedEffect(annotationState.activeObject) {
        track.inspectAnnotation(annotationState.activeObject)
    }
    LaunchedEffect(annotationState.draft?.id, annotationState.panel) {
        if (annotationState.draft != null || annotationState.panel) track.collapse()
    }
    val trackPaths = remember(trackState.planned, trackState.guidance, trackState.recorded, trackState.preview,
        trackState.preparing, trackState.correcting, trackState.selectedSection, trackState.metrics?.returning, state.annotationPyramid) {
        TrackMapLayer.paths(trackState, state.annotationPyramid)
    }
    LaunchedEffect(review, lifecycle, reviewDocuments) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            review.events.collect { event -> when (event) {
                is ActivityReviewEffect.Share -> reviewDocuments.share(event.document)
            } }
        }
    }
    LaunchedEffect(trackState.preview, trackState.preparing, trackState.recorded, camera?.session, activityId) {
        if (activityId == null) model.focusActivityPreview(if (trackState.preparing) trackState.preview else trackState.recorded)
    }
    LaunchedEffect(reviewState.loading, reviewState.fitPending, camera?.session) {
        if (activityId != null && !reviewState.loading && reviewState.fitPending && camera != null) {
            val points = reviewState.positions.map { GeographicCoordinate(it.coordinate.latitude, it.coordinate.longitude) } +
                reviewState.planned.flatMap { line -> line.points.map { GeographicCoordinate(it.latitude, it.longitude) } }
            model.fitCoordinates(points)
            review.fitted()
        }
    }
    LaunchedEffect(reviewState.selectedPosition) {
        reviewState.selectedPosition?.let { model.centerLocation(GeographicCoordinate(it.latitude, it.longitude)) }
    }
    val reviewPaths = remember(reviewState.planned, reviewState.recorded, state.annotationPyramid) {
        TrackMapLayer.paths(TrackState(planned = reviewState.planned, recorded = reviewState.recorded), state.annotationPyramid)
            .map { it.copy(clickable = it.id.startsWith("track:recorded:")) }
    }
    val objectRoutesVisible = annotationState.layers.any { it.kind == AnnotationKind.LINE && it.visible }
    val objectPaths = remember(objectState.mapLines, objectState.selection, state.annotationPyramid) {
        val lines = (objectState.mapLines + objectState.selection?.lines.orEmpty()).distinctBy { it.id }
        TrackMapLayer.objectPaths(lines, state.annotationPyramid).map { path ->
            if (objectState.selection?.id?.let { path.id.startsWith("track:object-route:$it~") } == true)
                path.copy(color = Color(0xFFFFB300), zIndex = 6f) else path
        }
    }
    val selectionLabel = stringResource(Res.string.history_selected_position)
    val reviewMarkers = remember(reviewState.selectedPosition, state.annotationPyramid, selectionLabel) {
        reviewState.selectedPosition?.let { coordinate ->
            state.annotationPyramid?.positionOf(GeographicCoordinate(coordinate.latitude, coordinate.longitude))?.let { point ->
                listOf(MapMarker("history:selected", point, "place", Color(0xFFFFB300), selectionLabel, zIndex = 10f))
            }
        }.orEmpty()
    }
    val location = metroViewModel<LocationViewModel>()
    val locationState by location.state.collectAsStateWithLifecycle()
    val locationAccess = LocationTracking(location, onStopped = model::cancelLocationMove)
    val usableLocation = locationState.status == LocationStatus.ACTIVE && !locationState.sourceConflict && !locationState.deliveryGap
    LaunchedEffect(locationState, trackScreen, trackState.following, camera?.session) {
        track.mapLocation(locationState.fix, usableLocation)
        if (trackScreen && !usableLocation) model.cancelLocationMove()
        if (trackScreen && trackState.following && usableLocation) locationState.fix?.let {
            model.followActivityLocation(GeographicCoordinate(it.latitude, it.longitude))
        }
    }
    val locationLabel = stringResource(Res.string.my_location)
    val coordinate = locationState.fix?.let { GeographicCoordinate(it.latitude, it.longitude) }?.takeIf { fix ->
        state.vectorOnly || state.manifest?.layers?.firstOrNull()?.bounds?.let { bounds ->
            WebMercator.splitBounds(bounds).orEmpty().any {
                fix.latitude in it.south..it.north && fix.longitude in it.west..it.east
            }
        } == true
    }
    val locationOverlays = remember(coordinate, locationState.fix?.accuracyMeters, state.annotationPyramid, locationLabel) {
        currentLocationOverlays(coordinate, locationState.fix?.accuracyMeters, state.annotationPyramid, locationLabel)
    }
    LaunchedEffect(location, lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            location.events.collect { event ->
                when (event) {
                    is LocationEvent.Center -> location.takeCenter(event)?.let {
                        model.centerLocation(GeographicCoordinate(it.latitude, it.longitude))
                    }
                    is LocationEvent.Message -> Unit
                }
            }
        }
    }

    val pickDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val pickPoint: (bes.max.bmaps.core.mapengine.MapPoint) -> Unit = { point ->
        camera?.let { track.pickStart(point, it, pickDensity) }
    }
    ViewerScreenEffects(resolvedPackageId, model, position, annotations, state.annotationPyramid, camera, snackbar) { point ->
        if (trackState.pickingStart) { pickPoint(point); true } else false
    }
    val startLabel = stringResource(Res.string.track_start_position)
    val startMarkers = remember(trackState.startCoordinate, trackState.pickingStart, state.annotationPyramid, startLabel) {
        if (!trackState.pickingStart) emptyList() else trackState.startCoordinate?.let { coordinate ->
            state.annotationPyramid?.positionOf(GeographicCoordinate(coordinate.latitude, coordinate.longitude))?.let { point ->
                listOf(MapMarker("track:start", point, "place", Color(0xFFFFB300), startLabel, anchor = bes.max.bmaps.core.mapengine.MapMarkerAnchor.CENTER, zIndex = 12f))
            }
        }.orEmpty()
    }

    LaunchedEffect(state.immersive) {
        if (state.immersive) snackbar.currentSnackbarData?.dismiss()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        ViewerMapContent(model, state, annotations, annotationState, camera, calloutAnimated, autoDismiss,
            locationOverlays, { location.cancelCenter(); if (!trackScreen) track.inspect(); if (autoDismiss) objects.callout(false) },
            activityPaths = if (activityId == null) trackPaths + objectPaths.takeIf { !trackState.preparing && trackState.metrics == null && objectRoutesVisible }.orEmpty() else reviewPaths,
            activityMarkers = if (activityId == null) startMarkers else reviewMarkers,
            onStartPick = pickPoint.takeIf { trackState.pickingStart }, startBearing = trackState.startBearing,
            onActivityClick = { id, point -> review.selectMap(id, point, state.annotationPyramid) },
            onObjectRouteClick = { id, point -> objects.mapSelect(id, state.annotationPyramid?.coordinateAt(point)); annotations.dismissSelection() },
            navigatorMarker = trackScreen, markerCourse = trackState.markerCourse,
            onMapPan = { track.inspect(); model.cancelLocationMove() },
            onFollow = if (activityId == null && trackState.metrics == null && annotationState.draft == null) { id ->
                annotationState.source.firstOrNull { it.id == id }?.let { annotation ->
                    track.followAnnotation(annotation)
                    annotations.dismissSelection()
                }
            } else null,
            showCallout = activityId == null && !trackState.expanded && !trackState.pickingStart,
            followLength = trackState.selectedAnnotationLength.takeIf { trackState.selectedAnnotationId == annotationState.activeObjectId },
            routeSelection = objectState.selection.takeIf { objectState.callout && objectRoutesVisible && annotationState.draft == null },
            routeBusy = objectState.busy,
            onRouteFollow = objectActions.follow,
            onRouteEdit = objects::editRoute,
            onRouteDelete = objects::confirmRouteDelete,
        )
        MapObjectsImportDialog(objectState, objectActions)
        MapRouteActionDialogs(objectState, objects::routeName, objects::routeDescription, objects::saveRoute,
            objects::deleteRoute, objects::dismissRouteAction)
        if (!state.immersive) {
            if (state.vectorOnly) Text(stringResource(Res.string.history_neutral_map),
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(start = 80.dp, end = 80.dp, top = 16.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (!trackScreen && !trackState.pickingStart && positionState.coordinate != null && state.error == null) {
                MapCrosshair(Modifier.align(Alignment.Center))
            }
            if (!trackState.pickingStart) ViewerBottomContent(
                state, annotations, annotationState, positionState,
                Modifier.align(Alignment.BottomCenter),
                singleLine = maxWidth > maxHeight,
                showPosition = !trackScreen && activityId == null,
                objects = objectState,
                objectActions = objectActions,
            )
            if (activityId != null) ReviewPanel(reviewState, review::panel, review::series, review::selectTime,
                review::eventList, review::exportOptions, review::retry,
                Modifier.align(if (maxWidth > maxHeight && reviewState.panelExpanded) Alignment.CenterStart else Alignment.BottomStart)
                    .safeDrawingPadding().padding(start = 16.dp, end = 80.dp, bottom = 16.dp)
                    .heightIn(max = maxHeight * 0.6f))
            if (activityId == null && trackScreen && trackState.metrics != null && !trackState.correcting) TrackRecordingPanel(
                trackState, trackActions, track::panel,
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 16.dp, end = 80.dp, top = 12.dp, bottom = 12.dp)
                    .heightIn(max = maxHeight * 0.45f),
                mapStatus = when {
                    locationState.sourceConflict -> stringResource(Res.string.track_gps_conflict)
                    locationState.deliveryGap -> stringResource(Res.string.track_gps_waiting)
                    !usableLocation -> stringResource(locationMessage(locationState.status))
                    locationState.fix?.let { state.annotationPyramid?.positionOf(GeographicCoordinate(it.latitude, it.longitude)) == null } == true -> stringResource(Res.string.track_outside_coverage)
                    else -> null
                },
            )
            if (!trackState.pickingStart && activityId == null && annotationState.draft == null && (!trackScreen || trackState.correcting)) {
                MapIconButton(
                    onClick = {
                        when {
                            trackState.correcting || trackState.preparing -> track.toggle()
                            trackState.metrics != null -> onTrack(trackState.packageId)
                            else -> track.prepareFree()
                        }
                    },
                    iconResId = Res.drawable.ic_track,
                    contentDescription = stringResource(if (trackState.metrics != null) Res.string.track_open_active else Res.string.track_start_settings),
                    enabled = !trackState.busy && state.error == null && state.manifest != null,
                    iconTint = if (trackState.metrics != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp),
                )
            }
            if (trackState.pickingStart) TrackStartPicker(trackState,
                { track.closeStartPick(true) }, { track.closeStartPick(false) }, track::nextStartPick,
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 16.dp, end = 80.dp, bottom = 16.dp)
                    .heightIn(max = maxHeight * 0.5f))
            ViewerMapControls(model, state, annotations, { if (trackState.pickingStart) track.closeStartPick(false) else onBack() },
                landscape = maxWidth > maxHeight,
                locationUnavailable = locationState.status != LocationStatus.ACTIVE,
                locationDescription = if (locationState.status != LocationStatus.ACTIVE)
                    "$locationLabel: ${stringResource(locationMessage(locationState.status))}" else locationLabel,
                onLocation = {
                    when (locationState.status) {
                        LocationStatus.PERMISSION_DENIED, LocationStatus.PRECISE_PERMISSION_REQUIRED -> locationAccess.requestPermission(openSettingsIfDenied = true)
                        LocationStatus.DISABLED -> locationAccess.openSettings()
                        else -> { if (!trackState.pickingStart) track.followPosition()
                            if (trackScreen && usableLocation) locationState.fix?.let {
                                model.followActivityLocation(GeographicCoordinate(it.latitude, it.longitude), force = true)
                            } else location.center()
                        }
                    }
                },
                onInteraction = { if (!trackScreen) track.inspect(); location.cancelCenter(); model.cancelLocationMove() },
            )
            state.error?.let { error ->
                ViewerError(
                    error = error,
                    onRetry = {
                        model.retry()
                        resolvedPackageId?.let { position.open(it, model.renderer.camera, retry = true) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
    if (!state.immersive && activityId == null) {
        TrackPreparationDialog(trackState, trackActions)
        TrackDialogs(trackState, trackActions)
    }
    if (!state.immersive && activityId != null) ReviewDialogs(reviewState, review::selectTime, review::eventList, review::export, { review.exportOptions(false) })
    if (!state.immersive) ViewerDialogs(model, state, annotations, annotationState, reliefProgress, relief::cancel)
}
