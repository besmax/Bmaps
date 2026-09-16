package bes.max.bmaps.core.mapengine

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

internal actual fun transparentTile(tileSize: Int): ByteArray {
    val surface = Surface.makeRasterN32Premul(tileSize, tileSize)
    return try {
        surface.canvas.clear(0)
        val image = surface.makeImageSnapshot()
        try {
            val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
            try { data.bytes } finally { data.close() }
        } finally { image.close() }
    } finally { surface.close() }
}
