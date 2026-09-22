package bes.max.bmaps.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KtorHttpStreamTransportTest {
    @Test fun largeContentLengthUsesLongCountersWithoutAnImplicitDemLimit() = runTest {
        val client = HttpClient(MockEngine {
            respond(ByteReadChannel(byteArrayOf(1)), headers = headersOf(HttpHeaders.ContentLength, "3000000000"))
        }) { configureBmapsHttpClient() }
        try {
            var consumed = 0
            val result = KtorHttpStreamTransport(HttpClients(client, client)).download(HttpStreamRequest("https://example.test/dem")) { _, n -> consumed += n }
            assertEquals(1, consumed)
            assertEquals(HttpStreamResult.Failed(HttpFailure.NETWORK), result)
        } finally { client.close() }
    }

    @Test fun streamsInBoundedChunksAndPreservesStorageFailures() = runTest {
        val client = HttpClient(MockEngine { respond(ByteReadChannel(ByteArray(150_000))) }) { configureBmapsHttpClient() }
        try {
            val transport = KtorHttpStreamTransport(HttpClients(client, client))
            var total = 0
            val result = transport.download(HttpStreamRequest("https://example.test/dem")) { bytes, count ->
                assertTrue(bytes.size <= 65_536)
                assertTrue(count <= bytes.size)
                total += count
            }
            assertEquals(HttpStreamResult.Complete(150_000), result)
            assertEquals(150_000, total)
            val failure = IllegalStateException("Storage full")
            assertSame(failure, assertFailsWith<IllegalStateException> {
                transport.download(HttpStreamRequest("https://example.test/dem")) { _, _ -> throw failure }
            })
        } finally { client.close() }
    }

    @Test fun limitsUnknownLengthAndNeverConsumesErrorOrRedirectBodies() = runTest {
        var status = HttpStatusCode.OK
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond(ByteReadChannel(ByteArray(65_537)), status,
                headersOf(HttpHeaders.Location, "https://other.test/secret"))
        }) { configureBmapsHttpClient() }
        try {
            val transport = KtorHttpStreamTransport(HttpClients(client, client))
            var consumed = 0
            assertEquals(HttpStreamResult.Failed(HttpFailure.RESPONSE_TOO_LARGE),
                transport.download(HttpStreamRequest("https://example.test/dem", maxResponseBytes = 65_536)) { _, n -> consumed += n })
            assertTrue(consumed <= 65_536)
            for (code in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Found)) {
                status = code
                assertEquals(HttpStreamResult.Status(code.value, null),
                    transport.download(HttpStreamRequest("https://example.test/dem?API_Key=synthetic")) { _, _ -> error("Unexpected body") })
            }
            assertEquals(3, calls)
        } finally { client.close() }
    }
}
