package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.getTimeMillis
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KtorHttpTransport(private val client: HttpClient) : HttpTransport {
    private val requests = Semaphore(16)

    override suspend fun fetch(request: HttpResourceRequest): HttpResourceResult = requests.withPermit {
        try {
            val url = Url(request.url)
            if (url.protocol != URLProtocol.HTTPS || url.user != null || url.password != null || url.fragment.isNotEmpty()) {
                return@withPermit HttpResourceResult.Failed(HttpFailure.INVALID_REQUEST)
            }
            client.prepareGet(url) {
                timeout {
                    requestTimeoutMillis = request.requestTimeoutMillis
                    connectTimeoutMillis = request.connectTimeoutMillis
                    socketTimeoutMillis = request.socketTimeoutMillis
                }
            }.execute { response ->
                if (response.status.value != 200) {
                    return@execute HttpResourceResult.Status(
                        response.status.value,
                        retryAfter(response.headers[HttpHeaders.RetryAfter]),
                    )
                }
                if ((response.contentLength() ?: 0) > request.maxResponseBytes) {
                    return@execute HttpResourceResult.Failed(HttpFailure.RESPONSE_TOO_LARGE)
                }
                val channel = response.bodyAsChannel()
                val buffer = Buffer()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = channel.readAvailable(chunk)
                    if (count == -1) break
                    if (buffer.size + count > request.maxResponseBytes) {
                        return@execute HttpResourceResult.Failed(HttpFailure.RESPONSE_TOO_LARGE)
                    }
                    buffer.write(chunk, 0, count)
                }
                HttpResourceResult.Content(
                    ByteString(buffer.readByteArray()),
                    response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            HttpResourceResult.Failed(HttpFailure.TIMEOUT)
        } catch (_: ConnectTimeoutException) {
            HttpResourceResult.Failed(HttpFailure.TIMEOUT)
        } catch (_: SocketTimeoutException) {
            HttpResourceResult.Failed(HttpFailure.TIMEOUT)
        } catch (_: IllegalArgumentException) {
            HttpResourceResult.Failed(HttpFailure.INVALID_REQUEST)
        } catch (_: Exception) {
            HttpResourceResult.Failed(HttpFailure.NETWORK)
        }
    }
}

private fun retryAfter(value: String?): Long? {
    if (value == null) return null
    value.toLongOrNull()?.let { return if (it >= 0) it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 else null }
    return try {
        (value.fromHttpToGmtDate().timestamp - getTimeMillis()).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}
