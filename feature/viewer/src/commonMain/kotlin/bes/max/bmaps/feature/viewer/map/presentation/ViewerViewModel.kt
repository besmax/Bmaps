/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.model.LayerId
import bes.max.bmaps.domain.mapbuilder.model.MapAvatar
import bes.max.bmaps.domain.mapbuilder.model.PackageFailure
import bes.max.bmaps.domain.mapbuilder.model.PackageId
import bes.max.bmaps.domain.mapbuilder.model.PackageLayer
import bes.max.bmaps.domain.mapbuilder.model.PackageManifest
import bes.max.bmaps.domain.mapbuilder.model.PackageResult
import bes.max.bmaps.domain.mapbuilder.model.PackageSummary
import bes.max.bmaps.domain.mapbuilder.repository.LayerPresentation
import bes.max.bmaps.domain.mapbuilder.repository.OpenedPackage
import bes.max.bmaps.domain.mapbuilder.repository.PackageRepository
import bes.max.bmaps.feature.viewer.activity.presentation.TrackLine
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.math.pow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource

data class ViewerState(
    val summary: PackageSummary? = null,
    val manifest: PackageManifest? = null,
    val loading: Boolean = true,
    val error: StringResource? = null,
    val tileWarning: StringResource? = null,
    val selectedLevel: Int? = null,
    val levels: List<Int> = emptyList(),
    val automaticAvailable: Boolean = false,
    val region: Int = 0,
    val regionCount: Int = 1,
    val immersive: Boolean = false,
    val details: Boolean = false,
    val layersVisible: Boolean = false,
    val reliefDialogVisible: Boolean = false,
    val layerDraft: List<PackageLayer> = emptyList(),
    val attributionVisible: Boolean = false,
    val busy: Boolean = false,
    val annotationPyramid: TilePyramid? = null,
    val vectorOnly: Boolean = false,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ViewerViewModel(private val packages: PackageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ViewerState())
    val state = mutableState.asStateFlow()
    private val channel = Channel<StringResource>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val annotationChannel = Channel<Pair<TilePyramid, MapEvent>>(Channel.UNLIMITED)
    val annotationEvents = annotationChannel.receiveAsFlow()
    val renderer = RasterMapRenderer()
    private var packageId: PackageId? = null
    private var opening: Job? = null
    private var observation: Job? = null
    private var opened: OpenedPackage? = null
    private var generation = 0
    private var pyramid: TilePyramid? = null
    private var viewport = MapViewport()
    private var pendingLocation: GeographicCoordinate? = null
    private var pendingLocationZoomScale: Double? = null
    private var locationRequestId = Long.MIN_VALUE
    private var vectorFocused = false

    fun openActivityMap(force: Boolean = false) {
        if (state.value.vectorOnly && !force) return
        packageId = null
        observation?.cancel()
        val previous = opening
        previous?.cancel()
        cancelLocationMove()
        vectorFocused = false
        val next = TilePyramid(ZoomRange(0, 18))
        pyramid = next
        viewport = MapViewport()
        generation++
        mutableState.value = ViewerState(loading = false, annotationPyramid = next, vectorOnly = true)
        renderer.setContent(generation, RasterMapConfig(next, maxScale = 262_144.0), emptyList())
        opening = viewModelScope.launch { previous?.join(); renderer.run() }
    }

    internal fun focusActivityPreview(lines: ImmutableList<TrackLine>) {
        if (!state.value.vectorOnly || vectorFocused || renderer.camera.value == null) return
        val coordinates = lines.flatMap { line -> line.points.map { GeographicCoordinate(it.latitude, it.longitude) } }
        if (coordinates.size == 1) centerLocation(coordinates.single())
        else if (fitCoordinates(coordinates)) vectorFocused = true
    }

    fun fitCoordinates(coordinates: List<GeographicCoordinate>): Boolean {
        val camera = renderer.camera.value ?: return false
        val points = coordinates.mapNotNull(camera.pyramid::positionOf)
        if (points.isEmpty()) return false
        val left = points.minOf { it.x }; val right = points.maxOf { it.x }
        val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
        val visible = camera.visibleWindow
        val factor = minOf((visible.right - visible.left) / (right - left).coerceAtLeast(1e-6),
            (visible.bottom - visible.top) / (bottom - top).coerceAtLeast(1e-6)) * 0.8
        renderer.controller.moveTo(camera.session, locationRequestId++, MapViewport(MapPoint((left + right) / 2, (top + bottom) / 2),
            (camera.viewport.scale * factor).coerceIn(camera.minScale, camera.maxScale)))
        return true
    }

    fun toggleImmersive() { mutableState.update { it.copy(immersive = !it.immersive) } }

    fun centerLocation(coordinate: GeographicCoordinate) {
        cancelLocationMove()
        if (state.value.vectorOnly) {
            if (WebMercator.normalized(coordinate) == null) return
            pendingLocation = coordinate
            pendingLocationZoomScale = renderer.camera.value?.let {
                if (it.viewport.scale <= 1.0) 8192.0 else it.viewport.scale * it.fitScale * 2.0.pow(it.pyramid.levels.max)
            } ?: 8192.0
            applyPendingLocation()
            return
        }
        val layer = state.value.manifest?.layers?.firstOrNull() ?: return
        val region = WebMercator.splitBounds(layer.bounds).orEmpty().indexOfFirst { bounds ->
            coordinate.latitude in bounds.south..bounds.north && coordinate.longitude in bounds.west..bounds.east
        }
        if (region < 0) {
            channel.trySend(Res.string.location_outside_map)
            return
        }
        pendingLocation = coordinate
        pendingLocationZoomScale = renderer.camera.value?.let {
            it.viewport.scale * it.fitScale * 2.0.pow(it.pyramid.levels.max)
        }
        if (region != state.value.region) selectRegion(region)
        else applyPendingLocation()
    }

    private var activityMove: kotlinx.coroutines.Job? = null
    fun followActivityLocation(coordinate: GeographicCoordinate, force: Boolean = false) {
        val camera = renderer.camera.value ?: return
        val point = camera.pyramid.positionOf(coordinate) ?: return
        val window = camera.visibleWindow
        val width = window.right - window.left
        val height = window.bottom - window.top
        if (width <= 0 || height <= 0) return
        val x = (point.x - window.left) / width
        val y = (point.y - window.top) / height
        if (!force && x in 0.3..0.7 && y in 0.25..0.6) return
        activityMove?.cancel()
        val start = camera.viewport.center
        val target = MapPoint(point.x.coerceIn(0.0, 1.0), (point.y + height * 0.08).coerceIn(0.0, 1.0))
        activityMove = viewModelScope.launch {
            repeat(12) { index ->
                val t = (index + 1) / 12.0
                val eased = t * t * (3 - 2 * t)
                renderer.controller.moveTo(camera.session, locationRequestId++, camera.viewport.copy(center =
                    MapPoint(start.x + (target.x - start.x) * eased, start.y + (target.y - start.y) * eased)))
                kotlinx.coroutines.delay(25)
            }
        }
    }

    internal fun locationCameraResult(result: CameraMoveResult) {
        if (result.requestId < 0 && result.outcome == CameraMoveOutcome.REJECTED) {
            channel.trySend(Res.string.location_center_failed)
        }
    }

    fun cancelLocationMove() { activityMove?.cancel(); activityMove = null; pendingLocation = null; pendingLocationZoomScale = null; if (state.value.vectorOnly) vectorFocused = true }

    private fun applyPendingLocation() {
        val coordinate = pendingLocation ?: return
        val camera = renderer.camera.value?.takeIf { it.generation == generation } ?: return
        val point = camera.pyramid.positionOf(coordinate) ?: return
        val scale = pendingLocationZoomScale?.let {
            (it / (camera.fitScale * 2.0.pow(camera.pyramid.levels.max))).coerceIn(camera.minScale, camera.maxScale)
        } ?: camera.viewport.scale
        cancelLocationMove()
        renderer.controller.moveTo(camera.session, locationRequestId++, camera.viewport.copy(center = point, scale = scale))
    }

    init {
        viewModelScope.launch { renderer.camera.collect { applyPendingLocation() } }
        viewModelScope.launch { renderer.events.collect { tagged ->
            if (tagged.generation != generation) return@collect
            if (tagged.event is MapEvent.Tap || tagged.event is MapEvent.ViewportChanged) {
                pyramid?.let { annotationChannel.trySend(it to tagged.event) }
            }
            when (val event = tagged.event) {
                is MapEvent.ViewportChanged -> viewport = event.viewport
                is MapEvent.TileLoaded -> mutableState.update { it.copy(loading = false) }
                is MapEvent.TileMissing -> mutableState.update { it.copy(loading = false, tileWarning = Res.string.viewer_missing_tile) }
                is MapEvent.TileFailed -> mutableState.update { it.copy(loading = false, tileWarning = Res.string.viewer_tile_error) }
                is MapEvent.Unavailable -> mutableState.update { it.copy(loading = false, error = Res.string.viewer_render_error) }
                else -> Unit
            }
        } }
    }

    fun open(id: PackageId) {
        if (packageId == id) return
        packageId = id
        cancelLocationMove()
        pyramid = null
        viewport = MapViewport()
        mutableState.value = ViewerState()
        observation?.cancel()
        observation = viewModelScope.launch {
            packages.observe(id).collect { result ->
                when (result) {
                    is PackageResult.Success -> mutableState.update { it.copy(summary = result.value) }
                    is PackageResult.Failure -> mutableState.update { it.copy(error = failureText(result.reason), loading = false) }
                }
            }
        }
        retry()
    }

    fun retry() {
        if (state.value.vectorOnly) { openActivityMap(force = true); return }
        val id = packageId ?: return
        val previous = opening
        previous?.cancel()
        generation++
        renderer.clearContent()
        mutableState.update { it.copy(loading = true, error = null, tileWarning = null) }
        opening = viewModelScope.launch {
            previous?.join()
            var session: OpenedPackage? = null
            try {
                when (val result = packages.open(id)) {
                    is PackageResult.Failure -> mutableState.update { it.copy(loading = false, error = failureText(result.reason)) }
                    is PackageResult.Success -> {
                        session = result.value
                        opened = session
                        val manifest = session.manifest
                        val layer = manifest.layers.firstOrNull()
                        if (layer == null) {
                            mutableState.update { it.copy(loading = false, error = Res.string.viewer_empty) }
                            return@launch
                        }
                        if (!alignedLayers(manifest.layers)) {
                            mutableState.update { it.copy(loading = false, error = Res.string.layer_alignment_error) }
                            return@launch
                        }
                        val levels = layer.zoomLevels.ifEmpty { (layer.zoomRange.min..layer.zoomRange.max).toSet() }.sorted()
                        val regions = WebMercator.splitBounds(layer.bounds).orEmpty()
                        mutableState.update { it.copy(manifest = manifest, levels = levels, regionCount = regions.size) }
                        configure(state.value.selectedLevel?.takeIf { it in levels })
                        renderer.run()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, error = Res.string.viewer_open_error) } }
            finally {
                withContext(NonCancellable) {
                    if (opened === session) opened = null
                    try { session?.close() }
                    catch (_: Exception) { channel.trySend(Res.string.viewer_close_error) }
                }
            }
        }
    }

    fun selectLevel(level: Int?) {
        if (level != null && level !in state.value.levels) return
        if (level == state.value.selectedLevel && state.value.error == null) return
        configure(level)
    }

    fun selectRegion(region: Int) {
        if (region !in 0 until state.value.regionCount || region == state.value.region) return
        mutableState.update { it.copy(region = region) }
        pyramid = null
        configure(state.value.selectedLevel)
    }

    private fun configure(level: Int?) {
        val session = opened ?: return
        val manifest = state.value.manifest ?: return
        val layers = if (state.value.layersVisible) state.value.layerDraft else manifest.layers.sortedBy { it.renderOrder }
        if (!alignedLayers(layers)) {
            mutableState.update { it.copy(loading = false, error = Res.string.layer_alignment_error) }
            return
        }
        val layer = manifest.layers.first()
        val automatic = offlineAutomaticPyramid(layer, state.value.region)
        val selected = level ?: if (automatic == null) state.value.levels.first() else null
        val next = if (selected == null) automatic else offlinePyramid(layer, selected, state.value.region)
        mutableState.update { it.copy(automaticAvailable = automatic != null) }
        if (next == null) {
            generation++
            renderer.clearContent()
            mutableState.update { it.copy(loading = false, error = Res.string.viewer_render_error) }
            return
        }
        val minimumScale = if (selected == null) 1.0 else maxOf(1.0, maxOf(next.columns, next.rows) / 8.0)
        val previous = pyramid
        val initial = if (previous == null) offlineInitialViewport(layer, state.value.region, next, minimumScale) else {
            val world = previous.worldViewport(viewport)
            next.localViewport(world).let {
                it.copy(center = MapPoint(it.center.x.coerceIn(0.0, 1.0), it.center.y.coerceIn(0.0, 1.0)),
                    scale = it.scale.coerceAtLeast(minimumScale))
            }
        }
        pyramid = next
        mutableState.update { it.copy(annotationPyramid = next) }
        annotationChannel.trySend(next to MapEvent.ViewportChanged(initial, MapWindow(0.0, 0.0, 1.0, 1.0)))
        viewport = initial
        generation++
        mutableState.update { it.copy(selectedLevel = selected, loading = true, error = null, tileWarning = null) }
        renderer.setContent(generation, RasterMapConfig(next, initialViewport = initial, minScale = minimumScale), layers.map { item ->
            RasterLayer(item.id.value, TileSourceFactory {
                when (val result = session.openTiles(item.id)) {
                    is PackageResult.Success -> result.value
                    is PackageResult.Failure -> error("Cannot open local tile source")
                }
            }, item.opacity.toFloat(), visible = item.visible)
        })
        if (layers.none { it.visible && it.opacity > 0 }) mutableState.update { it.copy(loading = false) }
    }

    fun showReliefSettings() {
        if (state.value.busy || state.value.manifest?.elevation == null) return
        dismissLayers()
        mutableState.update { it.copy(reliefDialogVisible = true) }
    }

    fun dismissReliefSettings() { mutableState.update { it.copy(reliefDialogVisible = false) } }

    fun refreshRelief() {
        mutableState.update { it.copy(layersVisible = false, layerDraft = emptyList()) }
        retry()
    }

    fun showLayers() {
        val manifest = state.value.manifest ?: return
        mutableState.update { it.copy(layersVisible = true, layerDraft = manifest.layers.sortedBy { layer -> layer.renderOrder }) }
    }

    fun layerAppearance(id: LayerId, visible: Boolean, opacity: Double) {
        if (state.value.busy || !opacity.isFinite() || opacity !in 0.0..1.0) return
        mutableState.update { it.copy(layerDraft = it.layerDraft.map { layer ->
            if (layer.id == id) layer.copy(visible = visible, opacity = opacity) else layer
        }) }
    }

    fun previewLayers() { configure(state.value.selectedLevel) }

    fun moveLayer(id: LayerId, offset: Int) {
        if (state.value.busy) return
        val layers = state.value.layerDraft.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || index + offset !in layers.indices) return
        val layer = layers.removeAt(index)
        layers.add(index + offset, layer)
        mutableState.update { it.copy(layerDraft = layers) }
        previewLayers()
    }

    fun dismissLayers() {
        if (state.value.busy) return
        mutableState.update { it.copy(layersVisible = false, layerDraft = emptyList()) }
        configure(state.value.selectedLevel)
    }

    fun saveLayers() {
        val id = packageId ?: return
        if (state.value.busy) return
        val layers = state.value.layerDraft.mapIndexed { index, layer -> layer.copy(renderOrder = index) }
        if (!alignedLayers(layers)) { channel.trySend(Res.string.layer_alignment_error); return }
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (packages.setLayerPresentation(id, layers.map { LayerPresentation(it.id, it.visible, it.opacity, it.renderOrder) })) {
                    is PackageResult.Success -> {
                        val byId = layers.associateBy { it.id }
                        mutableState.update { current -> current.copy(layersVisible = false, layerDraft = emptyList(),
                            manifest = current.manifest?.let { manifest -> manifest.copy(layers = manifest.layers.map { byId.getValue(it.id) }) }) }
                        configure(state.value.selectedLevel)
                    }
                    is PackageResult.Failure -> channel.send(Res.string.viewer_save_error)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channel.send(Res.string.viewer_save_error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun openLink(url: String, open: (String) -> Unit) {
        try { open(url) }
        catch (_: Exception) { channel.trySend(Res.string.viewer_link_error) }
    }

    fun showAttribution(show: Boolean) { mutableState.update { it.copy(attributionVisible = show) } }
    fun showDetails(show: Boolean) { mutableState.update { it.copy(details = show) } }
    fun favourite() = change { packages.setFavourite(it, !(state.value.summary?.favourite ?: false)) }
    fun avatar(value: MapAvatar) = change { packages.setAvatar(it, value) }

    private fun change(action: suspend (PackageId) -> PackageResult<Unit>) {
        val id = packageId ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { if (action(id) is PackageResult.Failure) channel.send(Res.string.viewer_save_error) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channel.send(Res.string.viewer_save_error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}

private fun failureText(failure: PackageFailure): StringResource = when (failure) {
    PackageFailure.NotFound -> Res.string.viewer_not_found
    PackageFailure.NotReady -> Res.string.viewer_not_ready
    PackageFailure.CorruptData -> Res.string.viewer_corrupt
    is PackageFailure.UnsupportedVersion, PackageFailure.UnsupportedContent, PackageFailure.UnsupportedCoordinateSystem -> Res.string.viewer_unsupported
    else -> Res.string.viewer_open_error
}
