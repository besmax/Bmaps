package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.datastore.*
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.network.*
import io.ktor.http.Url
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.buffered
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineProvidersTest {
    @Test
    fun providerBuildersPreserveAxesAndEncodeCredentialsExactlyOnce() {
        val registry = ProviderRegistry.builtIn()
        for (provider in BuiltInProviders.all) {
            val registration = registry.registration(provider.id)!!
            for (style in provider.styles) {
                val builder = registration.urlBuilderFactory.create(style.endpoint, emptyMap(), "a+b&c=%/ ключ")
                val url = Url(builder.build(12, 567, 1234))
                style.endpoint.credential?.let { assertEquals("a+b&c=%/ ключ", url.parameters[it.queryParameter]) }
                assertFalse(builder.toString().contains("ключ"))
                if (provider.id == BuiltInProviders.arcGis.id) assertTrue(url.encodedPath.endsWith("12/567/1234"))
                if (provider.id == BuiltInProviders.yandex.id) {
                    assertEquals("1234", url.parameters["x"])
                    assertEquals("567", url.parameters["y"])
                    assertEquals("web_mercator", url.parameters["projection"])
                }
            }
        }
        assertTrue(OsmUrlTileBuilder(BuiltInProviders.osm.styles.first().endpoint).build(33, 1, 4_294_967_296).contains("4294967296"))
        assertFailsWith<IllegalArgumentException> {
            EndpointUrlTileBuilder(TileEndpoint("https://tiles.test", parameters = listOf(EndpointParameter("lang", required = true))))
        }
        assertFailsWith<IllegalArgumentException> {
            EndpointUrlTileBuilder(BuiltInProviders.yandex.styles.first().endpoint)
        }
    }

    @Test
    fun customProviderRegistersWithoutChangingCoreAndDuplicateIdsFail() = runTest {
        val custom = provider().copy(id = ProviderId("custom"))
        val registry = ProviderRegistry(listOf(ProviderRegistration(custom)))
        assertEquals(listOf(custom), registry.list())
        assertEquals(custom, registry.find(custom.id))
        assertNull(registry.find(ProviderId("missing")))
        assertFailsWith<IllegalArgumentException> { ProviderRegistry(listOf(ProviderRegistration(custom), ProviderRegistration(custom))) }
    }

    @Test
    fun credentialsAndOnlinePolicyFailBeforeAnyNetworkRequest() = runTest {
        val transport = HttpTransport { error("Must not request tiles") }
        val factory = OnlineTileSourceFactory(ProviderRegistry.builtIn(), credentials, transport)
        assertEquals(OnlineSourceResult.Failed(OnlineSourceFailure.MISSING_CREDENTIAL), factory.open(
            ProviderStyleId(BuiltInProviders.yandex.id, BuiltInProviders.yandex.styles.first().id)))
        val disabled = provider().copy(capabilities = provider().capabilities.copy(onlineViewing = false))
        assertEquals(OnlineSourceResult.Failed(OnlineSourceFailure.ONLINE_DISABLED), factory(disabled, transport).open(id(disabled)))
    }

    @Test
    fun transientErrorsRetryButAuthenticationMissingAndBadImagesDoNot() = runTest {
        var calls = 0
        val p = provider().copy(requestPolicy = policy.copy(initialBackoffMillis = 100, maxBackoffMillis = 1000))
        val source = open(p, HttpTransport { if (++calls < 3) HttpResourceResult.Status(503) else png })
        val before = currentTime
        assertIs<TileReadResult.Available>(source.read(TileKey(0, 0, 0)))
        assertEquals(3, calls)
        assertEquals(300, currentTime - before)
        source.close()
        for ((response, expected) in listOf(
            HttpResourceResult.Status(401) to TileReadResult.Failed(TileReadFailure.AUTHENTICATION),
            HttpResourceResult.Status(404) to TileReadResult.Missing,
            HttpResourceResult.Content(ByteString("<html>".encodeToByteArray()), "image/png") to TileReadResult.Failed(TileReadFailure.CORRUPT_DATA),
        )) {
            calls = 0
            val other = open(provider(), HttpTransport { calls++; response })
            assertEquals(expected, other.read(TileKey(0, 0, 0)))
            assertEquals(1, calls)
            other.close()
        }
    }

    @Test
    fun retryAfterIsHonoredAndLongServerDelayDoesNotRetryEarly() = runTest {
        var calls = 0
        val p = provider().copy(requestPolicy = policy.copy(maxBackoffMillis = 1000))
        val source = open(p, HttpTransport { if (++calls == 1) HttpResourceResult.Status(429, 800) else png })
        val before = currentTime
        assertIs<TileReadResult.Available>(source.read(TileKey(0, 0, 0)))
        assertEquals(800, currentTime - before)
        source.close()
        calls = 0
        val limited = open(p, HttpTransport { calls++; HttpResourceResult.Status(429, 5000) })
        assertEquals(TileReadResult.Failed(TileReadFailure.RATE_LIMITED), limited.read(TileKey(0, 0, 0)))
        assertEquals(1, calls)
        limited.close()
    }

    @Test
    fun closeCancelsInflightReadsAndFurtherReadsFailClosed() = runTest {
        val entered = CompletableDeferred<Unit>()
        val source = open(provider(), HttpTransport { entered.complete(Unit); awaitCancellation() })
        val pending = async { source.read(TileKey(0, 0, 0)) }
        entered.await()
        source.close()
        assertTrue(pending.isCancelled)
        assertEquals(TileReadResult.Failed(TileReadFailure.CLOSED), source.read(TileKey(0, 0, 0)))
        source.close()
    }

    @Test
    fun concurrencyIsSharedAcrossSessionsAndSourceLimitsPreventRequests() = runTest {
        val p = provider().copy(requestPolicy = policy.copy(maxConcurrentRequests = 1))
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val factory = factory(p, HttpTransport { calls++; release.await(); png })
        val first = assertIs<OnlineSourceResult.Available>(factory.open(id(p))).source
        val second = assertIs<OnlineSourceResult.Available>(factory.open(id(p))).source
        assertEquals(TileReadResult.Failed(TileReadFailure.INVALID_REQUEST), first.read(TileKey(0, 1, 0)))
        val a = async { first.read(TileKey(0, 0, 0)) }
        val b = async { second.read(TileKey(0, 0, 0)) }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        a.await(); b.await()
        assertEquals(2, calls)
        first.close(); second.close()
    }

    @Test
    fun jpegAndPngStreamsAreIndependentAndFailuresAreReported() = runTest {
        val p = provider().copy(styles = listOf(provider().styles.first().copy(content = TileContentDescriptor())))
        val jpeg = HttpResourceResult.Content(ByteString(byteArrayOf(-1, -40, -1, 0)), "image/jpeg")
        var calls = 0
        val source = open(p, HttpTransport { if (++calls == 1) jpeg else png })
        val streams = TileSourceStreamProvider(source) { _, _ -> fail("Unexpected failure") }
        val a = streams.getTileStream(0, 0, 0)!!.buffered()
        val b = streams.getTileStream(0, 0, 0)!!.buffered()
        a.use { assertContentEquals(jpeg.bytes.toByteArray(), it.readByteArray()) }
        b.use { assertContentEquals(png.bytes.toByteArray(), it.readByteArray()) }
        source.close()
        var failure: TileReadFailure? = null
        assertNull(TileSourceStreamProvider(source) { _, reason -> failure = reason }.getTileStream(0, 0, 0))
        assertEquals(TileReadFailure.CLOSED, failure)
    }

    @Test
    fun retriesStopAtProviderBudgetAndCancellationDuringBackoffStopsRequests() = runTest {
        var calls = 0
        val p = provider().copy(requestPolicy = policy.copy(maxAttempts = 2, initialBackoffMillis = 1000, maxBackoffMillis = 1000))
        val source = open(p, HttpTransport { calls++; HttpResourceResult.Failed(HttpFailure.TIMEOUT) })
        val pending = async { source.read(TileKey(0, 0, 0)) }
        runCurrent()
        assertEquals(1, calls)
        pending.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(TileReadResult.Failed(TileReadFailure.TIMEOUT), source.read(TileKey(0, 0, 0)))
        assertEquals(3, calls)
        source.close()
    }

    @Test
    fun bottomOriginConversionAndFormatMismatchAreExplicit() = runTest {
        var requestedUrl = ""
        val p = provider().copy(config = ProviderConfig(
            tileMatrix = TileMatrixConfig(rowOrigin = TileRowOrigin.BOTTOM), levelLimits = LevelLimitsConfig(0, 63)))
        val source = open(p, HttpTransport { request ->
            requestedUrl = request.url
            png.copy(mediaType = "image/jpeg")
        })
        assertEquals(TileReadResult.Failed(TileReadFailure.UNSUPPORTED_CONTENT), source.read(TileKey(3, 2, 1)))
        assertTrue(requestedUrl.endsWith("/3/2/6.png"))
        source.read(TileKey(63, 0, 0))
        assertTrue(requestedUrl.endsWith("/63/0/9223372036854775807.png"))
        assertEquals(TileReadResult.Failed(TileReadFailure.INVALID_REQUEST), source.read(TileKey(64, 0, 0)))
        source.close()
    }

    @Test
    fun providerRequestRatePacesStartsAcrossConcurrentCallers() = runTest {
        val p = provider().copy(capabilities = provider().capabilities.copy(requestsPerSecond = 2.0))
        val gate = ProviderRequestGate(p, testScheduler.timeSource)
        val starts = mutableListOf<Long>()
        List(3) { async { gate.execute { starts.add(currentTime) } } }.awaitAll()
        assertEquals(listOf(0L, 500L, 1000L), starts)
    }

    private fun provider() = BuiltInProviders.osm.copy(requestPolicy = policy)
    private fun id(provider: TileProvider) = ProviderStyleId(provider.id, provider.styles.first().id)
    private fun factory(provider: TileProvider, transport: HttpTransport) = OnlineTileSourceFactory(
        ProviderRegistry(listOf(ProviderRegistration(provider))), credentials, transport)
    private suspend fun open(provider: TileProvider, transport: HttpTransport): TileSource =
        assertIs<OnlineSourceResult.Available>(factory(provider, transport).open(id(provider))).source

    private val policy = TileRequestPolicy(initialBackoffMillis = 0, maxBackoffMillis = 0, minRequestIntervalMillis = 0)
    private val png = HttpResourceResult.Content(ByteString(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)), "image/png")
    private val credentials = object : ProviderCredentials {
        override suspend fun read(identifier: String) = CredentialResult.Missing
        override suspend fun write(identifier: String, value: String) = CredentialWriteResult.SUCCESS
        override suspend fun remove(identifier: String) = CredentialWriteResult.SUCCESS
    }
}
