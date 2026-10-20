/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.navigation

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.RoutePosition
import kotlin.math.*

internal data class RouteCandidate(val position: RoutePosition, val measure: Double, val distance: Double, val course: Double)
internal data class RouteCandidateSearch(val candidates: List<RouteCandidate>, val exhausted: Boolean)

internal class RouteNavigationIndex(val route: ActivityRoute) {
    private val count = route.segments.sumOf { it.vertices.size - 1 }
    private val segments = IntArray(count)
    private val edges = IntArray(count)
    private val geometry = DoubleArray(count * 6)
    private val bounds = DoubleArray(count * 6)
    private val angles = DoubleArray(count)
    private val measures = DoubleArray(count)
    private val order = IntArray(count) { it }
    private val segmentEdges = IntArray(route.segments.size)
    val segmentStarts = DoubleArray(route.segments.size)
    val segmentEnds = DoubleArray(route.segments.size)
    val totalMeters: Double
    private val nodes = mutableListOf<Node>()

    private data class Node(val bounds: DoubleArray, val first: Int, val last: Int,
        val minimumMeasure: Double, val maximumMeasure: Double, val minimumSegment: Int, val maximumSegment: Int,
        var left: Int = -1, var right: Int = -1)

    init {
        var offset = 0.0
        var index = 0
        route.segments.forEachIndexed { segment, line ->
            segmentStarts[segment] = offset
            segmentEdges[segment] = index
            for (edge in 0 until line.vertices.lastIndex) {
                val u = vector(line.vertices[edge].coordinate)
                val v = vector(line.vertices[edge + 1].coordinate)
                val cross = doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
                val sine = sqrt(dot(cross, cross))
                val cosine = dot(u, v).coerceIn(-1.0, 1.0)
                val angle = atan2(sine, cosine)
                require(angle * EARTH_METERS >= 0.001 && PI - angle > 1e-7) { "Unsupported degenerate or antipodal route edge" }
                val q = DoubleArray(3) { (v[it] - u[it] * cosine) / sine }
                for (axis in 0..2) {
                    geometry[index * 6 + axis] = u[axis]
                    geometry[index * 6 + 3 + axis] = q[axis]
                    var minimum = min(u[axis], v[axis])
                    var maximum = max(u[axis], v[axis])
                    // Interior extrema keep arc bounds valid at datelines and along long edges.
                    val maximumAngle = positiveAngle(atan2(q[axis], u[axis]))
                    val minimumAngle = positiveAngle(maximumAngle + PI)
                    if (maximumAngle <= angle) maximum = max(maximum, hypot(u[axis], q[axis]))
                    if (minimumAngle <= angle) minimum = min(minimum, -hypot(u[axis], q[axis]))
                    bounds[index * 6 + axis] = minimum - 1e-12
                    bounds[index * 6 + 3 + axis] = maximum + 1e-12
                }
                segments[index] = segment; edges[index] = edge
                angles[index] = angle; measures[index] = offset
                offset += angle * EARTH_METERS
                index++
            }
            segmentEnds[segment] = offset
        }
        totalMeters = offset
        build(0, count)
    }

    fun measure(position: RoutePosition): Double {
        val index = segmentEdges[position.segmentIndex] + position.edgeIndex
        return measures[index] + angles[index] * EARTH_METERS * position.fraction
    }

    fun search(coordinate: GeographicCoordinate, radius: Double, policy: RouteNavigationPolicy,
        segment: Int? = null, minimumMeasure: Double = 0.0, maximumMeasure: Double = totalMeters): RouteCandidateSearch {
        val point = vector(coordinate)
        val found = linkedMapOf<Pair<Int, Long>, RouteCandidate>()
        var bestDistance = radius
        var visited = 0
        var projected = 0
        var exhausted = false
        fun distanceToBox(box: DoubleArray): Double = (0..2).sumOf { axis ->
            val delta = when { point[axis] < box[axis] -> box[axis] - point[axis]
                point[axis] > box[axis + 3] -> point[axis] - box[axis + 3] else -> 0.0 }
            delta * delta
        }
        fun visit(nodeId: Int) {
            if (exhausted) return
            if (++visited > policy.maxVisitedNodes) { exhausted = true; return }
            val node = nodes[nodeId]
            if (node.minimumMeasure > maximumMeasure || node.maximumMeasure < minimumMeasure ||
                segment != null && segment !in node.minimumSegment..node.maximumSegment) return
            val chord = 2 * sin(min(radius, bestDistance + max(50.0, policy.onRouteMeters * 2)) / EARTH_METERS / 2)
            if (distanceToBox(node.bounds) > chord * chord) return
            if (node.left >= 0) {
                val left = node.left; val right = node.right
                if (distanceToBox(nodes[left].bounds) <= distanceToBox(nodes[right].bounds)) { visit(left); visit(right) }
                else { visit(right); visit(left) }
                return
            }
            for (slot in node.first until node.last) {
                val edge = order[slot]
                if (segment != null && segments[edge] != segment || measures[edge] > maximumMeasure ||
                    measures[edge] + angles[edge] * EARTH_METERS < minimumMeasure) continue
                if (++projected > policy.maxProjectedEdges) { exhausted = true; return }
                val candidate = project(edge, point)
                if (candidate.distance > radius || candidate.measure !in minimumMeasure..maximumMeasure) continue
                bestDistance = min(bestDistance, candidate.distance)
                val section = candidate.position.segmentIndex to floor(candidate.measure / 10.0).toLong()
                val previous = found[section]
                if (previous == null) {
                    if (found.size >= 64) { exhausted = true; return }
                    found[section] = candidate
                } else if (candidate.distance < previous.distance) found[section] = candidate
            }
        }
        visit(0)
        return RouteCandidateSearch(found.values.toList(), exhausted)
    }

