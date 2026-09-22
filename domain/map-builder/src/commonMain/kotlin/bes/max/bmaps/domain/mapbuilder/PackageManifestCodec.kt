package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.storage.checkComponent
import kotlinx.serialization.json.Json

internal object PackageManifestCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun decode(value: String): PackageManifest = json.decodeFromString<PackageManifest>(value).also(::validate)
    fun encode(manifest: PackageManifest): String { validate(manifest); return json.encodeToString(manifest) }

    fun validate(manifest: PackageManifest) {
        when (manifest.compatibility()) {
            ManifestCompatibility.UNSUPPORTED_VERSION -> throw PackageStorageException(PackageFailure.UnsupportedVersion(manifest.schemaVersion))
            ManifestCompatibility.UNSUPPORTED_TILE_CONTENT -> throw PackageStorageException(PackageFailure.UnsupportedContent)
            ManifestCompatibility.SUPPORTED -> Unit
        }
        checkComponent(manifest.id.value)
        require(manifest.layers.isNotEmpty() && manifest.layers.size <= 32)
        require(manifest.layers.map { it.id }.toSet().size == manifest.layers.size)
        require(manifest.sizePolicy.maxLayerBytes > 0)
        require(manifest.zoomRange.min == manifest.layers.minOf { it.zoomRange.min } &&
            manifest.zoomRange.max == manifest.layers.maxOf { it.zoomRange.max })
        manifest.layers.forEachIndexed { index, layer ->
            checkComponent(layer.id.value)
            require(layer.bounds == manifest.bounds && layer.rowOrigin == TileRowOrigin.BOTTOM)
            if (layer.coordinateSystem != CoordinateSystemId.WebMercator) {
                throw PackageStorageException(PackageFailure.UnsupportedCoordinateSystem)
            }
            require(layer.tileWidth > 0 && layer.tileHeight == layer.tileWidth)
            require(layer.renderOrder >= 0)
            require(layer.opacity.isFinite() && layer.opacity in 0.0..1.0)
            require(layer.tiles.relativePath == if (index == 0) "map_data.mbtiles" else "layers/${layer.id.value}.mbtiles")
            val coverage = PackageTileCoverage(layer.bounds, layer.zoomRange, layer.zoomLevels)
            require(layer.tileCount == null || layer.tileCount == coverage.count)
        }
        require(manifest.annotations == null || manifest.annotations.relativePath == "annotations.db")
        val assets = assets(manifest)
        require(assets.map { it.relativePath }.toSet().size == assets.size)
        assets.forEach {
            require(it.sizeBytes >= 0 && it.relativePath !in setOf("config.json", "draft.json"))
            require(!it.relativePath.endsWith(".part") && !it.relativePath.endsWith("-journal") &&
                !it.relativePath.endsWith("-wal") && !it.relativePath.endsWith("-shm"))
            it.relativePath.split('/').forEach(::checkComponent)
            // Checksummed imports require the Phase 10 integrity reader; never accept an unchecked digest.
            if (it.sha256 != null) throw PackageStorageException(PackageFailure.UnsupportedContent)
        }
    }

    fun assets(manifest: PackageManifest): List<PackageAsset> = manifest.layers.map { it.tiles } +
        listOfNotNull(manifest.annotations, manifest.elevation) + manifest.auxiliaryAssets
}

internal class PackageStorageException(val failure: PackageFailure) : Exception()
