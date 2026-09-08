package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.RasterTileFormat
import bes.max.bmaps.core.mapengine.TileKey
import bes.max.bmaps.core.mapengine.TileReadFailure
import bes.max.bmaps.core.mapengine.TileReadResult
import bes.max.bmaps.core.mapengine.TileSource
import bes.max.bmaps.domain.providers.BuiltInProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString

class TileSourceContractsTest {
    @Test
    fun onlineAndLocalSourcesExposeTheSameRendererIndependentResult() = runTest {
        val key = TileKey(3, 2, 1)
        val tile = TileReadResult.Available(ByteString(1, 2, 3), RasterTileFormat.PNG)
        val address = BuiltInProviders.osm.styles.first().endpoint.address(key).url
        val online = FakeOnlineSource(mapOf(address to tile))
        val local = FakeLocalSource(mapOf(key to tile))
        assertEquals(local.read(key), online.read(key))
        assertEquals(TileReadResult.Missing, local.read(TileKey(3, 0, 0)))
        assertEquals(TileReadResult.Missing, online.read(TileKey(3, 0, 0)))
        local.close()
        online.close()
        assertEquals(TileReadResult.Failed(TileReadFailure.CLOSED), local.read(key))
        assertEquals(TileReadResult.Failed(TileReadFailure.CLOSED), online.read(key))
    }

    @Test
    fun cancellationRemainsCoroutineCancellationRatherThanAReadFailure() = runTest {
        val source = object : TileSource {
            override suspend fun read(key: TileKey): TileReadResult = throw CancellationException()
            override suspend fun close() = Unit
        }
        assertFailsWith<CancellationException> { source.read(TileKey(0, 0, 0)) }
    }
}

private class FakeLocalSource(private val tiles: Map<TileKey, TileReadResult.Available>) : TileSource {
    private var closed = false
    override suspend fun read(key: TileKey): TileReadResult =
        if (closed) TileReadResult.Failed(TileReadFailure.CLOSED) else tiles[key] ?: TileReadResult.Missing
    override suspend fun close() { closed = true }
}

private class FakeOnlineSource(private val tiles: Map<String, TileReadResult.Available>) : TileSource {
    private var closed = false
    override suspend fun read(key: TileKey): TileReadResult {
        if (closed) return TileReadResult.Failed(TileReadFailure.CLOSED)
        return tiles[BuiltInProviders.osm.styles.first().endpoint.address(key).url] ?: TileReadResult.Missing
    }
    override suspend fun close() { closed = true }
}
