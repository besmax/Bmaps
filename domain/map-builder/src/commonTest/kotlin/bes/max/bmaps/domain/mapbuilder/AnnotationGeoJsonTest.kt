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

    private fun point(longitude: Double, latitude: Double) = GeographicCoordinate(latitude, longitude)
}
