/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, nl.adaptivity.xmlutil.XmlUtilInternal::class)

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class GpxWaypoint(
    val coordinate: GeographicCoordinate,
    val name: String,
    val description: String,
    val elevation: ElevationMeasurement?,
    val sourceTimeEpochMillis: Long?,
)
data class GpxDocument(val routes: List<ActivityRoute>, val waypoints: List<GpxWaypoint>)

object ActivityRouteGpx {
    suspend fun decode(text: String): List<ActivityRoute> {
        require(text.length <= ActivityLimits.MAX_IMPORT_BYTES)
        val bytes = text.encodeToByteArray()
        require(bytes.size <= ActivityLimits.MAX_IMPORT_BYTES)
        return decode(Buffer().apply { write(bytes) })
    }

    suspend fun decode(source: RawSource): List<ActivityRoute> = decodeDocument(source).routes.also {
        require(it.isNotEmpty()) { "GPX has no routes" }
    }

    suspend fun decodeDocument(source: RawSource): GpxDocument = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val reader = xmlStreaming.newGenericReader(GpxUtf8Reader(source.buffered(), context), expandEntities = false)
        val stack = mutableListOf<Frame>()
        val routes = mutableListOf<ActivityRoute>()
        val waypoints = mutableListOf<GpxWaypoint>()
        var namespace: String? = null
        var rootSeen = false
        var route: DraftRoute? = null
        var points: MutableList<RouteVertex>? = null
        var labels = mutableListOf<Pair<Int, String>>()
        var sourcePoints = 0
        var segments = 0
        var controls = 0
        var elements = 0
        fun completeSegment() {
            val owner = requireNotNull(route)
            val vertices = requireNotNull(points)
            require(vertices.size >= 2) { "Degenerate GPX segment" }
            val segmentIndex = owner.segments.size
            owner.segments += RouteSegment(Uuid.random().toString(), vertices.toList())
            for ((index, name) in labels) {
                owner.controls += RouteControlPoint(Uuid.random().toString(), name,
                    RoutePosition(segmentIndex, minOf(index, vertices.lastIndex - 1), if (index == vertices.lastIndex) 1.0 else 0.0))
            }
            points = null
            labels = mutableListOf()
        }
        try {
            while (reader.hasNext()) {
                context.ensureActive()
                when (reader.next()) {
                    EventType.START_DOCUMENT -> require(reader.encoding == null || reader.encoding.equals("UTF-8", true)) { "GPX must be UTF-8" }
                    EventType.START_ELEMENT -> {
                        require(stack.size < 64 && ++elements <= 2_000_000 && reader.attributeCount <= 128) { "XML complexity limit exceeded" }
                        val attributes = buildMap {
                            for (index in 0 until reader.attributeCount) if (reader.getAttributeNamespace(index).isEmpty()) {
                                val name = reader.getAttributeLocalName(index)
                                require(name !in this)
                                put(name, reader.getAttributeValue(index))
                            }
                        }
                        val name = reader.localName
                        val ns = reader.namespaceURI
                        val parent = stack.lastOrNull()
                        if (stack.isEmpty()) {
                            require(!rootSeen && name == "gpx")
                            val version = attributes["version"]
                            require(version == "1.0" || version == "1.1")
                            require(ns.isEmpty() || ns == "http://www.topografix.com/GPX/" + version.replace('.', '/'))
                            rootSeen = true
                            namespace = ns
                        }
                        val frame = Frame(name, ns)
                        if (ns == namespace && parent != null && parent.namespace == namespace) {
                            when {
                                stack.size == 1 && name == "wpt" -> {
                                    require(waypoints.size < 1_000) { "GPX waypoint limit exceeded" }
                                    frame.waypoint = true
                                    frame.point = DraftPoint(GeographicCoordinate(requireNotNull(attributes["lat"]).toDouble(), requireNotNull(attributes["lon"]).toDouble()))
                                }
                                stack.size == 1 && (name == "trk" || name == "rte") -> {
                                    require(routes.size < ActivityLimits.MAX_IMPORT_ROUTES)
                                    route = DraftRoute()
                                    frame.closesRoute = true
                                    if (name == "rte") { require(++segments <= ActivityLimits.MAX_ROUTE_SEGMENTS); points = mutableListOf() }
                                }
                                stack.size == 2 && name == "trkseg" && parent.name == "trk" -> {
                                    require(++segments <= ActivityLimits.MAX_ROUTE_SEGMENTS)
                                    points = mutableListOf()
                                    frame.closesSegment = true
                                }
                                (stack.size == 2 && name == "rtept" && parent.name == "rte") ||
                                    (stack.size == 3 && name == "trkpt" && parent.name == "trkseg") -> {
                                    require(++sourcePoints <= ActivityLimits.MAX_ROUTE_VERTICES)
                                    frame.point = DraftPoint(GeographicCoordinate(requireNotNull(attributes["lat"]).toDouble(), requireNotNull(attributes["lon"]).toDouble()))
                                }
                                (stack.size == 2 && parent.name in setOf("rte", "trk") && name in setOf("name", "desc", "cmt")) ||
                                    (parent.point != null && (name in setOf("name", "ele", "time") || parent.waypoint && name in setOf("desc", "cmt"))) -> {
                                    require(parent.seen.add(name)) { "Duplicate GPX property" }
                                    frame.text = StringBuilder()
                                }
                            }
                        }
                        require(parent?.text == null) { "Nested GPX text property" }
                        stack += frame
                    }
                    EventType.END_ELEMENT -> {
                        val frame = stack.removeLast()
                        require(frame.name == reader.localName && frame.namespace == reader.namespaceURI)
                        val parent = stack.lastOrNull()
                        if (frame.namespace == namespace) {
                            frame.text?.toString()?.trim()?.let { text ->
                                if (parent?.point != null) when (frame.name) {
                                    "name" -> { require(text.length <= 120); parent.point!!.name = text }
                                    "ele" -> {
                                        val height = text.toDouble()
                                        require(height.isFinite())
                                        parent.point!!.elevation = ElevationMeasurement(height, "GPX_UNSPECIFIED")
                                    }
                                    "time" -> parent.point!!.time = Instant.parse(text).toEpochMilliseconds()
                                    "desc" -> parent.point!!.description = text
                                    "cmt" -> parent.point!!.comment = text
                                } else if (parent?.name in setOf("trk", "rte") && stack.size == 2) when (frame.name) {
                                    "name" -> { require(text.length <= 120); requireNotNull(route).name = text }
                                    "desc" -> requireNotNull(route).description = text
                                    "cmt" -> requireNotNull(route).comment = text
                                }
                            }
                            frame.point?.let { point ->
                                require(ActivityValidation.measurement(PositionMeasurement(point.coordinate, 0.0)) == null)
                                require(point.time?.let { it >= 0 } != false)
                                if (frame.waypoint) {
                                    waypoints += GpxWaypoint(point.coordinate, point.name,
                                        point.description.ifEmpty { point.comment }, point.elevation, point.time)
                                    return@let
                                }
                                val vertices = requireNotNull(points)
                                val vertex = RouteVertex(point.coordinate, point.elevation, point.time)
                                if (vertices.lastOrNull()?.coordinate != vertex.coordinate) vertices += vertex
                                if (point.name.isNotEmpty()) {
                                    require(++controls <= ActivityLimits.MAX_CONTROL_POINTS)
                                    labels += vertices.lastIndex to point.name
                                }
                            }
                            if (frame.closesSegment) completeSegment()
                            if (frame.closesRoute) {
                                if (frame.name == "rte") completeSegment()
                                val draft = requireNotNull(route)
                                val value = ActivityRoute(Uuid.random().toString(), 0, draft.name, draft.segments.toList(),
                                    draft.controls.toList(), description = draft.description.ifEmpty { draft.comment })
                                require(ActivityValidation.route(value) == null) { "Invalid GPX route" }
                                routes += value
                                route = null
                            }
                        }
                    }
                    EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE, EventType.ENTITY_REF -> {
                        if (reader.eventType == EventType.ENTITY_REF) require(reader.isKnownEntity)
                        if (stack.isEmpty()) require(reader.text.isBlank())
                        stack.lastOrNull()?.text?.let {
                            require(reader.text.length <= 4000 - it.length) { "GPX property limit exceeded" }
                            it.append(reader.text)
                        }
                    }
                    EventType.DOCDECL -> error("DTD declarations are not supported")
                    else -> Unit
                }
            }
            require(rootSeen && stack.isEmpty() && (routes.isNotEmpty() || waypoints.isNotEmpty())) { "GPX has no objects" }
            GpxDocument(routes, waypoints)
        } finally { reader.close() }
    }

    private class Frame(val name: String, val namespace: String) {
        var text: StringBuilder? = null
        var point: DraftPoint? = null
        var waypoint = false
        var closesSegment = false
        var closesRoute = false
        val seen = mutableSetOf<String>()
    }
    private class DraftPoint(val coordinate: GeographicCoordinate) {
        var name = ""
        var description = ""
        var comment = ""
        var elevation: ElevationMeasurement? = null
        var time: Long? = null
    }
    private class DraftRoute {
        var name = ""
        var description = ""
        var comment = ""
        val segments = mutableListOf<RouteSegment>()
        val controls = mutableListOf<RouteControlPoint>()
    }
}
