/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.map.ui

import bes.max.bmaps.feature.viewer.annotations.presentation.CalloutLocationViewModel
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import dev.zacsweers.metrox.viewmodel.metroViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationEditorState
import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationEditorViewModel
import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationHit
import bes.max.bmaps.feature.viewer.annotations.presentation.annotationOverlays
import bes.max.bmaps.feature.viewer.annotations.presentation.renderData
import bes.max.bmaps.feature.viewer.annotations.ui.AnnotationClusterBadge
import bes.max.bmaps.feature.viewer.annotations.ui.MapObjectCallout
import bes.max.bmaps.feature.viewer.annotations.ui.icons.MarkerIcon
import bes.max.bmaps.feature.viewer.annotations.ui.icons.MovingMarkerIcon
import bes.max.bmaps.feature.viewer.map.presentation.ViewerState
import bes.max.bmaps.feature.viewer.map.presentation.ViewerViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.*
import bes.max.bmaps.core.mapengine.CurrentLocationOverlays
import bes.max.bmaps.core.mapengine.CURRENT_LOCATION_MARKER
import bes.max.bmaps.core.mapengine.CurrentLocationMarker
import bes.max.bmaps.core.mapengine.MapCameraSnapshot
import bes.max.bmaps.core.mapengine.MapMarker
import bes.max.bmaps.core.mapengine.MapPoint
import bes.max.bmaps.core.mapengine.MapPath
import bes.max.bmaps.core.mapengine.RasterMap
import bes.max.bmaps.core.mapengine.positionOf

