/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.compose.ui.graphics.Color
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.activity.PositionMeasurement
import bes.max.bmaps.domain.activity.TelemetrySample
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs

internal class TrackPathBuffer {
    private val segments = linkedMapOf<String, MutableList<TrackCoordinate>>()
    private var lastId: String? = null
    fun add(sample: TelemetrySample) {
        if (sample.id == lastId) return
        val coordinate = (sample.measurement as? PositionMeasurement)?.coordinate ?: return
        val segment = sample.pathSegmentId ?: return
        lastId = sample.id
        segments.getOrPut(segment) { mutableListOf() }.add(TrackCoordinate(coordinate.latitude, coordinate.longitude))
        while (segments.size > 2000) segments.remove(segments.keys.first())
        if (segments.values.sumOf { it.size } > 20_000) segments.values.forEach { points ->
            if (points.size > 2) {
                val retained = points.filterIndexed { index, _ -> index == 0 || index == points.lastIndex || index % 2 == 0 }
                points.clear(); points.addAll(retained)
            }
        }
    }
    fun snapshot() = segments.map { (id, points) -> TrackLine(id, points.toImmutableList()) }.toImmutableList()
}

internal object TrackMapLayer {
    fun paths(state: TrackState, pyramid: TilePyramid?): List<MapPath> {
        if (pyramid == null) return emptyList()
        return buildList {
            if (state.preparing || state.correcting) addAll(paths(state.preview, pyramid, "preview", Color(0xFFFFB300)))
            addAll(paths(state.planned, pyramid, "planned", Color(0xFF7986CB)))
            if (state.metrics?.returning == true) addAll(paths(state.guidance, pyramid, "guidance", Color(0xFFFFB300)))
            addAll(paths(state.recorded, pyramid, "recorded", Color(0xFF00A878)))
            if (state.preparing || state.correcting) addAll(paths(state.selectedSection, pyramid, "selected", Color(0xFFE53935)))
        }
    }
    fun objectPaths(lines: List<TrackLine>, pyramid: TilePyramid?): List<MapPath> =
        if (pyramid == null) emptyList() else paths(lines, pyramid, "object-route", Color(0xFFE53935)).map { it.copy(clickable = true) }

    private fun paths(lines: List<TrackLine>, pyramid: TilePyramid, prefix: String, color: Color): List<MapPath> = buildList {
        lines.forEach { line ->
            var part = 0
            var points = mutableListOf<MapPoint>()
            fun flush() {
                if (points.size >= 2) add(MapPath("track:$prefix:${line.id}:${part++}", points.toList(), color, zIndex = if (prefix == "selected") 7f else 5f, clickable = false))
                points = mutableListOf()
            }
            line.points.zipWithNext().forEach { (a, b) ->
                val start = project(a, pyramid)
                val end = project(b, pyramid)
                val clipped = if (start != null && end != null && abs(a.longitude - b.longitude) <= 180) clip(start, end) else null
                if (clipped == null) flush() else {
                    if (points.lastOrNull() != clipped.first) { flush(); points.add(clipped.first) }
                    points.add(clipped.second)
                }
            }
            flush()
        }
    }

    private fun project(coordinate: TrackCoordinate, pyramid: TilePyramid): MapPoint? {
        if (pyramid.levels.min > 52) return null
        val world = WebMercator.normalized(GeographicCoordinate(coordinate.latitude, coordinate.longitude)) ?: return null
        val count = (1L shl pyramid.levels.min).toDouble()
        return MapPoint((world.x * count - pyramid.originColumn) / pyramid.columns,
            (world.y * count - pyramid.originRow) / pyramid.rows)
    }
    private fun clip(a: MapPoint, b: MapPoint): Pair<MapPoint, MapPoint>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        var from = 0.0
        var to = 1.0
        fun boundary(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val t = q / p
            if (p < 0) { if (t > to) return false; from = maxOf(from, t) }
            else { if (t < from) return false; to = minOf(to, t) }
            return true
        }
        if (!boundary(-dx, a.x) || !boundary(dx, 1 - a.x) || !boundary(-dy, a.y) || !boundary(dy, 1 - a.y)) return null
        fun point(t: Double) = MapPoint((a.x + t * dx).coerceIn(0.0, 1.0), (a.y + t * dy).coerceIn(0.0, 1.0))
        return point(from) to point(to)
    }
}
