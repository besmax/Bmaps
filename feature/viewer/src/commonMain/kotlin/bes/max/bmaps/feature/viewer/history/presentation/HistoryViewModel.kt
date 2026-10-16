/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.sharing.SharedDocument
import bes.max.bmaps.core.sharing.SharedDocumentStore
import bes.max.bmaps.domain.activity.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal sealed interface HistoryEvent {
    data class Share(val document: SharedDocument) : HistoryEvent
    data class Message(val message: StringResource) : HistoryEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HistoryViewModel(private val activities: ActivityRepository, private val routes: ActivityRouteRepository,
    private val session: ActivitySession, private val exporter: ActivityExporter, private val files: SharedDocumentStore) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<HistoryEvent>(Channel.BUFFERED)
    internal val events = channel.receiveAsFlow()
    private var cursor: ActivityHistoryCursor? = null
    private var routeCursor: String? = null
    private var load: Job? = null
    private var operation: Job? = null
    private var generation = 0
    private var mutation = false
    init {
        session.initialize()
        viewModelScope.launch { session.active.collect { record ->
            val active = record?.takeIf { it.status != ActivityStatus.COMPLETED }
            mutableState.update { it.copy(activeId = active?.id, activeItem = active?.let { value ->
                HistoryItem(value.id, value.name, Instant.fromEpochMilliseconds(value.startedAt.epochMillis).toString(),
                    historyStatus(value.status), historyProfile(value.profile), value.revision, false, true, false)
            }) }
        } }
    }
    fun refresh() { if (!state.value.busy) load(false) }
    fun tab(routes: Boolean) { if (state.value.busy) return; mutableState.update { it.copy(routesTab = routes) }; load(false) }
    fun search(value: String) {
        if (mutation || value.length > 120) return
        mutableState.update { it.copy(search = value) }; load(false)
    }
    fun filter(value: Int) { if (mutation || value !in 0..2) return; mutableState.update { it.copy(filter = value) }; load(false) }
    fun more() { if (state.value.more && !state.value.busy) load(true) }
    private fun load(append: Boolean, clearError: Boolean = true) {
        val request = state.value
        val token = ++generation
        load?.cancel()
        if (!append) { cursor = null; routeCursor = null }
        load = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = if (clearError) null else it.error) }
            try {
                if (request.routesTab) {
                    val page = routes.routes(ActivityRouteQuery(if (append) routeCursor else null, 50, request.search)).valueOrThrow()
                    if (token != generation) return@launch
                    routeCursor = page.nextId
                    val display = page.items.map { SavedRouteItem(it.id, it.name, it.revision, it.vertexCount, it.segmentCount) }
                    mutableState.update { it.copy(routes = (if (append) it.routes + display else display).toImmutableList(), more = page.nextId != null) }
                } else {
                    val statuses = when (request.filter) { 1 -> setOf(ActivityStatus.COMPLETED); 2 -> setOf(ActivityStatus.INTERRUPTED); else -> emptySet() }
                    val query = ActivityHistoryQuery(if (append) cursor else null, 50, request.search, statuses = statuses)
                    if (ActivityValidation.history(query) != null) return@launch
                    val page = activities.history(query).valueOrThrow()
                    if (token != generation) return@launch
                    cursor = page.next
                    val display = page.items.map { HistoryItem(it.id, it.name, Instant.fromEpochMilliseconds(it.startedAtEpochMillis).toString(),
                        historyStatus(it.status), historyProfile(it.profile), it.revision, it.status == ActivityStatus.COMPLETED, it.status != ActivityStatus.COMPLETED, it.status == ActivityStatus.COMPLETED || it.status == ActivityStatus.INTERRUPTED) }
                    mutableState.update { it.copy(activities = (if (append) it.activities + display else display).toImmutableList(), more = page.next != null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.history_error) } }
            finally { if (token == generation) mutableState.update { it.copy(busy = false) } }
        }
    }
    fun editActivity(id: String) { state.value.activities.firstOrNull { it.id == id }?.let { edit(id, false, it.name) } }
    fun editRoute(id: String) { state.value.routes.firstOrNull { it.id == id }?.let { edit(id, true, it.name) } }
    fun edit(id: String, route: Boolean, name: String) { if (!state.value.busy) mutableState.update { it.copy(editingId = id, editingRoute = route, draftName = name, error = null) } }
    fun draft(value: String) { if (!state.value.busy && value.length <= 120) mutableState.update { it.copy(draftName = value) } }
    fun dismissEdit() { if (!state.value.busy) mutableState.update { it.copy(editingId = null) } }
    fun rename() = work {
        val value = state.value
        val id = value.editingId ?: return@work
        val name = value.draftName.trim()
        if (name.isEmpty() || name.length > 120) { mutableState.update { it.copy(error = Res.string.history_invalid_name) }; return@work }
        if (value.editingRoute) {
            val route = routes.get(id).valueOrThrow()
            val expected = value.routes.firstOrNull { it.id == id }?.revision ?: return@work
            if (route.revision != expected) throw ReviewFailure(ActivityFailure.RevisionConflict)
            val changed = route.copy(name = name, revision = expected + 1)
            if (ActivityValidation.route(changed) != null) throw ReviewFailure(ActivityFailure.InvalidInput("route"))
            routes.save(changed, expected).valueOrThrow()
        } else {
            val record = activities.get(id).valueOrThrow()
            val expected = value.activities.firstOrNull { it.id == id }?.revision ?: return@work
            if (record.status != ActivityStatus.COMPLETED) throw ReviewFailure(ActivityFailure.ActivityBusy)
            if (record.revision != expected) throw ReviewFailure(ActivityFailure.RevisionConflict)
            val changed = record.copy(name = name, revision = expected + 1)
            if (ActivityValidation.record(changed) != null) throw ReviewFailure(ActivityFailure.InvalidInput("activity"))
            activities.commit(ActivityCommit(expected, changed)).valueOrThrow()
        }
        mutableState.update { it.copy(editingId = null) }
    }
    fun confirmDelete(id: String?, route: Boolean = false) { if (!state.value.busy) mutableState.update { it.copy(deletingId = id, deletingRoute = route, error = null) } }
    fun delete() = work {
        val value = state.value
        val id = value.deletingId ?: return@work
        if (value.deletingRoute) routes.delete(id).valueOrThrow() else {
            if (activities.get(id).valueOrThrow().status != ActivityStatus.COMPLETED) throw ReviewFailure(ActivityFailure.ActivityBusy)
            activities.delete(id).valueOrThrow()
        }
        mutableState.update { it.copy(deletingId = null) }
    }
    fun derive(id: String) = work {
        val route = routes.derive(id, Uuid.random().toString()).valueOrThrow()
        channel.send(HistoryEvent.Message(if (route == null) Res.string.history_no_geometry else Res.string.history_route_saved))
    }
    fun exportOptions(id: String?, route: Boolean = false) {
        if (id == null && state.value.busy && state.value.exportingId != null) {
            operation?.invokeOnCompletion { mutableState.update { it.copy(exportingId = null) } }
            operation?.cancel()
            return
        }
        if (!state.value.busy) mutableState.update { it.copy(exportingId = id, exportingRoute = route) }
    }
    fun export(kind: Int) = work {
        val value = state.value
        val id = value.exportingId ?: return@work
        if (kind !in 0..1 || value.exportingRoute && kind != 0) throw ReviewFailure(ActivityFailure.InvalidInput("exportKind"))
        val document = files.create(if (kind == 1) "json" else "gpx", if (kind == 1) "application/json" else "application/gpx+xml") { sink ->
            if (value.exportingRoute) exporter.route(id, sink) else if (kind == 1) exporter.telemetry(id, sink) else exporter.recordedPath(id, sink)
        }
        mutableState.update { it.copy(exportingId = null) }
        channel.send(HistoryEvent.Share(document))
    }
    fun nativeFailure() { mutableState.update { it.copy(error = Res.string.history_share_error) } }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutation = true
        mutableState.update { it.copy(busy = true, error = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: ReviewFailure) { mutableState.update { it.copy(error = historyFailure(failed.reason)) } }
            catch (failed: ActivityExportException) { mutableState.update { it.copy(error = historyFailure(failed.reason)) } }
            catch (_: Exception) { mutableState.update { it.copy(error = Res.string.history_error) } }
            finally { mutation = false; mutableState.update { it.copy(busy = false) } }
            load(false, clearError = false)
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
