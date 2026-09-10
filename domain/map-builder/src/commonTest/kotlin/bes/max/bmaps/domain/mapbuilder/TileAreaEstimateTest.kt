package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.BoundingBox
import kotlin.test.*

class TileAreaEstimateTest {
    @Test fun wholeWorldAndDatelineCountDistinctTiles() {
        val world = BoundingBox(-180.0, -85.0511287798066, 180.0, 85.0511287798066)
        assertEquals(21, TileAreaEstimate.estimate(world, setOf(0, 1, 2), 32000).tileCount)
        val crossing = BoundingBox(170.0, -10.0, -170.0, 10.0)
        assertEquals(1, TileAreaEstimate.estimate(crossing, setOf(0), 32000).tileCount)
        assertEquals(4, TileAreaEstimate.estimate(crossing, setOf(2), 32000).tileCount)
        assertNull(TileAreaEstimate.estimate(world, setOf(30), 32000).estimatedPackageBytes)
    }
}
