/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import kotlinx.io.Buffer
import kotlinx.io.RawSource

fun interface TileStreamProvider {
    suspend fun getTileStream(row: Long, col: Long, zoomLvl: Int): RawSource?
}

class TileSourceStreamProvider(
    private val source: TileSource,
    private val onFailure: suspend (TileKey, TileReadFailure) -> Unit,
) : TileStreamProvider {
    override suspend fun getTileStream(row: Long, col: Long, zoomLvl: Int): RawSource? {
        val key = TileKey(zoomLvl, col, row)
        return when (val result = source.read(key)) {
            is TileReadResult.Available -> Buffer().apply { write(result.bytes.toByteArray()) }
            TileReadResult.Missing -> null
            is TileReadResult.Failed -> { onFailure(key, result.reason); null }
        }
    }
}
