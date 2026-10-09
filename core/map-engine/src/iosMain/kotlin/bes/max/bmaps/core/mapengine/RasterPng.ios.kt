/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import org.jetbrains.skia.*

actual fun encodeRasterPng(size: Int, argb: IntArray): ByteArray {
    require(size in 1..1024 && argb.size == size * size)
    val rgba = ByteArray(argb.size * 4)
    argb.forEachIndexed { index, color ->
        rgba[index * 4] = (color ushr 16).toByte()
        rgba[index * 4 + 1] = (color ushr 8).toByte()
        rgba[index * 4 + 2] = color.toByte()
        rgba[index * 4 + 3] = (color ushr 24).toByte()
    }
    val image = Image.makeRaster(ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, size * 4)
    return try {
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
        try { data.bytes } finally { data.close() }
    } finally { image.close() }
}
