package bes.max.bmaps.core.mapengine

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.RawSource

internal class RasterTileSession private constructor(
    private val pyramid: TilePyramid,
    private val sources: Map<String, TileSource>,
    private val onEvent: (MapEvent) -> Unit,
    private val validate: (ByteArray, Int) -> Boolean,
) {
    private val lock = Mutex()
    private val closeLock = Mutex()
    private var closed = false
    private val readers = mutableSetOf<Job>()

    suspend fun stream(layer: String, row: Int, column: Int, level: Int): RawSource? = coroutineScope {
        val key = pyramid.sourceKey(level, row, column) ?: return@coroutineScope null
        val job = currentCoroutineContext().job
        if (!lock.withLock { if (closed) false else { readers.add(job); true } }) return@coroutineScope null
        try {
            when (val result = sources.getValue(layer).read(key)) {
                is TileReadResult.Available -> {
                    val bytes = result.bytes.toByteArray()
                    if (bytes.size > 2_000_000 || !matchesFormat(bytes, result.format) || !withContext(Dispatchers.Default) { validate(bytes, pyramid.tileSize) }) {
                        onEvent(MapEvent.TileFailed(layer, key, TileReadFailure.CORRUPT_DATA))
                        null
                    } else Buffer().apply { write(bytes) }
                }
                TileReadResult.Missing -> null
                is TileReadResult.Failed -> { onEvent(MapEvent.TileFailed(layer, key, result.reason)); null }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onEvent(MapEvent.TileFailed(layer, key, TileReadFailure.IO)); null }
        finally { withContext(NonCancellable) { lock.withLock { readers.remove(job) } } }
    }

    suspend fun close() = withContext(NonCancellable) {
        closeLock.withLock {
            val active = lock.withLock { if (closed) return@withContext; closed = true; readers.toList() }
            active.forEach { it.cancel() }
            active.joinAll()
            sources.values.forEach {
                try { it.close() } catch (_: Exception) { onEvent(MapEvent.Unavailable("Tile source cleanup failed")) }
            }
        }
    }

    companion object {
        suspend fun open(pyramid: TilePyramid, layers: List<RasterLayer>, onEvent: (MapEvent) -> Unit,
                         validate: (ByteArray, Int) -> Boolean = ::validRasterImage): RasterTileSession {
            require(layers.isNotEmpty() && layers.map { it.id }.distinct().size == layers.size)
            val sources = linkedMapOf<String, TileSource>()
            try {
                for (layer in layers) sources[layer.id] = layer.source.open()
                currentCoroutineContext().ensureActive()
                return RasterTileSession(pyramid, sources, onEvent, validate)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { sources.values.forEach { try { it.close() } catch (_: Exception) { } } }
                throw failure
            }
        }
    }
}

internal expect fun validRasterImage(bytes: ByteArray, tileSize: Int): Boolean

private fun matchesFormat(bytes: ByteArray, format: RasterTileFormat): Boolean = when (format) {
    RasterTileFormat.PNG -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    RasterTileFormat.JPEG -> bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
}
