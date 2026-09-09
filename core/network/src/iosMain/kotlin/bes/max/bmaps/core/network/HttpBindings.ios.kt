package bes.max.bmaps.core.network

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

@BindingContainer
@ContributesTo(AppScope::class)
object IosHttpBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun client(): HttpClient = HttpClient(Darwin) { configureBmapsHttpClient() }
}
