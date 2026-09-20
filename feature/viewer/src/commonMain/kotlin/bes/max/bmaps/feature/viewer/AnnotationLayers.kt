package bes.max.bmaps.feature.viewer

import androidx.compose.ui.graphics.Color
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.domain.mapbuilder.Annotation

internal data class AnnotationLayerState(val kind: AnnotationKind, val visible: Boolean = true, val selectedId: String? = null)

internal sealed class AnnotationLayer(val kind: AnnotationKind) {
    var state = AnnotationLayerState(kind)
        private set
    fun visibility(visible: Boolean) { state = state.copy(visible = visible, selectedId = if (visible) state.selectedId else null) }
    fun select(value: Annotation?) { state = state.copy(selectedId = value?.takeIf { it.kind == kind }?.id) }
}
internal class MarkersLayer : AnnotationLayer(AnnotationKind.MARKER)
internal class RouteLayer : AnnotationLayer(AnnotationKind.LINE)
internal class PolygonLayer : AnnotationLayer(AnnotationKind.POLYGON)

internal data class AnnotationOverlays(
    val markers: List<MapMarker> = emptyList(),
    val paths: List<MapPath> = emptyList(),
    val targets: Map<String, AnnotationHit> = emptyMap(),
)

internal fun annotationOverlays(state: AnnotationEditorState, pyramid: TilePyramid?): AnnotationOverlays {
    if (pyramid == null) return AnnotationOverlays()
    val markers = mutableListOf<MapMarker>()
    val paths = mutableListOf<MapPath>()
    val targets = mutableMapOf<String, AnnotationHit>()
    val visible = state.layers.filter { it.visible }.map { it.kind }
    val source = if (state.catalogReady) state.catalog else state.items
    val values = source.filter { it.kind in visible && it.id != state.draft?.id } + listOfNotNull(state.draft)
    for (value in values) {
        val projected = projectAnnotation(value, pyramid, value.id == state.selected?.id || value.id == state.draft?.id) ?: continue
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
