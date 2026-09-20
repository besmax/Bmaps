package bes.max.bmaps.core.mapengine

import kotlin.test.*

class OverlayRegistryTest {
    @Test fun viewportUpdatesKeepUnchangedOverlaysAndRemovalRestoresIndividualMarkers() {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val registry = OverlayRegistry<String>({ it }, added::add, removed::add)
        registry.sync(listOf("cluster", "route"))
        registry.sync(listOf("cluster", "route"))
        assertEquals(listOf("cluster", "route"), added)
        assertTrue(removed.isEmpty())
        registry.sync(listOf("a", "b", "route"))
        assertEquals(listOf("cluster"), removed)
        assertEquals(listOf("cluster", "route", "a", "b"), added)
        registry.clear()
        registry.clear()
        assertEquals(listOf("cluster", "a", "b", "route"), removed)
    }
}
