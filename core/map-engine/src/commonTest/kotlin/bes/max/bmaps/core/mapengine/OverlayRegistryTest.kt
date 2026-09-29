/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
