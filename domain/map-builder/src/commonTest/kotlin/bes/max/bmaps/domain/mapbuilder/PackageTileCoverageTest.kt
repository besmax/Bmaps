package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import kotlin.test.*

class PackageTileCoverageTest {
    @Test
    fun datelineDoesNotDuplicateTheWorldTile() {
        val coverage = PackageTileCoverage(BoundingBox(170.0, -10.0, -170.0, 10.0), ZoomRange(0, 1), emptySet())
        assertEquals(5L, coverage.count)
        assertTrue(coverage.contains(TileKey(0, 0, 0)))
        assertFalse(coverage.contains(TileKey(1, 2, 0)))
    }

    @Test
    fun separatedZoomLevelsDoNotDownloadTheGap() {
        val coverage = PackageTileCoverage(
            BoundingBox(-180.0, -WebMercator.MAX_LATITUDE, 180.0, WebMercator.MAX_LATITUDE),
            ZoomRange(0, 2), setOf(0, 2),
        )
        assertEquals(17L, coverage.count)
        assertFalse(coverage.contains(TileKey(1, 0, 0)))
        assertTrue(coverage.contains(TileKey(2, 3, 3)))
    }

    @Test
    fun overflowIsRejectedWithoutMaterializingTiles() {
        assertFailsWith<IllegalArgumentException> {
            PackageTileCoverage(BoundingBox(-180.0, -80.0, 180.0, 80.0), ZoomRange(52, 52), emptySet())
        }
    }
}
