package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

class HttpStreamRequest(
    val url: String,
    val requestTimeoutMillis: Long = 600_000,
    val connectTimeoutMillis: Long = 15_000,
    val socketTimeoutMillis: Long = 120_000,
    val maxResponseBytes: Long = Long.MAX_VALUE,
) {
    init {
        require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0)
        require(maxResponseBytes > 0)
    }
    override fun toString(): String = "HttpStreamRequest(<redacted>)"
}

sealed interface HttpStreamResult {
    data class Complete(val bytes: Long) : HttpStreamResult
    data class Status(val code: Int, val retryAfterMillis: Long?) : HttpStreamResult
    data class Failed(val reason: HttpFailure) : HttpStreamResult
}

fun interface HttpStreamTransport {
    suspend fun download(request: HttpStreamRequest, consume: suspend (ByteArray, Int) -> Unit): HttpStreamResult
}

@Inject
@ContributesBinding(AppScope::class)
class KtorHttpStreamTransport(private val clients: HttpClients) : HttpStreamTransport {
    override suspend fun download(request: HttpStreamRequest, consume: suspend (ByteArray, Int) -> Unit): HttpStreamResult {
        try {
            val url = Url(request.url)
            if (url.protocol != URLProtocol.HTTPS || url.user != null || url.password != null ||
                url.fragment.isNotEmpty()) {
                return HttpStreamResult.Failed(HttpFailure.INVALID_REQUEST)
            }
            return clients.uncached.prepareGet(url) {
                timeout {
                    requestTimeoutMillis = request.requestTimeoutMillis
                    connectTimeoutMillis = request.connectTimeoutMillis
                    socketTimeoutMillis = request.socketTimeoutMillis
                }
            }.execute { response ->
                if (response.status.value != 200) return@execute HttpStreamResult.Status(
                    response.status.value, retryAfter(response.headers[HttpHeaders.RetryAfter]))
                val expected = response.contentLength()
                if ((expected ?: 0) > request.maxResponseBytes) return@execute HttpStreamResult.Failed(HttpFailure.RESPONSE_TOO_LARGE)
                val channel = response.bodyAsChannel()
                val chunk = ByteArray(65_536)
                var received = 0L
                while (true) {
                    val count = channel.readAvailable(chunk)
                    if (count == -1) break
                    if (count == 0) continue
                    if (count > request.maxResponseBytes - received) return@execute HttpStreamResult.Failed(HttpFailure.RESPONSE_TOO_LARGE)
                    received += count
                    try { consume(chunk, count) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { throw ConsumerFailure(error) }
                }
                if (expected != null && received != expected) HttpStreamResult.Failed(HttpFailure.NETWORK)
                else HttpStreamResult.Complete(received)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ConsumerFailure) { throw error.original }
        catch (_: HttpRequestTimeoutException) { return HttpStreamResult.Failed(HttpFailure.TIMEOUT) }
        catch (_: ConnectTimeoutException) { return HttpStreamResult.Failed(HttpFailure.TIMEOUT) }
        catch (_: SocketTimeoutException) { return HttpStreamResult.Failed(HttpFailure.TIMEOUT) }
        catch (_: IllegalArgumentException) { return HttpStreamResult.Failed(HttpFailure.INVALID_REQUEST) }
        catch (_: Exception) { return HttpStreamResult.Failed(HttpFailure.NETWORK) }
    }
}

private class ConsumerFailure(val original: Exception) : Exception()
