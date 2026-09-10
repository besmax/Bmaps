package bes.max.bmaps.core.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

class HttpClients(val uncached: HttpClient, val publicCache: HttpClient)

fun HttpClientConfig<*>.configureBmapsHttpClient() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout)
    install(UserAgent) { agent = "Bmaps/0.1 (bes.max.bmaps)" }
}
