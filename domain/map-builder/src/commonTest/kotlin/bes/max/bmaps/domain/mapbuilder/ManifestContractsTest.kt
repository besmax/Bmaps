package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.RasterTileFormat
import bes.max.bmaps.core.mapengine.TileContentDescriptor
import bes.max.bmaps.core.mapengine.TileContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ManifestContractsTest {
    @Test
    fun versionOneFixtureSupportsMixedRasterLayersAndOptionalAssets() {
        val manifest = Json.decodeFromString<PackageManifest>(manifestFixture)
        assertEquals(ManifestCompatibility.SUPPORTED, manifest.compatibility())
        assertEquals(300_000_000L, manifest.sizePolicy.maxBytes)
        assertEquals(setOf(RasterTileFormat.PNG), manifest.layers[0].content.rasterFormats)
        assertEquals(setOf(RasterTileFormat.JPEG), manifest.layers[1].content.rasterFormats)
        assertEquals("layers/satellite.mbtiles", manifest.layers[1].tiles.relativePath)
        assertEquals(0, manifest.zoomRange.min)
        assertEquals(22, manifest.zoomRange.max)
        assertEquals(manifest, Json.decodeFromString<PackageManifest>(Json.encodeToString(manifest)))
        assertFalse(Json.encodeToString(manifest).contains("apikey"))
    }

    @Test
    fun unsupportedVersionOrVectorContentIsNotReportedAsSupported() {
        val manifest = Json.decodeFromString<PackageManifest>(manifestFixture)
        assertEquals(ManifestCompatibility.UNSUPPORTED_VERSION, manifest.copy(schemaVersion = 2).compatibility())
        val vector = manifest.layers.first().copy(content = TileContentDescriptor(TileContentKind.VECTOR, emptySet()))
        assertEquals(ManifestCompatibility.UNSUPPORTED_TILE_CONTENT, manifest.copy(layers = listOf(vector)).compatibility())
    }

    @Test
    fun absentVersionCannotSilentlyBecomeVersionOne() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PackageManifest>(manifestFixture.replace("\"schemaVersion\": 1,", ""))
        }
    }

    @Test
    fun singleLayerPackageDoesNotRequireAnnotationOrElevationAssets() {
        val manifest = Json.decodeFromString<PackageManifest>(manifestFixture).let {
            it.copy(layers = it.layers.take(1), annotations = null, elevation = null)
        }
        assertEquals(ManifestCompatibility.SUPPORTED, manifest.compatibility())
        assertEquals(manifest, Json.decodeFromString<PackageManifest>(Json.encodeToString(manifest)))
    }
}

private val manifestFixture = """
    {
      "schemaVersion": 1,
      "id": {"value": "fixture-package"},
      "name": "Aligned raster fixture",
      "bounds": {"west": 30.0, "south": 50.0, "east": 31.0, "north": 51.0},
      "zoomRange": {"min": 0, "max": 22},
      "createdAtEpochMillis": 1788825600000,
      "updatedAtEpochMillis": 1788825600000,
      "layers": [
        {
          "id": {"value": "base"},
          "name": "Base",
          "source": null,
          "tiles": {"relativePath": "map_data.mbtiles", "sizeBytes": 4096},
          "bounds": {"west": 30.0, "south": 50.0, "east": 31.0, "north": 51.0},
          "zoomRange": {"min": 0, "max": 22},
          "content": {"kind": "RASTER", "rasterFormats": ["PNG"]}
        },
        {
          "id": {"value": "satellite"},
          "name": "Satellite",
          "source": {"provider": {"value": "arcgis"}, "style": {"value": "world-imagery"}},
          "tiles": {"relativePath": "layers/satellite.mbtiles", "sizeBytes": 8192},
          "bounds": {"west": 30.0, "south": 50.0, "east": 31.0, "north": 51.0},
          "zoomRange": {"min": 0, "max": 22},
          "content": {"kind": "RASTER", "rasterFormats": ["JPEG"]},
          "opacity": 0.5
        }
      ],
      "annotations": {"relativePath": "annotations.db", "sizeBytes": 4096},
      "elevation": {"relativePath": "elevation.geotiff", "sizeBytes": 1024}
    }
""".trimIndent()
