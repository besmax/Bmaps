package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.*
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface DownloadSourceOpener {
    suspend fun open(layer: BuildLayerRequest): OnlineSourceResult
}

@Inject
@ContributesBinding(AppScope::class)
class ProviderDownloadSourceOpener(private val sources: OnlineTileSourceFactory) : DownloadSourceOpener {
    override suspend fun open(layer: BuildLayerRequest): OnlineSourceResult = when (val result = sources.open(layer.source, layer.endpointParameters)) {
        is OnlineSourceResult.Failed -> result
        is OnlineSourceResult.Available -> OnlineSourceResult.Available(object : TileSource {
            override suspend fun read(key: TileKey): TileReadResult {
                val tile = result.source.read(key)
                if (tile is TileReadResult.Available && !withContext(Dispatchers.Default) {
                    RasterTileValidation.hasExpectedDimensions(tile.bytes.toByteArray(), layer.config.tileMatrix.tileWidth)
                }) return TileReadResult.Failed(TileReadFailure.CORRUPT_DATA)
                return tile
            }
            override suspend fun close() = result.source.close()
        })
    }
}

@Inject
@SingleIn(AppScope::class)
class DownloadRunner(
    private val storage: PackageBuildStorage,
    private val providers: ProviderRepository,
    private val sources: DownloadSourceOpener,
) {
    private val lock = Mutex()
    private val running = mutableMapOf<BuildJobId, Job>()
    private val downloads = Semaphore(4)

    suspend fun stop(id: BuildJobId) { lock.withLock { running[id] }?.cancelAndJoin() }

    suspend fun run(id: BuildJobId, layerIndex: Int? = null): PackageResult<Unit> {
        val owner = currentCoroutineContext().job
        if (!lock.withLock { if (id in running) false else { running[id] = owner; true } }) {
            return PackageResult.Failure(PackageFailure.Conflict)
        }
        var packageId: PackageId? = null
        try {
            val progress = storage.observeProgress(id).first().valueOrThrow()
            packageId = progress.packageId
            if (progress.state == BuildJobState.COMPLETED) return PackageResult.Success(Unit)
            if (progress.state !in setOf(BuildJobState.QUEUED, BuildJobState.RUNNING, BuildJobState.FINALIZING)) {
                return PackageResult.Failure(PackageFailure.Conflict)
            }
            if (progress.state == BuildJobState.FINALIZING || progress.missingTiles == 0L) {
                storage.finalize(progress.packageId).valueOrThrow()
                return PackageResult.Success(Unit)
            }
            val request = storage.request(progress.packageId).valueOrThrow()
            require(layerIndex == null || layerIndex in request.layers.indices)
            storage.setState(request.packageId, BuildJobState.RUNNING).valueOrThrow()
            for ((index, layer) in request.layers.withIndex()) {
                if (layerIndex != null && index > layerIndex) break
                suspend fun complete() = storage.layerComplete(request.packageId, layer.id).valueOrThrow()
                if (complete()) continue
                if (layerIndex != null && index < layerIndex) throw PackageStorageException(PackageFailure.TileUnavailable)
                providers.downloadSource(layer)
                val source = when (val opened = sources.open(layer)) {
                    is OnlineSourceResult.Available -> opened.source
                    is OnlineSourceResult.Failed -> throw PackageStorageException(when (opened.reason) {
                        OnlineSourceFailure.MISSING_CREDENTIAL, OnlineSourceFailure.CREDENTIAL_UNAVAILABLE -> PackageFailure.AuthenticationRequired
                        else -> PackageFailure.CorruptData
                    })
                }
                try {
                    coroutineScope {
                        val queue = Channel<TileKey>(8)
                        launch {
                            try {
                                for (key in PackageTileCoverage(request.bounds, layer.zoomRange, layer.zoomLevels).tiles()) {
                                    ensureActive()
                                    if (!storage.contains(request.packageId, layer.id, key).valueOrThrow()) queue.send(key)
                                }
                            } finally { queue.close() }
                        }
                        repeat(4) {
                            launch {
                                for (key in queue) {
                                    downloads.withPermit {
                                        val result = source.read(key)
                                        val tiles = if (result is TileReadResult.Available) listOf(DownloadedTile(key, result.bytes)) else emptyList()
                                        val failures = if (result is TileReadResult.Available) emptyList() else
                                            listOf(FailedTile(key, (result as? TileReadResult.Failed)?.reason))
                                        storage.write(request.packageId, layer.id, tiles, failures,
                                            if (result is TileReadResult.Available) result.bytes.size.toLong() else 0).valueOrThrow()
                                        if (result is TileReadResult.Failed) throw PackageStorageException(result.reason.packageFailure())
                                    }
                                }
                            }
                        }
                    }
                } finally { withContext(NonCancellable) { source.close() } }
                if (!complete()) throw PackageStorageException(PackageFailure.TileUnavailable)
            }
            if (layerIndex != null && layerIndex < request.layers.lastIndex) {
                storage.setState(request.packageId, BuildJobState.QUEUED).valueOrThrow()
                return PackageResult.Success(Unit)
            }
            val final = storage.observeProgress(id).first().valueOrThrow()
            if (final.missingTiles > 0) throw PackageStorageException(PackageFailure.TileUnavailable)
            storage.finalize(request.packageId).valueOrThrow()
            return PackageResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { packageId?.let { storage.setState(it, BuildJobState.PAUSED) } }
            throw cancelled
        } catch (error: Exception) {
            val failure = failureOf(error)
            withContext(NonCancellable) { packageId?.let { storage.setState(it, BuildJobState.FAILED, failure) } }
            return PackageResult.Failure(failure)
        } finally { withContext(NonCancellable) { lock.withLock { running.remove(id) } } }
    }
}

private fun TileReadFailure.packageFailure(): PackageFailure = when (this) {
    TileReadFailure.NETWORK, TileReadFailure.TIMEOUT, TileReadFailure.SERVER -> PackageFailure.NetworkUnavailable
    TileReadFailure.AUTHENTICATION -> PackageFailure.AuthenticationRequired
    TileReadFailure.RATE_LIMITED -> PackageFailure.RateLimited(null)
    TileReadFailure.CORRUPT_DATA, TileReadFailure.RESPONSE_TOO_LARGE -> PackageFailure.CorruptData
    TileReadFailure.UNSUPPORTED_CONTENT -> PackageFailure.UnsupportedContent
    else -> PackageFailure.Io
}
