/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation
import androidx.compose.ui.graphics.Color
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
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
            marker = MapMarker(id, point, value.icon, color, value.name, anchor = MapMarkerAnchor.CENTER, zIndex = z))
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

    private class Node(val members: List<ProjectedAnnotation>, width: Double, height: Double) {
        val bounds = MapWindow(members.minOf { it.bounds.left }, members.minOf { it.bounds.top },
            members.maxOf { it.bounds.right }, members.maxOf { it.bounds.bottom })
        val children: List<Node> = if (members.size == 1) emptyList() else {
            val horizontal = (members.maxOf { it.anchor.x } - members.minOf { it.anchor.x }) * width >=
                (members.maxOf { it.anchor.y } - members.minOf { it.anchor.y }) * height
            val ordered = members.sortedWith(compareBy<ProjectedAnnotation> {
                if (horizontal) it.anchor.x else it.anchor.y
            }.thenBy { it.value.id })
            val middle = ordered.size / 2
            val split = (1 until ordered.size).filter { index ->
                if (horizontal) ordered[index - 1].anchor.x != ordered[index].anchor.x
                else ordered[index - 1].anchor.y != ordered[index].anchor.y
            }.minByOrNull { kotlin.math.abs(it - middle) } ?: middle
            listOf(Node(ordered.take(split), width, height), Node(ordered.drop(split), width, height))
        }
    }

    private val candidates = projected.filter { it.value.id != selectedId &&
        it.bounds.left >= 0 && it.bounds.top >= 0 && it.bounds.right <= 1 && it.bounds.bottom <= 1 }
    private val candidateIds = candidates.map { it.value.id }.toSet()
    private val ungrouped = projected.filter { it.value.id !in candidateIds }
    private val tree = pyramid.engineSize()?.let { (width, height) ->
        candidates.takeIf { it.isNotEmpty() }?.let { Node(it, width.toDouble(), height.toDouble()) }
    }

    fun groups(camera: MapCameraSnapshot, density: Float, checkActive: () -> Unit = {}): List<AnnotationGroup> {
        val pixels = pixels(camera) ?: return projected.map { AnnotationGroup(listOf(it)) }
        if (!density.isFinite() || density <= 0 || tree == null) return projected.map { AnnotationGroup(listOf(it)) }
        val threshold = CLUSTER_DISTANCE_DP * density
        val result = ungrouped.mapTo(mutableListOf()) { AnnotationGroup(listOf(it)) }
        fun collect(node: Node) {
            checkActive()
            val diagonal = hypot((node.bounds.right - node.bounds.left) * pixels.x,
                (node.bounds.bottom - node.bounds.top) * pixels.y)
            if (node.children.isEmpty() || diagonal <= threshold) {
                result += AnnotationGroup(node.members.sortedBy { it.value.id })
            } else {
                node.children.forEach(::collect)
            }
        }
        collect(tree)
        return result.sortedBy { it.ids.first() }
    }

    fun render(groups: List<AnnotationGroup>): AnnotationOverlays {
        val markers = mutableListOf<MapMarker>(); val paths = mutableListOf<MapPath>()
        val targets = mutableMapOf<String, AnnotationHit>()
        for (group in groups) {
            if (group.members.size == 1) {
                group.members.forEach { item ->
                    item.marker?.let(markers::add); item.path?.let(paths::add)
                    targets[objectOverlayId(item.value.id)] = AnnotationHit.Object(item.value.id)
                }
                continue
            }
            val anchor = MapPoint(group.members.map { it.anchor.x }.average(), group.members.map { it.anchor.y }.average())
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
