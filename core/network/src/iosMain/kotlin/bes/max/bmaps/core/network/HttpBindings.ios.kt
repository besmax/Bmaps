package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLCache
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

@BindingContainer
@ContributesTo(AppScope::class)
object IosHttpBindings {
    @Provides
    @SingleIn(AppScope::class)
    @OptIn(ExperimentalForeignApi::class)
    fun clients(): HttpClients = HttpClients(
        client(null),
        client(NSURLCache(8uL * 1024uL * 1024uL, 64uL * 1024uL * 1024uL, "bmaps-public-http")),
    )

    @OptIn(ExperimentalForeignApi::class)
    private fun client(cache: NSURLCache?): HttpClient = HttpClient(Darwin) {
        configureBmapsHttpClient()
        engine {
            configureSession {
                URLCache = cache
                requestCachePolicy = NSURLRequestUseProtocolCachePolicy
            }
        }
    }
}
