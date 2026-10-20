/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bes.max.bmaps.feature.viewer.annotations.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.sharing.PickedDocument
import bes.max.bmaps.domain.activity.exchange.ActivityRouteGpx
import bes.max.bmaps.domain.activity.model.ActivityLimits
import bes.max.bmaps.domain.activity.model.ActivityResult
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.ActivityValidation
import bes.max.bmaps.domain.activity.model.packageRouteId
import bes.max.bmaps.domain.activity.repository.ActivityRouteQuery
import bes.max.bmaps.domain.activity.repository.ActivityRouteRepository
import bes.max.bmaps.domain.activity.route.ActivityRoutePreview
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationElevation
import bes.max.bmaps.domain.mapbuilder.AnnotationElevationResolver
import bes.max.bmaps.domain.mapbuilder.AnnotationGeoJson
import bes.max.bmaps.domain.mapbuilder.AnnotationImport
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import bes.max.bmaps.domain.mapbuilder.AnnotationRepository
import bes.max.bmaps.domain.mapbuilder.AnnotationValidation
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.domain.mapbuilder.PackageResult
import bes.max.bmaps.feature.viewer.activity.presentation.TrackCoordinate
import bes.max.bmaps.feature.viewer.activity.presentation.TrackLine
import bes.max.bmaps.feature.viewer.activity.presentation.trackLength
import bes.max.bmaps.feature.viewer.activity.presentation.trackNumber
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.jetbrains.compose.resources.StringResource

@Immutable
internal data class MapAnnotationItem(val id: String, val name: String, val kind: AnnotationKind)
@Immutable
internal data class MapRouteItem(val id: String, val name: String, val segments: Int, val vertices: Int)
@Immutable
internal data class MapRouteSelection(val id: String, val name: String, val length: String,
    val lines: ImmutableList<TrackLine>, val display: MapObjectCalloutDisplay,
    val coordinate: TrackCoordinate, val elevationMeters: Double?, val revision: Long)
