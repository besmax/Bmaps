package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString

internal class LocalOpenedPackage(
    override val manifest: PackageManifest,
    private val paths: Map<LayerId, String>,
) : OpenedPackage {
    private val lock = Mutex()
    private val sources = mutableListOf<LocalTileSource>()
    var closed = false
        private set

    override suspend fun openTiles(layerId: LayerId): PackageResult<TileSource> = lock.withLock {
        if (closed) return@withLock PackageResult.Failure(PackageFailure.NotReady)
        val layer = manifest.layers.firstOrNull { it.id == layerId }
            ?: return@withLock PackageResult.Failure(PackageFailure.NotFound)
        try {
            val source = LocalTileSource(MbTiles.open(checkNotNull(paths[layerId])), layer)
            sources.removeAll { it.closed }
            sources.add(source)
            PackageResult.Success(source)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PackageResult.Failure(PackageFailure.Io) }
    }

    override suspend fun close()  {
        withContext(NonCancellable) {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                var failure: Throwable? = null
                sources.forEach {
                    try {
                        it.close()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }
                }
                sources.clear()
                failure?.let { throw it }
            }
        }
    }
}

private class LocalTileSource(private val database: MbTiles, private val layer: PackageLayer) : TileSource {
    var closed = false
        private set

    override suspend fun read(key: TileKey): TileReadResult {
        if (closed) return TileReadResult.Failed(TileReadFailure.CLOSED)
        if (!key.isValidXyz()) return TileReadResult.Failed(TileReadFailure.INVALID_REQUEST)
        if (key.level !in layer.zoomRange.min..layer.zoomRange.max ||
            (layer.zoomLevels.isNotEmpty() && key.level !in layer.zoomLevels)) return TileReadResult.Missing
        return try {
            val bytes = database.read(TileAddress(key.level, key.column, key.row)) ?: return TileReadResult.Missing
            val format = rasterFormat(bytes) ?: return TileReadResult.Failed(TileReadFailure.CORRUPT_DATA)
            if (format !in layer.content.rasterFormats) TileReadResult.Failed(TileReadFailure.UNSUPPORTED_CONTENT)
            else TileReadResult.Available(ByteString(bytes), format)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ClosedMbTiles) { TileReadResult.Failed(TileReadFailure.CLOSED) }
        catch (_: Exception) { TileReadResult.Failed(TileReadFailure.IO) }
    }

    override suspend fun close() { database.close(); closed = true }
}

internal fun rasterFormat(bytes: ByteArray): RasterTileFormat? = when {
    bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> RasterTileFormat.PNG
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> RasterTileFormat.JPEG
    else -> null
}
