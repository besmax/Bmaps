package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import android.content.Context
import java.io.File
import okhttp3.Cache
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidHttpBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun clients(context: Context): HttpClients = platformHttpClients(File(context.cacheDir, "public-http"))
}

internal fun platformHttpClients(directory: File): HttpClients = HttpClients(
    client(null), client(Cache(directory, 64L * 1024 * 1024)),
)

private fun client(cache: Cache?): HttpClient = HttpClient(OkHttp) {
    configureBmapsHttpClient()
    engine {
        config {
            retryOnConnectionFailure(false)
            cache(cache)
        }
    }
}
