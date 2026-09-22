@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.lifecycle.ViewModel
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.ElevationDataset
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

data class AdditionalLayer(val id: String, val choice: MapChoice, val visible: Boolean = true)
data class MapSaveSettings(val name: String, val bounds: BoundingBox, val levels: Set<Int>,
    val layers: List<AdditionalLayer> = emptyList(), val rootVisible: Boolean = true,
    val elevationDataset: ElevationDataset = ElevationDataset.NONE)
data class MapSaveSettingsState(
    val name: String = "", val availableLevels: List<Int> = emptyList(), val selectedLevels: Set<Int> = emptySet(),
    val estimate: BuildEstimate? = null, val error: StringResource? = null,
    val layers: List<AdditionalLayer> = emptyList(), val rootVisible: Boolean = true,
    val addingLayer: Boolean = false,
    val elevationDataset: ElevationDataset = ElevationDataset.NONE,
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
        if (WebMercator.splitBounds(area) == null || range.min !in 0..52 || range.max !in range.min..52) {
            mutableState.value = state.value.copy(error = Res.string.unsupported_area_or_zoom)
            return
        }
        bounds = area
        val available = (range.min..range.max).toList()
        mutableState.value = MapSaveSettingsState(previous?.name ?: defaultMapName(), available,
            previous?.levels?.intersect(available.toSet())?.takeIf { it.isNotEmpty() }
                ?.let { (it.min()..it.max()).toSet() } ?: setOf(range.min),
            layers = previous?.layers.orEmpty(), rootVisible = previous?.rootVisible ?: true,
            elevationDataset = previous?.elevationDataset ?: ElevationDataset.NONE)
        estimate()
    }
    fun showLayerPicker(show: Boolean) { mutableState.value = state.value.copy(addingLayer = show) }
    fun addLayer(choice: MapChoice, root: MapChoice) {
        val area = bounds ?: return
        if (state.value.layers.size >= 31) { mutableState.value = state.value.copy(error = Res.string.layer_limit, addingLayer = false); return }
        val id = kotlin.uuid.Uuid.random().toString()
        val layers = state.value.layers + AdditionalLayer(id, choice)
        val settings = MapSaveSettings(state.value.name, area, state.value.selectedLevels, layers,
            state.value.rootVisible)
        val error = validateComposition(settings, root)
        if (error != null) { mutableState.value = state.value.copy(error = error, addingLayer = false); return }
        mutableState.value = state.value.copy(layers = layers, addingLayer = false, error = null)
        estimate()
    }
    fun removeLayer(id: String) {
        mutableState.value = state.value.copy(layers = state.value.layers.filterNot { it.id == id })
        estimate()
    }
    fun moveLayer(id: String, offset: Int) {
        val layers = state.value.layers.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || index + offset !in layers.indices) return
        val item = layers.removeAt(index)
        layers.add(index + offset, item)
        mutableState.value = state.value.copy(layers = layers)
    }
    fun layerVisibility(id: String?, visible: Boolean) {
        mutableState.value = if (id == null) state.value.copy(rootVisible = visible)
        else state.value.copy(layers = state.value.layers.map { if (it.id == id) it.copy(visible = visible) else it })
    }
    fun elevation(dataset: ElevationDataset) {
        mutableState.value = state.value.copy(elevationDataset = dataset, error = null)
        estimate()
    }
    fun name(value: String) { mutableState.value = state.value.copy(name = value.take(120), error = null) }
    fun selectZoomRange(minimum: Int, maximum: Int) {
        val available = state.value.availableLevels
        if (minimum > maximum || minimum !in available || maximum !in available) return
        mutableState.value = state.value.copy(selectedLevels = (minimum..maximum).toSet(), error = null)
        estimate()
    }
    fun confirm() {
        val current = state.value
        val area = bounds ?: return
        val name = current.name.trim()
        val error = when {
            name.isBlank() || name.any { it.code < 32 } -> Res.string.invalid_map_name
            !current.elevationDataset.supportsRequest(area) -> Res.string.elevation_area_unsupported
            current.selectedLevels.isEmpty() -> Res.string.zoom_selection_required
            current.estimate?.estimatedPackageBytes == null -> Res.string.selection_estimate_unavailable
            else -> null
        }
        mutableState.value = current.copy(error = error)
        if (error == null) eventChannel.trySend(MapSaveSettings(name, area, current.selectedLevels.toSet(), current.layers, current.rootVisible, current.elevationDataset))
    }
    private fun estimate() {
        val area = bounds ?: return
        val single = TileAreaEstimate.estimate(area, state.value.selectedLevels, 32_000)
        mutableState.value = state.value.copy(estimate = single.let {
            val count = state.value.layers.size + 1
            val tiles = TileAreaEstimate.estimate(if (it.tileCount <= Long.MAX_VALUE / count) it.tileCount * count else Long.MAX_VALUE)
            val demBytes = state.value.elevationDataset.estimatedBytes(area)
            tiles.copy(estimatedPackageBytes = tiles.estimatedPackageBytes?.takeIf { it <= Long.MAX_VALUE - demBytes }?.plus(demBytes),
                estimatedLargestLayerBytes = single.estimatedPackageBytes)
        })
    }
}

internal fun defaultMapName(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.two() = toString().padStart(2, '0')
    return "${now.year}-${now.month.number.two()}-${now.day.two()}_${now.hour.two()}:${now.minute.two()}"
}
