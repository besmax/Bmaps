package bes.max.bmaps.domain.providers

enum class OnlineMapAvailability {
    AVAILABLE, ACCOUNT_KEY_REQUIRED, PROVIDER_PERMISSION_REQUIRED, ESRI_LICENSE_REQUIRED, YANDEX_INTEGRATION_REQUIRED,
}

fun ProviderStyleId.onlineMapAvailability(): OnlineMapAvailability = when {
    provider.value == "osm" && style.value == "world-street-map" -> OnlineMapAvailability.AVAILABLE
    provider.value == "thunderforest" && style.value == "atlas" -> OnlineMapAvailability.ACCOUNT_KEY_REQUIRED
    provider.value == "arcgis" -> OnlineMapAvailability.ESRI_LICENSE_REQUIRED
    provider.value == "yandex" -> OnlineMapAvailability.YANDEX_INTEGRATION_REQUIRED
    else -> OnlineMapAvailability.PROVIDER_PERMISSION_REQUIRED
}
