package bes.max.bmaps.feature.viewer

import androidx.compose.ui.graphics.Color
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal const val MAX_CLUSTER_OBJECTS = 1000
private const val CLUSTER_DISTANCE_DP = 64.0

internal sealed interface AnnotationHit {
    data class Object(val id: String) : AnnotationHit
    data class Cluster(val ids: List<String>) : AnnotationHit
    data class Vertex(val index: Int) : AnnotationHit
}

internal fun objectOverlayId(id: String) = "object:${id.length}:$id"
internal fun clusterOverlayId(ids: List<String>) = "cluster:" + ids.sorted().joinToString("") { "${it.length}:$it" }

internal data class ProjectedAnnotation(
    val value: Annotation,
    val anchor: MapPoint,
    val bounds: MapWindow,
    val marker: MapMarker? = null,
    val path: MapPath? = null,
)

internal fun projectAnnotation(value: Annotation, pyramid: TilePyramid, selected: Boolean = false): ProjectedAnnotation? {
    if (pyramid.levels.min > 52 || value.coordinates.isEmpty()) return null
    val color = Color((0xFF000000L or value.color.removePrefix("#").toLong(16)).toInt())
    val id = objectOverlayId(value.id)
    val z = if (selected) 2f else 0f
    if (value.kind == AnnotationKind.MARKER) {
        val point = pyramid.positionOf(value.coordinates.first()) ?: return null
        return ProjectedAnnotation(value, point, MapWindow(point.x, point.y, point.x, point.y),
            marker = MapMarker(id, point, value.icon, color, value.name, zIndex = z))
    }
    val side = (1L shl pyramid.levels.min).toDouble()
    val points = value.coordinates.map { point ->
        val y = WebMercator.normalized(point.copy(longitude = 0.0,
            latitude = point.latitude.coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE)))?.y ?: return null
        MapPoint(((point.longitude + 180) / 360 * side - pyramid.originColumn) / pyramid.columns,
            (y * side - pyramid.originRow) / pyramid.rows)
    }
    if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val bounds = MapWindow(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    if (!bounds.intersects(MapWindow(0.0, 0.0, 1.0, 1.0))) return null
    val closed = value.kind == AnnotationKind.POLYGON && points.size >= 3
    return ProjectedAnnotation(value, MapPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2), bounds,
        path = MapPath(id, if (closed) points + points.first() else points, color, closed, z))
}

internal data class AnnotationGroup(val members: List<ProjectedAnnotation>) {
    val ids = members.map { it.value.id }
}

