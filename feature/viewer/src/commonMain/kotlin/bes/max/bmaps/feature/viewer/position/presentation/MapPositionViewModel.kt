/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.position.presentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.datastore.UserPreferences
import bes.max.bmaps.core.datastore.UserPreferencesRepository
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.compose.resources.StringResource

data class MapPositionState(
    val coordinate: GeographicCoordinate? = null,
    val displayCoordinate: ProjectedCoordinate? = null,
    val coordinateOperation: CoordinateOperation? = null,
    val coordinateError: StringResource? = null,
    val preferences: UserPreferences? = null,
    val preferenceError: Boolean = false,
    val elevation: PackageElevation = PackageElevation.Missing,
    val sampling: Boolean = false,
    val error: StringResource? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MapPositionViewModel(
    private val packages: PackageRepository,
    private val preferences: UserPreferencesRepository,
    private val transformers: ProjTransformerFactory,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MapPositionState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var packageId: PackageId? = null
    private var observationVersion = 0L
    private var lookupVersion = 0L

    fun open(id: PackageId, camera: StateFlow<MapCameraSnapshot?>, retry: Boolean = false) {
        if (packageId == id && !retry) return
        packageId = id
        val owner = ++observationVersion
        lookupVersion++
        val previous = observation
        previous?.cancel()
        mutableState.value = MapPositionState()
        observation = viewModelScope.launch {
            previous?.join()
            ElevationDiagnostics.info("widget_start package=${id.value}")
            var session: OpenedPackage? = null
            val coordinates = transformers.openSession()
            var lastSampleKind: String? = null
            try {
                val settings = preferences.preferences.map<UserPreferences, UserPreferences?> { it }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        ElevationDiagnostics.error("preferences_read package=${id.value}", error)
                        emit(null)
                    }
                combine(camera.map { it?.let { snapshot -> snapshot.pyramid.coordinateAt(snapshot.viewport.center) } }
                    .distinctUntilChanged(), settings) { point, prefs -> point to prefs }
                    .map { (point, prefs) ->
                        currentCoroutineContext().ensureActive()
                        val request = ++lookupVersion
                        mutableState.value = MapPositionState(
                            coordinate = point, displayCoordinate = null,
                            preferences = prefs,
                            preferenceError = prefs == null
                        )
                        Triple(request, point, prefs)
                    }
                    .buffer(Channel.CONFLATED)
                    .collectLatest { (request, point, prefs) ->
                        if (request != lookupVersion) return@collectLatest
                        if (point == null) return@collectLatest
                        try {
                            delay(120)
                            if (request != lookupVersion) return@collectLatest
                            if (prefs != null) {
                                when (val transformed = coordinates.transform(
                                    ProjectedCoordinate(point.longitude, point.latitude, CoordinateSystemId.Wgs84),
                                    CoordinateSystemId(prefs.defaultCoordinateSystem),
                                )) {
                                    is TransformResult.Success -> updateLookup(request) { it.copy(
                                        displayCoordinate = transformed.coordinate,
                                        coordinateOperation = transformed.operation,
                                        coordinateError = null,
                                    ) }
                                    TransformResult.UnsupportedCoordinateSystem -> coordinateFailure(request, Res.string.position_coordinates_unsupported)
                                    TransformResult.OutsideCoverage -> coordinateFailure(request, Res.string.position_transform_outside)
                                    TransformResult.MissingTransformationData -> coordinateFailure(request, Res.string.position_transform_resources_missing)
                                    TransformResult.Failed -> coordinateFailure(request, Res.string.position_transform_failed)
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (request != lookupVersion) return@collectLatest
                            if (session == null) {
                                when (val result = packages.open(id)) {
                                    is PackageResult.Success -> {
                                        session = result.value
                                        ElevationDiagnostics.info("package_opened package=${id.value} " +
                                            "dataset=${result.value.manifest.elevationDataset} " +
                                            "hasElevation=${result.value.manifest.elevation != null}")
                                    }
                                    is PackageResult.Failure -> {
                                        ElevationDiagnostics.error("widget_package_open package=${id.value} reason=${result.reason}")
                                        updateLookup(request) { it.copy(error = Res.string.position_elevation_unavailable) }
                                        return@collectLatest
                                    }
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (request != lookupVersion) return@collectLatest
                            val opened = checkNotNull(session)
                            if (opened.manifest.elevation == null) return@collectLatest
                            updateLookup(request) { it.copy(sampling = true) }
                            val value = opened.elevation(point.latitude, point.longitude)
                            currentCoroutineContext().ensureActive()
                            if (request != lookupVersion) return@collectLatest
                            val kind = value::class.simpleName
                            if (kind != lastSampleKind) {
                                ElevationDiagnostics.info("widget_result package=${id.value} result=$value")
                                lastSampleKind = kind
                            }
                            updateLookup(request) { it.copy(elevation = value, sampling = false) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (request != lookupVersion) return@collectLatest
                            ElevationDiagnostics.error("widget_lookup package=${id.value}", error)
                            updateLookup(request) { it.copy(sampling = false, error = Res.string.position_elevation_unavailable) }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ElevationDiagnostics.error("widget_observation package=${id.value}", error)
                if (owner == observationVersion) mutableState.update {
                    it.copy(
                        sampling = false,
                        error = Res.string.position_elevation_unavailable
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    try { coordinates.close() }
                    catch (error: Exception) {
                        ElevationDiagnostics.error("coordinate_session_close package=${id.value}", error)
                    }
                    try {
                        session?.close()
                    } catch (error: Exception) {
                        ElevationDiagnostics.error("widget_session_close package=${id.value}", error)
                        if (owner == observationVersion) {
                            mutableState.update { it.copy(error = Res.string.viewer_close_error) }
                        }
                    }
                    ElevationDiagnostics.info("widget_stop package=${id.value}")
                }
            }
        }
    }

    private inline fun updateLookup(request: Long, update: (MapPositionState) -> MapPositionState) {
        mutableState.update { if (request == lookupVersion) update(it) else it }
    }

    private fun coordinateFailure(request: Long, message: StringResource) {
        updateLookup(request) { it.copy(coordinateError = message, displayCoordinate = null, coordinateOperation = null) }
    }
}
