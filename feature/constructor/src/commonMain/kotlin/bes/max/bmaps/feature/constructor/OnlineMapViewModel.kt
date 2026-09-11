package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.StringResource
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
    val unavailableReason: StringResource? get() = when (id.onlineMapAvailability()) {
        OnlineMapAvailability.AVAILABLE, OnlineMapAvailability.ACCOUNT_KEY_REQUIRED -> null
        OnlineMapAvailability.PROVIDER_PERMISSION_REQUIRED -> Res.string.provider_permission_required
        OnlineMapAvailability.ESRI_LICENSE_REQUIRED -> Res.string.arcgis_unavailable
        OnlineMapAvailability.YANDEX_INTEGRATION_REQUIRED -> Res.string.yandex_unavailable
    }
}

data class OnlineMapSession(val generation: Int, val config: RasterMapConfig, val layers: List<RasterLayer>)
data class OnlineMapState(
    val choices: List<MapChoice> = emptyList(),
    val selected: MapChoice? = null,
    val session: OnlineMapSession? = null,
    val loading: Boolean = true,
    val sourceMenuExpanded: Boolean = false,
    val linkError: Boolean = false,
    val visibleWindow: MapWindow? = null,
    val error: StringResource? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class OnlineMapViewModel(private val providers: ProviderRepository, private val sources: OnlineSourceOpener) : ViewModel() {
    private val mutableState = MutableStateFlow(OnlineMapState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var viewport: MapViewport? = null

    val renderer = RasterMapRenderer()
    private val fixtureLayers = listOf(RasterLayer("fixture", TileSourceFactory { openFixtureTileSource() }))
    private var showFixture = false

    init {
        viewModelScope.launch { renderer.run() }
        viewModelScope.launch { renderer.events.collect { onEvent(it.generation, it.event) } }
        loadChoices()
    }

    fun sourceMenu(expanded: Boolean) { mutableState.update { it.copy(sourceMenuExpanded = expanded) } }
    fun linkFailed() { mutableState.update { it.copy(linkError = true) } }
    fun display(provider: String, style: String, fixture: Boolean) {
        showFixture = fixture
        val choice = state.value.choices.find { it.provider.id.value == provider && it.style.id.value == style } ?: return
        if (choice != state.value.selected) select(choice) else updateRenderer()
    }

    private fun updateRenderer() {
        val session = state.value.session
        if (session == null) renderer.clearContent()
        else renderer.setContent(session.generation, session.config, if (showFixture) fixtureLayers else session.layers)
    }

    private fun loadChoices() = viewModelScope.launch {
        try {
            val choices = providers.list().flatMap { provider -> provider.styles.map { MapChoice(provider, it) } }
            mutableState.update { it.copy(choices = choices, loading = false) }
            choices.firstOrNull()?.let(::select)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = Res.string.map_sources_unavailable) } }
    }

    fun select(choice: MapChoice) {
        if (choice !in state.value.choices) return
        viewport = null
        mutableState.update { it.copy(sourceMenuExpanded = false, linkError = false, visibleWindow = null) }
        start(choice)
    }

    fun retry() {
        val choice = state.value.selected
        if (choice == null) loadChoices() else start(choice)
    }

    private fun start(choice: MapChoice) {
        val token = ++generation
        val config = rasterConfig(choice.provider.configFor(choice.style), viewport, state.value.session?.config?.pyramid)
        val reason = choice.unavailableReason ?: if (config == null) Res.string.unsupported_source_configuration else null
        if (reason != null) {
            mutableState.update { it.copy(selected = choice, session = null, loading = false, error = reason) }
            updateRenderer()
            return
        }
        val layer = RasterLayer("online", TileSourceFactory {
            when (val result = sources.open(choice.id)) {
                is OnlineSourceResult.Available -> result.source
                is OnlineSourceResult.Failed -> {
                    val message = when (result.reason) {
                        OnlineSourceFailure.MISSING_CREDENTIAL -> Res.string.provider_key_required
                        OnlineSourceFailure.CREDENTIAL_UNAVAILABLE -> Res.string.provider_saved_key_unavailable
                        else -> Res.string.map_source_open_error
                    }
                    mutableState.update { if (it.session?.generation == token) it.copy(loading = false, error = message) else it }
                    throw IllegalStateException("Online source unavailable")
                }
            }
        })
        mutableState.update { it.copy(selected = choice, session = OnlineMapSession(token, checkNotNull(config), listOf(layer)), loading = true, error = null) }
        updateRenderer()
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
                TileReadFailure.AUTHENTICATION -> Res.string.provider_access_denied
                TileReadFailure.RATE_LIMITED -> Res.string.provider_rate_limited
                TileReadFailure.NETWORK, TileReadFailure.TIMEOUT -> Res.string.tile_connection_error
                else -> Res.string.tile_load_error
            }
            is MapEvent.TileMissing -> Res.string.visible_tile_missing
            is MapEvent.Unavailable -> Res.string.map_display_error
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
