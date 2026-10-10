/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor.download.presentation

import bes.max.bmaps.feature.constructor.selection.presentation.AreaSelectionViewModel
import bmaps.feature.constructor.generated.resources.*
import bes.max.bmaps.core.mapengine.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import bes.max.bmaps.domain.mapbuilder.BuildEstimate
import bes.max.bmaps.domain.mapbuilder.DownloadSizeEstimator
import bes.max.bmaps.domain.providers.BuiltInProviders
import bes.max.bmaps.feature.constructor.map.presentation.MapChoice
import kotlin.test.*

class MapSaveSettingsTest {
    @Test fun selectionTracksViewportAndAcceptanceFreezesBounds() = runTest {
        val model = AreaSelectionViewModel()
        model.choose()
        assertNull(model.state.value.bounds)
        model.updateWindow(MapWindow(0.0, 0.0, 1.0, 1.0))
        assertNull(model.state.value.bounds)
        model.startDrawing(0.12f, 0.25f)
        model.draw(0.88f, 0.75f)
        model.finishDrawing()
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
        val model = MapSaveSettingsViewModel(DownloadSizeEstimator { kotlinx.coroutines.flow.emptyFlow() })
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
        val model = MapSaveSettingsViewModel(DownloadSizeEstimator { kotlinx.coroutines.flow.emptyFlow() })
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
        val model = MapSaveSettingsViewModel(DownloadSizeEstimator { kotlinx.coroutines.flow.emptyFlow() })
        model.initialize(bounds, ZoomRange(0, 4), previous)
        assertEquals(setOf(1, 2, 3), model.state.value.selectedLevels)
        val singleLevel = MapSaveSettingsViewModel(DownloadSizeEstimator { kotlinx.coroutines.flow.emptyFlow() })
        singleLevel.initialize(bounds, ZoomRange(2, 2), previous)
        assertEquals(setOf(2), singleLevel.state.value.selectedLevels)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun backgroundEstimateCancelsOldSettingsAndDoesNotBlockConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var cancelled = false
            val model = MapSaveSettingsViewModel(DownloadSizeEstimator { request -> flow {
                try {
                    delay(1000)
                    emit(BuildEstimate(5, request.layers.first().zoomLevels.max() * 1000L))
                } finally {
                    if (request.layers.first().zoomLevels == setOf(0)) cancelled = true
                }
            } })
            val provider = BuiltInProviders.arcGis
            model.initialize(BoundingBox(-10.0, -10.0, 10.0, 10.0), ZoomRange(0, 4),
                choice = MapChoice(provider, provider.styles.first()))
            assertTrue(model.state.value.estimating)
            advanceTimeBy(400)
            runCurrent()
            model.selectZoomRange(1, 4)
            model.confirm()
            val submitted = model.events.first()
            assertNotNull(submitted.sizeEstimate)
            assertEquals((1..4).toSet(), submitted.levels)
            assertTrue(model.state.value.estimating)
            advanceUntilIdle()
            assertTrue(cancelled)
            assertFalse(model.state.value.estimating)
            assertEquals(4000L, model.state.value.estimate?.estimatedPackageBytes)
        } finally { Dispatchers.resetMain() }
    }

}
