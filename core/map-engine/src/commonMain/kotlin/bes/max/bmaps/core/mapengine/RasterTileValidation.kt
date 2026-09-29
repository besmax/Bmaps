/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

object RasterTileValidation {
    fun hasExpectedDimensions(bytes: ByteArray, tileSize: Int): Boolean =
        tileSize > 0 && bytes.size in 1..2_000_000 && hasExpectedRasterDimensions(bytes, tileSize)
}
