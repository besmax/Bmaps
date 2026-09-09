package bes.max.bmaps.core.mapengine

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray

class RasterTileSessionTest {
    private val pyramid = TilePyramid(ZoomRange(0, 1))
    private val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    @Test fun independentStreamsMissingAndCorruptResults() = runTest {
        val events = mutableListOf<MapEvent>()
        var mode = 0
        val source = object : TileSource {
            override suspend fun read(key: TileKey) = when (mode) {
                0 -> TileReadResult.Available(ByteString(bytes), RasterTileFormat.PNG)
                1 -> TileReadResult.Missing
                else -> TileReadResult.Available(ByteString(byteArrayOf(1)), RasterTileFormat.PNG)
            }
            override suspend fun close() {}
        }
        val session = RasterTileSession.open(pyramid, listOf(RasterLayer("a", TileSourceFactory { source })), events::add) { _, _ -> true }
        val first = session.stream("a", 0, 0, 0)!!.buffered()
        val second = session.stream("a", 0, 0, 0)!!.buffered()
        first.use { assertContentEquals(bytes, it.readByteArray()) }
        second.use { assertContentEquals(bytes, it.readByteArray()) }
        mode = 1; assertNull(session.stream("a", 0, 0, 0)); assertTrue(events.isEmpty())
        mode = 2; assertNull(session.stream("a", 0, 0, 0)); assertIs<MapEvent.TileFailed>(events.single())
        session.close()
        assertNull(session.stream("a", 0, 0, 0))
    }
    @Test fun shutdownCancelsReadsAndClosesAllSourcesExactlyOnce() = runTest {
        val started = CompletableDeferred<Unit>()
        var closed = 0
        val source = object : TileSource {
            override suspend fun read(key: TileKey): TileReadResult { started.complete(Unit); awaitCancellation() }
            override suspend fun close() { closed++ }
        }
        val session = RasterTileSession.open(pyramid, listOf(RasterLayer("a", TileSourceFactory { source })), {}) { _, _ -> true }
        val read = async { session.stream("a", 0, 0, 0) }
        started.await(); session.close(); session.close()
        assertTrue(read.isCancelled); assertEquals(1, closed)
    }
    @Test fun partialOpenFailureReleasesPreviouslyOpenedSources() = runTest {
        var closed = false
        val source = object : TileSource {
            override suspend fun read(key: TileKey) = TileReadResult.Missing
            override suspend fun close() { closed = true }
        }
        assertFailsWith<IllegalStateException> {
            RasterTileSession.open(pyramid, listOf(RasterLayer("a", TileSourceFactory { source }), RasterLayer("b", TileSourceFactory { error("fixture") })), {})
        }
        assertTrue(closed)
    }
}
