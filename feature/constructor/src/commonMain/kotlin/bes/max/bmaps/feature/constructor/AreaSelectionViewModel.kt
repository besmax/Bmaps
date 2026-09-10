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
    fun accept() {
        val bounds = state.value.bounds ?: return
        mutableState.value = state.value.copy(acceptedBounds = bounds)
        eventChannel.trySend(Unit)
    }
    fun retain(settings: MapSaveSettings) {
        mutableState.value = state.value.copy(settings = settings, selecting = false)
    }
    private fun updateBounds() {
        if (!state.value.selecting) return
        mutableState.value = state.value.copy(bounds = window?.selectionBounds())
    }
}

internal fun MapWindow.selectionBounds(): BoundingBox? {
    val west = (left + (right - left) * 0.12).coerceAtLeast(0.0)
    val east = (left + (right - left) * 0.88).coerceAtMost(1.0)
    val north = (top + (bottom - top) * 0.25).coerceAtLeast(0.0)
    val south = (top + (bottom - top) * 0.75).coerceAtMost(1.0)
    if (west >= east || north >= south) return null
    val nw = WebMercator.geographic(MapPoint(west, north)) ?: return null
    val se = WebMercator.geographic(MapPoint(east, south)) ?: return null
    return BoundingBox(nw.longitude, se.latitude, se.longitude, nw.latitude)
}
