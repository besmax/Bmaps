package bes.max.bmaps.core.mapengine

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CameraControllerTest {
    private val camera = MapCameraSnapshot(7, TilePyramid(ZoomRange(0, 5)), MapViewport(),
        MapWindow(0.0, 0.0, 1.0, 1.0), IntSize(1024, 1024), 0.125, 32.0)

    @Test fun cameraCommandsClampRelativeScaleAndRejectInvalidDestinations() {
        val point = MapPoint(0.2, 0.7)
        assertEquals(MapViewport(point, 32.0), cameraDestination(camera, MapViewport(point, 90.0)))
        assertEquals(MapViewport(point, 1.0), cameraDestination(camera, MapViewport(point, 0.5)))
        assertNull(cameraDestination(camera, MapViewport(point, Double.NaN)))
        assertNull(cameraDestination(camera, MapViewport(MapPoint(-1.0, 0.5), 2.0)))
        assertNull(cameraDestination(camera.copy(zoomEnabled = false), MapViewport(point, 2.0)))
    }

    @Test fun replacingQueuedMoveReportsCancellationAndOnlyLatestMoveCompletes() = runTest {
        val controller = RasterMapController()
        controller.moveTo(7, 1, MapViewport(scale = 2.0))
        controller.moveTo(7, 2, MapViewport(scale = 8.0))
        assertEquals(CameraMoveResult(1, 7, CameraMoveOutcome.CANCELLED, null), controller.results.first())
        val command = controller.commands.first()
        assertTrue(controller.isCurrent(command))
        controller.finish(command, CameraMoveOutcome.COMPLETED, camera)
        assertEquals(CameraMoveResult(2, 7, CameraMoveOutcome.COMPLETED, camera), controller.results.first())
        assertFalse(controller.isCurrent(command))
    }

    @Test fun gestureAndRelativeZoomCancelPendingAbsoluteMove() = runTest {
        val controller = RasterMapController()
        controller.moveTo(7, 1, MapViewport(scale = 2.0))
        controller.cancelMove()
        assertEquals(CameraMoveOutcome.CANCELLED, controller.results.first().outcome)
        controller.moveTo(7, 2, MapViewport(scale = 2.0))
        controller.zoomIn()
        assertEquals(2L, controller.results.first().requestId)
        assertTrue(controller.commands.first() is CameraCommand.Zoom)
    }

    @Test fun engineRetirementCancelsItsPendingMove() = runTest {
        val controller = RasterMapController()
        controller.moveTo(7, 42, MapViewport(scale = 2.0))
        controller.retire(7)
        assertEquals(CameraMoveResult(42, 7, CameraMoveOutcome.CANCELLED, null), controller.results.first())
    }
}
