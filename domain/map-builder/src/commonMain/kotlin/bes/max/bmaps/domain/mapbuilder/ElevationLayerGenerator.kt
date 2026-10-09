/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import bes.max.bmaps.core.storage.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

@Inject
@SingleIn(AppScope::class)
class ElevationLayerGenerator(
    private val repository: LocalPackageRepository,
    private val files: PackageFileStorage,
    private val readers: DemReaderFactory,
) {
    private val execution = Mutex()
    private val control = Mutex()
    private val json get() = PackageManifestCodec.json
    private val live = MutableStateFlow<Map<PackageId, ElevationGenerationJob?>>(emptyMap())
    private var checkpoint = TimeSource.Monotonic.markNow()
    private var diskCheck = TimeSource.Monotonic.markNow()

    fun observe(id: PackageId): Flow<ElevationGenerationJob?> = flow {
        job(id)
        emitAll(live.map { it[id] }.distinctUntilChanged())
    }

    private suspend fun load(id: PackageId): ElevationGenerationJob? = files.access {
        readElevationJob(id.value)?.let { json.decodeFromString<ElevationGenerationJob>(it) }
    }

    private fun remember(id: PackageId, value: ElevationGenerationJob?) { live.value = live.value + (id to value) }

    private suspend fun cached(id: PackageId): ElevationGenerationJob? {
        if (id !in live.value) remember(id, load(id))
        return live.value[id]
    }

    suspend fun job(id: PackageId): ElevationGenerationJob? = control.withLock { cached(id) }

    suspend fun pending(): List<ElevationGenerationJob> = try {
        repository.availableBytes().valueOrThrow()
        control.withLock {
            val pending = files.access {
                elevationJobIds().mapNotNull { id ->
                    if (!exists(id, false)) {
                        deleteElevationJob(id)
                        return@mapNotNull null
                    }
                    val current = readElevationJob(id)?.let { json.decodeFromString<ElevationGenerationJob>(it) }
                    if (current?.active == true) current else {
                        clearElevationTiles(id)
                        null
                    }
                }
            }
            pending.map { durable ->
                val current = live.value[durable.packageId]
                val value = if (current?.token == durable.token && current.active) current else durable
                remember(value.packageId, value)
                value
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        ElevationDiagnostics.error("relief_pending_jobs", error)
        emptyList()
    }

    suspend fun prepare(id: PackageId, options: ElevationReliefOptions): PackageResult<Unit> = result {
        if (job(id)?.active == true) throw PackageStorageException(PackageFailure.Conflict)
        execution.withLock {
            control.withLock {
                remember(id, load(id))
                if (cached(id)?.active == true) throw PackageStorageException(PackageFailure.Conflict)
                val input = repository.reliefInput(id).valueOrThrow()
                require(options.valid())
                require(input.first.layers.first().tileWidth in setOf(256, 512))
                val base = input.first.layers.first()
                val total = PackageTileCoverage(base.bounds, base.zoomRange, base.zoomLevels).count
                val job = ElevationGenerationJob(id, kotlin.uuid.Uuid.random().toString(), options, totalTiles = total)
                files.access { requireCapacity(1_048_576); clearElevationTiles(id.value) }
                save(job, requireActiveToken = false)
            }
        }
    }

    suspend fun cancel(id: PackageId) = control.withLock {
        val current = cached(id) ?: return@withLock
        if (current.active) save(current.copy(state = ElevationGenerationState.CANCELLED))
    }

    suspend fun discardCancelled(id: PackageId) = execution.withLock {
        if (job(id)?.state == ElevationGenerationState.CANCELLED) files.access { clearElevationTiles(id.value) }
    }

    suspend fun fail(id: PackageId, failure: PackageFailure) = control.withLock {
        val current = cached(id) ?: return@withLock
        if (current.active) save(current.copy(state = ElevationGenerationState.FAILED, failure = failure))
    }

    suspend fun run(id: PackageId, token: String): PackageResult<Unit> = withContext(Dispatchers.Default) {
        execution.withLock {
            var reader: DemReader? = null
            var database: MbTiles? = null
            try {
                var current = job(id)?.takeIf { it.token == token && it.active } ?: return@withLock PackageResult.Success(Unit)
                val (manifest, path) = repository.reliefInput(id).valueOrThrow()
                if (manifest.layers.any { it.tiles.relativePath == "layers/elevation-relief-$token.mbtiles" }) {
                    repository.commitRelief(current.copy(completedTiles = current.totalTiles)).valueOrThrow()
                    refresh(id)
                    return@withLock PackageResult.Success(Unit)
                }
                val dem = readers.open(path).also { reader = it }
                if (dem.metadata.hasGdalMetadata) throw PackageStorageException(PackageFailure.UnsupportedContent)
                dem.enableRasterCache()
                current = current.copy(state = ElevationGenerationState.RUNNING)
                publish(current, force = true)
                val style = current.style ?: resolveStyle(id, token, path, dem, current.options) { ensureWanted(id, token) }
                current = current.copy(style = style)
                publish(current, force = true)
                val base = manifest.layers.first()
                val coverage = PackageTileCoverage(base.bounds, base.zoomRange, base.zoomLevels)
                val output = files.access { elevationTilesPath(id.value).also { requireCapacity(1_048_576) }.toString() }
                val db = (if (kotlinx.io.files.SystemFileSystem.exists(Path(output))) MbTiles.open(output, writable = true)
                    else MbTiles.create(output, mapOf("name" to "Elevation", "format" to "png", "type" to "overlay",
                        "version" to "1.0", "bmaps_package_id" to id.value,
                        "bounds" to "${base.bounds.west},${base.bounds.south},${base.bounds.east},${base.bounds.north}",
                        "minzoom" to base.zoomRange.min.toString(), "maxzoom" to base.zoomRange.max.toString()))).also { database = it }
                current = current.copy(completedTiles = db.counts().downloaded)
                publish(current, force = true)
                val resumed = current.completedTiles > 0
                val sampler = ElevationTileSampler(dem.metadata, dem::readGrid)
                val metrics = ReliefPerformance(id)
                coroutineScope {
                    val sampled = Channel<Pair<SampledReliefTile, Long>>(1)
                    val encoded = Channel<EncodedReliefTile>(2)
                    val producer = launch {
                        try {
                            for (key in coverage.tiles()) {
                                ensureWanted(id, token)
                                if (resumed && db.contains(TileAddress(key.level, key.column, key.row))) continue
                                val start = TimeSource.Monotonic.markNow()
                                val tile = sampler.sample(key, base.tileWidth)
                                sampled.send(tile to start.elapsedNow().inWholeNanoseconds)
                            }
                        } finally { sampled.close() }
                    }
                    val workers = List(2) { launch {
                        for ((tile, sampleNanos) in sampled) {
                            ensureWanted(id, token)
                            val colorStart = TimeSource.Monotonic.markNow()
                            val pixels = tile.colors(style)
                            val colorNanos = colorStart.elapsedNow().inWholeNanoseconds
                            val pngStart = TimeSource.Monotonic.markNow()
                            val bytes = encodeRasterPng(base.tileWidth, pixels)
                            require(bytes.size in 1..MbTiles.MAX_TILE_BYTES)
                            encoded.send(EncodedReliefTile(TileWrite(TileAddress(tile.key.level, tile.key.column, tile.key.row), bytes),
                                sampleNanos, colorNanos, pngStart.elapsedNow().inWholeNanoseconds))
                        }
                    } }
                    val closer = launch { workers.joinAll(); encoded.close() }
                    val batch = mutableListOf<TileWrite>()
                    var bytes = 0L
                    var batchStart = TimeSource.Monotonic.markNow()
                    suspend fun flush() {
                        if (batch.isEmpty()) return
                        ensureWanted(id, token)
                        files.access { requireCapacity(bytes * 3 + 1_048_576) }
                        val writeStart = TimeSource.Monotonic.markNow()
                        db.write(batch, emptyList(), manifest.sizePolicy.effectiveLayerLimit)
                        metrics.writeNanos += writeStart.elapsedNow().inWholeNanoseconds
                        current = current.copy(completedTiles = current.completedTiles + batch.size)
                        metrics.tiles += batch.size
                        val progressStart = TimeSource.Monotonic.markNow()
                        if (publish(current)) metrics.checkpoints++
                        metrics.progressNanos += progressStart.elapsedNow().inWholeNanoseconds
                        batch.clear(); bytes = 0; batchStart = TimeSource.Monotonic.markNow()
                        metrics.report(dem)
                    }
                    while (true) {
                        val timeout = if (batch.isEmpty()) null else (1000 - batchStart.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)
                        val received = if (timeout == null) encoded.receiveCatching()
                            else withTimeoutOrNull(timeout) { encoded.receiveCatching() }
                        if (received == null) { flush(); continue }
                        val tile = received.getOrNull() ?: break
                        if (bytes + tile.write.bytes.size > MbTiles.MAX_BATCH_BYTES) flush()
                        batch.add(tile.write); bytes += tile.write.bytes.size
                        metrics.sampleNanos += tile.sampleNanos; metrics.colorNanos += tile.colorNanos; metrics.pngNanos += tile.pngNanos
                        if (batch.size == 32 || bytes >= MbTiles.MAX_BATCH_BYTES || batchStart.elapsedNow().inWholeMilliseconds >= 1000) flush()
                    }
                    flush()
                    producer.join(); closer.join()
                }
                publish(current, force = true)
                metrics.report(dem, force = true)
                db.close(); database = null
                dem.close(); reader = null
                ensureWanted(id, token)
                repository.commitRelief(current).valueOrThrow()
                refresh(id)
                PackageResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { control.withLock {
                    val current = cached(id)
                    if (current?.token == token && current.active) save(current.copy(state = ElevationGenerationState.QUEUED))
                } }
                throw cancelled
            } catch (error: Exception) {
                ElevationDiagnostics.error("relief_generation package=${id.value}", error)
                val failure = if (error is DemReadException && error.reason in setOf(DemFailure.UNSUPPORTED, DemFailure.MEMORY_LIMIT))
                    PackageFailure.UnsupportedContent else failureOf(error)
                withContext(NonCancellable) { control.withLock {
                    val current = cached(id)
                    if (current?.token == token && current.active) {
                        val committed = (repository.reliefInput(id) as? PackageResult.Success)?.value?.first?.layers?.any {
                            it.tiles.relativePath == "layers/elevation-relief-$token.mbtiles"
                        } == true
                        save(if (committed) current.copy(state = ElevationGenerationState.QUEUED)
                            else current.copy(state = ElevationGenerationState.FAILED, failure = failure))
                    }
                } }
                PackageResult.Failure(failure)
            } finally {
                withContext(NonCancellable) {
                    try { database?.close() } finally { reader?.close() }
                    val current = job(id)
                    if (current?.token == token && !current.active) files.access { clearElevationTiles(id.value) }
                }
            }
        }
    }

    private suspend fun refresh(id: PackageId) = control.withLock { remember(id, load(id)) }

    private suspend fun publish(value: ElevationGenerationJob, force: Boolean = false): Boolean = control.withLock {
        checkWanted(value.packageId, value.token)
        if (force || checkpoint.elapsedNow().inWholeMilliseconds >= 1000) {
            save(value)
            checkpoint = TimeSource.Monotonic.markNow()
            true
        } else { remember(value.packageId, value); false }
    }

    private suspend fun ensureWanted(id: PackageId, token: String) = control.withLock { checkWanted(id, token) }

    private suspend fun checkWanted(id: PackageId, token: String) {
        currentCoroutineContext().ensureActive()
        if (diskCheck.elapsedNow().inWholeMilliseconds >= 1000) {
            val durable = load(id)
            if (durable?.token != token || !durable.active) remember(id, durable)
            diskCheck = TimeSource.Monotonic.markNow()
        }
        val current = cached(id)
        if (current?.token != token || !current.active) throw CancellationException("Elevation generation cancelled")
    }

    private suspend fun save(value: ElevationGenerationJob, requireActiveToken: Boolean = true) {
        files.access {
            if (!exists(value.packageId.value, false)) {
                remember(value.packageId, null)
                if (requireActiveToken) throw CancellationException("Elevation generation cancelled")
                throw PackageStorageException(PackageFailure.NotFound)
            }
            if (requireActiveToken) {
                val durable = readElevationJob(value.packageId.value)?.let { json.decodeFromString<ElevationGenerationJob>(it) }
                if (durable?.token != value.token || !durable.active) {
                    remember(value.packageId, durable)
                    throw CancellationException("Elevation generation cancelled")
                }
            }
            writeElevationJob(value.packageId.value, json.encodeToString(value))
        }
        remember(value.packageId, value)
    }

    private suspend fun resolveStyle(id: PackageId, token: String, path: String, reader: DemReader, options: ElevationReliefOptions, checkWanted: suspend () -> Unit): ElevationReliefStyle {
        if (options.minimumMeters != null && options.maximumMeters != null) return ElevationReliefStyle(options, options.minimumMeters, options.maximumMeters)
        val start = TimeSource.Monotonic.markNow()
        val digest = withContext(Dispatchers.IO) { TransferStreams.digest(Path(path)) }
        checkWanted()
        val hashMillis = start.elapsedNow().inWholeMilliseconds
        val cached = files.access { readElevationRange(id.value) }?.let {
            runCatching { json.decodeFromString<ReliefRangeCache>(it) }.getOrNull()
        }?.takeIf { it.version == 1 && it.digest == digest && it.minimum.isFinite() && it.maximum.isFinite() && it.minimum <= it.maximum && (it.maximum - it.minimum).isFinite() }
        var minimum = cached?.minimum ?: Double.POSITIVE_INFINITY
        var maximum = cached?.maximum ?: Double.NEGATIVE_INFINITY
        if (cached == null) {
            var block = 0L
            do {
                checkWanted()
                val chunk = reader.rangeChunk(block)
                minimum = minOf(minimum, chunk.minimum); maximum = maxOf(maximum, chunk.maximum)
                check(chunk.nextBlock > block)
                block = chunk.nextBlock
            } while (block < chunk.totalBlocks)
            if (!minimum.isFinite() || !maximum.isFinite() || !(maximum - minimum).isFinite()) throw PackageStorageException(PackageFailure.UnsupportedContent)
            files.access {
                val durable = readElevationJob(id.value)?.let { json.decodeFromString<ElevationGenerationJob>(it) }
                if (durable?.token != token || !durable.active || !exists(id.value, false)) {
                    throw CancellationException("Elevation generation cancelled")
                }
                writeElevationRange(id.value, json.encodeToString(ReliefRangeCache(digest = digest, minimum = minimum, maximum = maximum)))
            }
        }
        ElevationDiagnostics.info("relief_range package=${id.value} cacheHit=${cached != null} hashMs=$hashMillis totalMs=${start.elapsedNow().inWholeMilliseconds}")
        if (minimum == maximum) { minimum -= 0.5; maximum += 0.5 }
        return ElevationReliefStyle(options, minimum, maximum)
    }

    private suspend fun <T> result(block: suspend () -> T): PackageResult<T> = try { PackageResult.Success(block()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { PackageResult.Failure(failureOf(error)) }
}

@Serializable
private data class ReliefRangeCache(val version: Int = 1, val digest: String, val minimum: Double, val maximum: Double)

private data class EncodedReliefTile(val write: TileWrite, val sampleNanos: Long, val colorNanos: Long, val pngNanos: Long)

private class ReliefPerformance(private val id: PackageId) {
    private val start = TimeSource.Monotonic.markNow()
    private var reported = TimeSource.Monotonic.markNow()
    var tiles = 0L
    var sampleNanos = 0L
    var colorNanos = 0L
    var pngNanos = 0L
    var writeNanos = 0L
    var progressNanos = 0L
    var checkpoints = 0L
    suspend fun report(reader: DemReader, force: Boolean = false) {
        if (!force && reported.elapsedNow().inWholeMilliseconds < 5000) return
        val native = reader.metrics()
        ElevationDiagnostics.info("relief_performance package=${id.value} tiles=$tiles elapsedMs=${start.elapsedNow().inWholeMilliseconds} " +
            "sampleMs=${sampleNanos / 1_000_000} colorMs=${colorNanos / 1_000_000} pngMs=${pngNanos / 1_000_000} " +
            "dbMs=${writeNanos / 1_000_000} progressMs=${progressNanos / 1_000_000} checkpoints=$checkpoints " +
            "cacheHits=${native[0].toLong()} blockDecodes=${native[1].toLong()} decodedBytes=${native[2].toLong()} " +
            "nativeSamples=${native[3].toLong()} gridCalls=${native[4].toLong()}")
        reported = TimeSource.Monotonic.markNow()
    }
}

@Inject
@SingleIn(AppScope::class)
class ElevationGenerationController(private val generator: ElevationLayerGenerator, private val scheduler: ElevationGenerationScheduler) {
    suspend fun generate(id: PackageId, options: ElevationReliefOptions): PackageResult<Unit> = withContext(NonCancellable) {
        val prepared = generator.prepare(id, options)
        if (prepared is PackageResult.Failure) return@withContext prepared
        try { scheduler.schedule(id); PackageResult.Success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { generator.fail(id, PackageFailure.Io); PackageResult.Failure(PackageFailure.Io) }
    }

    suspend fun cancel(id: PackageId) {
        generator.cancel(id)
        scheduler.cancel(id)
        generator.discardCancelled(id)
    }
}
