/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
