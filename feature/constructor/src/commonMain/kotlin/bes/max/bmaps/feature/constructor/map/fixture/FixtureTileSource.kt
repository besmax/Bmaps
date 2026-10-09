/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor.map.fixture

import bes.max.bmaps.core.mapengine.*
import bmaps.feature.constructor.generated.resources.Res
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
internal suspend fun openFixtureTileSource(): TileSource {
        val png = ByteString(Res.readBytes("files/fixture.png"))
        val jpeg = ByteString(Res.readBytes("files/fixture.jpg"))
        return object : TileSource {
            private val closed = MutableStateFlow(false)
            override suspend fun read(key: TileKey): TileReadResult = when {
                closed.value -> TileReadResult.Failed(TileReadFailure.CLOSED)
                (key.column + key.row) % 2L == 0L -> TileReadResult.Available(png, RasterTileFormat.PNG)
                else -> TileReadResult.Available(jpeg, RasterTileFormat.JPEG)
            }
            override suspend fun close() { closed.value = true }
        }
}
