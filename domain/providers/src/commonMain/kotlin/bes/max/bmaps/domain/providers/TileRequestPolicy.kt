package bes.max.bmaps.domain.providers

import kotlinx.serialization.Serializable

@Serializable
data class TileRequestPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 10_000,
    val maxConcurrentRequests: Int = 8,
    val minRequestIntervalMillis: Long = 0,
    val requestTimeoutMillis: Long = 15_000,
    val connectTimeoutMillis: Long = 5_000,
    val socketTimeoutMillis: Long = 10_000,
    val maxTileBytes: Int = 2_000_000,
    val cachePublicResponses: Boolean = false,
) {
    init {
        require(maxAttempts in 1..10)
        require(initialBackoffMillis >= 0 && maxBackoffMillis >= initialBackoffMillis)
        require(maxConcurrentRequests in 1..16 && minRequestIntervalMillis >= 0)
        require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0)
        require(maxTileBytes in 1..16_000_000)
    }
}
