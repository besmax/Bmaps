package bes.max.bmaps.core.mapengine

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

internal actual fun validRasterImage(bytes: ByteArray, tileSize: Int): Boolean = try {
    val data = Data.makeFromBytes(bytes)
    try {
        val codec = Codec.makeFromData(data)
        try {
            if (codec.width != tileSize || codec.height != tileSize) false
            else {
                val image = Image.makeFromEncoded(bytes)
                try {
                    val bitmap = Bitmap.makeFromImage(image)
                    try { bitmap.width == tileSize && bitmap.height == tileSize } finally { bitmap.close() }
                } finally { image.close() }
            }
        } finally { codec.close() }
    } finally { data.close() }
} catch (_: Exception) { false }
