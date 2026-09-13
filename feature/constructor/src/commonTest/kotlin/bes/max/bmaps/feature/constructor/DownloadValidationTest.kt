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

    @Test fun rejectsSelectionsOutsideDeclaredCoverage() {
        val regional = config.copy(boundaries = BoundariesConfig(listOf(BoundingBox(-5.0, -5.0, 5.0, 5.0))))
        assertEquals(Res.string.unsupported_area_or_zoom, validateDownload(settings, regional))
        assertNull(validateDownload(settings.copy(bounds = BoundingBox(-4.0, -4.0, 4.0, 4.0)), regional))
    }
}
