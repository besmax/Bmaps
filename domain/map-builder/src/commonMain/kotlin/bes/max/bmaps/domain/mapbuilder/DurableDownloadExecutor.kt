package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.providers.*
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DownloadScheduler {
    fun initialize()
    suspend fun schedule(id: BuildJobId)
    suspend fun cancel(id: BuildJobId)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DurableDownloadExecutor(
    private val storage: PackageBuildStorage,
    private val packages: PackageRepository,
    private val providers: ProviderRepository,
    private val scheduler: DownloadScheduler,
    private val runner: DownloadRunner,
) : DownloadExecutor {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun start(request: BuildRequest): PackageResult<BuildJobId> = command {
        lock.withLock {
            val layers = request.layers.mapIndexed { index, layer ->
                val (provider, style) = providers.downloadSource(layer)
                PackageLayer(layer.id, style.name, layer.source,
                    PackageAsset(if (index == 0) "map_data.mbtiles" else "layers/${layer.id.value}.mbtiles", 0),
                    request.bounds, layer.zoomRange, content = style.content,
                    coordinateSystem = layer.config.tileMatrix.coordinateSystem,
                    tileWidth = layer.config.tileMatrix.tileWidth, tileHeight = layer.config.tileMatrix.tileHeight,
                    attribution = provider.attributionFor(style), zoomLevels = layer.zoomLevels,
                    visible = layer.visible, opacity = layer.opacity, renderOrder = index)
            }
            val manifest = PackageManifest(PackageManifest.CURRENT_SCHEMA_VERSION, request.packageId, request.name,
                request.bounds, ZoomRange(layers.minOf { it.zoomRange.min }, layers.maxOf { it.zoomRange.max }),
                0, 0, layers, sizePolicy = request.sizePolicy)
            val id = storage.prepare(request, manifest).valueOrThrow()
            if (storage.observeProgress(id).first().valueOrThrow().state != BuildJobState.COMPLETED) enqueue(id)
            id
        }
    }

    override fun observe(jobId: BuildJobId) = storage.observeProgress(jobId)

    override suspend fun resume(jobId: BuildJobId): PackageResult<Unit> = command {
        lock.withLock {
            val progress = observe(jobId).first().valueOrThrow()
            if (progress.state !in setOf(BuildJobState.PAUSED, BuildJobState.FAILED, BuildJobState.CANCELLED, BuildJobState.FINALIZING)) {
                throw PackageStorageException(PackageFailure.Conflict)
            }
            scheduler.cancel(jobId)
            runner.stop(jobId)
            enqueue(jobId)
        }
    }

    override suspend fun pause(jobId: BuildJobId): PackageResult<Unit> = stop(jobId, BuildJobState.PAUSED, false)

    override suspend fun cancel(jobId: BuildJobId, retention: PartialPackageRetention): PackageResult<Unit> =
        stop(jobId, BuildJobState.CANCELLED, retention == PartialPackageRetention.DELETE)

    private suspend fun stop(id: BuildJobId, state: BuildJobState, delete: Boolean): PackageResult<Unit> = command {
        lock.withLock {
            val progress = observe(id).first().valueOrThrow()
            if (progress.state == BuildJobState.COMPLETED && !delete) throw PackageStorageException(PackageFailure.Conflict)
            scheduler.cancel(id)
            runner.stop(id)
            if (observe(id).first().valueOrThrow().state == BuildJobState.COMPLETED && !delete) return@withLock
            if (delete) packages.delete(progress.packageId).valueOrThrow()
            else storage.setState(progress.packageId, state).valueOrThrow()
        }
    }

    private suspend fun enqueue(id: BuildJobId) {
        val progress = observe(id).first().valueOrThrow()
        if (progress.state in setOf(BuildJobState.RUNNING, BuildJobState.COMPLETED)) return
        if (progress.state != BuildJobState.FINALIZING) storage.setState(progress.packageId, BuildJobState.QUEUED).valueOrThrow()
        try { scheduler.schedule(id) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            storage.setState(progress.packageId, BuildJobState.FAILED, PackageFailure.Io)
            throw error
        }
    }

    private suspend fun <T> command(block: suspend () -> T): PackageResult<T> = scope.async { downloadResult(block) }.await()
}

internal suspend fun ProviderRepository.downloadSource(layer: BuildLayerRequest): Pair<TileProvider, TileStyle> {
    val provider = find(layer.source.provider) ?: throw PackageStorageException(PackageFailure.NotFound)
    val style = provider.styles.find { it.id == layer.source.style } ?: throw PackageStorageException(PackageFailure.NotFound)
    if (provider.capabilitiesFor(style).offlineDownload != OfflineDownloadPermission.ALLOWED) {
        throw PackageStorageException(PackageFailure.ProviderDownloadNotAllowed)
    }
    if (provider.configFor(style) != layer.config) throw PackageStorageException(PackageFailure.Conflict)
    return provider to style
}

internal fun <T> PackageResult<T>.valueOrThrow(): T = when (this) {
    is PackageResult.Success -> value
    is PackageResult.Failure -> throw PackageStorageException(reason)
}

internal suspend fun <T> downloadResult(block: suspend () -> T): PackageResult<T> = try {
    PackageResult.Success(block())
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { PackageResult.Failure(failureOf(error)) }
