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
import bes.max.bmaps.core.datastore.UserPreferencesRepository
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.domain.mapbuilder.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class CalloutLocationState(
    val packageId: PackageId? = null,
    val coordinate: GeographicCoordinate? = null,
    val elevationMeters: Double? = null,
    val format: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class CalloutLocationViewModel(
    private val packages: PackageRepository,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    private data class Lookup(val id: PackageId, val coordinate: GeographicCoordinate, val hasElevation: Boolean)
    private val lookup = MutableStateFlow<Lookup?>(null)
    private val mutableState = MutableStateFlow(CalloutLocationState())
    internal val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.preferences.retryWhen { _, _ -> delay(1000); true }.collect { prefs ->
                mutableState.update { it.copy(format = prefs.coordinateFormat) }
            }
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
        mutableState.update { it.copy(packageId = id, coordinate = coordinate, elevationMeters = null) }
        lookup.value = next
    }
}
