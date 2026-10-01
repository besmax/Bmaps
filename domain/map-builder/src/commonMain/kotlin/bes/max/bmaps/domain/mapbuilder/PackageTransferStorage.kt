/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import bes.max.bmaps.core.storage.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.*
import kotlinx.io.files.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal suspend fun PackageFiles.exportSnapshot(manifest: PackageManifest, destination: RawSink) {
    val token = Uuid.random().toString()
    val directory = createTransfer(token)
    try {
        val assets = PackageManifestCodec.assets(manifest)
        requireTransferLimits(assets)
        requireCapacity(assets.sumOf { it.sizeBytes } + TransferStreams.MAX_HEADER)
        val snapshots = assets.map { value ->
            currentCoroutineContext().ensureActive()
            val target = Path(directory, *value.relativePath.split('/').toTypedArray())
            SystemFileSystem.createDirectories(checkNotNull(target.parent))
            val source = asset(manifest.id.value, false, value.relativePath)
            if (value == manifest.annotations || manifest.layers.any { it.tiles == value }) {
                SqliteSnapshot.create(source.toString(), target.toString())
            } else {
                SystemFileSystem.source(source).buffered().use { input ->
                    SystemFileSystem.sink(target).use { output -> TransferStreams.copy(input, output, value.sizeBytes, ::requireCapacity) }
                }
            }
            PackageAsset(value.relativePath, checkNotNull(SystemFileSystem.metadataOrNull(target)).size, TransferStreams.digest(target))
        }
        val snapshot = manifest.replaceAssets(snapshots)
        requireTransferLimits(snapshots)
        val output = destination.buffered()
        TransferStreams.writeHeader(output, PackageManifestCodec.encode(snapshot).encodeToByteArray())
        for (value in snapshots) {
            SystemFileSystem.source(Path(directory, *value.relativePath.split('/').toTypedArray())).buffered().use { input ->
                if (TransferStreams.copy(input, output, value.sizeBytes) != value.sha256) throw InvalidTransfer()
            }
        }
        output.flush()
    } finally { deleteTransfer(token) }
}

internal suspend fun PackageFiles.readTransfer(source: RawSource, id: PackageId): PackageManifest {
    val input = source.buffered()
    val original = PackageManifestCodec.decode(TransferStreams.readHeader(input).decodeToString(throwOnInvalidSequence = true))
    val assets = PackageManifestCodec.assets(original)
    requireTransferLimits(assets)
    require(original.layers.all { it.tiles.sizeBytes <= original.sizePolicy.effectiveLayerLimit })
    require(assets.all { it.sha256 != null } && original.layers.all { it.tileCount != null })
    requireCapacity(assets.sumOf { it.sizeBytes } * 2 + TransferStreams.MAX_HEADER)
    for (value in assets) {
        val path = asset(id.value, true, value.relativePath)
        SystemFileSystem.createDirectories(checkNotNull(path.parent))
        val digest = SystemFileSystem.sink(path).use { output ->
            TransferStreams.copy(input, output, value.sizeBytes, ::requireCapacity)
        }
        if (digest != value.sha256) throw InvalidTransfer()
    }
    if (!input.exhausted()) throw InvalidTransfer()
    for (layer in original.layers) {
        val database = MbTiles.open(asset(id.value, true, layer.tiles.relativePath).toString(), writable = true)
        try {
            require(layer.tileWidth in setOf(256, 512) && layer.tileHeight == layer.tileWidth)
            database.visitTiles { tile ->
                require(rasterFormat(tile.bytes) in layer.content.rasterFormats)
                require(RasterTileValidation.hasExpectedDimensions(tile.bytes, layer.tileWidth))
            }
            database.reassign(original.id.value, id.value)
        } finally { database.close() }
    }
    original.annotations?.let {
        AnnotationDatabase.access(asset(id.value, true, it.relativePath).toString(), original.id.value, verify = true) {
            var cursor: String? = null
            do {
                val rows = query(null, cursor, 200)
                rows.forEach { row ->
                    val value = AnnotationGeoJson.decodeStoredFeature(row.geoJson)
                    require(value.id == row.id)
                    val box = value.boundingBox()
                    require(box.west == row.west && box.south == row.south && box.east == row.east && box.north == row.north)
                }
                cursor = rows.lastOrNull()?.id
            } while (cursor != null)
            reassign(id.value)
        }
    }
    // Identity rebinding changes database bytes; archive digests have already been verified.
    return original.copy(id = id).replaceAssets(assets.map {
        PackageAsset(it.relativePath, assetSize(id.value, true, it.relativePath))
    })
}

