package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.TileKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProviderContractsTest {
    @Test
    fun tileAddressesPreserveEachProvidersAxisOrder() {
        val key = TileKey(level = 12, column = 1234, row = 567)
        assertEquals(
            "https://tile.openstreetmap.org/12/1234/567.png",
            BuiltInProviders.osm.styles[0].endpoint.address(key).url,
        )
        assertEquals(
            "https://tile.osmand.net/hd/12/1234/567.png",
            BuiltInProviders.osm.styles[1].endpoint.address(key).url,
        )
        assertEquals(
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/12/567/1234",
            BuiltInProviders.arcGis.styles.single().endpoint.address(key).url,
        )
        assertEquals(
            "https://tile.thunderforest.com/atlas/12/1234/567.png",
            BuiltInProviders.thunderforest.styles.single().endpoint.address(key).url,
        )
        val yandex = BuiltInProviders.yandex.styles.single().endpoint.address(key)
        assertEquals(mapOf("x" to "1234", "y" to "567", "z" to "12", "l" to "map"), yandex.query)
        assertEquals("apikey", yandex.credential?.queryParameter)
        assertFalse(yandex.query.containsKey("apikey"))
        assertEquals("web_mercator", yandex.parameters.single { it.name == "projection" }.defaultValue)
    }

    @Test
    fun levelZeroAndLevelsAboveEighteenAreNotGloballyRestricted() {
        val endpoint = BuiltInProviders.osm.styles.first().endpoint
        assertEquals("https://tile.openstreetmap.org/0/0/0.png", endpoint.address(TileKey(0, 0, 0)).url)
        assertEquals(
            "https://tile.openstreetmap.org/33/4294967296/1.png",
            endpoint.address(TileKey(33, 4_294_967_296L, 1)).url,
        )
        assertNull(ProviderConfig().levelLimits.levelMax)
        assertNull(ProviderConfig().scaleLimits.maxScale)
        assertEquals(LevelLimitsConfig(0, 20), BuiltInProviders.yandex.config.levelLimits)
        assertEquals(LevelLimitsConfig(0, 22), BuiltInProviders.thunderforest.config.levelLimits)
    }

    @Test
    fun customProviderAndStyleConfigurationRoundTripWithoutRegistryChanges() {
        val config = ProviderConfig(
            boundaries = BoundariesConfig(listOf(BoundingBox(170.0, -10.0, -170.0, 10.0))),
            initialViewport = InitScaleAndScrollConfig(2.0, 0.1, 0.9),
            scaleLimits = ScaleLimitsConfig(0.25, 8.0),
            levelLimits = LevelLimitsConfig(0, 24),
        )
        val custom = BuiltInProviders.thunderforest.copy(
            id = ProviderId("custom-provider"),
            styles = listOf(BuiltInProviders.thunderforest.styles.single().copy(
                id = StyleId("custom-style"),
                configOverride = config,
            )),
        )
        val decoded = Json.decodeFromString<TileProvider>(Json.encodeToString(custom))
        assertEquals(custom, decoded)
        assertEquals(config, decoded.configFor(decoded.styles.single()))
        assertTrue(BuiltInProviders.all.none { it.id == custom.id })
    }

    @Test
    fun osmandDoesNotInheritPublicOsmDownloadPolicy() {
        val osm = BuiltInProviders.osm
        assertEquals(OfflineDownloadPermission.PROHIBITED, osm.capabilitiesFor(osm.styles[0]).offlineDownload)
        assertEquals(OfflineDownloadPermission.REQUIRES_VERIFICATION, osm.capabilitiesFor(osm.styles[1]).offlineDownload)
    }
}
