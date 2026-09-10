package bes.max.bmaps.core.network

import kotlinx.io.bytestring.ByteString

class HttpResourceRequest(
    val url: String,
    val requestTimeoutMillis: Long = 15_000,
    val connectTimeoutMillis: Long = 5_000,
    val socketTimeoutMillis: Long = 10_000,
    val maxResponseBytes: Int = 2_000_000,
    val cachePublicResponse: Boolean = false,
) {
    init {
        require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0)
        require(maxResponseBytes > 0)
    }

    override fun toString(): String = "HttpResourceRequest(<redacted>)"
}

sealed interface HttpResourceResult {
    data class Content(val bytes: ByteString, val mediaType: String?) : HttpResourceResult
    data class Status(val code: Int, val retryAfterMillis: Long? = null) : HttpResourceResult
    data class Failed(val reason: HttpFailure) : HttpResourceResult
}

enum class HttpFailure { NETWORK, TIMEOUT, RESPONSE_TOO_LARGE, INVALID_REQUEST }

fun interface HttpTransport {
    suspend fun fetch(request: HttpResourceRequest): HttpResourceResult
}
