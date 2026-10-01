/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
