package bes.max.bmaps.core.mapengine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

internal actual fun transparentTile(tileSize: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
    return try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
}
