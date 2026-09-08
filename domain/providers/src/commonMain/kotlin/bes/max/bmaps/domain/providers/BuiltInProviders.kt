package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.RasterTileFormat
import bes.max.bmaps.core.mapengine.TileContentDescriptor

object BuiltInProviders {
    private val osmAttribution = Attribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")
    private val png = TileContentDescriptor(rasterFormats = setOf(RasterTileFormat.PNG))

    val osm = TileProvider(
        id = ProviderId("osm"),
        name = "OSM",
        styles = listOf(
            TileStyle(
                id = StyleId("world-street-map"),
                name = "WorldStreetMap",
                endpoint = TileEndpoint("https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
                content = png,
            ),
            TileStyle(
                id = StyleId("osmand-hd"),
                name = "OsmAndHd",
                endpoint = TileEndpoint("https://tile.osmand.net/hd/{z}/{x}/{y}.png"),
                content = png,
                configOverride = ProviderConfig(
                    levelLimits = LevelLimitsConfig(1, 19),
                    tileMatrix = TileMatrixConfig(tileWidth = 512, tileHeight = 512),
                ),
                attributionOverride = listOf(osmAttribution, Attribution("OsmAnd", "https://osmand.net/")),
                capabilitiesOverride = ProviderCapabilities(policyUrl = "https://osmand.net/docs/user/map/raster-maps/"),
            ),
        ),
        config = ProviderConfig(levelLimits = LevelLimitsConfig(0, 19)),
        attribution = listOf(osmAttribution),
        capabilities = ProviderCapabilities(
            offlineDownload = OfflineDownloadPermission.PROHIBITED,
            policyUrl = "https://operations.osmfoundation.org/policies/tiles/",
            requiresIdentifyingUserAgent = true,
        ),
    )

    val arcGis = TileProvider(
        id = ProviderId("arcgis"),
        name = "ArcGIS",
        styles = listOf(
            TileStyle(
                id = StyleId("world-imagery"),
                name = "Satellite",
                endpoint = TileEndpoint("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
                content = TileContentDescriptor(rasterFormats = setOf(RasterTileFormat.JPEG)),
            ),
        ),
        attribution = listOf(Attribution("Esri and imagery contributors", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer")),
        capabilities = ProviderCapabilities(policyUrl = "https://www.esri.com/en-us/legal/terms/full-master-agreement"),
    )

    val yandex = TileProvider(
        id = ProviderId("yandex"),
        name = "Yandex",
        styles = listOf(
            TileStyle(
                id = StyleId("map"),
                name = "Map",
                endpoint = TileEndpoint(
                    urlTemplate = "https://tiles.api-maps.yandex.ru/v1/tiles/",
                    queryTemplates = mapOf("x" to "{x}", "y" to "{y}", "z" to "{z}", "l" to "map"),
                    parameters = listOf(
                        EndpointParameter("lang", "en_US", required = true),
                        EndpointParameter("scale", "1.0"),
                        EndpointParameter("projection", "web_mercator"),
                        EndpointParameter("maptype", "map"),
                    ),
                    credential = CredentialReference("yandex-api-key", "apikey"),
                ),
                content = png,
            ),
        ),
        config = ProviderConfig(levelLimits = LevelLimitsConfig(0, 20)),
        attribution = listOf(Attribution("© Yandex", "https://yandex.com/maps/", requiresLogo = true)),
        capabilities = ProviderCapabilities(policyUrl = "https://yandex.com/legal/maps_api/"),
    )

    val thunderforest = TileProvider(
        id = ProviderId("thunderforest"),
        name = "Thunderforest",
        styles = listOf(
            TileStyle(
                id = StyleId("atlas"),
                name = "Atlas",
                endpoint = TileEndpoint(
                    urlTemplate = "https://tile.thunderforest.com/atlas/{z}/{x}/{y}.png",
                    credential = CredentialReference("thunderforest-api-key", "apikey"),
                ),
                content = png,
            ),
        ),
        config = ProviderConfig(levelLimits = LevelLimitsConfig(0, 22)),
        attribution = listOf(Attribution("© Thunderforest", "https://www.thunderforest.com/"), osmAttribution),
        capabilities = ProviderCapabilities(policyUrl = "https://www.thunderforest.com/terms/"),
    )

    val all: List<TileProvider> = listOf(osm, arcGis, yandex, thunderforest)
}
