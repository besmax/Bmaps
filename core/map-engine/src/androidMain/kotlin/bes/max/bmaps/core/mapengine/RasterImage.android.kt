package bes.max.bmaps.core.mapengine

import android.graphics.BitmapFactory

internal actual fun hasExpectedRasterDimensions(bytes: ByteArray, tileSize: Int): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return options.outWidth == tileSize && options.outHeight == tileSize &&
        options.outMimeType in setOf("image/png", "image/jpeg")
}
