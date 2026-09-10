package bes.max.bmaps.core.mapengine

import kotlin.test.*

class WorldTileWindowTest {
    @Test fun highestSourceLevelUsesRegionalWindowWithoutMovingViewport() {
        val world = MapViewport(MapPoint(0.6123, 0.3123), 65536.0)
        val region = assertNotNull(worldTileWindow(ZoomRange(0, 23), 256, world))
        assertEquals(23, region.levels.max)
        assertNotNull(region.engineSize())
        val restored = region.worldViewport(region.localViewport(world))
        assertEquals(world.center.x, restored.center.x, 1e-12)
        assertEquals(world.center.y, restored.center.y, 1e-12)
        assertEquals(world.scale, restored.scale)
        assertEquals(region, worldTileWindow(ZoomRange(0, 23), 256, world.copy(scale = 24000.0), region))
        assertEquals(0, worldTileWindow(ZoomRange(0, 23), 256, world.copy(scale = 1000.0), region)?.levels?.min)
    }

    @Test fun movingBeyondRegionalCenterRebasesAtWorldEdges() {
        val initial = MapViewport(MapPoint(0.5, 0.5), 65536.0)
        val first = assertNotNull(worldTileWindow(ZoomRange(0, 23), 256, initial))
        for (point in listOf(MapPoint(0.8, 0.2), MapPoint(0.0, 0.0), MapPoint(1.0, 1.0))) {
            val moved = initial.copy(center = point)
            val next = assertNotNull(worldTileWindow(ZoomRange(0, 23), 256, moved, first))
            assertNotEquals(first, next)
            assertTrue(next.localViewport(moved).center.isValid)
            assertNotNull(next.engineSize())
        }
    }
}
