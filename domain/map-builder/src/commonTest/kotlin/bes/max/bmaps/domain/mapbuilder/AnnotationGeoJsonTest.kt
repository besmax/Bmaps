/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.serialization.json.*
import kotlin.test.*

class AnnotationGeoJsonTest {
    @Test fun recoversLegacyStoredPolygonWithoutRelaxingImports() {
        val stored = """{"type":"Feature","id":"area","geometry":{"type":"Polygon","coordinates":[[[1,1],[3,1],[2,3],1,1]]},"properties":{"name":"Camp","marker-color":"#123456","rating":4}}"""
        val polygon = AnnotationGeoJson.decodeStoredFeature(stored)
        assertEquals("area", polygon.id)
        assertEquals(AnnotationKind.POLYGON, polygon.kind)
        assertEquals(listOf(point(1.0, 1.0), point(3.0, 1.0), point(2.0, 3.0)), polygon.coordinates)
        assertEquals("Camp", polygon.name)
        assertEquals("#123456", polygon.color)
        assertEquals(JsonPrimitive(4), polygon.properties["rating"])
        assertEquals(listOf(polygon), AnnotationGeoJson.decode(AnnotationGeoJson.encode(listOf(polygon))))
        assertFails { AnnotationGeoJson.decode(stored) }
    }

    @Test fun storedPolygonRecoveryRejectsOtherCorruption() {
        listOf(
            "[[1,1],[3,1],[2,3],9,9]",
            "[[1,1],[3,1],[2,3],\"1\",\"1\"]",
            "[[1,1],[3,1],[2,3]]",
            "[[0,0],[2,2],[0,2],[2,0],0,0]",
        ).forEach { ring ->
            assertFails {
                AnnotationGeoJson.decodeStoredFeature("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[$ring]},"properties":{}}""")
            }
        }
    }

    @Test fun roundTripPreservesGeometryIdentityAppearanceAndCustomProperties() {
        val values = listOf(
            Annotation("pin", AnnotationKind.MARKER, listOf(point(37.6, 55.7)), "Camp", "Water nearby", "#123456", "future-icon",
                buildJsonObject { put("rating", 4); put("details", buildJsonObject { put("open", true) }) }),
            Annotation("route", AnnotationKind.LINE, listOf(point(1.0, 2.0), point(3.0, 4.0))),
            Annotation("area", AnnotationKind.POLYGON, listOf(point(1.0, 1.0), point(3.0, 1.0), point(2.0, 3.0))),
        )
        val encoded = AnnotationGeoJson.encode(values)
        assertEquals(values, AnnotationGeoJson.decode(encoded))
        val features = Json.parseToJsonElement(encoded).jsonObject.getValue("features").jsonArray
        assertEquals(values, features.map { AnnotationGeoJson.decodeStoredFeature(it.toString()) })
        assertEquals(JsonArray(listOf(JsonPrimitive(37.6), JsonPrimitive(55.7))), features[0].jsonObject.getValue("geometry").jsonObject["coordinates"])
        val ring = features[2].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.single().jsonArray
        assertEquals(4, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test fun rejectsUnsupportedOrInvalidInputWithoutPartialImport() {
        fun feature(geometry: String) = """{"type":"Feature","geometry":$geometry,"properties":{}}"""
        listOf(
            """{"type":"MultiPoint","coordinates":[[0,0]]}""",
            """{"type":"Point","coordinates":[0,0,10]}""",
            """{"type":"Point","coordinates":[181,0]}""",
            """{"type":"LineString","coordinates":[[0,0]]}""",
            """{"type":"Polygon","coordinates":[[[0,0],[1,0],[0,1]]]}""",
            """{"type":"Polygon","coordinates":[[[0,0],[2,0],[0,2],[0,0]],[[0,0],[1,0],[0,1],[0,0]]]}""",
        ).forEach { assertFails { AnnotationGeoJson.decode(feature(it)) } }
        val bowTie = Annotation(kind = AnnotationKind.POLYGON, coordinates = listOf(point(0.0, 0.0), point(2.0, 2.0), point(0.0, 2.0), point(2.0, 0.0)))
        assertNotNull(AnnotationValidation.error(bowTie))
        assertFails { AnnotationGeoJson.decode("""{"type":"FeatureCollection","crs":{},"features":[]}""") }
    }

    @Test fun longitudeAndPolarCoordinatesArePreservedWithoutWrapping() {
        val line = Annotation(kind = AnnotationKind.LINE, coordinates = listOf(point(-179.0, 80.0), point(179.0, 89.0)))
        assertNull(AnnotationValidation.error(line))
        assertEquals(-179.0, line.boundingBox().west)
        assertEquals(179.0, line.boundingBox().east)
        assertEquals(listOf(line), AnnotationGeoJson.decode(AnnotationGeoJson.encode(listOf(line))))
    }

    @Test fun heightsRoundTripWithNullZeroAndNegativeValues() {
        val value = Annotation("heights", AnnotationKind.LINE,
            listOf(point(1.0, 1.0), point(2.0, 2.0), point(3.0, 3.0)),
            elevations = listOf(AnnotationElevation(0.0, "EGM96"), null, AnnotationElevation(-12.5, "EGM2008")))
        val encoded = AnnotationGeoJson.encode(listOf(value))
        assertEquals(listOf(value), AnnotationGeoJson.decode(encoded))
        val feature = Json.parseToJsonElement(encoded).jsonObject.getValue("features").jsonArray.single()
        assertEquals(value, AnnotationGeoJson.decodeStoredFeature(feature.toString()))
        assertEquals(JsonNull, feature.jsonObject.getValue("properties").jsonObject
            .getValue("bmaps-elevations").jsonArray[1])
    }

    @Test fun polygonWindingKeepsHeightsAttachedToTheirVertices() {
        val polygon = Annotation("clockwise", AnnotationKind.POLYGON,
            listOf(point(0.0, 0.0), point(0.0, 2.0), point(2.0, 0.0)),
            elevations = listOf(AnnotationElevation(1.0, "EGM96"), null, AnnotationElevation(3.0, "EGM96")))
        val decoded = AnnotationGeoJson.decode(AnnotationGeoJson.encode(listOf(polygon))).single()
        assertEquals(polygon.coordinates.zip(polygon.elevations).toMap(), decoded.coordinates.zip(decoded.elevations).toMap())
    }

    @Test fun legacyAndUnknownHeightsRemainNullAndInvalidHeightsAreRejected() {
        val value = Annotation("unknown", AnnotationKind.MARKER, listOf(point(1.0, 1.0)))
        val encoded = AnnotationGeoJson.feature(value)
        assertEquals(JsonArray(listOf(JsonNull)), encoded.getValue("properties").jsonObject["bmaps-elevations"])
        assertEquals(value, AnnotationGeoJson.decodeStoredFeature(encoded.toString()))
        val malformed = encoded.toMutableMap().apply {
            put("properties", JsonObject(encoded.getValue("properties").jsonObject.toMutableMap().apply {
                put("bmaps-elevations", JsonArray(emptyList()))
            }))
        }
        assertFails { AnnotationGeoJson.decodeStoredFeature(JsonObject(malformed).toString()) }
        assertNotNull(AnnotationValidation.error(value.copy(elevations = listOf(AnnotationElevation(Double.NaN, "EGM96")))))
    }

    private fun point(longitude: Double, latitude: Double) = GeographicCoordinate(latitude, longitude)
}
