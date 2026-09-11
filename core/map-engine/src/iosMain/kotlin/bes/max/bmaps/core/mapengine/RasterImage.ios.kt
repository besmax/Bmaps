package bes.max.bmaps.core.mapengine

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

internal actual fun hasExpectedRasterDimensions(bytes: ByteArray, tileSize: Int): Boolean = try {
    val data = Data.makeFromBytes(bytes)
    try {
        val codec = Codec.makeFromData(data)
        try {
            codec.width == tileSize && codec.height == tileSize
        } finally { codec.close() }
    } finally { data.close() }
} catch (_: Exception) { false }
