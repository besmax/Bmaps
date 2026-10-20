/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.mapbuilder.model.PackageFailure
import bes.max.bmaps.domain.mapbuilder.model.PackageId
import bes.max.bmaps.domain.mapbuilder.model.PackageResult
import bes.max.bmaps.domain.mapbuilder.repository.PackageRepository
import bmaps.feature.library.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
data class PackageVerificationState(
    val summary: LibraryMapUi? = null,
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val progress: LibraryVerificationProgressUi = LibraryVerificationProgressUi(),
    val result: StringResource? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PackageVerificationViewModel(private val packages: PackageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PackageVerificationState())
    val state = mutableState.asStateFlow()
    private var verification: Job? = null
    private var observation: Job? = null

    fun load(id: PackageId) {
        observation?.cancel()
        observation = viewModelScope.launch {
            packages.observe(id).collect { result ->
                if (result is PackageResult.Success) mutableState.update { it.copy(summary = result.value.toLibraryUi()) }
            }
        }
    }

    fun start(id: PackageId) {
        if (state.value.running) return
        mutableState.value = PackageVerificationState(summary = state.value.summary, running = true)
        verification = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = packages.verifyFully(id) { progress -> mutableState.update { it.copy(progress = progress.toLibraryUi()) } }
                mutableState.update { it.copy(result = when (result) {
                    is PackageResult.Success -> Res.string.verification_success
                    is PackageResult.Failure -> when (result.reason) {
                        PackageFailure.CorruptData, PackageFailure.NotFound -> Res.string.verification_damaged
                        PackageFailure.NotReady -> Res.string.verification_not_ready
                        else -> Res.string.verification_failed
                    }
                }) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(result = Res.string.verification_cancelled) }
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(result = Res.string.verification_failed) }
            } finally {
                mutableState.update { it.copy(running = false, cancelling = false) }
            }
        }
    }

    fun cancel() {
        if (!state.value.running) return
        mutableState.update { it.copy(cancelling = true) }
        verification?.cancel()
    }
}
