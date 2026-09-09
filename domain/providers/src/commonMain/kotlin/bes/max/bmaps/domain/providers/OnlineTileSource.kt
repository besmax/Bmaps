package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.network.*
import kotlin.math.ceil
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal class ProviderRequestGate(provider: TileProvider, private val timeSource: TimeSource = TimeSource.Monotonic) {
    private val capabilities = provider.styles.map { provider.capabilitiesFor(it) }
    private val limit = (capabilities.mapNotNull { it.maxConcurrentRequests } + provider.requestPolicy.maxConcurrentRequests).min()
    private val requests = Semaphore(limit)
    private val pace = Mutex()
    private val interval = maxOf(provider.requestPolicy.minRequestIntervalMillis,
        capabilities.mapNotNull { it.requestsPerSecond }.minOrNull()?.let { ceil(1000 / it).toLong() } ?: 0)
    private var lastStart: kotlin.time.TimeMark? = null

    suspend fun <T> execute(block: suspend () -> T): T = requests.withPermit {
        pace.withLock {
            lastStart?.let { delay((interval - it.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)) }
            lastStart = timeSource.markNow()
        }
        block()
    }
}

internal class OnlineTileSource(
    private val builder: UrlTileBuilder,
    private val config: ProviderConfig,
    private val content: TileContentDescriptor,
    private val policy: TileRequestPolicy,
    private val gate: ProviderRequestGate,
    private val transport: HttpTransport,
) : TileSource {
    private val lock = Mutex()
    private var closed = false
    private val active = mutableSetOf<Job>()

    override suspend fun read(key: TileKey): TileReadResult = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        if (!lock.withLock { if (closed) false else { active.add(job); true } }) {
            return@coroutineScope TileReadResult.Failed(TileReadFailure.CLOSED)
        }
        try {
            val limits = config.levelLimits
            if (key.level < limits.levelMin || (limits.levelMax != null && key.level > limits.levelMax) ||
                key.level < 0 || key.column < 0 || key.row < 0 ||
                (key.level < 63 && (key.column >= (1L shl key.level) || key.row >= (1L shl key.level)))) {
                return@coroutineScope TileReadResult.Failed(TileReadFailure.INVALID_REQUEST)
            }
            if (content.kind != TileContentKind.RASTER) return@coroutineScope TileReadResult.Failed(TileReadFailure.UNSUPPORTED_CONTENT)
            val endpointRow = when (config.tileMatrix.rowOrigin) {
                TileRowOrigin.TOP -> key.row
                TileRowOrigin.BOTTOM -> {
                    if (key.level > 63) return@coroutineScope TileReadResult.Failed(TileReadFailure.INVALID_REQUEST)
                    if (key.level == 63) Long.MAX_VALUE - key.row else (1L shl key.level) - 1 - key.row
                }
            }
            val url = try { builder.build(key.level, endpointRow, key.column) }
            catch (_: IllegalArgumentException) { return@coroutineScope TileReadResult.Failed(TileReadFailure.INVALID_REQUEST) }
            var backoff = policy.initialBackoffMillis
            repeat(policy.maxAttempts) { attempt ->
                val response = gate.execute { transport.fetch(HttpResourceRequest(
                    url, policy.requestTimeoutMillis, policy.connectTimeoutMillis, policy.socketTimeoutMillis, policy.maxTileBytes,
                )) }
                val result = response.toTileResult(content)
                if (!response.retryable() || attempt == policy.maxAttempts - 1) return@coroutineScope result
                val retryAfter = (response as? HttpResourceResult.Status)?.retryAfterMillis ?: 0
                if (retryAfter > policy.maxBackoffMillis) return@coroutineScope result
                delay(maxOf(backoff, retryAfter))
                backoff = if (backoff > policy.maxBackoffMillis / 2) policy.maxBackoffMillis else backoff * 2
            }
            error("Unreachable attempt count")
        } finally { withContext(NonCancellable) { lock.withLock { active.remove(job) } } }
    }

    override suspend fun close() {
        val jobs = lock.withLock { closed = true; active.toList() }
        jobs.forEach { it.cancel(CancellationException("Tile source closed")) }
        jobs.forEach { it.join() }
    }
}

private fun HttpResourceResult.retryable(): Boolean = when (this) {
    is HttpResourceResult.Content -> false
    is HttpResourceResult.Status -> code == 408 || code == 429 || code in listOf(500, 502, 503, 504)
    is HttpResourceResult.Failed -> reason == HttpFailure.NETWORK || reason == HttpFailure.TIMEOUT
}

private fun HttpResourceResult.toTileResult(content: TileContentDescriptor): TileReadResult = when (this) {
    is HttpResourceResult.Content -> {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val format = when {
            bytes.size >= 8 && png.indices.all { bytes[it] == png[it] } -> RasterTileFormat.PNG
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> RasterTileFormat.JPEG
            else -> null
        }
        when {
            format == null -> TileReadResult.Failed(TileReadFailure.CORRUPT_DATA)
            format !in content.rasterFormats -> TileReadResult.Failed(TileReadFailure.UNSUPPORTED_CONTENT)
            mediaType != null && mediaType != "application/octet-stream" && mediaType != format.mediaType ->
                TileReadResult.Failed(TileReadFailure.UNSUPPORTED_CONTENT)
            else -> TileReadResult.Available(bytes, format)
        }
    }
    is HttpResourceResult.Status -> when (code) {
        404, 410 -> TileReadResult.Missing
        401, 403 -> TileReadResult.Failed(TileReadFailure.AUTHENTICATION)
        408 -> TileReadResult.Failed(TileReadFailure.TIMEOUT)
        429 -> TileReadResult.Failed(TileReadFailure.RATE_LIMITED)
        in 500..599 -> TileReadResult.Failed(TileReadFailure.SERVER)
        else -> TileReadResult.Failed(TileReadFailure.HTTP)
    }
    is HttpResourceResult.Failed -> TileReadResult.Failed(when (reason) {
        HttpFailure.NETWORK -> TileReadFailure.NETWORK
        HttpFailure.TIMEOUT -> TileReadFailure.TIMEOUT
        HttpFailure.RESPONSE_TOO_LARGE -> TileReadFailure.RESPONSE_TOO_LARGE
        HttpFailure.INVALID_REQUEST -> TileReadFailure.INVALID_REQUEST
    })
}
