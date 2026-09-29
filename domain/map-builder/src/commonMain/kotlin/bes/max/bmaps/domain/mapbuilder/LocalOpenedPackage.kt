package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import bes.max.bmaps.core.storage.*
import bes.max.bmaps.domain.providers.ElevationDataset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString

internal class LocalOpenedPackage(
    override val manifest: PackageManifest,
    private val paths: Map<LayerId, String>,
    private val elevationPath: String?,
    private val demReaders: DemReaderFactory,
) : OpenedPackage {
    private val lock = Mutex()
    private val sources = mutableListOf<LocalTileSource>()
    private var dem: DemReader? = null
    private var demFailure: PackageElevation? = null
    private var lastElevationFailure: String? = null
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

    override suspend fun elevation(latitude: Double, longitude: Double): PackageElevation = lock.withLock {
        if (closed) {
            logElevationFailure("session_closed")
            return@withLock PackageElevation.Unavailable
        }
        val path = elevationPath ?: return@withLock PackageElevation.Missing
        val reference = when (manifest.elevationDataset) {
            ElevationDataset.COP30, ElevationDataset.COP90, ElevationDataset.EU_DTM -> "EGM2008"
            ElevationDataset.NASADEM, ElevationDataset.SRTM15Plus -> "EGM96"
            ElevationDataset.NONE -> {
                logElevationFailure("unknown_dataset")
                return@withLock PackageElevation.Unsupported
            }
        }
        demFailure?.let { return@withLock it }
        try {
            val reader = dem ?: run {
                ElevationDiagnostics.info("dem_open package=${manifest.id.value} dataset=${manifest.elevationDataset} " +
                    "asset=${manifest.elevation?.relativePath} bytes=${manifest.elevation?.sizeBytes}")
                demReaders.open(path).also {
                    dem = it
                    ElevationDiagnostics.info("dem_opened package=${manifest.id.value} metadata=${it.metadata}")
                }
            }
            if (reader.metadata.hasGdalMetadata) {
                logElevationFailure("unsupported_gdal_metadata")
                demFailure = PackageElevation.Unsupported
                return@withLock PackageElevation.Unsupported
            }
            val sample = reader.sample(latitude, longitude)
            if (lastElevationFailure != null) {
                ElevationDiagnostics.info("dem_read_recovered package=${manifest.id.value}")
                lastElevationFailure = null
            }
            when (sample) {
                is DemSample.Value -> PackageElevation.Value(sample.rawValue, reference)
                DemSample.NoData -> PackageElevation.NoData
                DemSample.OutsideCoverage -> PackageElevation.OutsideCoverage
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DemReadException) {
            logElevationFailure("${if (dem == null) "dem_open" else "dem_sample"} reason=${error.reason}", error)
            val result = if (error.reason == DemFailure.UNSUPPORTED || error.reason == DemFailure.MEMORY_LIMIT)
                PackageElevation.Unsupported else PackageElevation.Unavailable
            if (dem == null) demFailure = result
            result
        } catch (error: Exception) {
            logElevationFailure("${if (dem == null) "dem_open" else "dem_sample"} unexpected=${error::class.simpleName}", error)
            if (dem == null) demFailure = PackageElevation.Unavailable
            PackageElevation.Unavailable
        }
    }

    private fun logElevationFailure(reason: String, cause: Throwable? = null) {
        if (lastElevationFailure == reason) return
        lastElevationFailure = reason
        ElevationDiagnostics.error("$reason package=${manifest.id.value} dataset=${manifest.elevationDataset} " +
            "asset=${manifest.elevation?.relativePath}", cause)
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
                try { dem?.close() }
                catch (error: Throwable) {
                    ElevationDiagnostics.error("dem_close package=${manifest.id.value}", error)
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
                dem = null
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
