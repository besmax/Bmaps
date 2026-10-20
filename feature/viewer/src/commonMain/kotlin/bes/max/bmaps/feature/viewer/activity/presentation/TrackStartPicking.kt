/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.compose.runtime.Immutable
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.activity.model.ActivityRoute
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

@Immutable
internal data class TrackStartPick(
    val segment: Int, val edge: Int, val fraction: Double,
    val coordinate: TrackCoordinate, val distance: Double, val bearing: Float,
)

internal fun routeEdgeLength(a: GeographicCoordinate, b: GeographicCoordinate): Double {
    val h = sin((b.latitude - a.latitude) * PI / 360).pow(2) +
        cos(a.latitude * PI / 180) * cos(b.latitude * PI / 180) * sin((b.longitude - a.longitude) * PI / 360).pow(2)
    return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

internal fun routeStartPick(route: ActivityRoute, segment: Int, edge: Int, fraction: Double, knownDistance: Double? = null): TrackStartPick {
    val vertices = route.segments[segment].vertices
    val a = vertices[edge].coordinate
    val b = vertices[edge + 1].coordinate
    val start = WebMercator.normalized(a)
    val end = WebMercator.normalized(b)
    val coordinate = if (start != null && end != null) WebMercator.geographic(MapPoint(
        start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction,
    )) else null
    val distance = knownDistance ?: (route.segments.take(segment).sumOf { part ->
        part.vertices.zipWithNext().sumOf { (a, b) -> routeEdgeLength(a.coordinate, b.coordinate) }
    } + vertices.take(edge + 1).zipWithNext().sumOf { (a, b) -> routeEdgeLength(a.coordinate, b.coordinate) } + routeEdgeLength(a, b) * fraction)
    val lon = (b.longitude - a.longitude) * PI / 180
    val latA = a.latitude * PI / 180
    val latB = b.latitude * PI / 180
    val bearing = ((atan2(sin(lon) * cos(latB), cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(lon)) * 180 / PI + 360) % 360).toFloat()
    return TrackStartPick(segment, edge, fraction, TrackCoordinate(coordinate?.latitude ?: a.latitude,
        coordinate?.longitude ?: a.longitude), distance, bearing)
}

internal suspend fun pickRouteStart(route: ActivityRoute, point: MapPoint, camera: MapCameraSnapshot,
    tolerancePixels: Double): List<TrackStartPick> {
    val window = camera.visibleWindow
    if (camera.pyramid.levels.min > 52 || window.right <= window.left || window.bottom <= window.top ||
        camera.layout.width <= 0 || camera.layout.height <= 0) return emptyList()
    val sx = camera.layout.width / (window.right - window.left)
    val sy = camera.layout.height / (window.bottom - window.top)
    val count = (1L shl camera.pyramid.levels.min).toDouble()
    fun project(coordinate: GeographicCoordinate): MapPoint? = WebMercator.normalized(coordinate)?.let {
        MapPoint(((it.x * count - camera.pyramid.originColumn) / camera.pyramid.columns - point.x) * sx,
            ((it.y * count - camera.pyramid.originRow) / camera.pyramid.rows - point.y) * sy)
    }
    val results = mutableListOf<Pair<Double, TrackStartPick>>()
    var travelled = 0.0
    route.segments.forEachIndexed { segment, part ->
        var bestEdge = -1
        var bestFraction = 0.0
        var bestTravelled = 0.0
        var bestDistance = Double.POSITIVE_INFINITY
        fun flush() {
            if (bestEdge >= 0) results.add(bestDistance to routeStartPick(route, segment, bestEdge, bestFraction, bestTravelled))
            if (results.size > 32) results.removeAt(results.indices.maxBy { results[it].first })
            bestEdge = -1
            bestDistance = Double.POSITIVE_INFINITY
        }
        for (edge in 0 until part.vertices.lastIndex) {
            if (edge % 1024 == 0) currentCoroutineContext().ensureActive()
            val edgeStart = travelled
            val length = routeEdgeLength(part.vertices[edge].coordinate, part.vertices[edge + 1].coordinate)
            travelled += length
            val a = project(part.vertices[edge].coordinate)
            val b = project(part.vertices[edge + 1].coordinate)
            if (a == null || b == null || abs(part.vertices[edge].coordinate.longitude - part.vertices[edge + 1].coordinate.longitude) > 180) { flush(); continue }
            val dx = b.x - a.x
            val dy = b.y - a.y
            val denominator = dx * dx + dy * dy
            val fraction = if (denominator > 0) (-(a.x * dx + a.y * dy) / denominator).coerceIn(0.0, 1.0) else 0.0
            val distance = hypot(a.x + fraction * dx, a.y + fraction * dy)
            if (distance > tolerancePixels) flush()
            else if (distance < bestDistance) { bestEdge = edge; bestFraction = fraction; bestDistance = distance; bestTravelled = edgeStart + length * fraction }
        }
        flush()
    }
    return results.sortedBy { it.first }.map { it.second }
}
