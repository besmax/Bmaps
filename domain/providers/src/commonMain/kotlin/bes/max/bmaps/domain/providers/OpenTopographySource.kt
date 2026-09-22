package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.datastore.CredentialResult
import bes.max.bmaps.core.datastore.ProviderCredentials
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.network.*
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.http.URLBuilder
import io.ktor.http.parameters

sealed interface ElevationDownloadResult {
    data object Complete : ElevationDownloadResult
    data object CredentialsRequired : ElevationDownloadResult
    data object AccessDenied : ElevationDownloadResult
    data object NoData : ElevationDownloadResult
    data object InvalidRequest : ElevationDownloadResult
    data object Network : ElevationDownloadResult
    data class RateLimited(val retryAfterMillis: Long?) : ElevationDownloadResult
}

fun interface ElevationSource {
    suspend fun download(dataset: ElevationDataset, bounds: BoundingBox, consume: suspend (ByteArray, Int) -> Unit): ElevationDownloadResult
}

@Inject
@ContributesBinding(AppScope::class)
class OpenTopographySource(private val credentials: ProviderCredentials, private val transport: HttpStreamTransport) : ElevationSource {
    override suspend fun download(dataset: ElevationDataset, bounds: BoundingBox, consume: suspend (ByteArray, Int) -> Unit): ElevationDownloadResult {
        val key = (credentials.read(OPENTOPOGRAPHY_CREDENTIAL) as? CredentialResult.Available)?.value
            ?: return ElevationDownloadResult.CredentialsRequired
        val datasetId = dataset.apiValue ?: return ElevationDownloadResult.InvalidRequest
        val url = URLBuilder(OpenTopographyEndpoints.globalDem).apply {
            parameters.append("demtype", datasetId)
            parameters.append("south", bounds.south.toString())
            parameters.append("north", bounds.north.toString())
            parameters.append("west", bounds.west.toString())
            parameters.append("east", bounds.east.toString())
            parameters.append("outputFormat", "GTiff")
            parameters.append("API_Key", key)
        }.buildString()
        return when (val result = transport.download(HttpStreamRequest(url), consume)) {
            is HttpStreamResult.Complete -> ElevationDownloadResult.Complete
            is HttpStreamResult.Failed -> when (result.reason) {
                HttpFailure.RESPONSE_TOO_LARGE -> ElevationDownloadResult.InvalidRequest
                HttpFailure.INVALID_REQUEST -> ElevationDownloadResult.InvalidRequest
                else -> ElevationDownloadResult.Network
            }
            is HttpStreamResult.Status -> when (result.code) {
                401 -> ElevationDownloadResult.CredentialsRequired
                403 -> ElevationDownloadResult.AccessDenied
                204, 404 -> ElevationDownloadResult.NoData
                400, 413, 422 -> ElevationDownloadResult.InvalidRequest
                429 -> ElevationDownloadResult.RateLimited(result.retryAfterMillis)
                else -> ElevationDownloadResult.Network
            }
        }
    }
}
