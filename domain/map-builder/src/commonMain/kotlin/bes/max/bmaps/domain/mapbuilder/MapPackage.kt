package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.CoordinateSystemId
import bes.max.bmaps.core.mapengine.TileContentDescriptor
import bes.max.bmaps.core.mapengine.TileContentKind
import bes.max.bmaps.core.mapengine.TileRowOrigin
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.providers.Attribution
import bes.max.bmaps.domain.providers.ProviderStyleId
import kotlinx.serialization.Serializable

@Serializable
data class PackageId(val value: String)

@Serializable
data class LayerId(val value: String)

@Serializable
data class PackageSizePolicy(val maxBytes: Long = INITIAL_MAX_BYTES) {
    companion object {
        const val INITIAL_MAX_BYTES: Long = 300_000_000L
    }
}

@Serializable
data class PackageAsset(val relativePath: String, val sizeBytes: Long, val sha256: String? = null)

@Serializable
data class PackageLayer(
    val id: LayerId,
    val name: String,
    val source: ProviderStyleId?,
    val tiles: PackageAsset,
    val bounds: BoundingBox,
    val zoomRange: ZoomRange,
    val content: TileContentDescriptor = TileContentDescriptor(),
    val coordinateSystem: CoordinateSystemId = CoordinateSystemId.WebMercator,
    val rowOrigin: TileRowOrigin = TileRowOrigin.BOTTOM,
    val tileWidth: Int = 256,
    val tileHeight: Int = 256,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val attribution: List<Attribution> = emptyList(),
)

@Serializable
data class PackageManifest(
    val schemaVersion: Int,
    val id: PackageId,
    val name: String,
    val bounds: BoundingBox,
    val zoomRange: ZoomRange,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val layers: List<PackageLayer>,
    val annotations: PackageAsset? = null,
    val elevation: PackageAsset? = null,
    val auxiliaryAssets: List<PackageAsset> = emptyList(),
    val sizePolicy: PackageSizePolicy = PackageSizePolicy(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

enum class ManifestCompatibility { SUPPORTED, UNSUPPORTED_VERSION, UNSUPPORTED_TILE_CONTENT }

fun PackageManifest.compatibility(): ManifestCompatibility = when {
    schemaVersion != PackageManifest.CURRENT_SCHEMA_VERSION -> ManifestCompatibility.UNSUPPORTED_VERSION
    layers.any { it.content.kind != TileContentKind.RASTER || it.content.rasterFormats.isEmpty() } ->
        ManifestCompatibility.UNSUPPORTED_TILE_CONTENT
    else -> ManifestCompatibility.SUPPORTED
}

@Serializable
enum class PackageState { BUILDING, PAUSED, FINALIZING, READY, FAILED, DELETING, CORRUPT, MISSING }

@Serializable
data class PackageSummary(
    val id: PackageId,
    val name: String,
    val bounds: BoundingBox,
    val state: PackageState,
    val sizeBytes: Long,
    val updatedAtEpochMillis: Long,
)