internal class AnnotationClusterer(
    values: List<Annotation>,
    private val pyramid: TilePyramid,
    visibleKinds: Set<AnnotationKind>,
    private val selectedId: String?,
) {
    private val projected = values.filter { it.kind in visibleKinds }.sortedBy { it.id }
        .mapNotNull { projectAnnotation(it, pyramid, it.id == selectedId) }

    fun groups(camera: MapCameraSnapshot, density: Float, checkActive: () -> Unit = {}): List<AnnotationGroup> {
        val pixels = pixels(camera) ?: return projected.map { AnnotationGroup(listOf(it)) }
        if (!density.isFinite() || density <= 0) return projected.map { AnnotationGroup(listOf(it)) }
        val threshold = CLUSTER_DISTANCE_DP * density
        val eligible = projected.filter {
            it.value.id != selectedId && (it.value.kind == AnnotationKind.MARKER ||
                (it.bounds.left >= 0 && it.bounds.top >= 0 && it.bounds.right <= 1 && it.bounds.bottom <= 1 &&
                    hypot((it.bounds.right - it.bounds.left) * pixels.x, (it.bounds.bottom - it.bounds.top) * pixels.y) <= threshold))
        }
        val parent = IntArray(eligible.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) { parent[current] = parent[parent[current]]; current = parent[current] }
            return current
        }
        fun connect(a: Int, b: Int) {
            val left = eligible[a].anchor; val right = eligible[b].anchor
            if (hypot((left.x - right.x) * pixels.x, (left.y - right.y) * pixels.y) <= threshold) parent[root(a)] = root(b)
        }
        val cells = eligible.map { floor(it.anchor.x * pixels.x / threshold) to floor(it.anchor.y * pixels.y / threshold) }
        if (cells.any { !it.first.isFinite() || !it.second.isFinite() || it.first < 0 || it.second < 0 ||
                it.first > Long.MAX_VALUE.toDouble() / 2 || it.second > Long.MAX_VALUE.toDouble() / 2 }) {
            for (a in eligible.indices) { checkActive(); for (b in 0 until a) connect(a, b) }
        } else {
            val grid = mutableMapOf<Pair<Long, Long>, MutableList<Int>>()
            for (a in eligible.indices) {
                checkActive()
                val x = cells[a].first.toLong(); val y = cells[a].second.toLong()
                for (dx in -1L..1L) for (dy in -1L..1L) grid[x + dx to y + dy]?.forEach { connect(a, it) }
                grid.getOrPut(x to y) { mutableListOf() }.add(a)
            }
        }
        val eligibleIds = eligible.map { it.value.id }.toSet()
        return (eligible.indices.groupBy(::root).values.map { indexes -> AnnotationGroup(indexes.map { eligible[it] }) } +
            projected.filter { it.value.id !in eligibleIds }.map { AnnotationGroup(listOf(it)) }).sortedBy { it.ids.first() }
    }

    fun render(groups: List<AnnotationGroup>, camera: MapCameraSnapshot, density: Float): AnnotationOverlays {
        val markers = mutableListOf<MapMarker>(); val paths = mutableListOf<MapPath>()
        val targets = mutableMapOf<String, AnnotationHit>()
        val pixels = pixels(camera)
        for (group in groups) {
            if (group.members.size == 1 || pixels == null) {
                group.members.forEach { item ->
                    item.marker?.let(markers::add); item.path?.let(paths::add)
                    targets[objectOverlayId(item.value.id)] = AnnotationHit.Object(item.value.id)
                }
                continue
            }
            val paddingX = 32 * density / pixels.x; val paddingY = 32 * density / pixels.y
            val window = camera.visibleWindow
            val padded = MapWindow(window.left - paddingX, window.top - paddingY, window.right + paddingX, window.bottom + paddingY)
            val visible = group.members.filter { it.bounds.intersects(padded) }
            if (visible.isEmpty()) continue
            val left = max(0.0, window.left); val right = min(1.0, window.right)
            val top = max(0.0, window.top); val bottom = min(1.0, window.bottom)
            if (right < left || bottom < top) continue
            fun inset(value: Double, low: Double, high: Double, padding: Double) =
                if (high - low <= 2 * padding) (low + high) / 2 else value.coerceIn(low + padding, high - padding)
            val anchor = MapPoint(inset(visible.map { it.anchor.x }.average(), left, right, paddingX),
                inset(visible.map { it.anchor.y }.average(), top, bottom, paddingY))
            val id = clusterOverlayId(group.ids)
            markers += MapMarker(id, anchor, "", Color.Unspecified, group.ids.size.toString(), MapMarkerAnchor.CENTER, 1f)
            targets[id] = AnnotationHit.Cluster(group.ids)
        }
        return AnnotationOverlays(markers, paths, targets)
    }

    fun splittingScale(ids: List<String>, camera: MapCameraSnapshot, density: Float, checkActive: () -> Unit = {}): Double? {
        if (ids.size < 2 || !camera.zoomEnabled || pixels(camera) == null ||
            camera.maxScale <= camera.viewport.scale * (1 + 1e-6)) return null
        if (!projected.map { it.value.id }.containsAll(ids)) return null
        fun split(scale: Double): Boolean {
            checkActive()
            return groups(camera.copy(viewport = camera.viewport.copy(scale = scale)), density, checkActive)
                .none { it.ids.containsAll(ids) }
        }
        if (!split(camera.maxScale)) return null
        var low = camera.viewport.scale; var high = camera.maxScale
        repeat(24) {
            val middle = low + (high - low) / 2
            if (split(middle)) high = middle else low = middle
        }
        val target = min(camera.maxScale, max(camera.viewport.scale * 1.5, high * 1.05))
        return target.takeIf(::split)
    }

    fun focus(ids: List<String>, badge: MapPoint, camera: MapCameraSnapshot): MapPoint {
        val pixels = pixels(camera) ?: return badge
        return projected.filter { it.value.id in ids }.minByOrNull {
            hypot((it.anchor.x - badge.x) * pixels.x, (it.anchor.y - badge.y) * pixels.y)
        }?.anchor ?: badge
    }

    private fun pixels(camera: MapCameraSnapshot): MapPoint? {
        if (camera.pyramid != pyramid) return null
        val (width, height) = pyramid.engineSize() ?: return null
        val scale = camera.fitScale * camera.viewport.scale
        return MapPoint(width * scale, height * scale).takeIf { it.x.isFinite() && it.y.isFinite() && it.x > 0 && it.y > 0 }
    }
}

private fun MapWindow.intersects(other: MapWindow) = right >= other.left && left <= other.right && bottom >= other.top && top <= other.bottom
