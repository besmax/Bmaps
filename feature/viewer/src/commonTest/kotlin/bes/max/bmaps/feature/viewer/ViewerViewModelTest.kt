package bes.max.bmaps.feature.viewer

import androidx.lifecycle.ViewModelStore
import bmaps.feature.viewer.generated.resources.*
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {
    @Test fun recreationReusesSessionAndRetryClosesBeforeOpeningReplacement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = LocalOnlyRepository()
            val model = ViewerViewModel(repository)
            owner.put("viewer", model)
            model.open(repository.id)
            runCurrent()
            model.open(repository.id)
            runCurrent()
            assertEquals(listOf("open"), repository.operations)
            assertEquals(0, repository.tileOpens)
            model.retry()
            runCurrent()
            assertEquals(listOf("open", "close", "open"), repository.operations)
            owner.clear()
            runCurrent()
            assertEquals(listOf("open", "close", "open", "close"), repository.operations)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun missingPackageIsRetryableAndPreferencesAreObserved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = LocalOnlyRepository().apply { missing = true }
            val model = ViewerViewModel(repository)
            owner.put("viewer", model)
            model.open(repository.id)
            runCurrent()
            assertFalse(model.state.value.loading)
            assertEquals(Res.string.viewer_not_found, model.state.value.error)
            repository.missing = false
            model.retry()
            runCurrent()
            assertNull(model.state.value.error)
            model.favourite()
            runCurrent()
            assertTrue(assertNotNull(model.state.value.summary).favourite)
            model.avatar(MapAvatar.FOREST)
            runCurrent()
            assertEquals("forest", model.state.value.summary?.avatarKey)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

private class LocalOnlyRepository : PackageRepository {
    val id = PackageId("local-map")
    private val bounds = BoundingBox(-10.0, -10.0, 10.0, 10.0)
    private val summary = MutableStateFlow(PackageSummary(id, "Local map", bounds, PackageState.READY, 100, 0))
    private val manifest = PackageManifest(1, id, "Local map", bounds, ZoomRange(0, 1), 0, 0,
        listOf(PackageLayer(LayerId("base"), "Base", null, PackageAsset("map_data.mbtiles", 100), bounds, ZoomRange(0, 1))))
    val operations = mutableListOf<String>()
    var tileOpens = 0
    var missing = false

    override fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>> =
        summary.map { PackageResult.Success(PackagePage(listOf(it), null)) }
    override fun observe(id: PackageId): Flow<PackageResult<PackageSummary>> = summary.map { PackageResult.Success(it) }
    override suspend fun open(id: PackageId): PackageResult<OpenedPackage> {
        if (missing) return PackageResult.Failure(PackageFailure.NotFound)
        operations += "open"
        return PackageResult.Success(object : OpenedPackage {
            override val manifest = this@LocalOnlyRepository.manifest
            override suspend fun openTiles(layerId: LayerId): PackageResult<TileSource> {
                tileOpens++
                return PackageResult.Failure(PackageFailure.NotFound)
            }
            override suspend fun close() { operations += "close" }
        })
    }
    override suspend fun delete(id: PackageId): PackageResult<Unit> = PackageResult.Success(Unit)
    override suspend fun setFavourite(id: PackageId, favourite: Boolean): PackageResult<Unit> {
        summary.update { it.copy(favourite = favourite) }
        return PackageResult.Success(Unit)
    }
    override suspend fun setAvatar(id: PackageId, avatar: MapAvatar): PackageResult<Unit> {
        summary.update { it.copy(avatarKey = avatar.storageKey) }
        return PackageResult.Success(Unit)
    }
}
