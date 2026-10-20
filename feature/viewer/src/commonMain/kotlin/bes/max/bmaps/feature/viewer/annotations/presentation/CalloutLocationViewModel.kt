/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.datastore.CoordinateFormat
import bes.max.bmaps.core.datastore.UserPreferences
import bes.max.bmaps.core.datastore.UserPreferencesRepository
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.elevation.ElevationDiagnostics
import bes.max.bmaps.domain.mapbuilder.elevation.PackageElevation
import bes.max.bmaps.domain.mapbuilder.model.PackageId
import bes.max.bmaps.domain.mapbuilder.model.PackageResult
import bes.max.bmaps.domain.mapbuilder.repository.OpenedPackage
import bes.max.bmaps.domain.mapbuilder.repository.PackageRepository
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource

internal data class CalloutLocationState(
    val packageId: PackageId? = null,
    val coordinate: GeographicCoordinate? = null,
    val elevationMeters: Double? = null,
    val displayCoordinate: ProjectedCoordinate? = null,
    val coordinateError: StringResource? = null,
    val format: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class CalloutLocationViewModel(
    private val packages: PackageRepository,
    private val preferences: UserPreferencesRepository,
    private val transformers: ProjTransformerFactory,
) : ViewModel() {
    private data class Lookup(val id: PackageId, val coordinate: GeographicCoordinate, val hasElevation: Boolean)
    private data class DisplaySettings(val format: CoordinateFormat, val system: CoordinateSystemId)
    private var coordinateVersion = 0L
    private val lookup = MutableStateFlow<Lookup?>(null)
    private val mutableState = MutableStateFlow(CalloutLocationState())
    internal val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val coordinates = transformers.openSession()
            try {
                val settings = preferences.preferences.map<UserPreferences, DisplaySettings?> { prefs ->
                    DisplaySettings(prefs.coordinateFormat, CoordinateSystemId(prefs.defaultCoordinateSystem))
                }.distinctUntilChanged().retryWhen { error, _ ->
                    if (error is CancellationException) throw error
                    emit(null)
                    delay(1000)
                    true
                }
                combine(lookup, settings) { request, prefs -> request to prefs }
                    .map { (request, prefs) ->
                        currentCoroutineContext().ensureActive()
                        val version = ++coordinateVersion
                        mutableState.update { it.copy(
                            displayCoordinate = null,
                            coordinateError = if (request != null && prefs == null) Res.string.position_preferences_unavailable else null,
                            format = prefs?.format ?: it.format,
                        ) }
                        Triple(version, request, prefs)
                    }
                    .buffer(kotlinx.coroutines.channels.Channel.CONFLATED)
                    .collectLatest { (version, request, prefs) ->
                        if (request == null || prefs == null || version != coordinateVersion) return@collectLatest
                        val point = request.coordinate
                        val result = coordinates.transform(
                            ProjectedCoordinate(point.longitude, point.latitude, CoordinateSystemId.Wgs84), prefs.system,
                        )
                        currentCoroutineContext().ensureActive()
                        if (version != coordinateVersion || lookup.value != request) return@collectLatest
                        mutableState.update { it.copy(
                            displayCoordinate = (result as? TransformResult.Success)?.coordinate,
                            coordinateError = when (result) {
                                is TransformResult.Success -> null
                                TransformResult.UnsupportedCoordinateSystem -> Res.string.position_coordinates_unsupported
                                TransformResult.OutsideCoverage -> Res.string.position_transform_outside
                                TransformResult.MissingTransformationData -> Res.string.position_transform_resources_missing
                                TransformResult.Failed -> Res.string.position_transform_failed
                            },
                        ) }
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(displayCoordinate = null, coordinateError = Res.string.position_transform_failed) }
                ElevationDiagnostics.error("callout_coordinate_lookup", error)
            } finally { coordinates.close() }
        }
        viewModelScope.launch {
            lookup.collectLatest { request ->
                if (request == null || !request.hasElevation) return@collectLatest
                var session: OpenedPackage? = null
                try {
                    val opened = (packages.open(request.id) as? PackageResult.Success)?.value ?: return@collectLatest
                    session = opened
                    val result = opened.elevation(request.coordinate.latitude, request.coordinate.longitude)
                    currentCoroutineContext().ensureActive()
                    if (lookup.value == request) mutableState.update {
                        it.copy(elevationMeters = (result as? PackageElevation.Value)?.meters?.takeIf(Double::isFinite))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { ElevationDiagnostics.error("callout_elevation_lookup", error) }
                finally {
                    withContext(NonCancellable) {
                        try { session?.close() }
                        catch (error: Exception) { ElevationDiagnostics.error("callout_elevation_close", error) }
                    }
                }
            }
        }
    }

    fun select(id: PackageId?, coordinate: GeographicCoordinate?, hasElevation: Boolean) {
        val next = if (id != null && coordinate != null) Lookup(id, coordinate, hasElevation) else null
        if (lookup.value == next) return
        coordinateVersion++
        mutableState.update { it.copy(packageId = id, coordinate = coordinate, elevationMeters = null,
            displayCoordinate = null, coordinateError = null) }
        lookup.value = next
    }
}
