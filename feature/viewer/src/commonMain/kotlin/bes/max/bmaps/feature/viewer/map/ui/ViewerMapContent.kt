/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.map.ui

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
) {
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
            overlays.targets[id]?.let { annotations.clickOverlay(it, point, model.renderer.controller) }
        }
    }
    RasterMap(
        model.renderer,
        Modifier.fillMaxSize().then(viewerImmersiveGesture(state.immersive, model::toggleImmersive)),
        overlays.markers.filterNot { state.immersive && overlays.targets[it.id] is AnnotationHit.Vertex } + location.markers,
        overlays.paths + location.paths,
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
        else ViewerMarker(marker, overlays.targets[marker.id], annotationState) {
            handleOverlayClick(marker.id, marker.position)
        }
    }
    if (!state.immersive) {
        ViewerObjectCallout(model, state, annotations, annotationState, camera, calloutAnimated)
    }
}

@Composable
private fun ViewerMarker(
    marker: MapMarker,
    target: AnnotationHit?,
    annotationState: AnnotationEditorState,
    onClick: () -> Unit,
) {
    val cluster = target as? AnnotationHit.Cluster
    if (cluster != null) {
        AnnotationClusterBadge(cluster.ids.size, onClick = onClick)
    } else {
        val label = marker.label.ifBlank { stringResource(Res.string.annotations_place) }
        val objectId = (target as? AnnotationHit.Object)?.id
        val moving = objectId?.let { annotationState.objectUi(it)?.moving } == true
        val moveHint = stringResource(Res.string.annotations_move_hint)
        Box(Modifier.size(width = 48.dp, height = if (objectId != null) 72.dp else 48.dp).clearAndSetSemantics {
            role = Role.Button
            contentDescription = label
            if (moving) stateDescription = moveHint
            onClick { onClick(); true }
        }, contentAlignment = Alignment.BottomCenter) {
            if (objectId != null) {
                MovingMarkerIcon(marker.icon, marker.color, moving, Modifier.fillMaxSize())
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
) {
    val activeObject = annotationState.activeObject
    val activeObjectUi = annotationState.activeObjectUi
    MapObjectCallout(
        renderer = model.renderer,
        annotation = activeObject,
        position = activeObjectUi?.calloutCoordinate?.let { state.annotationPyramid?.positionOf(it) },
        camera = camera,
        animated = animated,
        busy = annotationState.busy,
        moving = activeObjectUi?.moving == true,
        error = annotationState.error,
        onEdit = { activeObject?.let { annotations.edit(it.id) } },
        onMove = { activeObject?.let { annotations.moveObject(it.id) } },
        onDelete = { activeObject?.let { annotations.confirmDelete(it.id, true) } },
    )
}
