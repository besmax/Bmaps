package bes.max.bmaps.feature.constructor

import androidx.lifecycle.ViewModel
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class AreaSelectionState(
    val selecting: Boolean = false,
    val frame: SelectionRectangle? = null,
    val drawing: Boolean = false,
    val points: List<MapPoint> = emptyList(),
    val bounds: BoundingBox? = null,
    val acceptedBounds: BoundingBox? = null,
    val settings: MapSaveSettings? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AreaSelectionViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(AreaSelectionState())
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<Unit>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var window: MapWindow? = null

    fun updateWindow(value: MapWindow?) {
        if (state.value.drawing && value != window) cancelDrawing()
        window = value
        updateBounds()
    }
    fun choose() {
        mutableState.value = state.value.copy(selecting = true, frame = null, points = emptyList(), drawing = false, bounds = null)
    }
    fun cancel() {
        mutableState.value = state.value.copy(selecting = false, drawing = false, points = emptyList(), frame = null, bounds = null)
    }
    fun startDrawing(x: Float, y: Float) {
        if (!state.value.selecting || window == null || !x.isFinite() || !y.isFinite()) return
        mutableState.value = state.value.copy(drawing = true, frame = null, points = emptyList(), bounds = null)
        draw(x, y)
    }
    fun draw(x: Float, y: Float) {
        if (!state.value.drawing || !x.isFinite() || !y.isFinite()) return
        val point = MapPoint(x.coerceIn(0f, 1f).toDouble(), y.coerceIn(0f, 1f).toDouble())
        val frame = state.value.frame
        mutableState.value = state.value.copy(
            points = state.value.points + point,
            frame = SelectionRectangle(
                minOf(frame?.left ?: x, point.x.toFloat()).coerceIn(0f, 1f),
                minOf(frame?.top ?: y, point.y.toFloat()).coerceIn(0f, 1f),
                maxOf(frame?.right ?: x, point.x.toFloat()).coerceIn(0f, 1f),
                maxOf(frame?.bottom ?: y, point.y.toFloat()).coerceIn(0f, 1f),
            ),
        )
    }
    fun finishDrawing() {
        if (!state.value.drawing) return
        mutableState.value = state.value.copy(drawing = false, points = emptyList())
        updateBounds()
    }
    fun cancelDrawing() {
        mutableState.value = state.value.copy(drawing = false, frame = null, points = emptyList(), bounds = null)
    }
    fun accept() {
        if (!state.value.selecting) return
        val bounds = state.value.bounds ?: return
        mutableState.value = state.value.copy(acceptedBounds = bounds)
        eventChannel.trySend(Unit)
    }
    fun retain(settings: MapSaveSettings) {
        mutableState.value = state.value.copy(settings = settings, selecting = false)
    }
    private fun updateBounds() {
        if (!state.value.selecting) return
        mutableState.value = state.value.copy(bounds = if (state.value.drawing) null else state.value.frame?.let { window?.selectionBounds(it) })
    }
}

internal fun MapWindow.selectionBounds(frame: SelectionRectangle): BoundingBox? {
    val west = (left + (right - left) * frame.left).coerceAtLeast(0.0)
    val east = (left + (right - left) * frame.right).coerceAtMost(1.0)
    val north = (top + (bottom - top) * frame.top).coerceAtLeast(0.0)
    val south = (top + (bottom - top) * frame.bottom).coerceAtMost(1.0)
    if (west >= east || north >= south) return null
    val nw = WebMercator.geographic(MapPoint(west, north)) ?: return null
    val se = WebMercator.geographic(MapPoint(east, south)) ?: return null
    return BoundingBox(nw.longitude, se.latitude, se.longitude, nw.latitude)
}

data class SelectionRectangle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
