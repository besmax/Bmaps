/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import kotlin.test.*

class ElevationReliefTest {
    @Test fun absoluteScaleIncludesNegativeAndZeroHeightsAndClampsEndpoints() {
        val style = ElevationReliefStyle(ElevationReliefOptions(), -100.0, 100.0)
        assertEquals(ElevationPalette.GREEN.low, style.color(-100.0))
        assertEquals(ElevationPalette.GREEN.high, style.color(100.0))
        assertNotEquals(0, style.color(0.0))
        assertEquals(style.color(-100.0), style.color(-1000.0))
        assertEquals(style.color(100.0), style.color(1000.0))
        assertEquals(0, style.color(Double.NaN))
        assertEquals(0, style.color(Double.POSITIVE_INFINITY))
        val reversed = style.copy(options = style.options.copy(reversed = true))
        assertEquals(style.color(-100.0), reversed.color(100.0))
    }

    @Test fun invalidAndHalfSpecifiedRangesCannotBecomeManualScales() {
        assertTrue(ElevationReliefOptions().valid())
        assertTrue(ElevationReliefOptions(minimumMeters = -20.0, maximumMeters = 0.0).valid())
        assertFalse(ElevationReliefOptions(minimumMeters = 0.0).valid())
        assertFalse(ElevationReliefOptions(minimumMeters = 0.0, maximumMeters = 0.0).valid())
        assertFalse(ElevationReliefOptions(minimumMeters = 2.0, maximumMeters = 1.0).valid())
        assertFalse(ElevationReliefOptions(minimumMeters = Double.NaN, maximumMeters = 1.0).valid())
    }

    @Test fun selectedPalettePositionsMapHeightsAndAllowCrossingOrMatchingHandles() {
        val options = ElevationReliefOptions(minimumColorPosition = 0.25, maximumColorPosition = 0.75)
        val style = ElevationReliefStyle(options, -100.0, 100.0)
        assertEquals(options.palette.color(0.25), style.color(-100.0))
        assertEquals(options.palette.color(0.75), style.color(100.0))
        assertEquals(options.palette.color(0.5), style.color(0.0))
        assertEquals(style.color(-100.0), style.color(-200.0))
        assertEquals(style.color(100.0), style.color(200.0))
        val swapped = style.copy(options = options.copy(minimumColorPosition = 0.75, maximumColorPosition = 0.25))
        assertTrue(swapped.options.valid())
        assertEquals(style.color(-100.0), swapped.color(100.0))
        assertEquals(style.color(100.0), swapped.color(-100.0))
        val uniform = style.copy(options = options.copy(maximumColorPosition = 0.25))
        assertTrue(uniform.options.valid())
        assertEquals(uniform.color(-100.0), uniform.color(100.0))
        assertEquals(0, uniform.color(Double.NaN))
    }

    @Test fun palettePositionsAreValidatedIndependentlyOfHeightRange() {
        assertFalse(ElevationReliefOptions(minimumColorPosition = 0.2).valid())
        assertFalse(ElevationReliefOptions(minimumColorPosition = -0.1, maximumColorPosition = 0.9).valid())
        assertFalse(ElevationReliefOptions(minimumColorPosition = 0.1, maximumColorPosition = 1.1).valid())
        assertFalse(ElevationReliefOptions(minimumColorPosition = Double.NaN, maximumColorPosition = 1.0).valid())
        assertFalse(ElevationReliefOptions(minimumColorPosition = 0.0, maximumColorPosition = Double.POSITIVE_INFINITY).valid())
        assertTrue(ElevationReliefOptions(minimumColorPosition = 1.0, maximumColorPosition = 0.0).valid())
    }

    @Test fun legacyReversalAndSelectedEndpointsSurviveSerialization() {
        val json = PackageManifestCodec.json
        val legacy = json.decodeFromString<ElevationReliefOptions>("""{"palette":"BLUE","reversed":true}""")
        assertEquals(1.0, legacy.minimumColor)
        assertEquals(0.0, legacy.maximumColor)
        val legacyStyle = ElevationReliefStyle(legacy, 0.0, 10.0)
        assertEquals(ElevationPalette.BLUE.high, legacyStyle.color(0.0))
        assertEquals(ElevationPalette.BLUE.low, legacyStyle.color(10.0))
        val selected = legacy.copy(minimumColorPosition = 0.2, maximumColorPosition = 0.8)
        assertEquals(0.2, selected.minimumColor)
        assertEquals(0.8, selected.maximumColor)
        assertEquals(selected, json.decodeFromString<ElevationReliefOptions>(json.encodeToString(selected)))
        assertEquals(ElevationPalette.BLUE.color(0.2), ElevationReliefStyle(selected, 0.0, 10.0).color(0.0))
        val defaults = json.decodeFromString<ElevationReliefOptions>("{}")
        assertEquals(0.0, defaults.minimumColor)
        assertEquals(1.0, defaults.maximumColor)
    }

    @Test fun generatedStyleSurvivesTransferSerializationWithoutChangingLegacyLayers() {
        val bounds = BoundingBox(-10.0, -10.0, 10.0, 10.0)
        val base = PackageLayer(LayerId("base"), "Base", null, PackageAsset("map_data.mbtiles", 100), bounds, ZoomRange(0, 0), tileCount = 1)
        val style = ElevationReliefStyle(ElevationReliefOptions(ElevationPalette.PURPLE, minimumColorPosition = 0.8, maximumColorPosition = 0.2), -50.0, 700.0)
        val relief = base.copy(id = LayerId("elevation-relief"), name = "Elevation", tiles = PackageAsset("layers/elevation-relief-version.mbtiles", 200),
            content = TileContentDescriptor(rasterFormats = setOf(RasterTileFormat.PNG)), elevationRelief = style, renderOrder = 1)
        val manifest = PackageManifest(1, PackageId("map"), "Map", bounds, base.zoomRange, 0, 0, listOf(base, relief))
        assertEquals(manifest, PackageManifestCodec.decode(PackageManifestCodec.encode(manifest)))
        assertEquals(listOf(base.tiles, relief.tiles), PackageManifestCodec.assets(manifest))
        assertNull(PackageManifestCodec.decode(PackageManifestCodec.encode(manifest.copy(layers = listOf(base)))).layers.single().elevationRelief)
        assertFailsWith<IllegalArgumentException> { PackageManifestCodec.validate(manifest.copy(layers = listOf(base, relief.copy(tileWidth = 512, tileHeight = 512)))) }
        assertFailsWith<IllegalArgumentException> { PackageManifestCodec.validate(manifest.copy(layers = listOf(relief))) }
    }
}
