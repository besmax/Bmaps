/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityRouteGpxTest {
    @Test
    fun detailedTrackGroupsSegmentsAndPreservesSourceMetadata() = runTest {
        val points = (0..4_000).joinToString("") { "<trkpt lat=\"45\" lon=\"${30.0 + it * 0.00006}\"/>" }
        val route = ActivityRouteGpx.decode("""
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Forest loop</name><desc>Detailed route</desc>
                <trkseg>$points</trkseg>
                <trkseg><trkpt lat="45.1" lon="30.2"><name>Camp</name><ele>-2.5</ele><time>2026-10-12T08:00:00Z</time></trkpt><trkpt lat="45.2" lon="30.3"/></trkseg>
              </trk>
            </gpx>
        """.trimIndent()).single()
        assertEquals(2, route.segments.size)
        assertEquals(4_001, route.segments.first().vertices.size)
        assertEquals("Detailed route", route.description)
        assertEquals("GPX_UNSPECIFIED", route.segments.last().vertices.first().elevation?.verticalReference)
        assertEquals(-2.5, route.segments.last().vertices.first().elevation?.meters)
        assertTrue(checkNotNull(route.segments.last().vertices.first().sourceTimeEpochMillis) > 0)
        assertEquals(RoutePosition(1, 0, 0.0), route.controlPoints.single().position)
        val preview = ActivityRoutePreview.create(route, 100)
        assertEquals(2, preview.segments.size)
        assertTrue(preview.segments.sumOf { it.coordinates.size } <= 100)
        assertEquals(4_001, route.segments.first().vertices.size)
        assertEquals(4_000, preview.segments.first().sourceVertexIndices.last())
    }

    @Test
    fun streamHandlesSplitUtf8AndLeavesCallerSourceOpen() = runTest {
        val source = ChunkSource("\uFEFF<g:gpx xmlns:g=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\"><g:rte><g:name>Trail 🏕</g:name><g:rtept lat=\"50\" lon=\"30\"/><g:rtept lat=\"50.01\" lon=\"30.01\"/></g:rte></g:gpx>".encodeToByteArray())
        assertEquals("Trail 🏕", ActivityRouteGpx.decode(source).single().name)
        assertFalse(source.closed)
    }

    @Test
    fun duplicatePositionsNormalizeWithoutJoiningTrackGaps() = runTest {
        val route = ActivityRouteGpx.decode("<gpx version=\"1.0\"><trk><trkseg>${point(50.0)}${point(50.0)}${point(50.01)}</trkseg><trkseg>${point(50.1)}${point(50.2)}</trkseg></trk></gpx>").single()
        assertEquals(listOf(2, 2), route.segments.map { it.vertices.size })
        assertFails { ActivityRouteGpx.decode("<gpx version=\"1.1\"><trk><trkseg>${point(50.0)}${point(50.01)}</trkseg><trkseg>${point(Double.NaN)}${point(50.2)}</trkseg></trk></gpx>") }
    }

    @Test
    fun declarationsInvalidUtf8AndOversizedCollectionsFailExplicitly() = runTest {
        assertFails { ActivityRouteGpx.decode(ChunkSource("<!DOCTYPE gpx [<!ENTITY x 'value'>]><gpx version=\"1.1\"/>".encodeToByteArray())) }
        assertFails { ActivityRouteGpx.decode(ChunkSource(byteArrayOf(0xC0.toByte(), 0xAF.toByte()))) }
        val routes = (0..ActivityLimits.MAX_IMPORT_ROUTES).joinToString("") { "<rte>${point(50.0, "rtept")}${point(50.1, "rtept")}</rte>" }
        assertFails { ActivityRouteGpx.decode("<gpx version=\"1.1\">$routes</gpx>") }
        val controls = (0..ActivityLimits.MAX_CONTROL_POINTS).joinToString("") { "<rtept lat=\"50\" lon=\"${30.0 + it * 0.00001}\"><name>Point</name></rtept>" }
        assertFails { ActivityRouteGpx.decode("<gpx version=\"1.1\"><rte>$controls</rte></gpx>") }
    }

    @Test
    fun previewRetainsEndpointsAndControlPointEdges() = runTest {
        val route = ActivityRouteGpx.decode("<gpx version=\"1.1\"><rte>${point(50.0, "rtept")}<rtept lat=\"50.01\" lon=\"30\"><name>Turn</name></rtept>${point(50.02, "rtept")}${point(50.03, "rtept")}</rte></gpx>").single()
        val preview = ActivityRoutePreview.create(route, 4).segments.single()
        assertEquals(listOf(0, 1, 2, 3), preview.sourceVertexIndices)
        assertFails { ActivityRoutePreview.create(route, 2) }
    }

    @Test
    fun detailedStravaStyleTrackCanBecomeAPlannedActivity() = runTest {
        val points = (0 until 2_749).joinToString("") { index ->
            val step = index / 2
            """<trkpt lat="50" lon="${30.0 + step * 0.00001}"><ele>${100.0 + step}</ele><time>2026-10-07T13:59:42Z</time></trkpt>"""
        }
        val route = ActivityRouteGpx.decode("""
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
              <metadata><time>2026-10-07T13:59:42Z</time></metadata>
              <trk><name>Synthetic mountain bike ride</name><type>EMountainBikeRide</type><trkseg>$points</trkseg></trk>
            </gpx>
        """.trimIndent()).single()
        assertEquals(1_375, route.segments.single().vertices.size)
        assertEquals(100.0, route.segments.single().vertices.first().elevation?.meters)
        assertNotNull(route.segments.single().vertices.last().sourceTimeEpochMillis)
        assertTrue(RouteNavigationIndex(route).totalMeters > 0.0)
        val start = RoutePosition(0, 0, 0.0)
        val selection = RouteSelection(route, RouteDirection.FORWARD, start)
        assertNull(ActivityValidation.start(ActivityStartRequest("activity", route.name, ActivityProfile.ROAD_TRAVEL, selection)))
        val time = ActivityTime(1_000, 10, "phone-process")
        val navigation = ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE,
            route.id, route.revision, selection.direction, start, start)
        assertNull(ActivityValidation.record(ActivityRecord("activity", 0, "generation", route.name,
            ActivityProfile.ROAD_TRAVEL, ActivityStatus.RECORDING, time, time, route = selection, navigation = navigation)))
    }

    @Test
    fun unifiedGpxPreservesWaypointsAndGroupedRoutes() = runTest {
        val document = ActivityRouteGpx.decodeDocument(Buffer().apply { write("""
            <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" version="1.1">
              <g:wpt lat="50" lon="30"><g:name>Camp</g:name><g:cmt>Rest</g:cmt><g:ele>-2.5</g:ele><g:time>2026-10-07T13:59:42Z</g:time></g:wpt>
              <g:trk><g:trkseg><g:trkpt lat="50" lon="30"/><g:trkpt lat="50.1" lon="30.1"/></g:trkseg>
                <g:trkseg><g:trkpt lat="51" lon="31"/><g:trkpt lat="51.1" lon="31.1"/></g:trkseg></g:trk>
            </g:gpx>
        """.trimIndent().encodeToByteArray()) })
        assertEquals(2, document.routes.single().segments.size)
        assertEquals("Camp", document.waypoints.single().name)
        assertEquals("Rest", document.waypoints.single().description)
        assertEquals(-2.5, document.waypoints.single().elevation?.meters)
        assertNotNull(document.waypoints.single().sourceTimeEpochMillis)
        assertEquals(GeographicCoordinate(50.0, 30.0), document.waypoints.single().coordinate)
    }

    @Test
    fun waypointOnlyGpxIsSupportedByUnifiedImportAndInvalidWaypointRejectsWholeDocument() = runTest {
        val xml = "<gpx version=\"1.1\"><wpt lat=\"50\" lon=\"30\"/></gpx>"
        val document = ActivityRouteGpx.decodeDocument(Buffer().apply { write(xml.encodeToByteArray()) })
        assertTrue(document.routes.isEmpty())
        assertEquals(1, document.waypoints.size)
        assertFails { ActivityRouteGpx.decode(xml) }
        assertFails { ActivityRouteGpx.decodeDocument(Buffer().apply { write(xml.replace("lat=\"50\"", "lat=\"NaN\"").encodeToByteArray()) }) }
    }

    private fun point(latitude: Double, tag: String = "trkpt") = "<$tag lat=\"$latitude\" lon=\"30\"/>"

    private class ChunkSource(bytes: ByteArray) : RawSource {
        private val data = Buffer().apply { write(bytes) }
        var closed = false
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = data.readAtMostTo(sink, minOf(byteCount, 1))
        override fun close() { closed = true }
    }
}
