/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.shell.settings.presentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.datastore.CoordinateFormat
import bes.max.bmaps.core.datastore.DisplayCoordinateSystem
import bes.max.bmaps.core.datastore.ThemePreference
import bes.max.bmaps.core.datastore.UserPreferences
import bes.max.bmaps.core.datastore.UserPreferencesRepository
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PreferenceSetting { THEME, CLUSTERING, COORDINATE_SYSTEM, COORDINATE_FORMAT }

data class PreferencesState(
    val isLoading: Boolean = true,
    val selectedTheme: ThemePreference = ThemePreference.SYSTEM,
    val defaultCoordinateSystem: String = "EPSG:4326",
    val clusterMapObjects: Boolean = true,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES,
    val loadFailed: Boolean = false,
    val saving: Set<PreferenceSetting> = emptySet(),
    val saveErrors: Set<PreferenceSetting> = emptySet(),
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PreferencesViewModel(private val preferencesRepository: UserPreferencesRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PreferencesState())
    val state = mutableState.asStateFlow()
    private var loading: Job? = null
    private var persisted: UserPreferences? = null

    init { loadPreferences() }

    fun loadPreferences() {
        if (state.value.saving.isNotEmpty()) return
        loading?.cancel()
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        loading = viewModelScope.launch {
            try {
                preferencesRepository.preferences.collect { preferences ->
                    persisted = preferences
                    mutableState.update { current -> current.copy(
                        isLoading = false, loadFailed = false,
                        selectedTheme = if (PreferenceSetting.THEME in current.saving) current.selectedTheme else preferences.theme,
                        defaultCoordinateSystem = if (PreferenceSetting.COORDINATE_SYSTEM in current.saving) current.defaultCoordinateSystem else preferences.defaultCoordinateSystem,
                        clusterMapObjects = if (PreferenceSetting.CLUSTERING in current.saving) current.clusterMapObjects else preferences.clusterMapObjects,
                        coordinateFormat = if (PreferenceSetting.COORDINATE_FORMAT in current.saving) current.coordinateFormat else preferences.coordinateFormat,
                    ) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(isLoading = false, loadFailed = true) } }
        }
    }

    fun selectTheme(theme: ThemePreference) {
        val previous = state.value.selectedTheme
        if (theme == previous) return
        change(PreferenceSetting.THEME, { it.copy(selectedTheme = theme) }, { it.copy(selectedTheme = persisted?.theme ?: previous) }) {
            preferencesRepository.setTheme(theme)
        }
    }

    fun clusterMapObjects(enabled: Boolean) {
        val previous = state.value.clusterMapObjects
        if (enabled == previous) return
        change(PreferenceSetting.CLUSTERING, { it.copy(clusterMapObjects = enabled) }, { it.copy(clusterMapObjects = persisted?.clusterMapObjects ?: previous) }) {
            preferencesRepository.setClusterMapObjects(enabled)
        }
    }

    fun selectCoordinateFormat(format: CoordinateFormat) {
        val previous = state.value.coordinateFormat
        if (format == previous) return
        change(PreferenceSetting.COORDINATE_FORMAT, { it.copy(coordinateFormat = format) }, { it.copy(coordinateFormat = persisted?.coordinateFormat ?: previous) }) {
            preferencesRepository.setCoordinateFormat(format)
        }
    }

    fun selectCoordinateSystem(identifier: String) {
        val previous = state.value.defaultCoordinateSystem
        if (identifier == previous || DisplayCoordinateSystem.fromIdentifier(identifier) == null) return
        change(PreferenceSetting.COORDINATE_SYSTEM, { it.copy(defaultCoordinateSystem = identifier) }, { it.copy(defaultCoordinateSystem = persisted?.defaultCoordinateSystem ?: previous) }) {
            preferencesRepository.setDefaultCoordinateSystem(identifier)
        }
    }

    private fun change(setting: PreferenceSetting, select: (PreferencesState) -> PreferencesState,
        rollback: (PreferencesState) -> PreferencesState, persist: suspend () -> Unit) {
        if (state.value.isLoading || state.value.loadFailed || setting in state.value.saving) return
        mutableState.update { select(it).copy(saving = it.saving + setting, saveErrors = it.saveErrors - setting) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // An accepted setting must finish persisting even if Back immediately clears this screen.
            withContext(NonCancellable) {
                try { persist() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { rollback(it).copy(saveErrors = it.saveErrors + setting) } }
                finally { mutableState.update { it.copy(saving = it.saving - setting) } }
            }
        }
    }
}