@Composable
internal fun ViewerMapContent(
    model: ViewerViewModel,
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    annotationState: AnnotationEditorState,
    camera: MapCameraSnapshot?,
    calloutAnimated: Boolean,
    autoDismiss: Boolean,
    location: CurrentLocationOverlays = CurrentLocationOverlays(),
    onLocationGesture: () -> Unit = {},
    activityPaths: List<MapPath> = emptyList(),
    activityMarkers: List<MapMarker> = emptyList(),
    onActivityClick: ((String, MapPoint) -> Unit)? = null,
    onFollow: ((String) -> Unit)? = null,
    showCallout: Boolean = true,
    followLength: String? = null,
) {
    val markerGrowth = remember { Animatable(1f) }
    val placementVersion = annotationState.markerPlacementVersion
    var lastPlacementVersion by remember { mutableLongStateOf(placementVersion) }
    LaunchedEffect(placementVersion) {
        if (placementVersion != lastPlacementVersion) {
            markerGrowth.snapTo(0f)
            lastPlacementVersion = placementVersion
            markerGrowth.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    val renderData = annotationState.renderData()
    val fallback = remember(renderData, state.annotationPyramid) {
        annotationOverlays(annotationState, state.annotationPyramid)
    }
    val overlays = annotationState.presentation?.takeIf {
        it.data == renderData && it.display.pyramid == state.annotationPyramid &&
            it.display.camera?.session == camera?.session
    }?.overlays ?: fallback
    val handleOverlayClick: (String, MapPoint) -> Unit = { id, point ->
        if (!state.immersive) {
            if (id.startsWith("track:recorded:")) onActivityClick?.invoke(id, point)
            overlays.targets[id]?.let { annotations.clickOverlay(it, point, model.renderer.controller) }
        }
    }
    RasterMap(
        model.renderer,
        Modifier.fillMaxSize().then(viewerImmersiveGesture(state.immersive, model::toggleImmersive)),
        overlays.markers.filterNot {
            overlays.targets[it.id] is AnnotationHit.Vertex &&
                (state.immersive || annotationState.draft?.kind == AnnotationKind.MARKER)
        } + location.markers + activityMarkers,
        overlays.paths + location.paths + activityPaths,
        handleOverlayClick,
        onGestureStart = {
            onLocationGesture()
            model.cancelLocationMove()
            annotations.cancelExpansion()
            if (autoDismiss && !annotationState.hasMovingObject) annotations.dismissSelection()
        },
        onLongPress = {
            if (!state.immersive && state.manifest != null && state.error == null && !annotationState.busy && annotationState.draft == null) {
                annotations.panel(true)
            }
        }
    ) { marker ->
        if (marker.id == CURRENT_LOCATION_MARKER) CurrentLocationMarker(marker.label)
        else ViewerMarker(
            marker, overlays.targets[marker.id], annotationState, showSelection = !state.immersive,
            placementProgress = { if (lastPlacementVersion == placementVersion) markerGrowth.value else 0f },
        ) {
            handleOverlayClick(marker.id, marker.position)
        }
    }
    if (!state.immersive && showCallout) {
        ViewerObjectCallout(model, state, annotations, annotationState, camera, calloutAnimated, onFollow, followLength)
    }
}

@Composable
private fun ViewerMarker(
    marker: MapMarker,
    target: AnnotationHit?,
    annotationState: AnnotationEditorState,
    showSelection: Boolean,
    placementProgress: () -> Float,
    onClick: () -> Unit,
) {
    val cluster = target as? AnnotationHit.Cluster
    if (cluster != null) {
        AnnotationClusterBadge(cluster.ids.size, onClick = onClick)
    } else {
        val label = marker.label.ifBlank { stringResource(Res.string.annotations_place) }
        val objectId = (target as? AnnotationHit.Object)?.id
        val objectUi = objectId?.let { annotationState.objectUi(it) }
        val moving = objectUi?.moving == true
        val isSelected = showSelection && objectUi?.calloutOpen == true
        val moveHint = stringResource(Res.string.annotations_move_hint)
        Box(Modifier.size(width = 48.dp, height = if (objectId != null) 72.dp else 48.dp).clearAndSetSemantics {
            role = Role.Button
            contentDescription = label
            selected = isSelected
            if (moving) stateDescription = moveHint
            onClick { onClick(); true }
        }, contentAlignment = Alignment.BottomCenter) {
            if (objectId != null) {
                MovingMarkerIcon(
                    marker.icon, marker.color, moving, isSelected, Modifier.fillMaxSize(),
                    appearanceProgress = {
                        if (annotationState.draft?.kind == AnnotationKind.MARKER && annotationState.draft?.id == objectId)
                            placementProgress() else 1f
                    },
                )
            } else {
                MarkerIcon(marker.icon, marker.color, label, Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun ViewerObjectCallout(
    model: ViewerViewModel,
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    annotationState: AnnotationEditorState,
    camera: MapCameraSnapshot?,
    animated: Boolean,
    onFollow: ((String) -> Unit)?,
    followLength: String?,
) {
    val activeObject = annotationState.activeObject
    val activeObjectUi = annotationState.activeObjectUi
    val locationModel = metroViewModel<CalloutLocationViewModel>()
    val locationState by locationModel.state.collectAsStateWithLifecycle()
    val coordinate = if (activeObject?.kind == AnnotationKind.MARKER)
        activeObject.coordinates.firstOrNull() else activeObjectUi?.calloutCoordinate
    val manifest = state.manifest
    val storedElevation = activeObject?.let { value ->
        val index = if (value.kind == AnnotationKind.MARKER) 0 else coordinate?.let(value.coordinates::indexOf) ?: -1
        value.elevations.getOrNull(index)?.meters
    }
    LaunchedEffect(locationModel, manifest?.id, coordinate, manifest?.elevation, storedElevation) {
        locationModel.select(manifest?.id, coordinate, manifest?.elevation != null && storedElevation == null)
    }
    DisposableEffect(locationModel) {
        onDispose { locationModel.select(null, null, false) }
    }
    val locationMatches = locationState.packageId == manifest?.id && locationState.coordinate == coordinate
    MapObjectCallout(
        renderer = model.renderer,
        annotation = activeObject,
        position = activeObjectUi?.calloutCoordinate?.let { state.annotationPyramid?.positionOf(it) },
        camera = camera,
        displayCoordinate = locationState.displayCoordinate.takeIf { locationMatches },
        coordinateError = locationState.coordinateError.takeIf { locationMatches },
        coordinatesLoading = coordinate != null && (!locationMatches ||
            (locationState.displayCoordinate == null && locationState.coordinateError == null)),
        coordinateFormat = locationState.format,
        elevationMeters = storedElevation ?: locationState.elevationMeters.takeIf {
            locationMatches
        },
        animated = animated,
        busy = annotationState.busy,
        moving = activeObjectUi?.moving == true,
        error = annotationState.error,
        routeLength = followLength,
        onFollow = onFollow?.takeIf { activeObject?.kind == AnnotationKind.LINE }?.let { callback ->
            { activeObject?.id?.let(callback) }
        },
        onEdit = { activeObject?.let { annotations.edit(it.id) } },
        onMove = { activeObject?.let { annotations.moveObject(it.id) } },
        onDelete = { activeObject?.let { annotations.confirmDelete(it.id, true) } },
    )
}
