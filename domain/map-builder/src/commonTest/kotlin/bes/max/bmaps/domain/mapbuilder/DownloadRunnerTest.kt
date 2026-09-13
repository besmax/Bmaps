package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.*

class DownloadRunnerTest {
    @Test
    fun restoreDownloadsOnlyMissingTilesAndClosesEverySource() = runTest {
        val storage = MemoryBuildStorage()
        val attempts = mutableMapOf<TileKey, Int>()
        val missing = TileKey(1, 0, 0)
        var unavailable = true
        var closed = 0
        val runner = DownloadRunner(storage, fixtureProviders(), DownloadSourceOpener {
            OnlineSourceResult.Available(object : TileSource {
                override suspend fun read(key: TileKey): TileReadResult {
                    attempts[key] = (attempts[key] ?: 0) + 1
                    return if (key == missing && unavailable) TileReadResult.Missing else TileReadResult.Available(bytes, RasterTileFormat.PNG)
                }
                override suspend fun close() { closed++ }
            })
        })
        assertEquals(PackageFailure.TileUnavailable, assertIs<PackageResult.Failure>(runner.run(jobId)).reason)
        assertEquals(4L, storage.progress.value.completedTiles)
        assertEquals(1L, storage.progress.value.failedTiles)
        assertFalse(storage.finalized)
        unavailable = false
        storage.setState(packageId, BuildJobState.QUEUED)
        assertIs<PackageResult.Success<Unit>>(runner.run(jobId))
        assertEquals(2, attempts[missing])
        assertTrue(attempts.filterKeys { it != missing }.values.all { it == 1 })
        assertEquals(5L, storage.progress.value.completedTiles)
        assertEquals(0L, storage.progress.value.failedTiles)
        assertTrue(storage.finalized)
        assertEquals(2, closed)
    }

    @Test
    fun cancellationPausesAndClosesTheSource() = runTest {
        val storage = MemoryBuildStorage()
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val runner = DownloadRunner(storage, fixtureProviders(), DownloadSourceOpener {
            OnlineSourceResult.Available(object : TileSource {
                override suspend fun read(key: TileKey): TileReadResult { entered.complete(Unit); awaitCancellation() }
                override suspend fun close() { closed = true }
            })
        })
        val execution = launch { runner.run(jobId) }
        entered.await()
        runner.stop(jobId)
        execution.join()
        assertTrue(closed)
        assertTrue(execution.isCancelled)
        assertEquals(BuildJobState.PAUSED, storage.progress.value.state)
        assertEquals(0L, storage.progress.value.completedTiles)
        assertFalse(storage.finalized)
    }

    @Test
    fun networkFailureRemainsMissingAndActionable() = runTest {
        val storage = MemoryBuildStorage()
        val runner = DownloadRunner(storage, fixtureProviders(), DownloadSourceOpener {
            OnlineSourceResult.Available(object : TileSource {
                override suspend fun read(key: TileKey) = TileReadResult.Failed(TileReadFailure.NETWORK)
                override suspend fun close() = Unit
            })
        })
        assertEquals(PackageFailure.NetworkUnavailable, assertIs<PackageResult.Failure>(runner.run(jobId)).reason)
        assertEquals(BuildJobState.FAILED, storage.progress.value.state)
        assertEquals(5L, storage.progress.value.missingTiles)
        assertTrue(storage.progress.value.failedTiles > 0)
        assertFalse(storage.finalized)
    }

    @Test
    fun revokedPermissionPreventsNetworkAccess() = runTest {
        val storage = MemoryBuildStorage()
        val runner = DownloadRunner(storage, fixtureProviders(OfflineDownloadPermission.PROHIBITED), DownloadSourceOpener {
            error("A prohibited provider must never be opened")
        })
        assertEquals(PackageFailure.ProviderDownloadNotAllowed, assertIs<PackageResult.Failure>(runner.run(jobId)).reason)
        assertEquals(0L, storage.progress.value.completedTiles)
    }

    @Test
    fun completedTilesCanBeFinalizedWithoutReopeningTheProvider() = runTest {
        val storage = MemoryBuildStorage()
        PackageTileCoverage(request.bounds, ZoomRange(0, 1), emptySet()).tiles().forEach {
            storage.write(packageId, layerId, listOf(DownloadedTile(it, bytes)), emptyList(), bytes.size.toLong())
        }
        val runner = DownloadRunner(storage, fixtureProviders(OfflineDownloadPermission.PROHIBITED), DownloadSourceOpener {
            error("Finalization must use already stored tiles")
        })
        assertIs<PackageResult.Success<Unit>>(runner.run(jobId))
        assertTrue(storage.finalized)
    }

