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
import bes.max.bmaps.core.mapengine.TilePyramid
import bes.max.bmaps.core.mapengine.positionOf
import bes.max.bmaps.core.sharing.PickedDocument
import bes.max.bmaps.domain.activity.ActivityAction
import bes.max.bmaps.domain.activity.ActivityCommand
import bes.max.bmaps.domain.activity.ActivityDataAvailability
import bes.max.bmaps.domain.activity.ActivityEventKind
import bes.max.bmaps.domain.activity.ActivityFailure
import bes.max.bmaps.domain.activity.ActivityLifecycle
import bes.max.bmaps.domain.activity.ActivityLimits
import bes.max.bmaps.domain.activity.ActivityLiveState
import bes.max.bmaps.domain.activity.ActivityNavigationAction
import bes.max.bmaps.domain.activity.ActivityNavigationCommand
import bes.max.bmaps.domain.activity.ActivityPositionQuery
import bes.max.bmaps.domain.activity.ActivityProfile
import bes.max.bmaps.domain.activity.ActivityRepository
import bes.max.bmaps.domain.activity.ActivityResult
import bes.max.bmaps.domain.activity.ActivityRoute
import bes.max.bmaps.domain.activity.ActivityRouteAdapter
import bes.max.bmaps.domain.activity.ActivityRouteGpx
import bes.max.bmaps.domain.activity.ActivityRoutePreview
import bes.max.bmaps.domain.activity.ActivityRouteQuery
import bes.max.bmaps.domain.activity.ActivityRouteRepository
import bes.max.bmaps.domain.activity.ActivitySession
import bes.max.bmaps.domain.activity.ActivityStartRequest
import bes.max.bmaps.domain.activity.ActivityStatus
import bes.max.bmaps.domain.activity.ActivityValidation
import bes.max.bmaps.domain.activity.ElevationMeasurement
import bes.max.bmaps.domain.activity.PositionMeasurement
import bes.max.bmaps.domain.activity.RouteDirection
import bes.max.bmaps.domain.activity.RoutePosition
import bes.max.bmaps.domain.activity.RouteSelection
import bes.max.bmaps.domain.activity.RouteVertex
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import bes.max.bmaps.domain.mapbuilder.AnnotationValidation
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class TrackViewModel(
    private val session: ActivitySession,
    private val activities: ActivityRepository,
    private val routes: ActivityRouteRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TrackState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<TrackEvent>(Channel.RENDEZVOUS)
    internal val events = channel.receiveAsFlow()
    private var live: ActivityLiveState? = null
    private var selected: ActivityRoute? = null
    private var navigationRoute: ActivityRoute? = null
    private var coveragePyramid: TilePyramid? = null
    private var coverageJob: Job? = null
    private var annotationPreviewJob: Job? = null
    private var workJob: Job? = null
    private var nextRoute: String? = null

    init {
        session.initialize()
        viewModelScope.launch {
            session.live.collect { value ->
                live = value
                mutableState.update { current ->
                    current.copy(
                        metrics = value?.let { trackMetrics(it, navigationRoute) },
                        preparing = current.preparing && value == null,
                        choosing = current.choosing && value == null,
                        position = (value?.position?.measurement as? PositionMeasurement)?.coordinate?.takeIf {
                            value != null && value.record.status == ActivityStatus.RECORDING && value.positionAvailability == ActivityDataAvailability.LIVE && value.failure == null
                        }?.let { TrackCoordinate(it.latitude, it.longitude) },
                        positionId = value?.position?.id,
                        following = current.following && value != null,
                    )
                }
            }
        }
        viewModelScope.launch {
            session.active.map { record ->
                record?.let {
                    Triple(
                        it.id,
                        it.route?.snapshot?.id,
                        it.navigation?.routeId
                    )
                }
            }
                .distinctUntilChanged().collectLatest { key ->
                    navigationRoute = null
                    if (key == null) {
                        mutableState.update {
                            it.copy(
                                planned = emptyList<TrackLine>().toImmutableList(),
                                guidance = emptyList<TrackLine>().toImmutableList()
                            )
                        }
                        return@collectLatest
                    }
                    val record = live?.record?.takeIf { it.id == key.first }
                        ?: (activities.get(key.first) as? ActivityResult.Success)?.value
                        ?: return@collectLatest
                    val planned = withContext(Dispatchers.Default) {
                        record.route?.snapshot?.let(::preview).orEmpty().toImmutableList()
                    }
                    when (val result = activities.navigationRoute(key.first)) {
                        is ActivityResult.Failure -> error(result.reason)
                        is ActivityResult.Success -> {
                            navigationRoute = result.value
                            val guidance = withContext(Dispatchers.Default) {
                                result.value?.let(::preview).orEmpty().toImmutableList()
                            }
                            mutableState.update {
                                it.copy(
                                    planned = planned, guidance = guidance,
                                    metrics = live?.let { value ->
                                        trackMetrics(
                                            value,
                                            result.value
                                        )
                                    })
                            }
                        }
                    }
                }
        }
        viewModelScope.launch {
            session.active.map { it?.id }.distinctUntilChanged().collectLatest { id ->
                mutableState.update { it.copy(recorded = emptyList<TrackLine>().toImmutableList()) }
                if (id == null) return@collectLatest
                val path = TrackPathBuffer()
                var cursor = 0L
                while (isActive) {
                    when (val page = activities.positions(id, ActivityPositionQuery(cursor))) {
                        is ActivityResult.Failure -> {
                            error(page.reason); delay(3000)
                        }

                        is ActivityResult.Success -> {
                            withContext(Dispatchers.Default) { page.value.items.forEach(path::add) }
                            if (page.value.items.isNotEmpty() && page.value.nextSequence == null) mutableState.update {
                                it.copy(
                                    recorded = path.snapshot()
                                )
                            }
                            val next = page.value.lastSequence ?: page.value.nextSequence
                            if (next != null && next > cursor) cursor = next
                            if (page.value.items.size < ActivityLimits.MAX_PAGE_SIZE) delay(1000)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            session.navigationAlerts.collect { alert ->
                val label = when (alert.kind) {
                    ActivityEventKind.ROUTE_DEVIATED, ActivityEventKind.ROUTE_DEVIATION_REMINDER -> Res.string.track_off_route
                    ActivityEventKind.ROUTE_REJOINED -> Res.string.track_rejoined
                    ActivityEventKind.ROUTE_GAP -> Res.string.track_segment_gap
                    ActivityEventKind.ROUTE_END -> Res.string.track_end_reached
                    ActivityEventKind.CONTROL_POINT -> Res.string.track_control_reached
                    else -> null
                }
                if (label != null) channel.trySend(TrackEvent.Message(label, haptic = true))
            }
        }
    }

    fun mapCoverage(pyramid: TilePyramid?) {
        if (coveragePyramid == pyramid) return
        coveragePyramid = pyramid
        updateCoverage()
    }

    private fun updateCoverage() {
        coverageJob?.cancel()
        val route = if (state.value.correcting) navigationRoute else selected
        val pyramid = coveragePyramid
        coverageJob = viewModelScope.launch {
            val missing = withContext(Dispatchers.Default) {
                route?.segments?.any { segment -> segment.vertices.any { pyramid?.positionOf(it.coordinate) == null } } == true
            }
            mutableState.update { it.copy(coverageMissing = missing) }
        }
    }

    fun toggle() {
        mutableState.update {
            it.copy(
                expanded = !it.expanded,
                menu = !it.menu && it.metrics == null,
                error = null
            )
        }
    }

    fun menu(show: Boolean) {
        mutableState.update { it.copy(menu = show) }
    }

    fun collapse() {
        mutableState.update {
            it.copy(
                expanded = false,
                menu = false,
                preparing = false,
                choosing = false,
                correcting = false,
                details = false
            )
        }
    }

    fun prepareFree() {
        if (live != null || state.value.busy) return
        selected = null
        updateCoverage()
        mutableState.update {
            it.copy(
                menu = false,
                expanded = true,
                preparing = true,
                choosing = false,
                route = null,
                preview = emptyList<TrackLine>().toImmutableList(),
                segment = 0,
                edge = 0,
                error = null
            )
        }
    }

    fun chooseRoutes() {
        if (live != null || state.value.busy) return
        mutableState.update {
            it.copy(
                menu = false,
                choosing = true,
                expanded = true,
                preparing = false,
                error = null,
                routes = emptyList<TrackRouteItem>().toImmutableList()
            )
        }
        nextRoute = null
        loadRoutes(false)
    }

    fun loadMoreRoutes() = loadRoutes(true)
    fun refreshRoutes() { if (state.value.choosing && !state.value.busy) { nextRoute = null; loadRoutes(false) } }
    private fun loadRoutes(append: Boolean) = work {
        when (val result =
            routes.routes(ActivityRouteQuery(afterId = if (append) nextRoute else null))) {
            is ActivityResult.Failure -> error(result.reason)
            is ActivityResult.Success -> {
                nextRoute = result.value.nextId
                val items = result.value.items.map {
                    TrackRouteItem(
                        it.id,
                        it.name,
                        it.segmentCount,
                        it.vertexCount
                    )
                }
                mutableState.update {
                    it.copy(
                        routes = (if (append) it.routes + items else items).toImmutableList(),
                        moreRoutes = nextRoute != null
                    )
                }
            }
        }
    }

    fun selectRoute(id: String) = work {
        when (val result = routes.get(id)) {
            is ActivityResult.Failure -> error(result.reason)
            is ActivityResult.Success -> prepare(result.value)
        }
    }

    fun inspectAnnotation(annotation: Annotation?) {
        annotationPreviewJob?.cancel()
        mutableState.update {
            it.copy(
                selectedAnnotationId = annotation?.id,
                selectedAnnotationLength = null
            )
        }
        if (annotation?.kind != AnnotationKind.LINE || AnnotationValidation.error(annotation) != null) return
        annotationPreviewJob = viewModelScope.launch {
            val length = withContext(Dispatchers.Default) {
                val route = ActivityRouteAdapter.line(
                    "preview",
                    annotation.name,
                    annotation.coordinates.map { RouteVertex(it) })
                trackNumber(trackLength(route) / 1000)
            }
            mutableState.update { it.copy(selectedAnnotationLength = length) }
        }
    }

    fun followAnnotation(annotation: Annotation) = work {
        if (annotation.kind != AnnotationKind.LINE || AnnotationValidation.error(annotation) != null || live != null) return@work
        val route = withContext(Dispatchers.Default) {
            ActivityRouteAdapter.line(
                Uuid.random().toString(),
                annotation.name,
                annotation.coordinates.mapIndexed { index, coordinate ->
                    RouteVertex(
                        coordinate,
                        annotation.elevations.getOrNull(index)
                            ?.let { ElevationMeasurement(it.meters, it.verticalReference) })
                },
                annotation.description
            )
        }
        prepare(route)
    }

    private suspend fun prepare(route: ActivityRoute) {
        if (live != null || ActivityValidation.route(route) != null) {
            error(ActivityFailure.InvalidInput("route")); return
        }
        selected = route
        val length = withContext(Dispatchers.Default) { trackNumber(trackLength(route) / 1000) }
        val display = withContext(Dispatchers.Default) { preview(route).toImmutableList() }
        mutableState.update {
            it.copy(
                menu = false,
                choosing = false,
                preparing = true,
                correcting = false,
                expanded = true,
                preview = display,
                reverse = false,
                segment = 0,
                edge = 0,
                route = TrackRouteDisplay(
                    route.name,
                    length,
                    route.segments.size,
                    route.segments.first().vertices.lastIndex
                ),
                error = null
            )
        }
        updateSelectedSection()
        updateCoverage()
    }

    fun profile(index: Int) {
        if (index in ActivityProfile.entries.indices) mutableState.update { it.copy(profile = index) }
    }

    fun reverse(value: Boolean) {
        val route = selected ?: return
        val segment = if (value) route.segments.lastIndex else 0
        val edge = if (value) route.segments[segment].vertices.lastIndex - 1 else 0
        mutableState.update {
            it.copy(
                reverse = value,
                segment = segment,
                edge = edge,
                route = it.route?.copy(edges = route.segments[segment].vertices.lastIndex)
            )
        }
        updateSelectedSection()
    }

    fun segment(index: Int) {
        val route = if (state.value.correcting) navigationRoute else selected
        if (route == null || index !in route.segments.indices) return
        mutableState.update {
            it.copy(
                segment = index,
                edge = 0,
                route = it.route?.copy(edges = route.segments[index].vertices.lastIndex)
            )
        }
        updateSelectedSection()
    }

    fun edge(index: Int) {
        if (index in 0 until (state.value.route?.edges ?: 0)) {
            mutableState.update { it.copy(edge = index) }
            updateSelectedSection()
        }
    }

    fun start() = work {
        if (live != null || !state.value.preparing) return@work
        val state = state.value
        val selection = selected?.let {
            RouteSelection(
                it, if (state.reverse) RouteDirection.REVERSE else RouteDirection.FORWARD,
                RoutePosition(state.segment, state.edge, if (state.reverse) 1.0 else 0.0)
            )
        }
        val request = ActivityStartRequest(
            Uuid.random().toString(),
            selected?.name.orEmpty(),
            ActivityProfile.entries[state.profile],
            selection
        )
        if (ActivityValidation.start(request) != null) {
            error(ActivityFailure.InvalidInput("start")); return@work
        }
        when (val result = session.start(request)) {
            is ActivityResult.Failure -> error(result.reason)
            is ActivityResult.Success -> mutableState.update {
                it.copy(
                    preparing = false,
                    choosing = false,
                    expanded = false,
                    menu = false,
                    following = true,
                    error = null
                )
            }
        }
    }

    fun recover() = work {
        when (val result = session.recover()) {
            is ActivityResult.Failure -> error(result.reason)
            is ActivityResult.Success -> mutableState.update {
                it.copy(
                    error = null,
                    finishConfirmation = false
                )
            }
        }
    }

    fun pauseResume() =
        action(if (live?.record?.status == ActivityStatus.RECORDING) ActivityAction.PAUSE else ActivityAction.RESUME)

    fun markControlPoint() = action(ActivityAction.MARK_CONTROL_POINT)
    fun confirmFinish(show: Boolean) {
        mutableState.update { it.copy(finishConfirmation = show) }
    }

    fun finish() = action(ActivityAction.FINISH)
    private fun action(action: ActivityAction) = work {
        val record = live?.record ?: return@work
        if (ActivityLifecycle.next(record.status, action) == null) {
            error(ActivityFailure.InvalidTransition(record.status, action)); return@work
        }
        val command = ActivityCommand(
            Uuid.random().toString(),
            record.id,
            record.sessionGeneration,
            record.controlRevision,
            action
        )
        when (val result = session.apply(command)) {
            is ActivityResult.Failure -> error(result.reason)
            is ActivityResult.Success -> {
                mutableState.update { it.copy(finishConfirmation = false, error = null) }
                if (action == ActivityAction.FINISH) channel.trySend(TrackEvent.Completed(record.id))
                if (action == ActivityAction.MARK_CONTROL_POINT) channel.trySend(
                    TrackEvent.Message(
                        Res.string.track_point_saved
                    )
                )
            }
        }
    }

    fun returnAlongRecording() = navigate(ActivityNavigationAction.RETURN_ALONG_RECORDING)
    fun followPlanned() = navigate(ActivityNavigationAction.FOLLOW_PLANNED)
    fun toggleAlerts() = navigate(
        ActivityNavigationAction.SET_ALERTS_ENABLED,
        alerts = !(live?.record?.navigation?.alertsEnabled ?: true)
    )

    fun correct() = work {
        val route = navigationRoute ?: return@work
        val position = live?.record?.navigation?.checkpoint ?: return@work
        val display = withContext(Dispatchers.Default) { preview(route).toImmutableList() }
        val length = withContext(Dispatchers.Default) { trackNumber(trackLength(route) / 1000) }
        mutableState.update {
            it.copy(
                correcting = true,
                preparing = false,
                expanded = true,
                segment = position.segmentIndex,
                edge = position.edgeIndex,
                preview = display,
                route = TrackRouteDisplay(
                    route.name,
                    length,
                    route.segments.size,
                    route.segments[position.segmentIndex].vertices.lastIndex
                )
            )
        }
        updateSelectedSection()
        updateCoverage()
    }

    fun applyCorrection() {
        val state = state.value
        val position = RoutePosition(state.segment, state.edge, 0.0)
        if (navigationRoute?.let {
                ActivityValidation.validRoutePosition(
                    it,
                    position
                )
            } != true) return
        navigate(ActivityNavigationAction.CORRECT_POSITION, position)
    }

    private fun navigate(
        action: ActivityNavigationAction,
        position: RoutePosition? = null,
        alerts: Boolean? = null
    ) = work {
        val record = live?.record ?: return@work
        val command = ActivityNavigationCommand(
            Uuid.random().toString(),
            record.id,
            record.sessionGeneration,
            record.navigation?.revision ?: 0,
            action,
            position,
            alerts
        )
        if (ActivityValidation.navigationCommand(command) != null) {
            error(ActivityFailure.InvalidInput("navigation")); return@work
        }
        when (val result = session.navigate(command)) {
            is ActivityResult.Failure -> error(result.reason)
            is ActivityResult.Success -> mutableState.update {
                it.copy(
                    correcting = false,
                    error = null
                )
            }
        }
    }

    private fun updateSelectedSection() {
        val state = state.value
        val route = if (state.correcting) navigationRoute else selected
        val vertices = route?.segments?.getOrNull(state.segment)?.vertices ?: return
        val points = vertices.subList(state.edge, state.edge + 2)
            .map { TrackCoordinate(it.coordinate.latitude, it.coordinate.longitude) }
            .toImmutableList()
        mutableState.update {
            it.copy(
                selectedSection = listOf(
                    TrackLine(
                        "selected",
                        points
                    )
                ).toImmutableList()
            )
        }
    }

    fun selectionCoordinate(): TrackCoordinate? {
        val state = state.value
        val route = if (state.correcting) navigationRoute else selected
        val segment = route?.segments?.getOrNull(state.segment) ?: return null
        return segment.vertices.getOrNull(state.edge + if (state.reverse && !state.correcting) 1 else 0)?.coordinate?.let {
            TrackCoordinate(
                it.latitude,
                it.longitude
            )
        }
    }

    fun followPosition() {
        mutableState.update { it.copy(following = true) }
    }

    fun inspect() {
        mutableState.update { it.copy(following = false) }
    }

    fun details(show: Boolean) {
        mutableState.update { it.copy(details = show) }
    }

    fun nativeFailure() {
        mutableState.update { it.copy(error = Res.string.track_file_error) }
    }

    fun importGpx(document: PickedDocument) {
        if (state.value.busy || live != null) {
            document.dispose(); return
        }
        work {
            try {
                val imported = withContext(Dispatchers.IO) {
                    document.open().use { ActivityRouteGpx.decode(it) }
                }
                if (imported.any { ActivityValidation.route(it) != null }) {
                    error(ActivityFailure.InvalidInput("route")); return@work
                }
                when (val result = routes.saveImported(imported)) {
                    is ActivityResult.Failure -> error(result.reason)
                    is ActivityResult.Success -> if (imported.size == 1) prepare(imported.single()) else {
                        selected = null
                        nextRoute = null
                        mutableState.update {
                            it.copy(expanded = true, choosing = true, preparing = false, menu = false,
                                route = null, preview = emptyList<TrackLine>().toImmutableList(),
                                selectedSection = emptyList<TrackLine>().toImmutableList(),
                                routes = imported.map { route ->
                                TrackRouteItem(
                                    route.id,
                                    route.name,
                                    route.segments.size,
                                    route.segments.sumOf { segment -> segment.vertices.size })
                            }.toImmutableList(), moreRoutes = false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                nativeFailure()
            }
        }
        workJob?.invokeOnCompletion { document.dispose() }
    }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        workJob = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error(ActivityFailure.StorageUnavailable)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun error(failure: ActivityFailure) {
        val text: StringResource = when (failure) {
            ActivityFailure.PermissionRequired, ActivityFailure.PreciseLocationRequired -> Res.string.track_permission_required
            ActivityFailure.LocationDisabled -> Res.string.track_location_disabled
            ActivityFailure.RevisionConflict, ActivityFailure.StaleSession -> Res.string.track_state_changed
            ActivityFailure.CapacityExceeded -> Res.string.track_capacity
            ActivityFailure.AnotherActivityActive, ActivityFailure.ActivityBusy -> Res.string.track_busy
            is ActivityFailure.InvalidInput, ActivityFailure.NotFound -> Res.string.track_invalid
            else -> Res.string.track_storage_error
        }
        mutableState.update { it.copy(error = text) }
    }
}

private fun preview(route: ActivityRoute): List<TrackLine> =
    ActivityRoutePreview.create(route).segments.map { segment ->
        TrackLine(
            segment.sourceSegmentId,
            segment.coordinates.map { TrackCoordinate(it.latitude, it.longitude) }.toImmutableList()
        )
    }
