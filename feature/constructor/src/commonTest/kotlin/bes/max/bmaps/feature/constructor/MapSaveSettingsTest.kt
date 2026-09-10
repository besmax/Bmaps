package bes.max.bmaps.feature.constructor

import bes.max.bmaps.core.mapengine.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MapSaveSettingsTest {
    @Test fun selectionTracksViewportAndAcceptanceFreezesBounds() = runTest {
        val model = AreaSelectionViewModel()
        model.choose()
        assertNull(model.state.value.bounds)
        model.updateWindow(MapWindow(0.0, 0.0, 1.0, 1.0))
        val bounds = assertNotNull(model.state.value.bounds)
        assertEquals(-136.8, bounds.west, 1e-9)
        assertEquals(136.8, bounds.east, 1e-9)
        model.accept()
        model.events.first()
        model.updateWindow(MapWindow(0.2, 0.2, 0.8, 0.8))
        assertEquals(bounds, model.state.value.acceptedBounds)
        assertNotEquals(bounds, model.state.value.bounds)
        model.cancel()
        assertFalse(model.state.value.selecting)
        assertNull(model.state.value.bounds)
    }

    @Test fun invalidNameAndEmptyLevelsCannotBeConfirmed() = runTest {
        val model = MapSaveSettingsViewModel()
        model.initialize(BoundingBox(-10.0, -10.0, 10.0, 10.0), ZoomRange(0, 4))
        assertTrue(Regex("\\d{4}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}").matches(model.state.value.name))
        model.name("  ")
        model.confirm()
        assertNotNull(model.state.value.error)
        model.name(" Test map ")
        model.toggle(0)
        model.confirm()
        assertContains(assertNotNull(model.state.value.error), "zoom")
        model.toggle(4)
        assertTrue(assertNotNull(model.state.value.estimate?.estimatedPackageBytes) > 0)
        model.confirm()
        val saved = model.events.first()
        assertEquals("Test map", saved.name)
        assertEquals(setOf(4), saved.levels)
    }
}
