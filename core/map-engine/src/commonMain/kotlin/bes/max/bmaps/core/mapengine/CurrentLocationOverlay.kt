/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

const val CURRENT_LOCATION_MARKER = "bmaps-current-location"
private val locationColor = Color(0xFF4285F4)

data class CurrentLocationOverlays(val markers: List<MapMarker> = emptyList(), val paths: List<MapPath> = emptyList())

fun currentLocationOverlays(
    coordinate: GeographicCoordinate?,
    accuracyMeters: Double?,
    pyramid: TilePyramid?,
    label: String,
): CurrentLocationOverlays {
    if (coordinate == null || pyramid == null) return CurrentLocationOverlays()
    val center = pyramid.positionOf(coordinate) ?: return CurrentLocationOverlays()
    val marker = MapMarker(CURRENT_LOCATION_MARKER, center, "", locationColor, label, MapMarkerAnchor.CENTER, 10f)
    val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0 } ?: return CurrentLocationOverlays(listOf(marker))
    val latitude = coordinate.latitude * PI / 180
    val longitude = coordinate.longitude * PI / 180
    val angularRadius = accuracy / WebMercator.EARTH_RADIUS
    val worldCenter = WebMercator.normalized(coordinate) ?: return CurrentLocationOverlays(listOf(marker))
    val side = 2.0.pow(pyramid.levels.min)
    val ring = (0 until 48).mapNotNull { index ->
        val bearing = index * 2 * PI / 48
        val lat = asin((sin(latitude) * cos(angularRadius) + cos(latitude) * sin(angularRadius) * cos(bearing)).coerceIn(-1.0, 1.0))
        val lon = longitude + atan2(sin(bearing) * sin(angularRadius) * cos(latitude), cos(angularRadius) - sin(latitude) * sin(lat))
        val geographic = GeographicCoordinate((lat * 180 / PI).coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE),
            ((lon * 180 / PI + 540) % 360) - 180)
        WebMercator.normalized(geographic)?.let { world ->
            val delta = world.x - worldCenter.x
            val x = world.x + if (delta > 0.5) -1 else if (delta < -0.5) 1 else 0
            MapPoint((x * side - pyramid.originColumn) / pyramid.columns,
                (world.y * side - pyramid.originRow) / pyramid.rows)
        }
    }
    val clipped = clipLocationRing(ring)
    val paths = if (clipped.size < 3) emptyList() else listOf(MapPath(
        "bmaps-current-location-accuracy", clipped + clipped.first(), locationColor, filled = true, zIndex = 9f, clickable = false,
    ))
    return CurrentLocationOverlays(listOf(marker), paths)
}

private fun clipLocationRing(points: List<MapPoint>): List<MapPoint> {
    var result = points
    for (edge in 0..3) {
        if (result.isEmpty()) break
        fun value(point: MapPoint) = when (edge) { 0 -> point.x; 1 -> 1 - point.x; 2 -> point.y; else -> 1 - point.y }
        val input = result
        result = buildList {
            var previous = input.last()
            for (point in input) {
                val from = value(previous)
                val to = value(point)
                if ((from >= 0) != (to >= 0)) {
                    val fraction = from / (from - to)
                    add(MapPoint(previous.x + (point.x - previous.x) * fraction, previous.y + (point.y - previous.y) * fraction))
                }
                if (to >= 0) add(point)
                previous = point
            }
        }
    }
    return result.map { MapPoint(it.x.coerceIn(0.0, 1.0), it.y.coerceIn(0.0, 1.0)) }
}

@Composable
fun CurrentLocationMarker(label: String) {
    Canvas(Modifier.size(24.dp).semantics { contentDescription = label }) {
        drawCircle(Color.White, radius = 10.dp.toPx())
        drawCircle(locationColor, radius = 7.dp.toPx())
    }
}

fun MapCameraSnapshot.scaleAtZoom(level: Int): Double =
    (2.0.pow(level - pyramid.levels.max) / fitScale).coerceIn(minScale, maxScale)
