/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.shell

import bes.max.bmaps.core.datastore.CoordinateFormat
import bes.max.bmaps.core.datastore.DisplayCoordinateSystem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.datastore.ThemePreference
import bes.max.bmaps.core.datastore.UserPreferencesRepository
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PreferencesError { LOAD, SAVE }

data class PreferencesState(
    val isLoading: Boolean = true,
    val selectedTheme: ThemePreference = ThemePreference.SYSTEM,
    val defaultCoordinateSystem: String = "EPSG:4326",
    val clusterMapObjects: Boolean = true,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES,
    val isSaving: Boolean = false,
    val error: PreferencesError? = null,
)

sealed interface PreferencesEvent {
    data object Saved : PreferencesEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PreferencesViewModel(private val preferencesRepository: UserPreferencesRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PreferencesState())
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<PreferencesEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var loading: Job? = null

    init {
        loadPreferences()
    }

    fun loadPreferences() {
        if (state.value.isSaving) return
        loading?.cancel()
        mutableState.update { it.copy(isLoading = true, error = null) }
        loading = viewModelScope.launch {
            try {
                val preferences = preferencesRepository.preferences.first()
                mutableState.value = PreferencesState(
                    isLoading = false,
                    selectedTheme = preferences.theme,
                    defaultCoordinateSystem = preferences.defaultCoordinateSystem,
                    clusterMapObjects = preferences.clusterMapObjects,
                    coordinateFormat = preferences.coordinateFormat,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, error = PreferencesError.LOAD) }
            }
        }
    }

    fun selectTheme(theme: ThemePreference) {
        if (state.value.isLoading || state.value.isSaving || state.value.error == PreferencesError.LOAD) return
        mutableState.update { it.copy(selectedTheme = theme, error = null) }
    }

    fun clusterMapObjects(enabled: Boolean) {
        if (state.value.isLoading || state.value.isSaving || state.value.error == PreferencesError.LOAD) return
        mutableState.update { it.copy(clusterMapObjects = enabled, error = null) }
    }

    fun selectCoordinateFormat(format: CoordinateFormat) {
        if (state.value.isLoading || state.value.isSaving || state.value.error == PreferencesError.LOAD) return
        mutableState.update { it.copy(coordinateFormat = format, error = null) }
    }

    fun selectCoordinateSystem(identifier: String) {
        if (state.value.isLoading || state.value.isSaving || state.value.error == PreferencesError.LOAD) return
        if (DisplayCoordinateSystem.fromIdentifier(identifier) == null) return
        mutableState.update { it.copy(defaultCoordinateSystem = identifier, error = null) }
    }

    fun save() {
        val current = state.value
        if (current.isLoading || current.isSaving || current.error == PreferencesError.LOAD) return
        mutableState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                preferencesRepository.setDisplayPreferences(current.selectedTheme, current.clusterMapObjects,
                    current.coordinateFormat, current.defaultCoordinateSystem)
                eventChannel.send(PreferencesEvent.Saved)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update { it.copy(isSaving = false, error = PreferencesError.SAVE) }
            }
        }
    }

    override fun onCleared() {
        eventChannel.close()
    }
}
