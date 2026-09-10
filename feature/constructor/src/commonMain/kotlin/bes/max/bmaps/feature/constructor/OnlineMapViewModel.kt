package bes.max.bmaps.feature.constructor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapChoice(val provider: TileProvider, val style: TileStyle) {
    val id get() = ProviderStyleId(provider.id, style.id)
    val label get() = "${provider.name} · ${style.name}"
    val unavailableReason: String? get() = when (id.onlineMapAvailability()) {
        OnlineMapAvailability.AVAILABLE, OnlineMapAvailability.ACCOUNT_KEY_REQUIRED -> null
        OnlineMapAvailability.PROVIDER_PERMISSION_REQUIRED -> "Third-party tile access needs provider permission."
        OnlineMapAvailability.ESRI_LICENSE_REQUIRED -> "ArcGIS is not available in this version. Choose another source."
        OnlineMapAvailability.YANDEX_INTEGRATION_REQUIRED -> "Yandex is not available in this version. Choose another source."
    }
}

data class OnlineMapSession(val generation: Int, val config: RasterMapConfig, val layers: List<RasterLayer>)
data class OnlineMapState(
    val choices: List<MapChoice> = emptyList(),
    val selected: MapChoice? = null,
    val session: OnlineMapSession? = null,
    val loading: Boolean = true,
    val visibleWindow: MapWindow? = null,
    val error: String? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class OnlineMapViewModel(private val providers: ProviderRepository, private val sources: OnlineSourceOpener) : ViewModel() {
    private val mutableState = MutableStateFlow(OnlineMapState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var viewport: MapViewport? = null

    init { loadChoices() }

    private fun loadChoices() = viewModelScope.launch {
        try {
            val choices = providers.list().flatMap { provider -> provider.styles.map { MapChoice(provider, it) } }
            mutableState.update { it.copy(choices = choices, loading = false) }
            choices.firstOrNull()?.let(::select)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = "Map sources could not be loaded.") } }
    }

    fun select(choice: MapChoice) {
        if (choice !in state.value.choices) return
        viewport = null
        start(choice)
    }

    fun retainViewport() {
        val world = viewport ?: return
        mutableState.update { current -> current.copy(session = current.session?.let {
            it.copy(config = it.config.copy(initialViewport = it.config.pyramid.localViewport(world)))
        }) }
    }

    fun retry() {
        val choice = state.value.selected
        if (choice == null) loadChoices() else start(choice)
    }

    private fun start(choice: MapChoice) {
        val token = ++generation
        val config = rasterConfig(choice.provider.configFor(choice.style), viewport, state.value.session?.config?.pyramid)
        val reason = choice.unavailableReason ?: if (config == null) "This source's tile matrix or display limits are not supported." else null
        if (reason != null) {
            mutableState.update { it.copy(selected = choice, session = null, loading = false, error = reason) }
            return
        }
        val layer = RasterLayer("online", TileSourceFactory {
            when (val result = sources.open(choice.id)) {
                is OnlineSourceResult.Available -> result.source
                is OnlineSourceResult.Failed -> {
                    val message = when (result.reason) {
                        OnlineSourceFailure.MISSING_CREDENTIAL -> "Add an API key for this source, then retry."
                        OnlineSourceFailure.CREDENTIAL_UNAVAILABLE -> "The saved API key is unavailable. Remove or replace it, then retry."
                        else -> "This map source could not be opened."
                    }
                    mutableState.update { if (it.session?.generation == token) it.copy(loading = false, error = message) else it }
                    throw IllegalStateException("Online source unavailable")
                }
            }
        })
        mutableState.update { it.copy(selected = choice, session = OnlineMapSession(token, checkNotNull(config), listOf(layer)), loading = true, error = null) }
    }

    fun onEvent(token: Int, event: MapEvent) {
        if (state.value.session?.generation != token) return
        if (event is MapEvent.ViewportChanged) {
            val session = state.value.session ?: return
            viewport = session.config.pyramid.worldViewport(event.viewport)
            mutableState.update { it.copy(visibleWindow = event.visibleWindow?.let(session.config.pyramid::worldWindow)) }
            val choice = state.value.selected ?: return
            val desired = rasterConfig(choice.provider.configFor(choice.style), viewport, session.config.pyramid)
            if (desired != null && desired.pyramid != session.config.pyramid) start(choice)
            return
        }
        val error = when (event) {
            is MapEvent.TileFailed -> when (event.failure) {
                TileReadFailure.AUTHENTICATION -> "The source rejected access. Check your API key and account."
                TileReadFailure.RATE_LIMITED -> "The source is busy or your request allowance was reached. Try again later."
                TileReadFailure.NETWORK, TileReadFailure.TIMEOUT -> "Could not load tiles. Check your connection and retry."
                else -> "Some map tiles could not be loaded. Retry to try again."
            }
            is MapEvent.TileMissing -> "This source has no tile for part of the visible area."
            is MapEvent.Unavailable -> "The map could not be displayed. Retry or choose another source."
            else -> null
        }
        mutableState.update { it.copy(loading = false, error = it.error ?: error) }
    }
}

internal fun rasterConfig(config: ProviderConfig, viewport: MapViewport? = null, previous: TilePyramid? = null): RasterMapConfig? = try {
    val matrix = config.tileMatrix
    val levels = config.levelLimits
    require(matrix.coordinateSystem == CoordinateSystemId.WebMercator && matrix.tileWidth == matrix.tileHeight)
    val world = viewport ?: MapViewport(MapPoint(config.initialViewport.scrollX, config.initialViewport.scrollY), config.initialViewport.scale)
    val pyramid = requireNotNull(worldTileWindow(ZoomRange(levels.levelMin, requireNotNull(levels.levelMax)), matrix.tileWidth, world, previous))
    val fraction = pyramid.columns.toDouble() / (1L shl pyramid.levels.min)
    RasterMapConfig(pyramid, pyramid.localViewport(world),
        minScale = (config.scaleLimits.minScale ?: 1.0) * fraction,
        maxScale = config.scaleLimits.maxScale?.times(fraction))
} catch (_: IllegalArgumentException) { null }
