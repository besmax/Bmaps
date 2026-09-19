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

internal data class AnnotationOverlays(val markers: List<MapMarker>, val paths: List<MapPath>)

internal fun annotationOverlays(state: AnnotationEditorState, pyramid: TilePyramid?): AnnotationOverlays {
    if (pyramid == null) return AnnotationOverlays(emptyList(), emptyList())
    val markers = mutableListOf<MapMarker>()
    val paths = mutableListOf<MapPath>()
    val visible = state.layers.filter { it.visible }.map { it.kind }
    val values = state.items.filter { it.kind in visible && it.id != state.draft?.id } + listOfNotNull(state.draft)
    for (value in values) {
        val color = Color((0xFF000000L or value.color.removePrefix("#").toLong(16)).toInt())
        if (value.kind == AnnotationKind.MARKER) {
            value.coordinates.firstOrNull()?.let { point ->
                pyramid.positionOf(point)?.let { markers += MapMarker(value.id, it, value.icon, color, value.name) }
            }
        } else {
            val points = value.coordinates
            if (points.isEmpty()) continue
            val side = (1L shl pyramid.levels.min).toDouble()
            run {
                val projected = points.map { point ->
                    val y = WebMercator.normalized(point.copy(longitude = 0.0, latitude = point.latitude.coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE)))!!.y
                    MapPoint((((point.longitude + 180) / 360) * side - pyramid.originColumn) / pyramid.columns,
                        (y * side - pyramid.originRow) / pyramid.rows)
                }
                if (projected.maxOf { it.x } < 0 || projected.minOf { it.x } > 1 || projected.maxOf { it.y } < 0 || projected.minOf { it.y } > 1) return@run
                val closed = value.kind == AnnotationKind.POLYGON && projected.size >= 3
                paths += MapPath(value.id, if (closed) projected + projected.first() else projected, color, closed)
            }
        }
    }
    state.draft?.coordinates?.forEachIndexed { index, coordinate ->
        pyramid.positionOf(coordinate)?.let { markers += MapMarker("vertex:$index", it, "place", Color(0xFF1565C0), (index + 1).toString()) }
    }
    return AnnotationOverlays(markers, paths)
}
