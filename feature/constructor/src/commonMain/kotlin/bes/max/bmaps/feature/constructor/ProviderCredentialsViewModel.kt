package bes.max.bmaps.feature.constructor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.datastore.*
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderCredentialsState(val draft: String = "", val saving: Boolean = false, val error: String? = null) {
    override fun toString() = "ProviderCredentialsState(<redacted>)"
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ProviderCredentialsViewModel(private val credentials: ProviderCredentials) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderCredentialsState())
    val state = mutableState.asStateFlow()
    private val completed = Channel<Unit>(Channel.BUFFERED)
    val events = completed.receiveAsFlow()

    fun edit(value: String) {
        if (!state.value.saving) mutableState.value = ProviderCredentialsState(value.take(4096))
    }

    fun save(identifier: String, remove: Boolean = false) {
        if (state.value.saving) return
        val draft = state.value.draft.trim()
        if (!remove && (draft.isBlank() || draft.any { it.isWhitespace() || it.code < 32 })) {
            mutableState.update { it.copy(error = "Enter a valid API key without spaces.") }
            return
        }
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val result = if (remove) credentials.remove(identifier) else credentials.write(identifier, draft)
                if (result == CredentialWriteResult.SUCCESS) {
                    mutableState.value = ProviderCredentialsState()
                    completed.send(Unit)
                } else mutableState.update { it.copy(saving = false, error = "Secure storage is unavailable. Try again.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(saving = false, error = "Secure storage is unavailable. Try again.") } }
        }
    }
}
