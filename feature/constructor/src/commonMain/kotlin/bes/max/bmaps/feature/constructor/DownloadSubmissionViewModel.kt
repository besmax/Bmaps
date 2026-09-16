@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bes.max.bmaps.feature.constructor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.domain.providers.*
import bmaps.feature.constructor.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

data class DownloadSubmissionState(
    val availableBytes: Long? = null, val busy: Boolean = false,
    val error: StringResource? = null, val policyError: StringResource? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DownloadSubmissionViewModel(
    private val storage: PackageBuildStorage, private val executor: DownloadExecutor, private val planner: DownloadPlanner,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DownloadSubmissionState())
    val state = mutableState.asStateFlow()
    private val channel = Channel<Unit>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var request: BuildRequest? = null

    fun configure(choice: MapChoice) {
        mutableState.update { it.copy(policyError = when (choice.provider.capabilitiesFor(choice.style).offlineDownload) {
            OfflineDownloadPermission.ALLOWED -> null
            OfflineDownloadPermission.PROHIBITED -> Res.string.offline_download_prohibited
            OfflineDownloadPermission.REQUIRES_VERIFICATION -> Res.string.download_permission_unverified
        }) }
        refreshCapacity()
    }

    fun refreshCapacity() { viewModelScope.launch {
        when (val available = storage.availableBytes()) {
            is PackageResult.Success -> mutableState.update { it.copy(availableBytes = available.value, error = null) }
            is PackageResult.Failure -> mutableState.update { it.copy(error = Res.string.download_storage_unavailable) }
        }
    } }

    fun start(settings: MapSaveSettings, choice: MapChoice) {
        if (state.value.busy) return
        val config = choice.provider.configFor(choice.style)
        val error = validateComposition(settings, choice) ?: state.value.policyError
        if (error != null) { mutableState.update { it.copy(error = error) }; return }
        val levels = settings.levels.sorted().toSet()
        val candidate = BuildRequest(PackageId("draft"), settings.name.trim(), settings.bounds,
            listOf(BuildLayerRequest(LayerId("base"), choice.id, config, ZoomRange(levels.min(), levels.max()),
                zoomLevels = levels, visible = settings.rootVisible, opacity = settings.rootOpacity)) + settings.layers.map { layer ->
                BuildLayerRequest(LayerId(layer.id), layer.choice.id, layer.choice.provider.configFor(layer.choice.style),
                    ZoomRange(levels.min(), levels.max()), zoomLevels = levels, visible = layer.visible, opacity = layer.opacity)
            })
        val previous = request
        val submitting = if (previous != null && previous.copy(packageId = candidate.packageId) == candidate) previous
            else candidate.copy(packageId = PackageId(Uuid.random().toString())).also { request = it }
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val estimate = planner.estimate(submitting)
                val bytes = (estimate as? PackageResult.Success)?.value?.estimatedPackageBytes
                val capacity = (storage.availableBytes() as? PackageResult.Success)?.value
                val failure = when {
                    bytes == null -> Res.string.selection_estimate_unavailable
                    bytes > PackageSizePolicy.INITIAL_MAX_BYTES -> Res.string.package_size_limit_exceeded
                    capacity == null -> Res.string.download_storage_unavailable
                    capacity < bytes * 2 -> Res.string.download_insufficient_storage
                    else -> null
                }
                if (failure != null) { mutableState.update { it.copy(error = failure, availableBytes = capacity) }; return@launch }
                when (val result = executor.start(submitting)) {
                    is PackageResult.Success -> channel.send(Unit)
                    is PackageResult.Failure -> mutableState.update { it.copy(error = when (result.reason) {
                        PackageFailure.ProviderDownloadNotAllowed -> Res.string.download_permission_unverified
                        PackageFailure.AuthenticationRequired -> Res.string.download_credentials_required
                        is PackageFailure.SizeLimitExceeded -> Res.string.package_size_limit_exceeded
                        is PackageFailure.InsufficientStorage -> Res.string.download_insufficient_storage
                        else -> Res.string.download_start_failed
                    }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.download_start_failed) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}

internal fun validateDownload(settings: MapSaveSettings, config: ProviderConfig): StringResource? {
    if (settings.name.isBlank() || settings.name.length > 120 || settings.name.any { it.code < 32 }) return Res.string.invalid_map_name
    val selected = settings.levels
    if (selected.isEmpty()) return Res.string.zoom_selection_required
    val maximum = config.levelLimits.levelMax ?: return Res.string.unsupported_area_or_zoom
    if (selected.any { it !in config.levelLimits.levelMin..maximum || it !in 0..52 }) return Res.string.unsupported_area_or_zoom
    if (config.tileMatrix.coordinateSystem != CoordinateSystemId.WebMercator ||
        config.tileMatrix.tileWidth !in 1..4096 || config.tileMatrix.tileWidth != config.tileMatrix.tileHeight) return Res.string.unsupported_area_or_zoom
    val regions = WebMercator.splitBounds(settings.bounds) ?: return Res.string.unsupported_area_or_zoom
    val available = config.boundaries.boundingBoxList.flatMap { WebMercator.splitBounds(it).orEmpty() }
    if (config.boundaries.boundingBoxList.isNotEmpty() && regions.any { region -> available.none {
        region.west >= it.west && region.east <= it.east && region.south >= it.south && region.north <= it.north
    } }) return Res.string.unsupported_area_or_zoom
    return null
}

internal fun validateComposition(settings: MapSaveSettings, root: MapChoice): StringResource? {
    if (settings.layers.size > 31) return Res.string.layer_limit
    val opacities = listOf(settings.rootOpacity) + settings.layers.map { it.opacity }
    if (opacities.any { !it.isFinite() || it !in 0.0..1.0 } ||
        settings.layers.map { it.id }.let { ids -> ids.distinct().size != ids.size || "base" in ids }) return Res.string.layer_alignment_error
    val config = root.provider.configFor(root.style)
    return (listOf(root) + settings.layers.map { it.choice }).firstNotNullOfOrNull { choice ->
        val source = choice.provider.configFor(choice.style)
        validateDownload(settings, source) ?: when {
            source.tileMatrix.tileWidth != config.tileMatrix.tileWidth ||
                choice.style.content.kind != TileContentKind.RASTER || choice.style.content.rasterFormats.isEmpty() -> Res.string.layer_alignment_error
            choice.provider.capabilitiesFor(choice.style).offlineDownload != OfflineDownloadPermission.ALLOWED -> Res.string.download_permission_unverified
            else -> null
        }
    }
}
