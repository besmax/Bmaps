/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.activity.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HistoryViewModel(private val activities: ActivityRepository, private val session: ActivitySession) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryState())
    internal val state = mutableState.asStateFlow()
    private var cursor: ActivityHistoryCursor? = null
    private var load: Job? = null
    private var generation = 0
    init {
        session.initialize()
        observeLoadingIndicator()
        viewModelScope.launch { session.active.collect { record ->
            val active = record?.takeIf { it.status != ActivityStatus.COMPLETED }
            mutableState.update { it.copy(activeItem = active?.let { value ->
                HistoryItem(value.id, value.name, activityTimeText(value.startedAt.epochMillis),
                    historyStatus(value.status), historyProfile(value.profile))
            }) }
        } }
    }
    private fun observeLoadingIndicator() {
        viewModelScope.launch {
            var indicator: Job? = null
            state.map { it.busy }.distinctUntilChanged().collect { busy ->
                if (busy) {
                    if (!state.value.loadingVisible) {
                        indicator?.cancel()
                        indicator = launch {
                            delay(250)
                            mutableState.update { it.copy(loadingVisible = true) }
                            delay(350)
                            if (!state.value.busy) mutableState.update { it.copy(loadingVisible = false) }
                        }
                    }
                } else if (!state.value.loadingVisible) {
                    indicator?.cancel()
                } else if (indicator?.isActive != true) {
                    mutableState.update { it.copy(loadingVisible = false) }
                }
            }
        }
    }
    fun refresh() { if (!state.value.busy) load(false) }
    fun search(value: String) {
        if (value.length > 120) return
        mutableState.update { it.copy(search = value) }; load(false)
    }
    fun more() { if (state.value.more && !state.value.busy) load(true) }
    private fun load(append: Boolean) {
        val request = state.value
        val token = ++generation
        load?.cancel()
        if (!append) cursor = null
        load = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                val query = ActivityHistoryQuery(if (append) cursor else null, 50, request.search, statuses = setOf(ActivityStatus.COMPLETED))
                if (ActivityValidation.history(query) != null) return@launch
                val page = activities.history(query).valueOrThrow()
                if (token != generation) return@launch
                cursor = page.next
                val display = page.items.map { HistoryItem(it.id, it.name, activityTimeText(it.startedAtEpochMillis),
                    historyStatus(it.status), historyProfile(it.profile)) }
                mutableState.update { it.copy(activities = (if (append) it.activities + display else display).toImmutableList(), more = page.next != null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.history_error) } }
            finally { if (token == generation) mutableState.update { it.copy(busy = false) } }
        }
    }
}
internal class ReviewFailure(val reason: ActivityFailure) : Exception()
internal fun <T> ActivityResult<T>.valueOrThrow(): T = when (this) { is ActivityResult.Success -> value; is ActivityResult.Failure -> throw ReviewFailure(reason) }
internal fun historyFailure(reason: ActivityFailure): StringResource = when (reason) {
    ActivityFailure.ActivityBusy, ActivityFailure.AnotherActivityActive -> Res.string.history_active_protected
    ActivityFailure.RevisionConflict -> Res.string.history_changed
    ActivityFailure.CapacityExceeded -> Res.string.history_limit
    else -> Res.string.history_error
}
internal fun historyStatus(status: ActivityStatus): StringResource = when (status) {
    ActivityStatus.RECORDING -> Res.string.track_recording
    ActivityStatus.PAUSED -> Res.string.track_paused
    ActivityStatus.INTERRUPTED -> Res.string.track_interrupted
    ActivityStatus.FINALIZING -> Res.string.track_finalizing
    ActivityStatus.COMPLETED -> Res.string.track_completed
}
internal fun historyProfile(profile: ActivityProfile): StringResource = when (profile) {
    ActivityProfile.HIKING -> Res.string.track_hiking
    ActivityProfile.OFF_ROAD_MOTORCYCLE -> Res.string.track_motorcycle
    ActivityProfile.ROAD_TRAVEL -> Res.string.track_road
}
