/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

actual fun encodeRasterPng(size: Int, argb: IntArray): ByteArray {
    require(size in 1..1024 && argb.size == size * size)
    val bitmap = Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888)
    return try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
}
