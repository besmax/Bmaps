package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
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
        assertEquals(-136.8, bounds.west, 1e-5)
        assertEquals(136.8, bounds.east, 1e-5)
        model.accept()
        model.events.first()
        model.updateWindow(MapWindow(0.2, 0.2, 0.8, 0.8))
        assertEquals(bounds, model.state.value.acceptedBounds)
        assertNotEquals(bounds, model.state.value.bounds)
        model.cancel()
        assertFalse(model.state.value.selecting)
        assertNull(model.state.value.bounds)
    }

    @Test fun invalidNameCannotBeConfirmedAndSingleLevelIsSupported() = runTest {
        val model = MapSaveSettingsViewModel()
        model.initialize(BoundingBox(-10.0, -10.0, 10.0, 10.0), ZoomRange(0, 4))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}_\\d{2}:\\d{2}").matches(model.state.value.name))
        model.name("  ")
        model.confirm()
        assertNotNull(model.state.value.error)
        model.name(" Test map ")
        model.selectZoomRange(4, 4)
        assertTrue(assertNotNull(model.state.value.estimate?.estimatedPackageBytes) > 0)
        model.confirm()
        val saved = model.events.first()
        assertEquals("Test map", saved.name)
        assertEquals(setOf(4), saved.levels)
    }

    @Test fun zoomRangeIncludesEveryLevelAndUpdatesEstimate() {
        val model = MapSaveSettingsViewModel()
        model.initialize(BoundingBox(-10.0, -10.0, 10.0, 10.0), ZoomRange(0, 4))
        val initialCount = assertNotNull(model.state.value.estimate).tileCount
        model.selectZoomRange(0, 4)
        assertEquals((0..4).toSet(), model.state.value.selectedLevels)
        assertTrue(assertNotNull(model.state.value.estimate).tileCount > initialCount)
        model.selectZoomRange(1, 3)
        assertEquals(setOf(1, 2, 3), model.state.value.selectedLevels)
        model.selectZoomRange(3, 1)
        model.selectZoomRange(-1, 3)
        model.selectZoomRange(1, 5)
        assertEquals(setOf(1, 2, 3), model.state.value.selectedLevels)
    }

    @Test fun restoredSparseSelectionBecomesContinuousAndEmptySelectionUsesMinimum() {
        val bounds = BoundingBox(-10.0, -10.0, 10.0, 10.0)
        val previous = MapSaveSettings("Saved", bounds, setOf(1, 3, 8))
        val model = MapSaveSettingsViewModel()
        model.initialize(bounds, ZoomRange(0, 4), previous)
        assertEquals(setOf(1, 2, 3), model.state.value.selectedLevels)
        val singleLevel = MapSaveSettingsViewModel()
        singleLevel.initialize(bounds, ZoomRange(2, 2), previous)
        assertEquals(setOf(2), singleLevel.state.value.selectedLevels)
    }

}
