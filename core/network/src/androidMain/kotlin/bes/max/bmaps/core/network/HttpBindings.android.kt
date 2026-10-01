/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import android.content.Context
import java.io.File
import okhttp3.Dispatcher
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
            dispatcher(Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            })
            retryOnConnectionFailure(false)
            cache(cache)
        }
    }
}
