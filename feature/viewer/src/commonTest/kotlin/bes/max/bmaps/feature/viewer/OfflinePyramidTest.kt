package bes.max.bmaps.feature.viewer

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import kotlin.test.*

class OfflinePyramidTest {
    @Test fun regionalLevelsBeyondEighteenKeepAbsoluteTileAddresses() {
        val bounds = assertNotNull(WebMercator.tileBounds(TileKey(23, 4_194_304, 4_194_304)))
        val pyramid = assertNotNull(offlinePyramid(layer(bounds, ZoomRange(23, 23)), 23))
        assertEquals(TileKey(23, 4_194_304, 4_194_304), pyramid.sourceKey(0, 0, 0))
        assertNotNull(pyramid.engineSize())
    }

    @Test fun automaticZoomDoesNotInventMissingLevelsOrTruncateOverflow() {
        val world = BoundingBox(-180.0, -WebMercator.MAX_LATITUDE, 180.0, WebMercator.MAX_LATITUDE)
        assertEquals(ZoomRange(0, 2), offlineAutomaticPyramid(layer(world, ZoomRange(0, 2)))?.levels)
        val sparse = layer(world, ZoomRange(0, 2)).copy(zoomLevels = setOf(0, 2))
        assertNull(offlineAutomaticPyramid(sparse))
        assertNull(offlinePyramid(sparse, 1))
        assertNotNull(offlinePyramid(sparse, 2))
        assertNull(offlineAutomaticPyramid(layer(world, ZoomRange(0, 23))))
    }

    @Test fun datelinePartsUseBothCanonicalEdgesWithoutAWorldSizedPyramid() {
        val layer = layer(BoundingBox(179.0, -1.0, -179.0, 1.0), ZoomRange(10, 10))
        val west = assertNotNull(offlinePyramid(layer, 10, 0))
        val east = assertNotNull(offlinePyramid(layer, 10, 1))
        assertTrue(west.originColumn > 1000)
        assertEquals(0L, east.originColumn)
        assertTrue(west.columns < 10 && east.columns < 10)
        assertNull(offlinePyramid(layer, 10, 2))
    }

    @Test fun initialViewportCentersTheDownloadedAreaWithinCoarseTiles() {
        val layer = layer(BoundingBox(30.0, 50.0, 31.0, 51.0), ZoomRange(0, 10))
        val pyramid = assertNotNull(offlineAutomaticPyramid(layer))
        val viewport = offlineInitialViewport(layer, 0, pyramid, 1.0)
        val world = pyramid.worldViewport(viewport)
        val coordinate = assertNotNull(WebMercator.geographic(world.center))
        assertEquals(30.5, coordinate.longitude, 0.000001)
        assertTrue(coordinate.latitude in 50.0..51.0)
        assertTrue(viewport.scale > 1.0)
    }

    @Test fun compositionRejectsDifferentBoundsDimensionsAndZoomCoverage() {
        val base = layer(BoundingBox(-10.0, -10.0, 10.0, 10.0), ZoomRange(0, 2))
        val overlay = base.copy(id = LayerId("satellite"), tiles = PackageAsset("layers/satellite.mbtiles", 0))
        assertTrue(alignedLayers(listOf(base, overlay.copy(opacity = 0.4, visible = false))))
        assertFalse(alignedLayers(listOf(base, overlay.copy(bounds = BoundingBox(-9.0, -10.0, 10.0, 10.0)))))
        assertFalse(alignedLayers(listOf(base, overlay.copy(tileWidth = 512, tileHeight = 512))))
        assertFalse(alignedLayers(listOf(base, overlay.copy(zoomLevels = setOf(0, 2)))))
        assertFalse(alignedLayers(listOf(base, overlay.copy(coordinateSystem = CoordinateSystemId.Wgs84))))
        assertTrue(alignedLayers(listOf(base.copy(zoomLevels = setOf(0, 2)), overlay.copy(zoomLevels = setOf(0, 2)))))
    }

    private fun layer(bounds: BoundingBox, range: ZoomRange) = PackageLayer(
        LayerId("base"), "Map", null, PackageAsset("map_data.mbtiles", 0), bounds, range,
    )
}