internal fun requireTransferLimits(assets: List<PackageAsset>) {
    require(assets.size in 1..TransferStreams.MAX_ASSETS)
    var total = 0L
    assets.forEach {
        require(it.sizeBytes in 0..(TransferStreams.MAX_BYTES - total))
        total += it.sizeBytes
    }
}

internal fun PackageManifest.replaceAssets(values: List<PackageAsset>): PackageManifest {
    val byPath = values.associateBy { it.relativePath }
    fun replacement(asset: PackageAsset) = byPath.getValue(asset.relativePath)
    return copy(layers = layers.map { it.copy(tiles = replacement(it.tiles)) },
        annotations = annotations?.let(::replacement), elevation = elevation?.let(::replacement),
        auxiliaryAssets = auxiliaryAssets.map(::replacement))
}

internal suspend fun PackageFiles.readStandaloneMbTiles(source: RawSource, id: PackageId, displayName: String): PackageManifest {
    write(id.value, "input.mbtiles", source, PackageSizePolicy.MAX_LAYER_BYTES, Long.MAX_VALUE)
    val input = MbTiles.open(asset(id.value, true, "input.mbtiles").toString())
    try {
        val metadata = input.metadata()
        if (metadata["format"] !in setOf("png", "jpg", "jpeg") || metadata["scheme"] !in setOf(null, "tms") ||
            metadata["crs"] !in setOf(null, "EPSG:3857") || metadata["srs"] !in setOf(null, "EPSG:3857")) {
            throw PackageStorageException(PackageFailure.UnsupportedContent)
        }
        val coordinates = metadata["bounds"]?.split(',')?.map { it.trim().toDouble() }
            ?: throw PackageStorageException(PackageFailure.UnsupportedContent)
        require(coordinates.size == 4)
        val bounds = BoundingBox(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
        val levels = input.levels()
        require(levels.isNotEmpty() && levels.all { it in 0..30 })
        val range = ZoomRange(levels.min(), levels.max())
        val coverage = PackageTileCoverage(bounds, range, levels)
        val format = if (metadata["format"] == "png") RasterTileFormat.PNG else RasterTileFormat.JPEG
        val output = MbTiles.create(asset(id.value, true, "map_data.mbtiles").toString(), metadata + ("bmaps_package_id" to id.value))
        var count = 0L
        var dimension: Int? = null
        try {
            input.visitTiles { tile ->
                require(coverage.contains(TileKey(tile.address.zoom, tile.address.column, tile.address.row)))
                require(rasterFormat(tile.bytes) == format)
                val imageSize = listOf(256, 512).firstOrNull { RasterTileValidation.hasExpectedDimensions(tile.bytes, it) }
                    ?: throw PackageStorageException(PackageFailure.UnsupportedContent)
                require(dimension == null || dimension == imageSize)
                dimension = imageSize
                requireCapacity(tile.bytes.size.toLong() * 3 + 1_048_576)
                output.write(listOf(tile), emptyList(), PackageSizePolicy.MAX_LAYER_BYTES)
                count++
            }
            if (count != coverage.count || output.counts().downloaded != count) {
                throw PackageStorageException(PackageFailure.UnsupportedContent)
            }
        } finally { output.close() }
        val now = Clock.System.now().toEpochMilliseconds()
        val layer = PackageLayer(LayerId("imported"), displayName, null,
            PackageAsset("map_data.mbtiles", assetSize(id.value, true, "map_data.mbtiles")), bounds, range,
            content = TileContentDescriptor(rasterFormats = setOf(format)), tileWidth = checkNotNull(dimension), tileHeight = checkNotNull(dimension),
            attribution = metadata["attribution"]?.let { listOf(bes.max.bmaps.domain.providers.Attribution(it, "")) }.orEmpty(),
            zoomLevels = levels, tileCount = count)
        return PackageManifest(1, id, displayName, bounds, range, now, now, listOf(layer))
    } finally { input.close(); deleteAsset(id.value, true, "input.mbtiles") }
}
