/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlin.test.*

class AnnotationGpxTest {
    private fun gpx(body: String) = """<gpx version="1.1" creator="Bmaps" xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>"""

    @Test fun importsWaypointsRoutesAndSeparateTrackSegments() {
        val values = AnnotationImport.decode(gpx("""
            <wpt lat="55.75" lon="37.61"><name>Camp &amp; water</name><desc><![CDATA[Near <river>]]></desc><ele>100</ele></wpt>
            <rte><name>Walk</name><rtept lat="1" lon="2"/><rtept lat="3" lon="4"/></rte>
            <trk><name>Recorded</name><cmt>Morning</cmt>
                <trkseg><trkpt lat="5" lon="6"/><trkpt lat="7" lon="8"/></trkseg>
                <trkseg><trkpt lat="9" lon="10"/><trkpt lat="11" lon="12"/></trkseg>
            </trk>
        """))
        assertEquals(listOf(AnnotationKind.MARKER, AnnotationKind.LINE, AnnotationKind.LINE, AnnotationKind.LINE), values.map { it.kind })
        assertEquals(listOf(GeographicCoordinate(55.75, 37.61)), values.first().coordinates)
        assertEquals("Camp & water", values.first().name)
        assertEquals("Near <river>", values.first().description)
        assertEquals("Morning", values.last().description)
        assertEquals(listOf(GeographicCoordinate(9.0, 10.0), GeographicCoordinate(11.0, 12.0)), values.last().coordinates)
        assertEquals(values, AnnotationGeoJson.decode(AnnotationGeoJson.encode(values)))
    }

    @Test fun acceptsPrefixedGpx10AndIgnoresForeignExtensions() {
        val text = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/0" xmlns:x="urn:example" version="1.0">
            <x:wpt lat="0" lon="0"/>
            <g:wpt lat="1" lon="2"><x:name>Wrong</x:name><g:name>Right</g:name></g:wpt>
        </g:gpx>"""
        assertEquals("Right", AnnotationImport.decode("\uFEFF$text").single().name)
    }

    @Test fun removesConsecutiveDuplicateTrackPositions() {
        val values = AnnotationGpx.decode(gpx("""<rte><rtept lat="1" lon="2"/><rtept lat="1" lon="2"/><rtept lat="3" lon="4"/></rte>"""))
        assertEquals(2, values.single().coordinates.size)
    }

    @Test fun rejectsInvalidCoordinatesIncompleteGeometryAndMalformedXml() {
        listOf(
            gpx("""<wpt lat="91" lon="0"/>"""),
            gpx("""<wpt lat="NaN" lon="0"/>"""),
            gpx("""<wpt lat="0"/>"""),
            gpx("""<rte><rtept lat="0" lon="0"/></rte>"""),
            gpx("""<trk/>"""),
            gpx("""<trk><trkseg/></trk>"""),
            gpx(""),
            """<gpx version="2.0"><wpt lat="0" lon="0"/></gpx>""",
            """<gpx version="1.1" xmlns="urn:wrong"><wpt lat="0" lon="0"/></gpx>""",
            gpx("""<wpt lat="0" lon="0">"""),
            gpx("""<wpt lat="0" lon="0"/>""") + "<extra/>",
            """<!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" + gpx("""<wpt lat="0" lon="0"><name>&x;</name></wpt>"""),
        ).forEach { text -> assertFails { AnnotationGpx.decode(text) } }
    }

    @Test fun rejectsOversizedBatchesAndLinesWithoutTruncation() {
        assertFails {
            AnnotationGpx.decode(gpx("""<wpt lat="0" lon="0"/>""".repeat(AnnotationGeoJson.MAX_FEATURES + 1)))
        }
        val points = (0..AnnotationValidation.MAX_VERTICES).joinToString("") { """<rtept lat="0" lon="${it / 100.0}"/>""" }
        assertFails { AnnotationGpx.decode(gpx("<rte>$points</rte>")) }
        assertFails { AnnotationImport.decode(" ".repeat(AnnotationGeoJson.MAX_BYTES + 1) + gpx("")) }
    }
}
