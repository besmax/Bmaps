/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.datastore.*
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.viewer.generated.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CalloutLocationViewModelTest {
    @Test fun changingDisplaySystemReprojectsSelectedPointWithoutOpeningPackage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val preferences = CalloutPreferences()
            val model = CalloutLocationViewModel(NoElevationRepository(), preferences, ProjTransformerFactory())
            owner.put("callout", model)
            val point = GeographicCoordinate(55.75, 37.6)
            model.select(PackageId("map"), point, false)
            val original = model.awaitCoordinate(point, CoordinateSystemId.Wgs84)
            assertEquals(point.longitude, original.displayCoordinate?.x)
            assertEquals(point.latitude, original.displayCoordinate?.y)
            preferences.setDefaultCoordinateSystem("EPSG:3857")
            val projected = model.awaitCoordinate(point, CoordinateSystemId.WebMercator)
            assertTrue(assertNotNull(projected.displayCoordinate).x > 1_000_000)
            assertTrue(assertNotNull(projected.displayCoordinate).y > 1_000_000)
            assertEquals(point, projected.coordinate)
            preferences.setCoordinateFormat(CoordinateFormat.DEGREES_MINUTES_SECONDS)
            withContext(Dispatchers.Default) {
                withTimeout(5000) { model.state.first { it.format == CoordinateFormat.DEGREES_MINUTES_SECONDS && it.displayCoordinate != null } }
            }
            preferences.setDefaultCoordinateSystem("EPSG:4326")
            assertEquals(original.displayCoordinate, model.awaitCoordinate(point, CoordinateSystemId.Wgs84).displayCoordinate)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun unsupportedPreferenceDoesNotDisplayWgs84AndSelectionClearsPreviousPoint() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val preferences = CalloutPreferences()
            val model = CalloutLocationViewModel(NoElevationRepository(), preferences, ProjTransformerFactory())
            owner.put("callout", model)
            val point = GeographicCoordinate(55.75, 37.6)
            model.select(PackageId("map"), point, false)
            model.awaitCoordinate(point, CoordinateSystemId.Wgs84)
            preferences.setDefaultCoordinateSystem("EPSG:unknown")
            val failed = withContext(Dispatchers.Default) {
                withTimeout(5000) { model.state.first { it.coordinateError != null } }
            }
            assertNull(failed.displayCoordinate)
            assertEquals(Res.string.position_coordinates_unsupported, failed.coordinateError)
            model.select(null, null, false)
            assertNull(model.state.value.coordinate)
            assertNull(model.state.value.displayCoordinate)
            assertNull(model.state.value.coordinateError)
            preferences.setDefaultCoordinateSystem("EPSG:4326")
            val next = GeographicCoordinate(-12.5, 25.5)
            model.select(PackageId("other-map"), next, false)
            val selected = model.awaitCoordinate(next, CoordinateSystemId.Wgs84)
            assertEquals(PackageId("other-map"), selected.packageId)
            assertEquals(next.latitude, selected.displayCoordinate?.y)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private suspend fun CalloutLocationViewModel.awaitCoordinate(point: GeographicCoordinate, system: CoordinateSystemId) =
        withContext(Dispatchers.Default) {
            withTimeout(5000) { state.first { it.coordinate == point && it.displayCoordinate?.coordinateSystem == system } }
        }
}

private class CalloutPreferences : UserPreferencesRepository {
    private val values = MutableStateFlow(UserPreferences())
    override val preferences = values.asStateFlow()
    override suspend fun setDefaultCoordinateSystem(identifier: String) { values.update { it.copy(defaultCoordinateSystem = identifier) } }
    override suspend fun setCoordinateFormat(format: CoordinateFormat) { values.update { it.copy(coordinateFormat = format) } }
    override suspend fun setTheme(theme: ThemePreference) { values.update { it.copy(theme = theme) } }
    override suspend fun setClusterMapObjects(enabled: Boolean) { values.update { it.copy(clusterMapObjects = enabled) } }
    override suspend fun setDisplayPreferences(theme: ThemePreference, clusterMapObjects: Boolean,
        coordinateFormat: CoordinateFormat, defaultCoordinateSystem: String) {
        values.value = UserPreferences(theme, defaultCoordinateSystem, clusterMapObjects, coordinateFormat)
    }
}

private class NoElevationRepository : PackageRepository {
    override suspend fun open(id: PackageId): PackageResult<OpenedPackage> = error("Coordinate display must not open a DEM package")
    override fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>> = error("Not used")
    override fun observe(id: PackageId): Flow<PackageResult<PackageSummary>> = error("Not used")
    override suspend fun verifyFully(id: PackageId, onProgress: suspend (PackageVerificationProgress) -> Unit): PackageResult<Unit> = error("Not used")
    override suspend fun delete(id: PackageId): PackageResult<Unit> = error("Not used")
    override suspend fun setLayerPresentation(id: PackageId, layers: List<LayerPresentation>): PackageResult<Unit> = error("Not used")
    override suspend fun setFavourite(id: PackageId, favourite: Boolean): PackageResult<Unit> = error("Not used")
    override suspend fun setAvatar(id: PackageId, avatar: MapAvatar): PackageResult<Unit> = error("Not used")
}
