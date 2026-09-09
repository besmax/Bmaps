package bes.max.bmaps.core.mapengine

import android.graphics.BitmapFactory

internal actual fun validRasterImage(bytes: ByteArray, tileSize: Int): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth != tileSize || options.outHeight != tileSize || options.outMimeType !in setOf("image/png", "image/jpeg")) return false
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
    bitmap.recycle()
    return true
}
