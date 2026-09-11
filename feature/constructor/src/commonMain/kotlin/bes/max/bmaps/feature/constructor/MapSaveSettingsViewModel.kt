package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.lifecycle.ViewModel
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.BuildEstimate
import bes.max.bmaps.domain.mapbuilder.TileAreaEstimate
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.number
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class MapSaveSettings(val name: String, val bounds: BoundingBox, val levels: Set<Int>)
data class MapSaveSettingsState(
    val name: String = "", val availableLevels: List<Int> = emptyList(), val selectedLevels: Set<Int> = emptySet(),
    val estimate: BuildEstimate? = null, val error: StringResource? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MapSaveSettingsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(MapSaveSettingsState())
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<MapSaveSettings>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var bounds: BoundingBox? = null

    fun initialize(area: BoundingBox, range: ZoomRange, previous: MapSaveSettings? = null) {
        if (bounds != null) return
        if (WebMercator.splitBounds(area) == null || range.min !in 0..30 || range.max !in range.min..30) {
            mutableState.value = state.value.copy(error = Res.string.unsupported_area_or_zoom)
            return
        }
        bounds = area
        val available = (range.min..range.max).toList()
        mutableState.value = MapSaveSettingsState(previous?.name ?: defaultMapName(), available,
            previous?.levels?.intersect(available.toSet()) ?: setOf(range.min))
        estimate()
    }
    fun name(value: String) { mutableState.value = state.value.copy(name = value.take(120), error = null) }
    fun toggle(level: Int) {
        if (level !in state.value.availableLevels) return
        val selected = state.value.selectedLevels
        mutableState.value = state.value.copy(selectedLevels = if (level in selected) selected - level else selected + level, error = null)
        estimate()
    }
    fun confirm() {
        val current = state.value
        val area = bounds ?: return
        val name = current.name.trim()
        val error = when {
            name.isBlank() || name.any { it.code < 32 } -> Res.string.invalid_map_name
            current.selectedLevels.isEmpty() -> Res.string.zoom_selection_required
            current.estimate?.estimatedPackageBytes == null -> Res.string.selection_estimate_unavailable
            else -> null
        }
        mutableState.value = current.copy(error = error)
        if (error == null) eventChannel.trySend(MapSaveSettings(name, area, current.selectedLevels.toSet()))
    }
    private fun estimate() {
        val area = bounds ?: return
        mutableState.value = state.value.copy(estimate = TileAreaEstimate.estimate(area, state.value.selectedLevels, 32_000))
    }
}

internal fun defaultMapName(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.two() = toString().padStart(2, '0')
    return "${now.year}.${now.month.number.two()}.${now.day.two()} ${now.hour.two()}:${now.minute.two()}"
}
