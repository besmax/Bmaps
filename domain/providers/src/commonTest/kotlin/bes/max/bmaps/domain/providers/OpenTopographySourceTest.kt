package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.datastore.*
import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.network.*
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpenTopographySourceTest {
    private val bounds = BoundingBox(10.0, 45.0, 10.1, 45.1)
    private class Credentials(var result: CredentialResult) : ProviderCredentials {
        override suspend fun read(identifier: String): CredentialResult {
            assertEquals(OPENTOPOGRAPHY_CREDENTIAL, identifier)
            return result
        }
        override suspend fun write(identifier: String, value: String) = CredentialWriteResult.SUCCESS
        override suspend fun remove(identifier: String) = CredentialWriteResult.SUCCESS
    }

    @Test fun missingKeyDoesNotRequestAndReplacementIsReadOnRetry() = runTest {
        val credentials = Credentials(CredentialResult.Missing)
        var calls = 0
        val source = OpenTopographySource(credentials, HttpStreamTransport { request, consume ->
            calls++
            val url = Url(request.url)
            assertEquals("synthetic +/&key", url.parameters["API_Key"])
            assertEquals("SRTM15Plus", url.parameters["demtype"])
            assertEquals("GTiff", url.parameters["outputFormat"])
            assertEquals("45.0", url.parameters["south"])
            assertEquals(Long.MAX_VALUE, request.maxResponseBytes)
            assertFalse(request.toString().contains("synthetic"))
            consume(byteArrayOf(1, 2), 2)
            HttpStreamResult.Complete(2)
        })
        assertEquals(ElevationDownloadResult.CredentialsRequired, source.download(ElevationDataset.SRTM15Plus, bounds) { _, _ -> })
        assertEquals(0, calls)
        credentials.result = CredentialResult.Available("synthetic +/&key")
        var bytes = 0
        assertEquals(ElevationDownloadResult.Complete, source.download(ElevationDataset.SRTM15Plus, bounds) { _, count -> bytes += count })
        assertEquals(2, bytes)
        assertEquals(1, calls)
    }

    @Test fun distinguishesAuthenticationQuotaAccessAndNoCoverage() = runTest {
        for ((status, expected) in listOf(
            401 to ElevationDownloadResult.CredentialsRequired,
            403 to ElevationDownloadResult.AccessDenied,
            429 to ElevationDownloadResult.RateLimited(123),
            204 to ElevationDownloadResult.NoData,
            400 to ElevationDownloadResult.InvalidRequest,
            500 to ElevationDownloadResult.Network,
        )) {
            val source = OpenTopographySource(Credentials(CredentialResult.Available("synthetic")),
                HttpStreamTransport { _, _ -> HttpStreamResult.Status(status, 123) })
            assertEquals(expected, source.download(ElevationDataset.COP30, bounds) { _, _ -> error("No content expected") })
        }
    }

    @Test fun selectionRulesRejectDateLineAndOversizeWithoutRestrictingNone() {
        assertTrue(ElevationDataset.COP30.supportsRequest(bounds))
        assertFalse(ElevationDataset.COP30.supportsRequest(BoundingBox(170.0, 0.0, -170.0, 10.0)))
        assertFalse(ElevationDataset.COP30.supportsRequest(BoundingBox(-180.0, -80.0, 180.0, 80.0)))
        assertTrue(ElevationDataset.NONE.supportsRequest(BoundingBox(170.0, 0.0, -170.0, 10.0)))
        assertTrue(ElevationDataset.COP30.estimatedBytes(bounds) > ElevationDataset.COP90.estimatedBytes(bounds))
    }
}
