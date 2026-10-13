/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.map.ui

import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationEditorViewModel
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
import bes.max.bmaps.feature.viewer.history.presentation.ReviewViewModel
import bes.max.bmaps.feature.viewer.history.presentation.HistoryEvent
import bes.max.bmaps.feature.viewer.history.ui.ReviewPanel
import bes.max.bmaps.feature.viewer.history.ui.ReviewDialogs
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import bes.max.bmaps.core.sharing.rememberNativeDocuments
import bes.max.bmaps.feature.viewer.activity.presentation.*
import bes.max.bmaps.feature.viewer.activity.ui.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.core.location.*
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.mapengine.WebMercator
import bes.max.bmaps.core.mapengine.currentLocationOverlays
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.mapengine.positionOf
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
    initialRouteId: String? = null,
    onHistory: () -> Unit = {},
    onReview: (String) -> Unit = {},
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
    val review = metroViewModel<ReviewViewModel>()
    val reviewState by review.state.collectAsStateWithLifecycle()
    val reviewDocuments = rememberNativeDocuments({ it.dispose() }, review::nativeFailure)
    LaunchedEffect(activityId) { activityId?.let(review::open) }
    LaunchedEffect(initialRouteId) { initialRouteId?.let(track::selectRoute) }
    val documents = rememberNativeDocuments(track::importGpx, track::nativeFailure)
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val trackActions = remember(track, annotations, documents, model, onHistory) {
        TrackActions(track::toggle, track::collapse, track::prepareFree, track::chooseRoutes,
            track::selectRoute, track::loadMoreRoutes, documents::pick,
            track::profile, track::reverse, track::segment, track::edge,
            track::start, track::pauseResume, track::finish, track::confirmFinish, track::menu,
            track::returnAlongRecording, track::followPlanned, track::correct, track::applyCorrection, track::toggleAlerts,
            track::followPosition, track::inspect, {
                track.selectionCoordinate()?.let { model.centerLocation(GeographicCoordinate(it.latitude, it.longitude)) }
            }, track::markControlPoint, track::recover, onHistory, track::details)
    }
    LaunchedEffect(track, haptics, lifecycle, onReview) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            track.events.collectLatest { event ->
                when (event) {
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
    LaunchedEffect(trackState.following, trackState.positionId, trackState.position) {
        if (activityId == null && trackState.following) trackState.position?.let {
            model.followActivityLocation(GeographicCoordinate(it.latitude, it.longitude))
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
                is HistoryEvent.Share -> reviewDocuments.share(event.document)
                is HistoryEvent.Message -> snackbar.showSnackbar(getString(event.message))
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
    val selectionLabel = stringResource(Res.string.history_selected_position)
    val reviewMarkers = remember(reviewState.selectedPosition, state.annotationPyramid, selectionLabel) {
        reviewState.selectedPosition?.let { coordinate ->
            state.annotationPyramid?.positionOf(GeographicCoordinate(coordinate.latitude, coordinate.longitude))?.let { point ->
                listOf(MapMarker("history:selected", point, "place", Color(0xFFFFB300), selectionLabel, zIndex = 10f))
            }
        }.orEmpty()
    }
    val density = LocalDensity.current
    var bottomHeight by remember { mutableStateOf(0.dp) }
    val location = metroViewModel<LocationViewModel>()
    val locationState by location.state.collectAsStateWithLifecycle()
    val locationAccess = LocationTracking(location, onStopped = model::cancelLocationMove)
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

    ViewerScreenEffects(packageId, model, position, annotations, state.annotationPyramid, camera, snackbar)

    LaunchedEffect(state.immersive) {
        if (state.immersive) snackbar.currentSnackbarData?.dismiss()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        ViewerMapContent(model, state, annotations, annotationState, camera, calloutAnimated, autoDismiss,
            locationOverlays, { location.cancelCenter(); track.inspect() },
            activityPaths = if (activityId == null) trackPaths else reviewPaths,
            activityMarkers = if (activityId == null) emptyList() else reviewMarkers,
            onActivityClick = { id, point -> review.selectMap(id, point, state.annotationPyramid) },
            onFollow = if (activityId == null && trackState.metrics == null && annotationState.draft == null) { id ->
                annotationState.source.firstOrNull { it.id == id }?.let { annotation ->
                    track.followAnnotation(annotation)
                    annotations.dismissSelection()
                }
            } else null,
            showCallout = activityId == null && !trackState.expanded,
            followLength = trackState.selectedAnnotationLength.takeIf { trackState.selectedAnnotationId == annotationState.activeObjectId },
        )
        if (!state.immersive) {
            if (state.vectorOnly) Text(stringResource(Res.string.history_neutral_map),
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(start = 80.dp, end = 80.dp, top = 16.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (positionState.coordinate != null && state.error == null) {
                MapCrosshair(Modifier.align(Alignment.Center))
            }
            ViewerBottomContent(
                state, annotations, annotationState, positionState,
                Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomHeight = with(density) { it.height.toDp() } },
                singleLine = maxWidth > maxHeight,
                showPosition = activityId == null && !trackState.expanded,
            )
            if (activityId != null) ReviewPanel(reviewState, review::panel, review::series, review::selectTime,
                review::eventList, review::exportOptions, review::retry,
                Modifier.align(if (maxWidth > maxHeight && reviewState.panelExpanded) Alignment.CenterStart else Alignment.BottomStart)
                    .safeDrawingPadding().padding(start = 16.dp, end = 80.dp, bottom = 16.dp)
                    .heightIn(max = maxHeight * 0.6f))
            if (activityId == null && (annotationState.draft == null || trackState.metrics != null)) TrackControls(
                trackState, trackActions,
                Modifier.align(if (maxWidth > maxHeight && trackState.expanded) Alignment.CenterStart else Alignment.BottomStart)
                    .safeDrawingPadding().padding(start = 16.dp, end = 80.dp,
                        bottom = if (trackState.expanded) 16.dp else bottomHeight + 8.dp)
                    .widthIn(max = (maxWidth - 112.dp).coerceAtLeast(120.dp))
                    .heightIn(max = (maxHeight * 0.6f).coerceAtLeast(48.dp)),
                outsideCoverage = trackState.position?.let {
                    state.annotationPyramid?.positionOf(GeographicCoordinate(it.latitude, it.longitude)) == null
                } == true,
            )
            ViewerMapControls(model, state, annotations, onBack,
                landscape = maxWidth > maxHeight,
                locationUnavailable = locationState.status != LocationStatus.ACTIVE,
                locationDescription = if (locationState.status != LocationStatus.ACTIVE)
                    "$locationLabel: ${stringResource(locationMessage(locationState.status))}" else locationLabel,
                onLocation = {
                    when (locationState.status) {
                        LocationStatus.PERMISSION_DENIED, LocationStatus.PRECISE_PERMISSION_REQUIRED -> locationAccess.requestPermission(openSettingsIfDenied = true)
                        LocationStatus.DISABLED -> locationAccess.openSettings()
                        else -> { track.followPosition(); location.center() }
                    }
                },
                onInteraction = { track.inspect(); location.cancelCenter(); model.cancelLocationMove() },
            )
            state.error?.let { error ->
                ViewerError(
                    error = error,
                    onRetry = {
                        model.retry()
                        packageId?.let { position.open(it, model.renderer.camera, retry = true) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
    if (!state.immersive && activityId == null) TrackDialogs(trackState, trackActions)
    if (!state.immersive && activityId != null) ReviewDialogs(reviewState, review::selectTime, review::eventList, review::export, { review.exportOptions(false) })
    if (!state.immersive) ViewerDialogs(model, state, annotations, annotationState, reliefProgress, relief::cancel)
}
