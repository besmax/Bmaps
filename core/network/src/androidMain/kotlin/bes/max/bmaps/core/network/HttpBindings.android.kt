package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidHttpBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun client(): HttpClient = HttpClient(OkHttp) {
        configureBmapsHttpClient()
        engine { config { retryOnConnectionFailure(false) } }
    }
}
