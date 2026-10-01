/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.ui.graphics.Color
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.domain.mapbuilder.Annotation

internal data class AnnotationObjectUiState(
    val calloutOpen: Boolean = false,
    val calloutCoordinate: GeographicCoordinate? = null,
    val moving: Boolean = false,
    val deleteConfirmation: Boolean = false,
)

internal data class AnnotationLayerState(
    val kind: AnnotationKind,
    val visible: Boolean = true,
    val objectStates: Map<String, AnnotationObjectUiState> = emptyMap(),
)

internal sealed class AnnotationLayer(val kind: AnnotationKind) {
    var state = AnnotationLayerState(kind)
        private set
    fun visibility(visible: Boolean) {
        state = state.copy(visible = visible, objectStates = if (visible) state.objectStates else emptyMap())
    }
    fun openCallout(id: String, coordinate: GeographicCoordinate) {
        if (state.visible) state = state.copy(objectStates = state.objectStates +
            (id to AnnotationObjectUiState(calloutOpen = true, calloutCoordinate = coordinate)))
    }
    fun clearInteractions() { state = state.copy(objectStates = emptyMap()) }
    fun retainObjects(values: List<Annotation>) {
        val ids = values.filter { it.kind == kind }.map { it.id }.toSet()
        state = state.copy(objectStates = state.objectStates.filterKeys { it in ids })
    }
    fun beginMove(id: String) {
        if (kind == AnnotationKind.MARKER) updateObject(id) { it.copy(moving = true, deleteConfirmation = false) }
    }
    fun endMove(id: String) { updateObject(id) { it.copy(moving = false) } }
    fun confirmDelete(id: String, show: Boolean) {
        updateObject(id) { it.copy(deleteConfirmation = show, moving = if (show) false else it.moving) }
    }
    private fun updateObject(id: String, change: (AnnotationObjectUiState) -> AnnotationObjectUiState) {
        val current = state.objectStates[id] ?: return
        state = state.copy(objectStates = state.objectStates + (id to change(current)))
    }
}
internal class MarkersLayer : AnnotationLayer(AnnotationKind.MARKER)
internal class RouteLayer : AnnotationLayer(AnnotationKind.LINE)
internal class PolygonLayer : AnnotationLayer(AnnotationKind.POLYGON)

internal data class AnnotationOverlays(
    val markers: List<MapMarker> = emptyList(),
    val paths: List<MapPath> = emptyList(),
    val targets: Map<String, AnnotationHit> = emptyMap(),
)

internal fun annotationOverlays(state: AnnotationEditorState, pyramid: TilePyramid?, selectedId: String? = state.activeObjectId): AnnotationOverlays {
    if (pyramid == null) return AnnotationOverlays()
    val markers = mutableListOf<MapMarker>()
    val paths = mutableListOf<MapPath>()
    val targets = mutableMapOf<String, AnnotationHit>()
    val visible = state.layers.filter { it.visible }.map { it.kind }
    val source = if (state.catalogReady) state.catalog else state.items
    val values = source.filter { it.kind in visible && it.id != state.draft?.id } + listOfNotNull(state.draft)
    for (value in values) {
        val projected = projectAnnotation(value, pyramid, value.id == selectedId || value.id == state.draft?.id) ?: continue
        projected.marker?.let(markers::add)
        projected.path?.let(paths::add)
        targets[objectOverlayId(value.id)] = AnnotationHit.Object(value.id)
    }
    state.draft?.coordinates?.forEachIndexed { index, coordinate ->
        pyramid.positionOf(coordinate)?.let {
            val id = "draft-vertex:$index"
            markers += MapMarker(id, it, "place", Color(0xFF1565C0), (index + 1).toString(), zIndex = 2f)
            targets[id] = AnnotationHit.Vertex(index)
        }
    }
    return AnnotationOverlays(markers, paths, targets)
}
