package bes.max.bmaps.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class KtorHttpTransportTest {
    @Test
    fun publicCacheIsOptInAndRejectsCredentialQueries() = runTest {
        var publicCalls = 0
        var privateCalls = 0
        val publicClient = HttpClient(MockEngine { publicCalls++; respond("public") }) { configureBmapsHttpClient() }
        val privateClient = HttpClient(MockEngine { privateCalls++; respond("private") }) { configureBmapsHttpClient() }
        try {
            val transport = KtorHttpTransport(HttpClients(privateClient, publicClient))
            transport.fetch(HttpResourceRequest("https://tiles.test/0.png", cachePublicResponse = true))
            transport.fetch(HttpResourceRequest("https://tiles.test/0.png?apikey=synthetic"))
            assertEquals(HttpResourceResult.Failed(HttpFailure.INVALID_REQUEST),
                transport.fetch(HttpResourceRequest("https://tiles.test/0.png?apikey=synthetic", cachePublicResponse = true)))
            assertEquals(1, publicCalls)
            assertEquals(1, privateCalls)
        } finally { publicClient.close(); privateClient.close() }
    }

    @Test
    fun readsBytesAndSetsIdentityWithoutFollowingCredentialRedirects() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("Bmaps/0.1 (bes.max.bmaps)", request.headers[HttpHeaders.UserAgent])
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test/?apikey=secret"))
        }) { configureBmapsHttpClient() }
        try {
            assertEquals(HttpResourceResult.Status(302), KtorHttpTransport(client).fetch(HttpResourceRequest("https://tiles.test/?apikey=secret")))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test
    fun boundsBodiesEvenWithoutContentLength() = runTest {
        val client = HttpClient(MockEngine {
            respond(ByteReadChannel(ByteArray(8193)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }) { configureBmapsHttpClient() }
        try {
            assertEquals(HttpResourceResult.Failed(HttpFailure.RESPONSE_TOO_LARGE),
                KtorHttpTransport(client).fetch(HttpResourceRequest("https://tiles.test/tile", maxResponseBytes = 8192)))
        } finally { client.close() }
    }

    @Test
    fun readsMimeAndRetryAfterAndRejectsInsecureUrls() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (calls == 1) respond("abc", headers = headersOf(HttpHeaders.ContentType, "image/jpeg; charset=binary"))
            else respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "12"))
        }) { configureBmapsHttpClient() }
        try {
            val transport = KtorHttpTransport(client)
            val content = assertIs<HttpResourceResult.Content>(transport.fetch(HttpResourceRequest("https://tiles.test/a")))
            assertEquals("image/jpeg", content.mediaType)
            assertEquals(3, content.bytes.size)
            assertEquals(HttpResourceResult.Status(429, 12_000), transport.fetch(HttpResourceRequest("https://tiles.test/b")))
            assertEquals(HttpResourceResult.Failed(HttpFailure.INVALID_REQUEST), transport.fetch(HttpResourceRequest("http://tiles.test/a")))
            assertEquals(2, calls)
            assertFalse(HttpResourceRequest("https://tiles.test/?apikey=secret").toString().contains("secret"))
        } finally { client.close() }
    }

    @Test
    fun socketTimeoutIsTypedAndDoesNotExposeTheExceptionUrl() = runTest {
        val client = HttpClient(MockEngine {
            throw io.ktor.client.network.sockets.SocketTimeoutException("https://tiles.test/?apikey=secret")
        }) { configureBmapsHttpClient() }
        try {
            val result = KtorHttpTransport(client).fetch(HttpResourceRequest("https://tiles.test/a"))
            assertEquals(HttpResourceResult.Failed(HttpFailure.TIMEOUT), result)
            assertFalse(result.toString().contains("secret"))
        } finally { client.close() }
    }

    @Test
    fun callerCancellationCancelsTheRequestAndReleasesItsPermit() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val client = HttpClient(MockEngine {
            if (++calls == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            } else respond("ok")
        }) { configureBmapsHttpClient() }
        try {
            val transport = KtorHttpTransport(client)
            val pending = async { transport.fetch(HttpResourceRequest("https://tiles.test/a")) }
            entered.await()
            pending.cancelAndJoin()
            cancelled.await()
            assertIs<HttpResourceResult.Content>(transport.fetch(HttpResourceRequest("https://tiles.test/b")))
        } finally { client.close() }
    }
}