    @Test
    fun aStaleScheduledTaskCannotResumeAManuallyPausedMap() = runTest {
        val storage = MemoryBuildStorage()
        storage.setState(packageId, BuildJobState.PAUSED)
        val runner = DownloadRunner(storage, fixtureProviders(), DownloadSourceOpener { error("Paused work must not fetch tiles") })
        assertEquals(PackageFailure.Conflict, assertIs<PackageResult.Failure>(runner.run(jobId)).reason)
        assertEquals(BuildJobState.PAUSED, storage.progress.value.state)
    }

    @Test
    fun plannerStreamsSelectedLevelsAndMatchesTheDialogEstimate() = runTest {
        val selected = request.copy(layers = request.layers.map { it.copy(zoomRange = ZoomRange(0, 2), zoomLevels = setOf(0, 2)) })
        val planner = TileDownloadPlanner()
        val tiles = planner.tiles(selected).toList()
        val estimate = planner.estimate(selected).valueOrThrow()
        assertEquals(17, tiles.size)
        assertEquals(tiles.size, tiles.toSet().size)
        assertFalse(tiles.any { it.key.level == 1 })
        assertEquals(TileAreaEstimate.estimate(selected.bounds, setOf(0, 2), 32_000), estimate)
    }

    private class MemoryBuildStorage : PackageBuildStorage {
        val progress = MutableStateFlow(BuildProgress(jobId, packageId, BuildJobState.QUEUED, 5, 0, 0, 0, 0))
        private val downloaded = mutableSetOf<TileKey>()
        private val failed = mutableSetOf<TileKey>()
        var finalized = false

        override suspend fun availableBytes() = PackageResult.Success(1_000_000_000L)
        override suspend fun prepare(request: BuildRequest, manifest: PackageManifest) = PackageResult.Success(jobId)
        override suspend fun request(id: PackageId) = PackageResult.Success(request)
        override suspend fun contains(id: PackageId, layerId: LayerId, key: TileKey) = PackageResult.Success(key in downloaded)
        override suspend fun write(id: PackageId, layerId: LayerId, tiles: List<DownloadedTile>, failures: List<FailedTile>, receivedBytes: Long): PackageResult<BuildProgress> {
            downloaded.addAll(tiles.map { it.key })
            failed.addAll(failures.map { it.key })
            failed.removeAll(downloaded)
            progress.value = progress.value.copy(completedTiles = downloaded.size.toLong(), failedTiles = failed.size.toLong(),
                receivedBytes = progress.value.receivedBytes + receivedBytes)
            return PackageResult.Success(progress.value)
        }
        override suspend fun setState(id: PackageId, state: BuildJobState, failure: PackageFailure?): PackageResult<Unit> {
            progress.value = progress.value.copy(state = state, failure = failure)
            return PackageResult.Success(Unit)
        }
        override suspend fun finalize(id: PackageId): PackageResult<Unit> {
            check(downloaded.size == 5 && failed.isEmpty())
            finalized = true
            progress.value = progress.value.copy(state = BuildJobState.COMPLETED)
            return PackageResult.Success(Unit)
        }
        override suspend fun reconcile() = PackageResult.Success(Unit)
        override fun observeProgress(jobId: BuildJobId): Flow<PackageResult<BuildProgress>> = progress.map { PackageResult.Success(it) }
        override fun observeUnfinished(): Flow<PackageResult<List<BuildProgress>>> = progress.map { PackageResult.Success(listOf(it)) }
    }

    private companion object {
        val packageId = PackageId("fixture")
        val jobId = BuildJobId(packageId.value)
        val layerId = LayerId("base")
        val providerId = ProviderId("fixture")
        val styleId = StyleId("raster")
        val config = ProviderConfig(levelLimits = LevelLimitsConfig(0, 2))
        val request = BuildRequest(packageId, "Fixture", BoundingBox(-180.0, -WebMercator.MAX_LATITUDE, 180.0, WebMercator.MAX_LATITUDE),
            listOf(BuildLayerRequest(layerId, ProviderStyleId(providerId, styleId), config, ZoomRange(0, 1))))
        val bytes = ByteString(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))

        fun fixtureProviders(permission: OfflineDownloadPermission = OfflineDownloadPermission.ALLOWED) = object : ProviderRepository {
            val provider = TileProvider(providerId, "Fixture", listOf(TileStyle(styleId, "Raster", TileEndpoint("https://example.invalid/{z}/{x}/{y}"))),
                config, emptyList(), ProviderCapabilities(offlineDownload = permission, policyUrl = "https://example.invalid/policy"))
            override suspend fun list() = listOf(provider)
            override suspend fun find(id: ProviderId) = provider.takeIf { it.id == id }
        }
    }
}
