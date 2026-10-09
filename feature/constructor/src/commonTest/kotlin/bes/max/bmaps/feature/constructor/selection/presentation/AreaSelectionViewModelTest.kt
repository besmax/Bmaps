/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor.selection.presentation

import bes.max.bmaps.core.mapengine.MapWindow
import kotlin.test.*

class AreaSelectionViewModelTest {
    private fun model() = AreaSelectionViewModel().apply {
        updateWindow(MapWindow(0.0, 0.0, 1.0, 1.0))
        choose()
    }

    @Test fun closedTraceUsesEveryExtremeRatherThanEndpoints() {
        val model = model()
        model.startDrawing(0.5f, 0.5f)
        model.draw(0.2f, 0.4f)
        model.draw(0.6f, 0.1f)
        model.draw(0.9f, 0.7f)
        model.draw(0.4f, 0.8f)
        model.draw(0.5f, 0.5f)
        assertNull(model.state.value.bounds)
        model.finishDrawing()
        assertEquals(SelectionRectangle(0.2f, 0.1f, 0.9f, 0.8f), model.state.value.frame)
        assertNotNull(model.state.value.bounds)
    }

    @Test fun redrawReplacesBoundsAndCancellationDiscardsUnfinishedTrace() {
        val model = model()
        model.startDrawing(0.1f, 0.1f)
        model.draw(0.9f, 0.9f)
        model.finishDrawing()
        model.startDrawing(0.4f, 0.4f)
        model.draw(0.6f, 0.6f)
        assertNull(model.state.value.bounds)
        model.finishDrawing()
        assertEquals(SelectionRectangle(0.4f, 0.4f, 0.6f, 0.6f), model.state.value.frame)
        model.startDrawing(0.2f, 0.2f)
        model.cancelDrawing()
        assertNull(model.state.value.bounds)
        assertNull(model.state.value.frame)
        assertFalse(model.state.value.drawing)
    }

    @Test fun cameraChangesCancelTheTraceToAvoidMixingCoordinateSystems() {
        val model = model()
        model.startDrawing(0.1f, 0.1f)
        model.draw(0.9f, 0.9f)
        model.updateWindow(MapWindow(0.2, 0.2, 0.8, 0.8))
        model.finishDrawing()
        assertFalse(model.state.value.drawing)
        assertNull(model.state.value.bounds)
    }

    @Test fun tapsAndAxisAlignedLinesCannotBeAccepted() {
        val model = model()
        model.startDrawing(0.5f, 0.5f)
        model.finishDrawing()
        assertNull(model.state.value.bounds)
        model.startDrawing(0.5f, 0.2f)
        model.draw(0.5f, 0.8f)
        model.finishDrawing()
        model.accept()
        assertNull(model.state.value.acceptedBounds)
    }

    @Test fun pointsAreClampedToViewportAndInvalidCoordinatesAreIgnored() {
        val model = model()
        model.startDrawing(-1f, -1f)
        model.draw(Float.NaN, 0.5f)
        model.draw(2f, 2f)
        model.finishDrawing()
        assertEquals(SelectionRectangle(0f, 0f, 1f, 1f), model.state.value.frame)
        assertNotNull(model.state.value.bounds)
    }
}
