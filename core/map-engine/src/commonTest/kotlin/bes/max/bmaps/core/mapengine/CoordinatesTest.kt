package bes.max.bmaps.core.mapengine

import kotlin.test.*

class CoordinatesTest {
    @Test fun publishedHachikoFixtureAndWorldEdges() {
        assertEquals(TileKey(18, 232798, 103246), WebMercator.tileAt(GeographicCoordinate(35.6590699, 139.7006793), 18))
        assertEquals(TileKey(0, 0, 0), WebMercator.tileAt(GeographicCoordinate(0.0, 0.0), 0))
        assertEquals(TileKey(3, 7, 7), WebMercator.tileAt(GeographicCoordinate(-WebMercator.MAX_LATITUDE, 180.0), 3))
        assertEquals(TileKey(3, 0, 0), WebMercator.tileAt(GeographicCoordinate(WebMercator.MAX_LATITUDE, -180.0), 3))
        assertEquals(TileKey(33, 4294967296, 4294967296), WebMercator.tileAt(GeographicCoordinate(0.0, 0.0), 33))
        assertNull(WebMercator.tileAt(GeographicCoordinate(0.0, 0.0), 53))
    }
    @Test fun projectionRoundTripAndKnownMetricValue() {
        val origin = ProjectedCoordinate(180.0, 0.0, CoordinateSystemId.Wgs84)
        val meters = assertIs<TransformResult.Success>(WebMercator.transform(origin, CoordinateSystemId.WebMercator)).coordinate
        assertEquals(20037508.342789244, meters.x, 1e-8)
        for (latitude in listOf(-WebMercator.MAX_LATITUDE, -45.0, 0.0, 45.0, WebMercator.MAX_LATITUDE)) {
            val input = ProjectedCoordinate(120.0, latitude, CoordinateSystemId.Wgs84)
            val projected = assertIs<TransformResult.Success>(WebMercator.transform(input, CoordinateSystemId.WebMercator)).coordinate
            val inverse = assertIs<TransformResult.Success>(WebMercator.transform(projected, CoordinateSystemId.Wgs84)).coordinate
            assertEquals(input.x, inverse.x, 1e-10)
            assertEquals(input.y, inverse.y, 1e-10)
        }
    }
    @Test fun invalidCoverageNeverClampsOrGuessesCrs() {
        for (point in listOf(GeographicCoordinate(86.0, 0.0), GeographicCoordinate(0.0, 181.0), GeographicCoordinate(Double.NaN, 0.0))) {
            assertNull(WebMercator.normalized(point))
        }
        assertEquals(TransformResult.UnsupportedCoordinateSystem, WebMercator.transform(
            ProjectedCoordinate(0.0, 0.0, CoordinateSystemId("SK-91")), CoordinateSystemId.Wgs84))
        assertEquals(TransformResult.OutsideCoverage, WebMercator.transform(
            ProjectedCoordinate(0.0, Double.POSITIVE_INFINITY, CoordinateSystemId.WebMercator), CoordinateSystemId.Wgs84))
    }
    @Test fun antimeridianBoundsSplitWithoutSortingOrIncludingUnselectedArea() {
        val bounds = BoundingBox(170.0, -10.0, -170.0, 10.0)
        assertEquals(listOf(bounds.copy(east = 180.0), bounds.copy(west = -180.0)), WebMercator.splitBounds(bounds))
        val world = BoundingBox(-180.0, -WebMercator.MAX_LATITUDE, 180.0, WebMercator.MAX_LATITUDE)
        assertEquals(listOf(world), WebMercator.splitBounds(world))
        assertEquals(world, WebMercator.tileBounds(TileKey(0, 0, 0)))
        assertNull(WebMercator.splitBounds(bounds.copy(north = 90.0)))
        assertNull(WebMercator.splitBounds(bounds.copy(west = 1.0, east = 1.0)))
        assertNull(WebMercator.tileBounds(TileKey(2, 4, 0)))
    }
    @Test fun regionalInteractionCoordinatesRoundTripAndRejectOutsideWindow() {
        val region = TilePyramid(ZoomRange(2, 5), 1, 1)
        val coordinate = region.coordinateAt(MapPoint(0.5, 0.5))!!
        assertEquals(-45.0, coordinate.longitude, 1e-10)
        val point = region.positionOf(coordinate)!!
        assertEquals(0.5, point.x, 1e-10)
        assertEquals(0.5, point.y, 1e-10)
        assertNull(region.positionOf(GeographicCoordinate(0.0, 120.0)))
        assertNull(region.coordinateAt(MapPoint(-0.1, 0.5)))
    }
    @Test fun regionalPyramidMapsHighLevelsAndRejectsOverflow() {
        val region = TilePyramid(ZoomRange(40, 42), 123456789012, 345678901234, 2, 3)
        assertEquals(2048 to 3072, region.engineSize())
        assertEquals(TileKey(42, 493827156055, 1382715604947), region.sourceKey(2, 11, 7))
        assertNull(region.sourceKey(2, 12, 0))
        assertNull(TilePyramid(ZoomRange(0, 23)).engineSize())
        assertNotNull(TilePyramid(ZoomRange(0, 22)).engineSize())
        val edge = TilePyramid(ZoomRange(62, 63), (1L shl 62) - 1, (1L shl 62) - 1)
        assertEquals(TileKey(63, Long.MAX_VALUE, Long.MAX_VALUE), edge.sourceKey(1, 1, 1))
    }
}
