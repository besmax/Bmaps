/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import kotlinx.coroutines.test.runTest
import kotlinx.io.*
import kotlin.test.*

class ActivityExportWriterTest {
    @Test fun invalidXmlNameFailsInsteadOfSharingMalformedGeometry() = runTest {
        assertFailsWith<ActivityExportException> { ActivityExportWriter(Buffer()).gpxStart("bad\u0000name") }
    }

    @Test fun gpxEscapesNamesRetainsTimesAndLeavesTheCallerSinkOpen() = runTest {
        val sink = Buffer()
        val writer = ActivityExportWriter(sink)
        writer.gpxStart("A & <B> \"trail\"")
        writer.text("<trkseg>")
        writer.point(0.00001, -0.00002, 1000)
        writer.point(50.001, 30.0, null)
        writer.text("</trkseg></trk></gpx>")
        writer.finish()
        val text = sink.readByteArray().decodeToString()
        assertTrue("lat=\"0.00001" in text)
        assertTrue("lon=\"-0.00002" in text)
        val decoded = ActivityRouteGpx.decode(text).single()
        assertEquals("A & <B> \"trail\"", decoded.name)
        assertEquals(1000L, decoded.segments.single().vertices.first().sourceTimeEpochMillis)
        assertNull(decoded.segments.single().vertices.last().sourceTimeEpochMillis)
        sink.write(byteArrayOf(1))
        assertEquals(1L, sink.size)
    }
    @Test fun outputIsWrittenIncrementallyBeforeFinish() = runTest {
        val sink = Buffer()
        val writer = ActivityExportWriter(sink)
        repeat(1000) { writer.text("x".repeat(100)) }
        assertTrue(sink.size >= 65_536)
        writer.finish()
        assertEquals(100_000L, sink.size)
    }
}
