package bes.max.bmaps.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.datastore.UserPreferences
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShellState(val preferences: UserPreferences? = null, val preferencesUnavailable: Boolean = false)

sealed interface ShellEvent {
    data class Navigate(val destination: ShellDestination) : ShellEvent
    data object OpenPreferences : ShellEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ShellViewModel(private val preferencesRepository: UserPreferencesRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ShellState())
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<ShellEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var observation: Job? = null

    init {
        retryPreferences()
    }

    fun navigateTo(destination: ShellDestination) {
        viewModelScope.launch { eventChannel.send(ShellEvent.Navigate(destination)) }
    }

    fun openPreferences() {
        viewModelScope.launch { eventChannel.send(ShellEvent.OpenPreferences) }
    }

    fun retryPreferences() {
        observation?.cancel()
        mutableState.update { it.copy(preferencesUnavailable = false) }
        observation = viewModelScope.launch {
            try {
                preferencesRepository.preferences.collect { preferences ->
                    mutableState.value = ShellState(preferences = preferences)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update { it.copy(preferencesUnavailable = true) }
            }
        }
    }

    override fun onCleared() {
        eventChannel.close()
    }
}
