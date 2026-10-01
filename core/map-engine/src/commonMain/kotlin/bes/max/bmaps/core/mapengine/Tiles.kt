/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
    TIMEOUT, RESPONSE_TOO_LARGE, INVALID_REQUEST, SERVER, HTTP,
}
