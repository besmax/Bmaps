/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.providers

enum class OnlineMapAvailability {
    AVAILABLE, ACCOUNT_KEY_REQUIRED, PROVIDER_PERMISSION_REQUIRED, ESRI_LICENSE_REQUIRED, YANDEX_INTEGRATION_REQUIRED,
}

fun ProviderStyleId.onlineMapAvailability(): OnlineMapAvailability = when {
    provider.value == "osm" && style.value == "world-street-map" -> OnlineMapAvailability.AVAILABLE
    provider.value == "thunderforest" && style.value == "atlas" -> OnlineMapAvailability.ACCOUNT_KEY_REQUIRED
    provider.value == "arcgis" && style.value == "world-imagery" -> OnlineMapAvailability.AVAILABLE
    provider.value == "yandex" && style.value == "map" -> OnlineMapAvailability.ACCOUNT_KEY_REQUIRED
    provider.value == "osm" && style.value == "osmand-hd" -> OnlineMapAvailability.AVAILABLE
    else -> OnlineMapAvailability.PROVIDER_PERMISSION_REQUIRED
}
