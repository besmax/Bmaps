package bes.max.bmaps.feature.constructor

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMapViewModelTest {
    @Test fun selectionRetryPreservesViewportAndIgnoresRetiredEvents() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var opens = 0
            val model = OnlineMapViewModel(ProviderRegistry.builtIn(), OnlineSourceOpener { opens++; OnlineSourceResult.Failed(OnlineSourceFailure.MISSING_CREDENTIAL) })
            runCurrent()
            val first = assertNotNull(model.state.value.session)
            assertEquals(0, opens)
            val viewport = MapViewport(MapPoint(0.4, 0.6), 2.0)
            model.onEvent(first.generation, MapEvent.ViewportChanged(viewport))
            model.onEvent(first.generation, MapEvent.TileFailed("online", TileKey(0, 0, 0), TileReadFailure.NETWORK))
            assertContains(assertNotNull(model.state.value.error), "connection")
            model.retry()
            val retry = assertNotNull(model.state.value.session)
            assertEquals(viewport, retry.config.initialViewport)
            model.onEvent(first.generation, MapEvent.Unavailable("old"))
            assertNull(model.state.value.error)
            val blocked = model.state.value.choices.first { it.provider.id.value == "yandex" }
            model.select(blocked)
            assertNull(model.state.value.session)
            assertEquals(0, opens)
            model.select(model.state.value.choices.first { it.provider.id.value == "thunderforest" })
            val keyed = assertNotNull(model.state.value.session)
            assertFailsWith<IllegalStateException> { keyed.layers.single().source.open() }
            assertContains(assertNotNull(model.state.value.error), "API key")
        } finally { Dispatchers.resetMain() }
    }

    @Test fun validatesGlobalMatricesWithoutTruncatingSourceLevels() {
        assertEquals(19, rasterConfig(BuiltInProviders.osm.config)?.pyramid?.levels?.max)
        assertEquals(22, rasterConfig(BuiltInProviders.thunderforest.config)?.pyramid?.levels?.max)
        assertEquals(22, rasterConfig(ProviderConfig(levelLimits = LevelLimitsConfig(22, 22)))?.pyramid?.levels?.min)
        assertNull(rasterConfig(ProviderConfig()))
        assertNull(rasterConfig(ProviderConfig(levelLimits = LevelLimitsConfig(0, 23))))
        assertNull(rasterConfig(BuiltInProviders.osm.config.copy(tileMatrix = TileMatrixConfig(tileHeight = 512))))
        assertNull(rasterConfig(BuiltInProviders.osm.config.copy(initialViewport = InitScaleAndScrollConfig(scale = Double.NaN))))
    }
}
