/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.route

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.ActivityValidation
import bes.max.bmaps.domain.activity.model.RouteSegment
import bes.max.bmaps.domain.activity.model.RouteVertex

data class RoutePreviewSegment(val sourceSegmentId: String, val coordinates: List<GeographicCoordinate>, val sourceVertexIndices: List<Int>)
data class RoutePreview(val sourceRouteId: String, val sourceRevision: Long, val segments: List<RoutePreviewSegment>)

object ActivityRoutePreview {
    fun create(route: ActivityRoute, maximumVertices: Int = 10_000): RoutePreview {
        require(ActivityValidation.route(route) == null)
        val required = route.segments.map { mutableSetOf(0, it.vertices.lastIndex) }
        route.controlPoints.forEach {
            required[it.position.segmentIndex].add(it.position.edgeIndex)
            required[it.position.segmentIndex].add(it.position.edgeIndex + 1)
        }
        val mandatory = required.sumOf { it.size }
        require(maximumVertices >= mandatory)
        val available = route.segments.sumOf { it.vertices.size } - mandatory
        val remaining = minOf(maximumVertices - mandatory, available)
        var allocated = 0
        var visited = 0
        val segments = route.segments.mapIndexed { index, segment ->
            val extras = segment.vertices.size - required[index].size
            val before = visited
            visited += extras
            val quota = if (available == 0) 0 else ((visited.toLong() * remaining / available) - (before.toLong() * remaining / available)).toInt()
            var rank = 0
            val indices = buildList {
                segment.vertices.indices.forEach { vertex ->
                    if (vertex in required[index]) add(vertex)
                    else {
                        val previous = rank++
                        if (extras > 0 && rank.toLong() * quota / extras > previous.toLong() * quota / extras) add(vertex)
                    }
                }
            }
            allocated += indices.size
            RoutePreviewSegment(segment.id, indices.map { segment.vertices[it].coordinate }, indices)
        }
        check(allocated <= maximumVertices)
        return RoutePreview(route.id, route.revision, segments)
    }
}

object ActivityRouteAdapter {
    fun line(id: String, name: String, vertices: List<RouteVertex>, description: String = ""): ActivityRoute =
        ActivityRoute(id, 0, name, listOf(RouteSegment("line", vertices.toList())), description = description).also {
            require(ActivityValidation.route(it) == null)
        }
}
