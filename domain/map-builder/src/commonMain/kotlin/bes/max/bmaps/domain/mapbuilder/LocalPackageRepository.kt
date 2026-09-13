package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.database.*
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import bes.max.bmaps.core.storage.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.files.FileNotFoundException

@Inject
@SingleIn(AppScope::class)
class LocalPackageRepository(
    private val catalog: PackageCatalog,
    private val storage: PackageFileStorage,
) : PackageRepository, PackageBuildStorage {
    private val mutex = Mutex()
    private var reconciled = false
    private val sessions = mutableMapOf<PackageId, MutableList<LocalOpenedPackage>>()
    private val records get() = catalog.records
    private val json get() = PackageManifestCodec.json

    override suspend fun availableBytes(): PackageResult<Long> = operation { storage.access { availableBytes() } }

    override fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>> = flow<PackageResult<PackagePage>> {
        requireInitialized()
        emitAll(catalog.observe(PackageFilter(query.nameContains, query.states.map { it.name }.toSet()),
            query.limit, query.cursor).map { page ->
            PackageResult.Success(PackagePage(page.items.map { summary(it) }, page.nextCursor))
        })
    }.catch { emit(PackageResult.Failure(failureOf(it))) }

    override fun observe(id: PackageId): Flow<PackageResult<PackageSummary>> = initializedFlow { records.observe(id.value).map { record ->
        if (record == null) PackageResult.Failure(PackageFailure.NotFound)
        else PackageResult.Success(summary(record))
    } }.catch { emit(PackageResult.Failure(failureOf(it))) }

    override fun observeProgress(jobId: BuildJobId): Flow<PackageResult<BuildProgress>> = initializedFlow { records.observeJob(jobId.value).map {
        if (it == null) PackageResult.Failure(PackageFailure.NotFound) else PackageResult.Success(it.progress())
    } }.catch { emit(PackageResult.Failure(failureOf(it))) }

    override fun observeUnfinished(): Flow<PackageResult<List<BuildProgress>>> = initializedFlow { records.observeUnfinishedJobs().map {
        PackageResult.Success(it.map { job -> job.progress() }) as PackageResult<List<BuildProgress>>
    } }.catch { emit(PackageResult.Failure(failureOf(it))) }

    override suspend fun prepare(request: BuildRequest, manifest: PackageManifest): PackageResult<BuildJobId> = operation {
        checkComponent(request.packageId.value)
        val requestJson = json.encodeToString(request)
        records.get(request.packageId.value)?.let {
            val existing = records.job(it.id)
            if (existing == null || json.decodeFromString<BuildRequest>(existing.requestJson) != request) fail(PackageFailure.Conflict)
            return@operation BuildJobId(existing.id)
        }
        PackageManifestCodec.validate(manifest)
        require(manifest.id == request.packageId && manifest.bounds == request.bounds && manifest.name == request.name)
        require(manifest.sizePolicy == request.sizePolicy && request.layers.size == manifest.layers.size)
        require(manifest.elevation == null && manifest.annotations == null && manifest.auxiliaryAssets.isEmpty())
        request.layers.zip(manifest.layers).forEach { (source, layer) ->
            require(source.id == layer.id && source.source == layer.source && source.zoomRange == layer.zoomRange &&
                source.zoomLevels == layer.zoomLevels)
        }
        val total = manifest.layers.fold(0L) { sum, layer ->
            val count = coverage(layer).count
            require(count <= Long.MAX_VALUE - sum)
            sum + count
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val draft = manifest.copy(createdAtEpochMillis = now, updatedAtEpochMillis = now,
            layers = manifest.layers.map { it.copy(tileCount = null, tiles = it.tiles.copy(sizeBytes = 0)) })
        val record = PackageRecord(request.packageId.value, request.name, PackageState.BUILDING.name,
            PackageManifestCodec.encode(draft), 0, now)
        val job = DownloadJobRecord(request.packageId.value, request.packageId.value, requestJson, BuildJobState.QUEUED.name, total)
        storage.access {
            if (exists(record.id, true) || exists(record.id, false)) fail(PackageFailure.Conflict)
            requireCapacity(1_048_576)
            records.create(record, job)
            try {
                create(record.id)
                for (layer in draft.layers) {
                    val path = asset(record.id, true, layer.tiles.relativePath)
                    kotlinx.io.files.SystemFileSystem.createDirectories(checkNotNull(path.parent))
                    MbTiles.create(path.toString(), mapOf(
                        "name" to layer.name, "type" to "baselayer", "version" to "1.0",
                        "format" to if (layer.content.rasterFormats.size == 1) layer.content.rasterFormats.single().extension else "mixed",
                        "bounds" to "${draft.bounds.west},${draft.bounds.south},${draft.bounds.east},${draft.bounds.north}",
                        "minzoom" to layer.zoomRange.min.toString(), "maxzoom" to layer.zoomRange.max.toString(),
                        "bmaps_package_id" to record.id,
                    )).close()
                }
                val bytes = enforceLimit(record.id, true, limit(draft))
                records.checkpoint(record.copy(sizeBytes = bytes), job.copy(packageBytes = bytes))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                withContext(NonCancellable) { markFailed(record, job, failureOf(failure)) }
                throw failure
            }
        }
        BuildJobId(job.id)
    }

    override suspend fun write(
        id: PackageId, layerId: LayerId, tiles: List<DownloadedTile>, failures: List<FailedTile>, receivedBytes: Long,
    ): PackageResult<BuildProgress> = operation {
        val record = building(id)
        val job = job(id)
        if (job.state !in setOf(BuildJobState.QUEUED.name, BuildJobState.RUNNING.name)) fail(PackageFailure.Conflict)
        require(receivedBytes >= 0 && receivedBytes <= Long.MAX_VALUE - job.receivedBytes)
        val manifest = PackageManifestCodec.decode(record.manifestJson)
        val layer = manifest.layers.firstOrNull { it.id == layerId } ?: fail(PackageFailure.NotFound)
        val coverage = coverage(layer)
        require(tiles.all { coverage.contains(it.key) } && failures.all { coverage.contains(it.key) })
        val writes = tiles.map {
            val bytes = it.bytes.toByteArray()
            require(rasterFormat(bytes) in layer.content.rasterFormats)
            TileWrite(it.key.address(), bytes)
        }
        storage.access {
            try {
                val bytesBefore = size(id.value, true)
                val path = asset(id.value, true, layer.tiles.relativePath)
                val databaseBytes = assetSize(id.value, true, layer.tiles.relativePath)
                requireCapacity(databaseBytes + writes.sumOf { it.bytes.size.toLong() } + 1_048_576)
                val database = MbTiles.open(path.toString(), writable = true)
                try {
                    database.write(writes, failures.map { MissingTile(it.key.address(), it.reason?.name ?: "MISSING") },
                        (limit(manifest) - (bytesBefore - databaseBytes) - MANIFEST_RESERVE).coerceAtLeast(0))
                } finally { database.close() }
                val counts = counts(manifest, true)
                val size = enforceLimit(id.value, true, limit(manifest))
                val updated = job.copy(state = BuildJobState.RUNNING.name, completedTiles = counts.downloaded,
                    failedTiles = counts.failed, receivedBytes = job.receivedBytes + receivedBytes, packageBytes = size, failure = null)
                records.checkpoint(record.copy(state = PackageState.BUILDING.name, sizeBytes = size,
                    updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds()), updated)
                updated.progress()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                withContext(NonCancellable) { markFailed(record, job, failureOf(failure)) }
                throw failure
            }
        }
    }

    override suspend fun contains(id: PackageId, layerId: LayerId, key: TileKey): PackageResult<Boolean> = operation {
        val manifest = PackageManifestCodec.decode(building(id).manifestJson)
        val layer = manifest.layers.firstOrNull { it.id == layerId } ?: fail(PackageFailure.NotFound)
        storage.access {
            val database = MbTiles.open(asset(id.value, true, layer.tiles.relativePath).toString())
            try { database.contains(key.address()) } finally { database.close() }
        }
    }

    override suspend fun request(id: PackageId): PackageResult<BuildRequest> = operation {
        checkComponent(id.value)
        json.decodeFromString<BuildRequest>(job(id).requestJson).also { require(it.packageId == id) }
    }

    override suspend fun setState(id: PackageId, state: BuildJobState, failure: PackageFailure?): PackageResult<Unit> = operation {
        require(state in setOf(BuildJobState.QUEUED, BuildJobState.RUNNING, BuildJobState.PAUSED, BuildJobState.CANCELLED, BuildJobState.FAILED))
        require((state == BuildJobState.FAILED) == (failure != null))
        val record = building(id, allowFinalizing = true)
        val job = job(id)
        val manifest = PackageManifestCodec.decode(record.manifestJson)
        storage.access {
            if (record.state == PackageState.FINALIZING.name && exists(id.value, false)) {
                indexFinal(record)
                return@access
            }
            val counts = counts(manifest, true)
            val bytes = size(id.value, true)
            records.checkpoint(record.copy(state = when (state) {
                BuildJobState.QUEUED, BuildJobState.RUNNING -> PackageState.BUILDING
                BuildJobState.FAILED -> PackageState.FAILED
                else -> PackageState.PAUSED
            }.name, sizeBytes = bytes, updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds()),
                job.copy(state = state.name, completedTiles = counts.downloaded, failedTiles = counts.failed,
                    packageBytes = bytes, failure = failure?.let { json.encodeToString(it) }))
        }
    }

    override suspend fun finalize(id: PackageId): PackageResult<Unit> = operation {
        checkComponent(id.value)
        val existing = records.get(id.value) ?: fail(PackageFailure.NotFound)
        if (existing.state == PackageState.READY.name) return@operation
        if (existing.state == PackageState.FINALIZING.name && storage.access { exists(id.value, false) }) {
            storage.access { indexFinal(existing) }
            return@operation
        }
        val record = building(id, allowFinalizing = true)
        val job = job(id)
        val draft = PackageManifestCodec.decode(record.manifestJson)
        storage.access {
            val counts = counts(draft, true)
            if (counts.downloaded != job.totalTiles || counts.failed != 0L) fail(PackageFailure.NotReady)
            val finalizing = record.copy(state = PackageState.FINALIZING.name)
            val finalJob = job.copy(state = BuildJobState.FINALIZING.name, completedTiles = counts.downloaded,
                failedTiles = 0, failure = null)
            records.checkpoint(finalizing, finalJob)
            val manifest = draft.copy(updatedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                layers = draft.layers.map { it.copy(tileCount = coverage(it).count,
                    tiles = it.tiles.copy(sizeBytes = assetSize(id.value, true, it.tiles.relativePath))) })
            val encoded = PackageManifestCodec.encode(manifest).encodeToByteArray()
            write(id.value, "config.json", Buffer().apply { write(encoded) }, MANIFEST_RESERVE, limit(manifest))
            verify(manifest, true)
            promote(id.value)
            val bytes = enforceLimit(id.value, false, limit(manifest))
            records.checkpoint(finalizing.copy(state = PackageState.READY.name, manifestJson = encoded.decodeToString(),
                sizeBytes = bytes, updatedAtEpochMillis = manifest.updatedAtEpochMillis, hasElevationData = manifest.elevation != null),
                finalJob.copy(state = BuildJobState.COMPLETED.name, packageBytes = bytes))
        }
    }

    override suspend fun open(id: PackageId): PackageResult<OpenedPackage> = operation {
        val record = records.get(id.value) ?: fail(PackageFailure.NotFound)
        if (record.state != PackageState.READY.name) fail(PackageFailure.NotReady)
        storage.access {
            try {
                val manifest = readManifest(id, false)
                verify(manifest, false)
                LocalOpenedPackage(manifest, manifest.layers.associate {
                    it.id to asset(id.value, false, it.tiles.relativePath).toString()
                }).also {
                    val opened = sessions.getOrPut(id) { mutableListOf() }
                    opened.removeAll { it.closed }
                    opened.add(it)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                records.putPackage(record.copy(state = if (error is FileNotFoundException) PackageState.MISSING.name else PackageState.CORRUPT.name))
                throw error
            }
        }
    }

    override suspend fun delete(id: PackageId): PackageResult<Unit> = operation {
        checkComponent(id.value)
        records.get(id.value)?.let { records.putPackage(it.copy(state = PackageState.DELETING.name)) }
        closeSessions(id)
        storage.access { delete(id.value, true); delete(id.value, false) }
        records.delete(id.value)
    }

    override suspend fun reconcile(): PackageResult<Unit> = operation(initialize = false) {
        reconcileFiles()
        reconciled = true
    }

    private suspend fun reconcileFiles() {
        storage.access {
            for (record in records.all()) {
                val id = PackageId(record.id)
                if (record.state == PackageState.DELETING.name) {
                    closeSessions(id)
                    delete(record.id, true); delete(record.id, false)
                    records.delete(record.id)
                    continue
                }
                try {
                    when {
                        exists(record.id, false) -> {
                            indexFinal(record)
                        }
                        exists(record.id, true) -> {
                            removeTemporaryFiles(record.id)
                            val manifest = PackageManifestCodec.decode(record.manifestJson)
                            val job = job(id)
                            val counts = counts(manifest, true)
                            val bytes = size(record.id, true)
                            val queued = job.state in setOf(BuildJobState.QUEUED.name, BuildJobState.RUNNING.name, BuildJobState.FINALIZING.name)
                            records.checkpoint(record.copy(state = if (queued) PackageState.BUILDING.name else record.state, sizeBytes = bytes),
                                job.copy(state = if (queued) BuildJobState.QUEUED.name else job.state, completedTiles = counts.downloaded,
                                    failedTiles = counts.failed, packageBytes = bytes))
                        }
                        else -> records.putPackage(record.copy(state = PackageState.MISSING.name))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    records.putPackage(record.copy(state = PackageState.CORRUPT.name))
                }
            }
            for (id in ids(false)) {
                if (records.get(id) != null) continue
                try {
                    val manifest = readManifest(PackageId(id), false)
                    verify(manifest, false)
                    records.putPackage(PackageRecord(id, manifest.name, PackageState.READY.name,
                        PackageManifestCodec.encode(manifest), size(id, false), manifest.updatedAtEpochMillis,
                        manifest.elevation != null))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    records.putPackage(PackageRecord(id, id, PackageState.CORRUPT.name, "", 0, 0))
                }
            }
            for (id in ids(true)) {
                if (records.get(id) == null) records.putPackage(PackageRecord(id, id, PackageState.CORRUPT.name, "", 0, 0))
            }
        }
    }

    private suspend fun PackageFiles.indexFinal(record: PackageRecord) {
        val manifest = readManifest(PackageId(record.id), false)
        verify(manifest, false)
        val bytes = size(record.id, false)
        val ready = record.copy(state = PackageState.READY.name, manifestJson = PackageManifestCodec.encode(manifest),
            sizeBytes = bytes, hasElevationData = manifest.elevation != null)
        val job = records.job(record.id)
        if (job == null) records.putPackage(ready) else {
            val counts = counts(manifest, false)
            if (counts.downloaded != job.totalTiles || counts.failed != 0L) fail(PackageFailure.CorruptData)
            records.checkpoint(ready, job.copy(state = BuildJobState.COMPLETED.name,
                completedTiles = counts.downloaded, failedTiles = 0, packageBytes = bytes, failure = null))
        }
    }

    private suspend fun PackageFiles.verify(manifest: PackageManifest, staged: Boolean) {
        enforceLimit(manifest.id.value, staged, limit(manifest))
        if (relativeFiles(manifest.id.value, staged) !=
            PackageManifestCodec.assets(manifest).map { it.relativePath }.toSet() + "config.json") fail(PackageFailure.CorruptData)
        for (asset in PackageManifestCodec.assets(manifest)) {
            if (assetSize(manifest.id.value, staged, asset.relativePath) != asset.sizeBytes) fail(PackageFailure.CorruptData)
        }
        for (layer in manifest.layers) {
            if (layer.tileCount == null) fail(PackageFailure.NotReady)
            val database = MbTiles.open(asset(manifest.id.value, staged, layer.tiles.relativePath).toString())
            try {
                val coverage = coverage(layer)
                database.verify { coverage.contains(TileKey(it.zoom, it.column, it.row)) }
                val counts = database.counts()
                if (counts.downloaded != layer.tileCount || counts.failed != 0L ||
                    database.metadata()["bmaps_package_id"] != manifest.id.value) fail(PackageFailure.CorruptData)
            } finally { database.close() }
        }
    }

    private suspend fun PackageFiles.counts(manifest: PackageManifest, staged: Boolean): TileCounts {
        var downloaded = 0L
        var failed = 0L
        for (layer in manifest.layers) {
            val database = MbTiles.open(asset(manifest.id.value, staged, layer.tiles.relativePath).toString(), writable = staged)
            try {
                val counts = database.counts()
                val expected = coverage(layer).count
                require(counts.downloaded in 0..expected && counts.failed in 0..(expected - counts.downloaded))
                downloaded += counts.downloaded
                failed += counts.failed
            } finally { database.close() }
        }
        return TileCounts(downloaded, failed)
    }

    private fun PackageFiles.readManifest(id: PackageId, staged: Boolean): PackageManifest =
        PackageManifestCodec.decode(read(id.value, staged, "config.json", MANIFEST_RESERVE.toInt()).decodeToString()).also {
            require(it.id == id)
        }

    private suspend fun building(id: PackageId, allowFinalizing: Boolean = false): PackageRecord {
        checkComponent(id.value)
        val record = records.get(id.value) ?: fail(PackageFailure.NotFound)
        if (record.state !in setOf(PackageState.BUILDING.name, PackageState.PAUSED.name, PackageState.FAILED.name) &&
            !(allowFinalizing && record.state == PackageState.FINALIZING.name)) {
            fail(PackageFailure.NotReady)
        }
        return record
    }

    private suspend fun job(id: PackageId): DownloadJobRecord = records.job(id.value) ?: fail(PackageFailure.NotFound)
    private fun coverage(layer: PackageLayer) = PackageTileCoverage(layer.bounds, layer.zoomRange, layer.zoomLevels)
    private fun limit(manifest: PackageManifest) = minOf(manifest.sizePolicy.maxBytes, PackageSizePolicy.INITIAL_MAX_BYTES)

    private suspend fun summary(record: PackageRecord): PackageSummary {
        val manifest = record.manifestJson.takeIf { it.isNotEmpty() }?.let {
            runCatching { PackageManifestCodec.decode(it) }.getOrNull()
        }
        val job = records.job(record.id)
        return PackageSummary(PackageId(record.id), record.name,
            manifest?.bounds,
            PackageState.valueOf(record.state), record.sizeBytes, record.updatedAtEpochMillis, record.hasElevationData,
            job?.totalTiles ?: manifest?.layers?.sumOf { it.tileCount ?: 0 } ?: 0,
            job?.completedTiles ?: manifest?.layers?.sumOf { it.tileCount ?: 0 } ?: 0, job?.failedTiles ?: 0)
    }

    private fun DownloadJobRecord.progress() = BuildProgress(BuildJobId(id), PackageId(packageId), BuildJobState.valueOf(state),
        totalTiles, completedTiles, failedTiles, receivedBytes, packageBytes, failure?.let { json.decodeFromString<PackageFailure>(it) })

    private suspend fun PackageFiles.markFailed(record: PackageRecord, job: DownloadJobRecord, failure: PackageFailure) {
        val counts = runCatching { counts(PackageManifestCodec.decode(record.manifestJson), true) }.getOrNull()
        val bytes = runCatching { size(record.id, true) }.getOrDefault(record.sizeBytes)
        records.checkpoint(record.copy(state = PackageState.FAILED.name, sizeBytes = bytes),
            job.copy(state = BuildJobState.FAILED.name, completedTiles = counts?.downloaded ?: job.completedTiles,
                failedTiles = counts?.failed ?: job.failedTiles, packageBytes = bytes, failure = json.encodeToString(failure)))
    }

    private suspend fun closeSessions(id: PackageId) {
        val opened = sessions.remove(id) ?: return
        var failure: Throwable? = null
        opened.forEach {
            try { it.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun <T> initializedFlow(block: () -> Flow<T>): Flow<T> = flow {
        requireInitialized()
        emitAll(block())
    }

    private suspend fun requireInitialized() {
        mutex.withLock {
            if (!reconciled) { reconcileFiles(); reconciled = true }
        }
    }

    private suspend fun <T> operation(initialize: Boolean = true, block: suspend () -> T): PackageResult<T> = try {
        mutex.withLock {
            if (initialize && !reconciled) { reconcileFiles(); reconciled = true }
            PackageResult.Success(block())
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { PackageResult.Failure(failureOf(error)) }

    private fun fail(failure: PackageFailure): Nothing = throw PackageStorageException(failure)

    private companion object { const val MANIFEST_RESERVE = 1_048_576L }
}

private fun TileKey.address() = TileAddress(level, column, row)

internal fun failureOf(error: Throwable): PackageFailure = when (error) {
    is CancellationException -> throw error
    is PackageStorageException -> error.failure
    is FileNotFoundException -> PackageFailure.NotFound
    is PackageAlreadyExists -> PackageFailure.Conflict
    is StorageLimitExceeded -> PackageFailure.SizeLimitExceeded(error.limit, error.required)
    is MbTilesSizeExceeded -> PackageFailure.SizeLimitExceeded(PackageSizePolicy.INITIAL_MAX_BYTES, null)
    is StorageCapacityExceeded -> PackageFailure.InsufficientStorage(error.required)
    is IllegalArgumentException, is UnsafePackagePath -> PackageFailure.CorruptData
    else -> PackageFailure.Io
}
