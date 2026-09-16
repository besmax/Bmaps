package bes.max.bmaps.feature.constructor

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.*
import bmaps.feature.constructor.generated.resources.*
import kotlin.test.*

class DownloadValidationTest {
    private val settings = MapSaveSettings("2026-09-11_15:38", BoundingBox(-10.0, -10.0, 10.0, 10.0), setOf(0, 2))
    private val config = ProviderConfig(levelLimits = LevelLimitsConfig(0, 4))

    @Test fun validatesBeforeSubmission() {
        assertNull(validateDownload(settings, config))
        assertEquals(Res.string.invalid_map_name, validateDownload(settings.copy(name = "\n"), config))
        assertEquals(Res.string.zoom_selection_required, validateDownload(settings.copy(levels = emptySet()), config))
        assertEquals(Res.string.unsupported_area_or_zoom, validateDownload(settings.copy(levels = setOf(5)), config))
        assertEquals(Res.string.unsupported_area_or_zoom, validateDownload(settings, config.copy(levelLimits = LevelLimitsConfig())))
    }

    @Test fun compositionValidatesEveryProviderAndRechecksChangedZooms() {
        fun choice(id: String, sourceConfig: ProviderConfig = config, permission: OfflineDownloadPermission = OfflineDownloadPermission.ALLOWED): MapChoice {
            val style = TileStyle(StyleId("raster"), "Raster", TileEndpoint("https://example.invalid/{z}/{x}/{y}"))
            return MapChoice(TileProvider(ProviderId(id), id, listOf(style), sourceConfig, emptyList(),
                ProviderCapabilities(offlineDownload = permission, policyUrl = "https://example.invalid/policy")), style)
        }
        val root = choice("osm")
        val satellite = choice("satellite")
        val layered = settings.copy(layers = listOf(AdditionalLayer("satellite", satellite, opacity = 0.5)), rootOpacity = 0.3)
        assertNull(validateComposition(layered, root))
        val smaller = choice("regional", config.copy(boundaries = BoundariesConfig(listOf(BoundingBox(-5.0, -5.0, 5.0, 5.0)))))
        assertEquals(Res.string.unsupported_area_or_zoom, validateComposition(layered.copy(layers = listOf(AdditionalLayer("regional", smaller))), root))
        val restricted = choice("restricted", permission = OfflineDownloadPermission.PROHIBITED)
        assertEquals(Res.string.download_permission_unverified, validateComposition(layered.copy(layers = listOf(AdditionalLayer("restricted", restricted))), root))
        val limited = choice("limited", config.copy(levelLimits = LevelLimitsConfig(0, 1)))
        assertEquals(Res.string.unsupported_area_or_zoom, validateComposition(layered.copy(layers = listOf(AdditionalLayer("limited", limited))), root))
        assertEquals(Res.string.layer_alignment_error, validateComposition(layered.copy(rootOpacity = Double.NaN), root))
        val dimensions = choice("large", config.copy(tileMatrix = config.tileMatrix.copy(tileWidth = 512, tileHeight = 512)))
        assertEquals(Res.string.layer_alignment_error, validateComposition(layered.copy(layers = listOf(AdditionalLayer("large", dimensions))), root))
    }

    @Test fun rejectsSelectionsOutsideDeclaredCoverage() {
        val regional = config.copy(boundaries = BoundariesConfig(listOf(BoundingBox(-5.0, -5.0, 5.0, 5.0))))
        assertEquals(Res.string.unsupported_area_or_zoom, validateDownload(settings, regional))
        assertNull(validateDownload(settings.copy(bounds = BoundingBox(-4.0, -4.0, 4.0, 4.0)), regional))
    }
}
