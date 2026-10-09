/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.database.*
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import bes.max.bmaps.core.mbtiles.TileAddress
import bes.max.bmaps.core.storage.*
import bes.max.bmaps.domain.providers.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.files.*

internal class PackageStorageScenarios(private val database: (String) -> PackageDatabase) {
    suspend fun eachLayerHasItsOwnLimitAndElevationIsExcluded() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (baseRequest, baseManifest) = fixtureRequest()
        val policy = PackageSizePolicy(maxLayerBytes = 262_144)
        val overlayId = LayerId("overlay")
        val request = baseRequest.copy(sizePolicy = policy, elevationDataset = ElevationDataset.COP30,
            layers = baseRequest.layers + baseRequest.layers.single().copy(id = overlayId))
        val manifest = baseManifest.copy(sizePolicy = policy, elevationDataset = ElevationDataset.COP30,
            layers = baseManifest.layers + baseManifest.layers.single().copy(id = overlayId,
                tiles = PackageAsset("layers/overlay.mbtiles", 0)))
        val id = request.packageId
        val dem = ByteArray(300_000).apply { byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0).copyInto(this) }
        fun tile(key: TileKey, length: Int) = DownloadedTile(key, ByteString(ByteArray(length).apply {
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).copyInto(this)
        }))
        try {
            repository.prepare(request, manifest).success()
            repository.beginElevation(id).success()
            repository.appendElevation(id, dem, dem.size).success()
            repository.finishElevation(id).success()
            val keys = PackageTileCoverage(request.bounds, ZoomRange(0, 1), emptySet()).tiles().toList()
            val baseId = manifest.layers.first().id
            val oversized = repository.write(id, baseId, listOf(tile(keys.first(), 300_000)), emptyList(), 300_000)
            assertEquals(PackageFailure.SizeLimitExceeded(policy.maxLayerBytes, null), assertIs<PackageResult.Failure>(oversized).reason)
            assertFalse(repository.contains(id, baseId, keys.first()).success())
            repository.setState(id, BuildJobState.RUNNING).success()
            for (layer in manifest.layers) {
                repository.write(id, layer.id, keys.map { tile(it, 32_000) }, emptyList(), 160_000).success()
            }
            repository.finalize(id).success()
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            val opened = repository.open(id).success()
            assertTrue(opened.manifest.layers.all { it.tiles.sizeBytes <= policy.maxLayerBytes })
            assertTrue(opened.manifest.layers.sumOf { it.tiles.sizeBytes } > policy.maxLayerBytes)
            assertEquals(300_000L, opened.manifest.elevation?.sizeBytes)
            opened.close()
            repository.saveAnnotations(id, listOf(Annotation("point", AnnotationKind.MARKER,
                listOf(GeographicCoordinate(0.0, 0.0))))).success()
            repository.open(id).success().close()
        } finally { db.close() }
    }

    suspend fun elevationIsRequiredAndSurvivesRestart() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (baseRequest, baseManifest) = fixtureRequest(ZoomRange(0, 0))
        val request = baseRequest.copy(elevationDataset = ElevationDataset.COP30)
        val manifest = baseManifest.copy(elevationDataset = ElevationDataset.COP30)
        val id = request.packageId
        val tile = DownloadedTile(TileKey(0, 0, 0), ByteString(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        val tiff = byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        try {
            repository.prepare(request, manifest).success()
            repository.write(id, manifest.layers.single().id, listOf(tile), emptyList(), 8).success()
            assertEquals(PackageFailure.NotReady, assertIs<PackageResult.Failure>(repository.finalize(id)).reason)
            repository.beginElevation(id).success()
            repository.appendElevation(id, "invalid".encodeToByteArray(), 7).success()
            assertEquals(PackageFailure.CorruptData, assertIs<PackageResult.Failure>(repository.finishElevation(id)).reason)
            repository.discardElevation(id).success()
            repository.beginElevation(id).success()
            repository.appendElevation(id, tiff, tiff.size).success()
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            repository.reconcile().success()
            assertFalse(repository.elevationComplete(id).success())
            assertFalse(files.access { relativeFiles(id.value, true).any { it.endsWith(".part") } })
            repository.beginElevation(id).success()
            repository.appendElevation(id, tiff, tiff.size).success()
            repository.finishElevation(id).success()
            assertTrue(repository.elevationComplete(id).success())
            repository.finalize(id).success()
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            val opened = repository.open(id).success()
            assertEquals(ElevationDataset.COP30, opened.manifest.elevationDataset)
            assertEquals(PackageAsset("elevation.geotiff", tiff.size.toLong()), opened.manifest.elevation)
            assertTrue(repository.observe(id).first().success().hasElevationData)
            assertContentEquals(tiff, files.access { read(id.value, false, "elevation.geotiff", 100) })
            opened.close()
        } finally { db.close() }
    }

    suspend fun annotationsSurviveRestartAndStayIsolated() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (request, manifest) = fixtureRequest(ZoomRange(0, 0))
        val second = PackageId("annotation-second")
        val tile = DownloadedTile(TileKey(0, 0, 0), ByteString(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        try {
            for (id in listOf(request.packageId, second)) {
                repository.prepare(request.copy(packageId = id), manifest.copy(id = id)).success()
                repository.write(id, manifest.layers.single().id, listOf(tile), emptyList(), 8).success()
                repository.finalize(id).success()
            }
            val id = request.packageId
            val east = Annotation("east", AnnotationKind.MARKER, listOf(GeographicCoordinate(0.0, 179.0)), color = "#123456", icon = "future")
            val west = east.copy(id = "west", coordinates = listOf(GeographicCoordinate(0.0, -179.0)))
            val middle = east.copy(id = "middle", coordinates = listOf(GeographicCoordinate(0.0, 0.0)))
            val polygon = Annotation("area", AnnotationKind.POLYGON, listOf(
                GeographicCoordinate(10.0, 10.0), GeographicCoordinate(10.0, 12.0), GeographicCoordinate(12.0, 11.0)
            ))
            repository.saveAnnotations(id, listOf(east, west, middle, polygon)).success()
            val legacyPolygon = """{"type":"Feature","id":"area","geometry":{"type":"Polygon","coordinates":[[[10,10],[12,10],[11,12],10,10]]},"properties":{}}"""
            val annotationPath = files.access { asset(id.value, false, "annotations.db").toString() }
            AnnotationDatabase.access(annotationPath, id.value) {
                change(listOf(AnnotationRecord("area", legacyPolygon, 10.0, 10.0, 12.0, 12.0)), null, 300_000_000, modifiedAt())
            }
            assertEquals(listOf(polygon), repository.annotations(id, BoundingBox(10.5, 10.5, 11.5, 11.5)).success().items)
            assertTrue(repository.annotations(second).success().items.isEmpty())
            assertEquals(setOf("east", "west"), repository.annotations(id, BoundingBox(170.0, -1.0, -170.0, 1.0)).success().items.map { it.id }.toSet())
            assertEquals(listOf("middle"), repository.annotations(id, BoundingBox(-1.0, -1.0, 1.0, 1.0)).success().items.map { it.id })
            val before = files.access { read(id.value, false, "config.json", 1_048_576) }
            val lots = (0..210).map { middle.copy(id = "point-$it", description = "payload".repeat(200)) }
            repository.saveAnnotations(id, lots).success()
            // Simulate SQLite commit followed by process death before the manifest replacement.
            files.access { write(id.value, "config.json", Buffer().apply { write(before) }, 1_048_576, 300_000_000, staged = false) }
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            repository.reconcile().success()
            val session = repository.open(id).success()
            try { assertNotNull(session.manifest.annotations) } finally { session.close() }
            val page = repository.annotations(id).success()
            assertEquals(200, page.items.size)
            val next = repository.annotations(id, after = assertNotNull(page.nextCursor)).success()
            assertEquals(215, (page.items + next.items).map { it.id }.distinct().size)
            assertEquals(polygon, page.items.first { it.id == "area" })
            assertEquals(east, page.items.first { it.id == "east" })
            val path = files.access { asset(id.value, false, "annotations.db").toString() }
            assertFailsWith<StorageLimitExceeded> {
                AnnotationDatabase.access(path, id.value) {
                    change(listOf(AnnotationRecord("rollback", AnnotationGeoJson.encode(listOf(middle.copy(id = "rollback"))), 0.0, 0.0, 0.0, 0.0)), null, 1, 0)
                }
            }
            assertFalse(repository.annotations(id, after = "point-z").success().items.any { it.id == "rollback" })
            repository.deleteAnnotation(id, "east").success()
            assertFalse(repository.annotations(id).success().items.any { it.id == "east" })
            repository.delete(id).success()
            assertFalse(files.access { exists(id.value, false) })
            assertTrue(repository.annotations(second).success().items.isEmpty())
        } finally { db.close() }
    }

    suspend fun layerConfigurationSurvivesReopening() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (baseRequest, baseManifest) = fixtureRequest(ZoomRange(0, 0))
        val overlayId = LayerId("satellite")
        val request = baseRequest.copy(layers = baseRequest.layers + baseRequest.layers.single().copy(id = overlayId))
        val manifest = baseManifest.copy(layers = baseManifest.layers + baseManifest.layers.single().copy(
            id = overlayId, tiles = PackageAsset("layers/satellite.mbtiles", 0), renderOrder = 1))
        val tile = DownloadedTile(TileKey(0, 0, 0), ByteString(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        try {
            repository.prepare(request, manifest).success()
            manifest.layers.forEach { repository.write(request.packageId, it.id, listOf(tile), emptyList(), 8).success() }
            repository.finalize(request.packageId).success()
            val rootId = manifest.layers.first().id
            repository.setLayerPresentation(request.packageId, listOf(
                LayerPresentation(rootId, false, 0.25, 1), LayerPresentation(overlayId, true, 0.6, 0))).success()
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            // Simulate an interrupted subsequent manifest edit; the committed settings must survive.
            files.access {
                val temporary = asset(request.packageId.value, false, "config.json.part")
                SystemFileSystem.sink(temporary).use { it.write(Buffer().apply { write(byteArrayOf(1)) }, 1) }
            }
            repository.reconcile().success()
            val session = repository.open(request.packageId).success()
            try {
                val layers = session.manifest.layers
                assertEquals("map_data.mbtiles", layers.first().tiles.relativePath)
                assertEquals(listOf(overlayId, rootId), layers.sortedBy { it.renderOrder }.map { it.id })
                assertFalse(layers.first().visible)
                assertEquals(0.25, layers.first().opacity)
                assertEquals(0.6, layers.last().opacity)
                val source = session.openTiles(overlayId).success()
                try { assertIs<TileReadResult.Available>(source.read(tile.key)) } finally { source.close() }
            } finally { session.close() }
        } finally { db.close() }
    }

    suspend fun recoverAndReopen() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (request, manifest) = fixtureRequest()
        val id = request.packageId
        val layer = manifest.layers.single().id
        val bytes = ByteString(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val first = TileKey(0, 0, 0)
        val failed = TileKey(1, 0, 0)
        try {
            assertEquals(id.value, repository.prepare(request, manifest).success().value)
            assertEquals(id.value, repository.prepare(request, manifest).success().value)
            val progress = repository.write(id, layer, listOf(DownloadedTile(first, bytes)),
                listOf(FailedTile(failed, TileReadFailure.NETWORK)), 8).success()
            assertEquals(1L, progress.completedTiles)
            assertEquals(1L, progress.failedTiles)
            assertIs<PackageResult.Failure>(repository.finalize(id))
            repository.setFavourite(id, true).success()
            repository.setAvatar(id, MapAvatar.MOUNTAIN).success()
            repository.setState(id, BuildJobState.FAILED, PackageFailure.NetworkUnavailable).success()
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            val summary = repository.observe(PackageQuery()).first().success().items.single()
            assertEquals(PackageState.FAILED, summary.state)
            assertTrue(summary.favourite)
            assertEquals(MapAvatar.MOUNTAIN.storageKey, summary.avatarKey)
            assertEquals(1L, summary.failedTiles)
            assertFalse(summary.hasElevationData)
            assertIs<PackageResult.Failure>(repository.open(id))
            assertTrue(repository.contains(id, layer, first).success())
            assertFalse(repository.contains(id, layer, failed).success())
            repository.setState(id, BuildJobState.RUNNING).success()
            val remaining = (0L..1L).flatMap { column -> (0L..1L).map { row ->
                DownloadedTile(TileKey(1, column, row), bytes)
            } }
            val restored = repository.write(id, layer, remaining, emptyList(), 32).success()
            assertEquals(5L, restored.completedTiles)
            assertEquals(0L, restored.failedTiles)
            val duplicate = repository.write(id, layer, listOf(DownloadedTile(first, bytes)),
                listOf(FailedTile(failed)), 8).success()
            assertEquals(5L, duplicate.completedTiles)
            assertEquals(0L, duplicate.failedTiles)
            repository.finalize(id).success()
            val config = files.access { read(id.value, false, "config.json", 1_048_576).decodeToString() }
            assertTrue(config.contains("\"elevation\":null"))
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            val ready = repository.observe(PackageQuery(favouritesOnly = true)).first().success().items.single()
            assertTrue(ready.favourite)
            assertEquals(MapAvatar.MOUNTAIN.storageKey, ready.avatarKey)
            val opened = repository.open(id).success()
            val source = opened.openTiles(layer).success()
            assertEquals(bytes, assertIs<TileReadResult.Available>(source.read(first)).bytes)
            repository.delete(id).success()
            assertEquals(TileReadResult.Failed(TileReadFailure.CLOSED), source.read(first))
            opened.close()
            assertTrue(repository.observe(PackageQuery()).first().success().items.isEmpty())
            assertNull(db.packages().preferences(id.value))
        } finally { db.close() }
    }

    suspend fun reconcilePromotionAndMissingAssets() = fixture { root ->
        val db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        val repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (request, manifest) = fixtureRequest(ZoomRange(0, 0))
        val id = request.packageId
        try {
            repository.prepare(request, manifest).success()
            repository.write(id, manifest.layers.single().id, listOf(DownloadedTile(TileKey(0, 0, 0),
                ByteString(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))), emptyList(), 8).success()
            repository.finalize(id).success()
            val original = checkNotNull(db.packages().get(id.value))
            val job = checkNotNull(db.packages().job(id.value))
            db.packages().checkpoint(original.copy(state = "FINALIZING"), job.copy(state = "FINALIZING"))
            repository.reconcile().success()
            assertEquals("READY", db.packages().get(id.value)?.state)
            db.packages().delete(id.value)
            repository.reconcile().success()
            assertEquals("READY", db.packages().get(id.value)?.state)
            files.access { delete(id.value, false) }
            repository.reconcile().success()
            assertEquals("MISSING", db.packages().get(id.value)?.state)
        } finally { db.close() }
    }

    suspend fun paginationAndWriteRollback() = fixture { root ->
        val db = database(Path(root, "catalog.db").toString())
        try {
            val catalog = PackageCatalog(db)
            for (id in listOf("c", "a", "b")) catalog.records.putPackage(PackageRecord(id, "100% map", "PAUSED", "", 0, 42))
            catalog.records.putPackage(PackageRecord("d", "100 map", "PAUSED", "", 0, 42))
            val filter = PackageFilter("%", setOf("PAUSED"))
            val first = catalog.observe(filter, 2, null).first()
            assertEquals(listOf("a", "b"), first.items.map { it.id })
            val second = catalog.observe(filter, 2, first.nextCursor).first()
            assertEquals(listOf("c"), second.items.map { it.id })
            assertNull(second.nextCursor)
            assertFailsWith<IllegalArgumentException> { catalog.observe(PackageFilter("changed"), 2, first.nextCursor) }
            catalog.records.putPreferences(PackagePreferencesRecord("b", favourite = true, avatar = "forest"))
            catalog.records.putPreferences(PackagePreferencesRecord("c", favourite = true))
            val favourites = filter.copy(favouritesOnly = true)
            val favouritePage = catalog.observe(favourites, 1, null).first()
            assertEquals(listOf("b"), favouritePage.items.map { it.id })
            assertEquals(listOf("c"), catalog.observe(favourites, 1, favouritePage.nextCursor).first().items.map { it.id })
            assertFailsWith<IllegalArgumentException> { catalog.observe(favourites, 2, first.nextCursor) }
            val tiles = MbTiles.create(Path(SystemFileSystem.resolve(root), "fixture.mbtiles").toString(), mapOf("format" to "png"))
            try {
                val address = TileAddress(2, 1, 0)
                assertEquals(3L, MbTiles.tmsRow(address))
                assertEquals(Long.MAX_VALUE, MbTiles.tmsRow(TileAddress(63, 0, 0)))
                try {
                    tiles.write(listOf(TileWrite(address, byteArrayOf(1, 2, 3))), emptyList(), 1)
                    fail("Expected storage limit failure")
                } catch (_: MbTilesSizeExceeded) { }
                assertFalse(tiles.contains(address))
                assertEquals(0L, tiles.counts().downloaded)
            } finally { tiles.close() }
            val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
            files.access {
                create("bounded")
                try {
                    write("bounded", "asset.bin", Buffer().apply { write(ByteArray(100)) }, 10, 1000)
                    fail("Expected stream limit failure")
                } catch (_: StorageLimitExceeded) { }
                assertTrue(relativeFiles("bounded", true).isEmpty())
                assertFailsWith<UnsafePackagePath> { asset("bounded", true, "../outside") }
            }
        } finally { db.close() }
    }


    suspend fun transferRoundTripAndInterruptedImport() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (baseRequest, baseManifest) = fixtureRequest(ZoomRange(0, 0))
        val request = baseRequest.copy(elevationDataset = ElevationDataset.COP30,
            layers = baseRequest.layers + baseRequest.layers.single().copy(id = LayerId("overlay")))
        val baseLayer = baseManifest.layers.single().copy(content = TileContentDescriptor(rasterFormats = setOf(RasterTileFormat.PNG)))
        val manifest = baseManifest.copy(elevationDataset = ElevationDataset.COP30,
            layers = listOf(baseLayer, baseLayer.copy(id = LayerId("overlay"), tiles = PackageAsset("layers/overlay.mbtiles", 0))))
        val dem = byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val png = kotlin.io.encoding.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAIAAADTED8xAAACvklEQVR4nO3TMQ0AMAzAsJIsp8EejB6xZAB5MvsWsua8AA4ZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpBiDNAKQZgDQDkGYA0gxAmgFIMwBpH4wuEe+QCDRfAAAAAElFTkSuQmCC")
        try {
            repository.prepare(request, manifest).success()
            for (layer in manifest.layers) repository.write(request.packageId, layer.id,
                listOf(DownloadedTile(TileKey(0, 0, 0), ByteString(png))), emptyList(), png.size.toLong()).success()
            repository.beginElevation(request.packageId).success()
            repository.appendElevation(request.packageId, dem, dem.size).success()
            repository.finishElevation(request.packageId).success()
            repository.finalize(request.packageId).success()
            files.access {
                val current = PackageManifestCodec.decode(read(request.packageId.value, false, "config.json", 1_048_576).decodeToString())
                write(request.packageId.value, "assets/info.txt", Buffer().apply { write("fixture".encodeToByteArray()) }, 7, Long.MAX_VALUE, staged = false)
                val updated = current.copy(auxiliaryAssets = listOf(PackageAsset("assets/info.txt", 7)))
                write(request.packageId.value, "config.json", Buffer().apply { write(PackageManifestCodec.encode(updated).encodeToByteArray()) }, 1_048_576, Long.MAX_VALUE, staged = false)
            }
            val marker = Annotation("fresh", AnnotationKind.MARKER, listOf(GeographicCoordinate(1.0, 2.0)), name = "Recent edit")
            repository.saveAnnotations(request.packageId, listOf(marker)).success()
            repository.setLayerPresentation(request.packageId, manifest.layers.mapIndexed { index, layer -> LayerPresentation(layer.id, index != 0, 0.4, 1 - index) }).success()
            val archive = Buffer()
            repository.exportPackage(request.packageId, archive).success()
            val bytes = archive.readByteArray()
            val first = repository.importPackage(Buffer().apply { write(bytes) }).success()
            val second = repository.importPackage(Buffer().apply { write(bytes) }).success()
            assertNotEquals(first, second)
            assertNotEquals(request.packageId, first)
            assertEquals(listOf(marker), repository.annotations(first).success().items)
            val opened = repository.open(first).success()
            assertEquals(2, opened.manifest.layers.size)
            assertEquals(1, opened.manifest.layers.first().renderOrder)
            assertContentEquals(dem, files.access { read(first.value, false, "elevation.geotiff", 100) })
            assertEquals("fixture", files.access { read(first.value, false, "assets/info.txt", 100).decodeToString() })
            assertEquals(0.4, opened.manifest.layers.first().opacity)
            assertFalse(opened.manifest.layers.first().visible)
            val tiles = opened.openTiles(opened.manifest.layers.first().id).success()
            assertContentEquals(png, assertIs<TileReadResult.Available>(tiles.read(TileKey(0, 0, 0))).bytes.toByteArray())
            opened.close()
            val before = repository.observe(PackageQuery()).first().success().items.map { it.id }.toSet()
            assertIs<PackageResult.Failure>(repository.importPackage(Buffer().apply { write(bytes.copyOf(bytes.size - 1)) }))
            val tampered = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertIs<PackageResult.Failure>(repository.importPackage(Buffer().apply { write(tampered) }))
            assertIs<PackageResult.Failure>(repository.importPackage(Buffer().apply { write(bytes); writeByte(1) }))
            val input = Buffer().apply { write(bytes) }
            var reads = 0
            val cancelled = object : kotlinx.io.RawSource {
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                    if (reads++ > 1) throw kotlinx.coroutines.CancellationException()
                    return input.readAtMostTo(sink, minOf(byteCount, 1024))
                }
                override fun close() = Unit
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> { repository.importPackage(cancelled) }
            assertEquals(before, repository.observe(PackageQuery()).first().success().items.map { it.id }.toSet())
            assertTrue(files.access { ids(true).isEmpty() })
            files.access { create("import-abandoned"); createTransfer("abandoned") }
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            repository.reconcile().success()
            assertTrue(files.access { ids(true).isEmpty() })
            assertFalse(files.access { SystemFileSystem.exists(transferDirectory("abandoned")) })
            assertEquals(listOf(marker), repository.annotations(first).success().items)
            val mbtiles = files.access { read(request.packageId.value, false, "map_data.mbtiles", 1_000_000) }
            val standalone = repository.importMbTiles(Buffer().apply { write(mbtiles) }, "Standalone").success()
            repository.open(standalone).success().close()
        } finally { db.close() }
    }

    suspend fun elevationCommitRejectsInsufficientFootprint() = fixture { root ->
        val db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        val repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (baseRequest, baseManifest) = fixtureRequest(ZoomRange(8, 8))
        val request = baseRequest.copy(elevationDataset = ElevationDataset.COP30)
        val manifest = baseManifest.copy(elevationDataset = ElevationDataset.COP30)
        val dem = kotlin.io.encoding.Base64.decode("TU0AKgAAAAgAEwEAAAQAAAABAAAABwEBAAQAAAABAAAABQECAAMAAAABABAAAAEDAAMAAAABAAUAAAEGAAMAAAABAAEAAAERAAQAAAADAAAA8gEVAAMAAAABAAEAAAEWAAQAAAABAAAAAgEXAAMAAAADAAAA/gEaAAUAAAABAAABBAEbAAUAAAABAAABDAEoAAMAAAABAAEAAAExAAIAAAAMAAABFAE9AAMAAAABAAIAAAFTAAMAAAABAAIAAIMOAAwAAAADAAABIISCAAwAAAAGAAABOIevAAMAAAAUAAABaKSBAAIAAAAHAAABkAAAAAAAAAGgAAABsQAAAcYAEQAVAAwAAAABAAAAAQAAAAEAAAABdGlmZmZpbGUucHkAP9AAAAAAAAA/0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQCQAAAAAAABASQAAAAAAAAAAAAAAAAAAAAEAAQAAAAQEAAAAAAEAAgQBAAAAAQABCAAAAAABEOYIBgAAAAEjji0zMjc2OAAAAAAAAAAAAACAP97AAAwSDQWEP9+weGQiAoAAAIAADBD++kACIJCwXC4LD4dAQIAAAkAADBINBYRAQA==")
        try {
            repository.prepare(request, manifest).success()
            repository.beginElevation(request.packageId).success()
            repository.appendElevation(request.packageId, dem, dem.size).success()
            assertEquals(PackageFailure.ElevationUnavailable, assertIs<PackageResult.Failure>(repository.finishElevation(
                request.packageId, BoundingBox(9.99, 48.75, 11.75, 50.0))).reason)
            assertFalse(repository.elevationComplete(request.packageId).success())
            assertFalse(files.access { "elevation.geotiff" in relativeFiles(request.packageId.value, true) })
            repository.finishElevation(request.packageId, BoundingBox(10.0, 48.75, 11.75, 50.0)).success()
            assertTrue(repository.elevationComplete(request.packageId).success())
        } finally { db.close() }
    }

    suspend fun generatedElevationLayerReplacementAndSizeAccounting() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
        val (baseRequest, baseManifest) = fixtureRequest(ZoomRange(0, 0))
        val request = baseRequest.copy(elevationDataset = ElevationDataset.COP30)
        val manifest = baseManifest.copy(elevationDataset = ElevationDataset.COP30)
        val id = request.packageId
        val png = encodeRasterPng(256, IntArray(256 * 256) { 0xff55aa33.toInt() })
        val dem = byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val style = ElevationReliefStyle(ElevationReliefOptions(), -10.0, 100.0)
        suspend fun readyJob(token: String, selectedStyle: ElevationReliefStyle = style): ElevationGenerationJob {
            val job = ElevationGenerationJob(id, token, selectedStyle.options, completedTiles = 1, totalTiles = 1, style = selectedStyle)
            files.access {
                writeElevationJob(id.value, PackageManifestCodec.json.encodeToString(job))
                clearElevationTiles(id.value)
                val database = MbTiles.create(Path(elevationJobDirectory(id.value), "tiles.mbtiles").toString(), mapOf("bmaps_package_id" to id.value, "format" to "png"))
                try { database.write(listOf(TileWrite(TileAddress(0, 0, 0), png)), emptyList(), PackageSizePolicy.MAX_LAYER_BYTES) }
                finally { database.close() }
            }
            return job
        }
        try {
            repository.prepare(request, manifest).success()
            repository.write(id, manifest.layers.single().id, listOf(DownloadedTile(TileKey(0, 0, 0), ByteString(png))), emptyList(), png.size.toLong()).success()
            repository.beginElevation(id).success()
            repository.appendElevation(id, dem, dem.size).success()
            repository.finishElevation(id).success()
            repository.finalize(id).success()
            val before = repository.observe(id).first().success().sizeBytes
            val rangeCache = """{"version":1,"digest":"fixture","minimum":-10.0,"maximum":100.0}"""
            files.access { writeElevationRange(id.value, rangeCache) }
            assertEquals(before, files.access { size(id.value, false) })
            repository.commitRelief(readyJob("first")).success()
            assertEquals(rangeCache, files.access { readElevationRange(id.value) })
            val first = repository.open(id).success()
            val oldPath = first.manifest.layers.last().tiles.relativePath
            val oldSource = first.openTiles(LayerId("elevation-relief")).success()
            assertTrue(repository.observe(id).first().success().sizeBytes > before)
            repository.setLayerPresentation(id, listOf(LayerPresentation(LayerId("base"), true, 1.0, 1), LayerPresentation(LayerId("elevation-relief"), false, 0.3, 0))).success()
            val secondStyle = style.copy(options = style.options.copy(palette = ElevationPalette.BLUE))
            repository.commitRelief(readyJob("second", secondStyle)).success()
            assertIs<TileReadResult.Available>(oldSource.read(TileKey(0, 0, 0)))
            val second = repository.open(id).success()
            val generated = second.manifest.layers.last()
            assertEquals(secondStyle, generated.elevationRelief)
            assertFalse(generated.visible)
            assertEquals(0.3, generated.opacity)
            assertEquals(0, generated.renderOrder)
            assertTrue(files.access { oldPath in relativeFiles(id.value, false) })
            first.close(); second.close()
            val clean = repository.open(id).success()
            assertFalse(files.access { oldPath in relativeFiles(id.value, false) })
            assertEquals(files.access { size(id.value, false) }, repository.observe(id).first().success().sizeBytes)
            clean.close()
            val cancelled = readyJob("cancelled").copy(state = ElevationGenerationState.CANCELLED)
            files.access { writeElevationJob(id.value, PackageManifestCodec.json.encodeToString(cancelled)) }
            assertIs<PackageResult.Failure>(repository.commitRelief(cancelled))
            assertEquals(secondStyle, repository.open(id).success().also { it.close() }.manifest.layers.last().elevationRelief)
            db.close()
            db = database(Path(root, "catalog.db").toString())
            repository = LocalPackageRepository(PackageCatalog(db), files, DemReaderFactory())
            repository.reconcile().success()
            assertEquals(PackageState.READY, repository.observe(id).first().success().state)
            val archive = Buffer()
            repository.exportPackage(id, archive).success()
            val imported = repository.importPackage(archive).success()
            val transferred = repository.open(imported).success()
            assertEquals(secondStyle, transferred.manifest.layers.last().elevationRelief)
            transferred.close()
            repository.delete(id).success()
            assertNull(files.access { readElevationJob(id.value) })
            assertNull(files.access { readElevationRange(id.value) })
        } finally { db.close() }
    }

    private suspend fun fixture(block: suspend (Path) -> Unit) {
        val root = Path(SystemTemporaryDirectory, "bmaps-storage-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(root)
        try { block(root) } finally {
            fun delete(path: Path) {
                if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::delete)
                SystemFileSystem.delete(path)
            }
            delete(root)
        }
    }

    private fun fixtureRequest(zoom: ZoomRange = ZoomRange(0, 1)): Pair<BuildRequest, PackageManifest> {
        val id = PackageId("fixture")
        val layer = LayerId("base")
        val bounds = BoundingBox(-180.0, -WebMercator.MAX_LATITUDE, 180.0, WebMercator.MAX_LATITUDE)
        val source = ProviderStyleId(ProviderId("fixture"), StyleId("raster"))
        val request = BuildRequest(id, "2026-09-11_15:38", bounds,
            listOf(BuildLayerRequest(layer, source, ProviderConfig(), zoom)))
        return request to PackageManifest(1, id, request.name, bounds, zoom, 0, 0,
            listOf(PackageLayer(layer, "Base", source, PackageAsset("map_data.mbtiles", 0), bounds, zoom)))
    }
}

private fun <T> PackageResult<T>.success(): T = assertIs<PackageResult.Success<T>>(this).value