@Immutable
internal data class MapObjectsState(
    val hasPackage: Boolean = false,
    val routes: ImmutableList<MapRouteItem> = persistentListOf(), val more: Boolean = false,
    val loading: Boolean = false, val routeError: StringResource? = null,
    val mapLines: ImmutableList<TrackLine> = persistentListOf(),
    val selection: MapRouteSelection? = null, val fitSelection: Boolean = false, val callout: Boolean = false,
    val importOpen: Boolean = false, val text: String = "", val busy: Boolean = false,
    val routeCount: Int = 0, val objectCount: Int = 0, val pointCount: Int = 0,
    val prepared: Boolean = false, val partial: Boolean = false, val error: StringResource? = null,
    val editingRoute: Boolean = false, val deletingRoute: Boolean = false,
    val routeName: String = "", val routeDescription: String = "", val routeActionError: StringResource? = null,
)
internal sealed interface MapObjectsEvent {
    data object Imported : MapObjectsEvent
    data class Selected(val id: String) : MapObjectsEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MapObjectsViewModel(
    private val routes: ActivityRouteRepository,
    private val annotations: AnnotationRepository,
    private val elevations: AnnotationElevationResolver,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MapObjectsState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<MapObjectsEvent>(Channel.BUFFERED)
    internal val events = channel.receiveAsFlow()
    private var packageId: PackageId? = null
    private var nextId: String? = null
    private var catalogJob: Job? = null
    private var catalogGeneration = 0L
    private var selectionJob: Job? = null
    private var importJob: Job? = null
    private var pending: Pending? = null
    private var selectedRoute: ActivityRoute? = null
    private data class Pending(val routes: List<ActivityRoute>, val annotations: List<Annotation>,
        val packageId: PackageId?, var routesSaved: Boolean = false)

    fun context(id: PackageId?) {
        if (packageId != id) { catalogJob?.cancel(); selectionJob?.cancel(); nextId = null
            selectedRoute = null
            mutableState.update { it.copy(routes = persistentListOf(), mapLines = persistentListOf(), selection = null, callout = false,
                editingRoute = false, deletingRoute = false, routeActionError = null) }
        }
        packageId = id; mutableState.update { it.copy(hasPackage = id != null) }
    }

    fun refresh() = load(false)
    fun more() = load(true)
    private fun load(append: Boolean) {
        val owner = packageId?.value ?: return
        if (append && (state.value.loading || !state.value.more)) return
        catalogJob?.cancel()
        val generation = ++catalogGeneration
        mutableState.update { it.copy(loading = true, routeError = null) }
        catalogJob = viewModelScope.launch {
            try {
                when (val result = routes.routes(ActivityRouteQuery(afterId = if (append) nextId else null, packageId = owner))) {
                    is ActivityResult.Failure -> mutableState.update { it.copy(routeError = Res.string.objects_routes_error) }
                    is ActivityResult.Success -> {
                        nextId = result.value.nextId
                        val allRoutes = result.value.items.toMutableList()
                        if (!append) {
                            var cursor = result.value.nextId
                            while (cursor != null) {
                                currentCoroutineContext().ensureActive()
                                when (val page = routes.routes(ActivityRouteQuery(afterId = cursor, limit = 500, packageId = owner))) {
                                    is ActivityResult.Failure -> { mutableState.update { it.copy(routeError = Res.string.objects_routes_error) }; break }
                                    is ActivityResult.Success -> { allRoutes += page.value.items; cursor = page.value.nextId }
                                }
                            }
                        }
                        val lines = if (append) emptyList() else allRoutes.flatMap { summary ->
                            val route = (routes.get(summary.id) as? ActivityResult.Success<ActivityRoute>)?.value ?: return@flatMap emptyList()
                            withContext(Dispatchers.Default) {
                                val mandatory = route.segments.size * 2 + route.controlPoints.size * 2
                                ActivityRoutePreview.create(route, maxOf(1_000, mandatory)).segments.map { segment ->
                                    TrackLine("${route.id}~${segment.sourceSegmentId}", segment.coordinates.map { TrackCoordinate(it.latitude, it.longitude) }.toImmutableList())
                                }
                            }
                        }
                        val items = result.value.items.map { MapRouteItem(it.id, it.name, it.segmentCount, it.vertexCount) }
                        mutableState.update { it.copy(routes = (if (append) it.routes + items else items).toImmutableList(),
                            mapLines = (if (append) it.mapLines + lines else lines).toImmutableList(), more = nextId != null) }
                    }
                }
            } finally { if (generation == catalogGeneration) mutableState.update { it.copy(loading = false) } }
        }
    }

    fun select(id: String) { if (!state.value.busy) selectRoute(id, true) }
    fun mapSelect(id: String, coordinate: GeographicCoordinate? = null) { if (!state.value.busy) selectRoute(id, false, coordinate) }
    fun fitted() { mutableState.update { it.copy(fitSelection = false) } }
    private fun selectRoute(id: String, fit: Boolean, coordinate: GeographicCoordinate? = null) {
        selectionJob?.cancel()
        val owner = packageId
        selectionJob = viewModelScope.launch {
            when (val result = routes.get(id)) {
                is ActivityResult.Failure -> mutableState.update { it.copy(routeError = Res.string.objects_routes_error) }
                is ActivityResult.Success -> {
                    val route = result.value
                    if (ActivityValidation.route(route) != null) {
                        mutableState.update { it.copy(routeError = Res.string.objects_routes_error) }; return@launch
                    }
                    val display = withContext(Dispatchers.Default) {
                        val preview = ActivityRoutePreview.create(route)
                        val anchor = coordinate ?: route.segments.first().vertices.first().coordinate
                        val elevation = route.segments.asSequence().flatMap { it.vertices.asSequence() }
                            .firstOrNull { it.coordinate == anchor }?.elevation?.meters
                        MapRouteSelection(route.id, route.name, trackNumber(trackLength(route) / 1000),
                            preview.segments.map { segment -> TrackLine("${route.id}~${segment.sourceSegmentId}",
                                segment.coordinates.map { TrackCoordinate(it.latitude, it.longitude) }.toImmutableList()) }.toImmutableList(),
                            MapObjectCalloutDisplay(route.id, route.name, route.description, Res.string.annotations_lines),
                            TrackCoordinate(anchor.latitude, anchor.longitude), elevation, route.revision)
                    }
                    currentCoroutineContext().ensureActive()
                    if (owner != packageId) return@launch
                    selectedRoute = route
                    mutableState.update { it.copy(selection = display, fitSelection = fit, callout = true, routeError = null,
                        editingRoute = false, deletingRoute = false, routeActionError = null) }
                    channel.send(MapObjectsEvent.Selected(id))
                }
            }
        }
    }
    fun callout(show: Boolean) { mutableState.update { it.copy(callout = show && it.selection != null) } }
    fun clearSelection() {
        selectionJob?.cancel(); selectedRoute = null
        mutableState.update { it.copy(selection = null, callout = false, editingRoute = false, deletingRoute = false, routeActionError = null) }
    }

    fun editRoute() {
        val route = selectedRoute ?: return
        if (!state.value.busy) mutableState.update { it.copy(editingRoute = true, deletingRoute = false,
            routeName = route.name, routeDescription = route.description, routeActionError = null) }
    }
    fun routeName(value: String) { if (!state.value.busy && value.length <= 120) mutableState.update { it.copy(routeName = value) } }
    fun routeDescription(value: String) { if (!state.value.busy && value.length <= 4000) mutableState.update { it.copy(routeDescription = value) } }
    fun dismissRouteAction() {
        if (!state.value.busy) mutableState.update { it.copy(editingRoute = false, deletingRoute = false, routeActionError = null) }
    }
    fun confirmRouteDelete() {
        if (!state.value.busy && selectedRoute != null) mutableState.update { it.copy(deletingRoute = true, editingRoute = false, routeActionError = null) }
    }
    fun saveRoute() = routeWork { route ->
        if (!state.value.editingRoute || route.revision == Long.MAX_VALUE) return@routeWork
        val updated = route.copy(revision = route.revision + 1, name = state.value.routeName.trim(), description = state.value.routeDescription)
        if (withContext(Dispatchers.Default) { ActivityValidation.route(updated) } != null) {
            mutableState.update { it.copy(routeActionError = Res.string.annotations_invalid) }; return@routeWork
        }
        when (val result = routes.save(updated, route.revision)) {
            is ActivityResult.Failure -> mutableState.update { it.copy(routeActionError = Res.string.annotations_save_error) }
            is ActivityResult.Success -> {
                if (selectedRoute?.id != route.id) return@routeWork
                selectedRoute = result.value
                mutableState.update { it.copy(editingRoute = false, selection = it.selection?.copy(name = updated.name,
                    display = it.selection.display.copy(name = updated.name, description = updated.description), revision = updated.revision),
                    routes = it.routes.map { item -> if (item.id == route.id) item.copy(name = updated.name) else item }.toImmutableList()) }
            }
        }
    }
    fun deleteRoute() = routeWork { route ->
        if (!state.value.deletingRoute) return@routeWork
        when (routes.delete(route.id)) {
            is ActivityResult.Failure -> mutableState.update { it.copy(routeActionError = Res.string.annotations_save_error) }
            is ActivityResult.Success -> {
                if (selectedRoute?.id != route.id) return@routeWork
                clearSelection()
                mutableState.update { it.copy(routes = it.routes.filterNot { item -> item.id == route.id }.toImmutableList(),
                    mapLines = it.mapLines.filterNot { line -> line.id.substringBefore('~') == route.id }.toImmutableList()) }
                refresh()
            }
        }
    }
    private fun routeWork(block: suspend (ActivityRoute) -> Unit) {
        val route = selectedRoute ?: return
        if (state.value.busy) return
        catalogJob?.cancel()
        catalogGeneration++
        mutableState.update { it.copy(busy = true, loading = false, routeActionError = null) }
        viewModelScope.launch {
            try { block(route) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(routeActionError = Res.string.annotations_save_error) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun beginImport() {
        if (state.value.busy || state.value.partial) return
        pending = null
        mutableState.update { it.copy(importOpen = true, text = "", prepared = false, error = null,
            routeCount = 0, objectCount = 0, pointCount = 0) }
    }
    fun text(value: String) {
        if (state.value.busy || state.value.partial) return
        pending = null
        mutableState.update { it.copy(text = value, prepared = false, error = null) }
    }
    fun closeImport() {
        if (state.value.busy) return
        pending = null
        mutableState.update { it.copy(importOpen = false, text = "", prepared = false, partial = false, error = null) }
    }
    fun cancel() { importJob?.cancel() }
    fun nativeFailure() { mutableState.update { it.copy(importOpen = true, error = Res.string.objects_import_error) } }

    fun pick(document: PickedDocument) {
        if (state.value.busy || state.value.partial) { document.dispose(); return }
        beginImport()
        val target = packageId
        work {
            val text = withContext(Dispatchers.IO) {
                document.open().use { source ->
                    val buffer = Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.readAtMostTo(buffer, 65_536)
                        if (count == -1L) break
                        require(count > 0 && buffer.size <= ActivityLimits.MAX_IMPORT_BYTES)
                    }
                    buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
                }
            }
            prepare(text, target)
        }
        importJob?.invokeOnCompletion { document.dispose() }
    }
    fun prepareText() {
        val text = state.value.text
        val target = packageId
        work { prepare(text, target) }
    }
    private suspend fun prepare(text: String, target: PackageId?) {
        val value = withContext(Dispatchers.Default) {
            val bytes = text.encodeToByteArray()
            require(bytes.size <= ActivityLimits.MAX_IMPORT_BYTES)
            if (text.removePrefix("\uFEFF").trimStart().startsWith('<')) {
                val document = ActivityRouteGpx.decodeDocument(Buffer().apply { write(bytes) })
                val markers = document.waypoints.map { point -> Annotation(
                    id = Uuid.random().toString(), kind = AnnotationKind.MARKER,
                    coordinates = listOf(point.coordinate), name = point.name, description = point.description,
                    elevations = point.elevation?.let { listOf(AnnotationElevation(it.meters, it.verticalReference)) }.orEmpty()) }
                Pending(document.routes.map { it.copy(id = target?.value?.let { owner -> packageRouteId(owner, it.id) } ?: it.id) }, markers, target)
            } else {
                require(bytes.size <= AnnotationGeoJson.MAX_BYTES)
                Pending(emptyList(), AnnotationImport.decode(text).map { it.copy(id = Uuid.random().toString()) }, target)
            }.also { imported ->
                require(imported.routes.isNotEmpty() || imported.annotations.isNotEmpty())
                require(imported.routes.all { ActivityValidation.route(it) == null })
                require(imported.annotations.all { AnnotationValidation.error(it) == null })
            }
        }
        if (target == null) {
            mutableState.update { it.copy(error = Res.string.objects_import_needs_map) }; return
        }
        pending = value
        mutableState.update { it.copy(importOpen = true, text = "", prepared = true, error = null,
            routeCount = value.routes.size, objectCount = value.annotations.size,
            pointCount = value.routes.sumOf { route -> route.segments.sumOf { it.vertices.size } } +
                value.annotations.sumOf { it.coordinates.size }) }
    }

    fun confirmImport() = work {
        val value = pending ?: return@work
        if (value.packageId != packageId) {
            mutableState.update { it.copy(error = Res.string.objects_import_needs_map) }; return@work
        }
        if (!value.routesSaved && value.routes.isNotEmpty()) {
            when (routes.saveImported(value.routes)) {
                is ActivityResult.Failure -> { nativeFailure(); return@work }
                is ActivityResult.Success -> { value.routesSaved = true; mutableState.update { it.copy(partial = value.annotations.isNotEmpty()) }; refresh() }
            }
        }
        if (value.annotations.isNotEmpty()) {
            val id = checkNotNull(value.packageId)
            val resolved = elevations.resolve(id, value.annotations)
            require(resolved.all { AnnotationValidation.error(it) == null })
            when (annotations.saveAnnotations(id, resolved)) {
                is PackageResult.Failure -> { mutableState.update { it.copy(error = Res.string.objects_import_partial.takeIf { value.routesSaved } ?: Res.string.objects_import_error) }; return@work }
                is PackageResult.Success -> Unit
            }
        }
        pending = null
        mutableState.update { it.copy(importOpen = false, text = "", prepared = false, partial = false, error = null) }
        channel.send(MapObjectsEvent.Imported)
        refresh()
        if (value.routes.size == 1) selectRoute(value.routes.single().id, true)
    }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        importJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = if (pending?.routesSaved == true) Res.string.objects_import_partial else Res.string.objects_import_error) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
