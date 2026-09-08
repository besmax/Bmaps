package bes.max.bmaps.core.mapengine

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable

@Serializable
data class TileKey(val level: Int, val column: Long, val row: Long)

@Serializable
data class ZoomRange(val min: Int, val max: Int)

@Serializable
enum class TileRowOrigin { TOP, BOTTOM }

@Serializable
enum class TileContentKind { RASTER, VECTOR }

@Serializable
enum class RasterTileFormat(val extension: String, val mediaType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
}

@Serializable
data class TileContentDescriptor(
    val kind: TileContentKind = TileContentKind.RASTER,
    val rasterFormats: Set<RasterTileFormat> = setOf(RasterTileFormat.PNG, RasterTileFormat.JPEG),
)

interface TileSource {
    suspend fun read(key: TileKey): TileReadResult
    suspend fun close()
}

sealed interface TileReadResult {
    data class Available(val bytes: ByteString, val format: RasterTileFormat) : TileReadResult
    data object Missing : TileReadResult
    data class Failed(val reason: TileReadFailure) : TileReadResult
}

enum class TileReadFailure {
    IO, NETWORK, AUTHENTICATION, RATE_LIMITED, CORRUPT_DATA, UNSUPPORTED_CONTENT, CLOSED,
}