    private fun project(index: Int, point: DoubleArray): RouteCandidate {
        val u = DoubleArray(3) { geometry[index * 6 + it] }
        val q = DoubleArray(3) { geometry[index * 6 + 3 + it] }
        val angle = angles[index]
        var theta = atan2(dot(point, q), dot(point, u))
        if (theta !in 0.0..angle) {
            val end = DoubleArray(3) { u[it] * cos(angle) + q[it] * sin(angle) }
            theta = if (dot(point, u) >= dot(point, end)) 0.0 else angle
        }
        val closest = DoubleArray(3) { u[it] * cos(theta) + q[it] * sin(theta) }
        val tangent = DoubleArray(3) { -u[it] * sin(theta) + q[it] * cos(theta) }
        val longitude = atan2(closest[1], closest[0])
        val east = doubleArrayOf(-sin(longitude), cos(longitude), 0.0)
        val north = doubleArrayOf(-closest[2] * cos(longitude), -closest[2] * sin(longitude), hypot(closest[0], closest[1]))
        val chord = sqrt((0..2).sumOf { (point[it] - closest[it]).pow(2) })
        return RouteCandidate(RoutePosition(segments[index], edges[index], theta / angle), measures[index] + theta * EARTH_METERS,
            2 * EARTH_METERS * asin((chord / 2).coerceIn(0.0, 1.0)), positiveAngle(atan2(dot(tangent, east), dot(tangent, north))) * 180 / PI)
    }

    private fun build(first: Int, last: Int): Int {
        val box = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        var minMeasure = Double.POSITIVE_INFINITY; var maxMeasure = 0.0
        var minSegment = Int.MAX_VALUE; var maxSegment = 0
        for (slot in first until last) {
            val edge = order[slot]
            for (axis in 0..2) { box[axis] = min(box[axis], bounds[edge * 6 + axis]); box[axis + 3] = max(box[axis + 3], bounds[edge * 6 + axis + 3]) }
            minMeasure = min(minMeasure, measures[edge]); maxMeasure = max(maxMeasure, measures[edge] + angles[edge] * EARTH_METERS)
            minSegment = min(minSegment, segments[edge]); maxSegment = max(maxSegment, segments[edge])
        }
        val id = nodes.size
        val node = Node(box, first, last, minMeasure, maxMeasure, minSegment, maxSegment)
        nodes += node
        if (last - first > 16) {
            val axis = (0..2).maxBy { box[it + 3] - box[it] }
            val middle = (first + last) / 2
            partition(first, last - 1, middle, axis)
            node.left = build(first, middle); node.right = build(middle, last)
        }
        return id
    }

    private fun partition(first: Int, last: Int, target: Int, axis: Int) {
        fun compare(a: Int, b: Int): Int {
            val delta = (bounds[a * 6 + axis] + bounds[a * 6 + axis + 3]).compareTo(bounds[b * 6 + axis] + bounds[b * 6 + axis + 3])
            return if (delta != 0) delta else a.compareTo(b)
        }
        var low = first; var high = last
        while (low < high) {
            val pivot = order[(low + high) / 2]
            var left = low; var right = high
            while (left <= right) {
                while (left <= high && compare(order[left], pivot) < 0) left++
                while (right >= low && compare(order[right], pivot) > 0) right--
                if (left <= right) { val value = order[left]; order[left] = order[right]; order[right] = value; left++; right-- }
            }
            when { target <= right -> high = right; target >= left -> low = left; else -> return }
        }
    }

    private fun vector(coordinate: GeographicCoordinate): DoubleArray {
        val latitude = coordinate.latitude * PI / 180; val longitude = coordinate.longitude * PI / 180
        return doubleArrayOf(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))
    }
    private fun dot(a: DoubleArray, b: DoubleArray) = (0..2).sumOf { a[it] * b[it] }
    private fun positiveAngle(value: Double) = (value % (2 * PI) + 2 * PI) % (2 * PI)
    private companion object { const val EARTH_METERS = 6_371_008.8 }
}
