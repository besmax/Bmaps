package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.database.*
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.mbtiles.*
import bes.max.bmaps.core.storage.*
import bes.max.bmaps.domain.providers.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.files.*

internal class PackageStorageScenarios(private val database: (String) -> PackageDatabase) {
    suspend fun layerConfigurationSurvivesReopening() = fixture { root ->
        var db = database(Path(root, "catalog.db").toString())
        val files = PackageFileStorage(PackageStorageLocation(Path(root, "packages").toString()))
        var repository = LocalPackageRepository(PackageCatalog(db), files)
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
            repository = LocalPackageRepository(PackageCatalog(db), files)
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
        var repository = LocalPackageRepository(PackageCatalog(db), files)
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
            repository = LocalPackageRepository(PackageCatalog(db), files)
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
            repository = LocalPackageRepository(PackageCatalog(db), files)
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
        val repository = LocalPackageRepository(PackageCatalog(db), files)
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
            val tiles = MbTiles.create(Path(root, "fixture.mbtiles").toString(), mapOf("format" to "png"))
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
