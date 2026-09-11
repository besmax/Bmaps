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
    val frame: SelectionRectangle = SelectionRectangle(),
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

    fun updateWindow(value: MapWindow?) { window = value; updateBounds() }
    fun choose() { mutableState.value = state.value.copy(selecting = true); updateBounds() }
    fun cancel() { mutableState.value = state.value.copy(selecting = false, bounds = null) }
    fun drag(handle: SelectionHandle, dx: Float, dy: Float) {
        if (!state.value.selecting || !dx.isFinite() || !dy.isFinite()) return
        mutableState.value = state.value.copy(frame = state.value.frame.drag(handle, dx, dy))
        updateBounds()
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
        mutableState.value = state.value.copy(bounds = window?.selectionBounds(state.value.frame))
    }
}

internal fun MapWindow.selectionBounds(frame: SelectionRectangle = SelectionRectangle()): BoundingBox? {
    val west = (left + (right - left) * frame.left).coerceAtLeast(0.0)
    val east = (left + (right - left) * frame.right).coerceAtMost(1.0)
    val north = (top + (bottom - top) * frame.top).coerceAtLeast(0.0)
    val south = (top + (bottom - top) * frame.bottom).coerceAtMost(1.0)
    if (west >= east || north >= south) return null
    val nw = WebMercator.geographic(MapPoint(west, north)) ?: return null
    val se = WebMercator.geographic(MapPoint(east, south)) ?: return null
    return BoundingBox(nw.longitude, se.latitude, se.longitude, nw.latitude)
}

enum class SelectionHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class SelectionRectangle(
    val left: Float = 0.12f,
    val top: Float = 0.25f,
    val right: Float = 0.88f,
    val bottom: Float = 0.75f,
) {
    internal fun drag(handle: SelectionHandle, dx: Float, dy: Float): SelectionRectangle {
        if (handle == SelectionHandle.MOVE) {
            val x = dx.coerceIn(-left, 1f - right)
            val y = dy.coerceIn(-top, 1f - bottom)
            return copy(left = left + x, right = right + x, top = top + y, bottom = bottom + y)
        }
        val minimum = 0.12f
        return when (handle) {
            SelectionHandle.TOP_LEFT -> copy(left = (left + dx).coerceIn(0f, right - minimum), top = (top + dy).coerceIn(0f, bottom - minimum))
            SelectionHandle.TOP_RIGHT -> copy(right = (right + dx).coerceIn(left + minimum, 1f), top = (top + dy).coerceIn(0f, bottom - minimum))
            SelectionHandle.BOTTOM_LEFT -> copy(left = (left + dx).coerceIn(0f, right - minimum), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
            SelectionHandle.BOTTOM_RIGHT -> copy(right = (right + dx).coerceIn(left + minimum, 1f), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
            SelectionHandle.MOVE -> this
        }
    }
}
