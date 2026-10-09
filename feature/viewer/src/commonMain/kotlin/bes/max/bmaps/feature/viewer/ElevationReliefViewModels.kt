/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource

data class ElevationProgressState(val job: ElevationGenerationJob? = null, val error: StringResource? = null)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ElevationProgressViewModel(private val generator: ElevationLayerGenerator, private val controller: ElevationGenerationController) : ViewModel() {
    private val mutableState = MutableStateFlow(ElevationProgressState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var packageId: PackageId? = null

    fun open(id: PackageId) {
        if (packageId == id) return
        packageId = id
        mutableState.value = ElevationProgressState()
        observation?.cancel()
        observation = viewModelScope.launch {
            try { generator.observe(id).collect { job -> mutableState.value = ElevationProgressState(job) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.relief_generation_failed) } }
        }
    }

    fun cancel() {
        val id = packageId ?: return
        viewModelScope.launch {
            try { controller.cancel(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.relief_generation_failed) } }
        }
    }
}

data class ElevationReliefDialogState(
    val palette: ElevationPalette = ElevationPalette.GREEN,
    val minimumColor: Double = 0.0,
    val maximumColor: Double = 1.0,
    val automatic: Boolean = true,
    val minimum: String = "",
    val maximum: String = "",
    val submitting: Boolean = false,
    val error: StringResource? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ElevationReliefDialogViewModel(private val controller: ElevationGenerationController) : ViewModel() {
    private val mutableState = MutableStateFlow(ElevationReliefDialogState())
    val state = mutableState.asStateFlow()
    private val channel = Channel<Unit>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var loaded = false

    fun load(style: ElevationReliefStyle?) {
        if (loaded) return
        loaded = true
        val options = style?.options ?: ElevationReliefOptions()
        mutableState.value = ElevationReliefDialogState(palette = options.palette, minimumColor = options.minimumColor, maximumColor = options.maximumColor,
            automatic = options.minimumMeters == null, minimum = options.minimumMeters?.toString().orEmpty(), maximum = options.maximumMeters?.toString().orEmpty())
    }

    fun palette(value: ElevationPalette) = edit { copy(palette = value) }
    fun minimumColor(value: Float) = edit { copy(minimumColor = value.toDouble()) }
    fun maximumColor(value: Float) = edit { copy(maximumColor = value.toDouble()) }
    fun automatic(value: Boolean) = edit { copy(automatic = value) }
    fun minimum(value: String) = edit { copy(minimum = value) }
    fun maximum(value: String) = edit { copy(maximum = value) }
    private fun edit(block: ElevationReliefDialogState.() -> ElevationReliefDialogState) {
        if (!state.value.submitting) mutableState.update { it.block().copy(error = null) }
    }

    fun generate(id: PackageId) {
        val current = state.value
        if (current.submitting) return
        val minimum = if (current.automatic) null else current.minimum.trim().replace(',', '.').toDoubleOrNull()
        val maximum = if (current.automatic) null else current.maximum.trim().replace(',', '.').toDoubleOrNull()
        val options = ElevationReliefOptions(current.palette, minimum, maximum, minimumColorPosition = current.minimumColor, maximumColorPosition = current.maximumColor)
        if ((!current.automatic && (minimum == null || maximum == null)) || !options.valid()) {
            mutableState.update { it.copy(error = Res.string.relief_invalid_range) }
            return
        }
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = controller.generate(id, options)) {
                    is PackageResult.Success -> channel.send(Unit)
                    is PackageResult.Failure -> mutableState.update { it.copy(error = reliefFailure(result.reason)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.relief_generation_failed) } }
            finally { mutableState.update { it.copy(submitting = false) } }
        }
    }
}

internal fun reliefFailure(failure: PackageFailure): StringResource = when (failure) {
    is PackageFailure.SizeLimitExceeded -> Res.string.relief_size_limit
    is PackageFailure.InsufficientStorage -> Res.string.relief_no_space
    PackageFailure.UnsupportedContent, PackageFailure.UnsupportedCoordinateSystem -> Res.string.relief_unsupported
    PackageFailure.Conflict -> Res.string.relief_conflict
    else -> Res.string.relief_generation_failed
}
