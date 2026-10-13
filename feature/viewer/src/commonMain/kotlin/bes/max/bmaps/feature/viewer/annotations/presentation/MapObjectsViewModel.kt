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
import bes.max.bmaps.core.sharing.PickedDocument
import bes.max.bmaps.domain.activity.*
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

@Immutable
internal data class MapRouteItem(val id: String, val name: String, val segments: Int, val vertices: Int)
@Immutable
internal data class MapRouteSelection(val id: String, val name: String, val length: String,
    val lines: ImmutableList<TrackLine>)
@Immutable
internal data class MapObjectsState(
    val hasPackage: Boolean = false,
    val routes: ImmutableList<MapRouteItem> = persistentListOf(), val more: Boolean = false,
    val loading: Boolean = false, val routeError: StringResource? = null,
    val selection: MapRouteSelection? = null, val callout: Boolean = false,
    val importOpen: Boolean = false, val text: String = "", val busy: Boolean = false,
    val routeCount: Int = 0, val objectCount: Int = 0, val pointCount: Int = 0,
    val prepared: Boolean = false, val partial: Boolean = false, val error: StringResource? = null,
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
    private data class Pending(val routes: List<ActivityRoute>, val annotations: List<Annotation>,
        val packageId: PackageId?, var routesSaved: Boolean = false)

    fun context(id: PackageId?) { packageId = id; mutableState.update { it.copy(hasPackage = id != null) } }

    fun refresh() = load(false)
    fun more() = load(true)
    private fun load(append: Boolean) {
        if (append && (state.value.loading || !state.value.more)) return
        catalogJob?.cancel()
        val generation = ++catalogGeneration
        mutableState.update { it.copy(loading = true, routeError = null) }
        catalogJob = viewModelScope.launch {
            try {
                when (val result = routes.routes(ActivityRouteQuery(afterId = if (append) nextId else null))) {
                    is ActivityResult.Failure -> mutableState.update { it.copy(routeError = Res.string.objects_routes_error) }
                    is ActivityResult.Success -> {
                        nextId = result.value.nextId
                        val items = result.value.items.map { MapRouteItem(it.id, it.name, it.segmentCount, it.vertexCount) }
                        mutableState.update { it.copy(routes = (if (append) it.routes + items else items).toImmutableList(), more = nextId != null) }
                    }
                }
            } finally { if (generation == catalogGeneration) mutableState.update { it.copy(loading = false) } }
        }
    }

    fun select(id: String) {
        selectionJob?.cancel()
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
                        MapRouteSelection(route.id, route.name, trackNumber(trackLength(route) / 1000),
                            preview.segments.map { segment -> TrackLine(segment.sourceSegmentId,
                                segment.coordinates.map { TrackCoordinate(it.latitude, it.longitude) }.toImmutableList()) }.toImmutableList())
                    }
                    mutableState.update { it.copy(selection = display, callout = true, routeError = null) }
                    channel.send(MapObjectsEvent.Selected(id))
                }
            }
        }
    }
    fun callout(show: Boolean) { mutableState.update { it.copy(callout = show && it.selection != null) } }
    fun clearSelection() { selectionJob?.cancel(); mutableState.update { it.copy(selection = null, callout = false) } }

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
                Pending(document.routes, markers, target)
            } else {
                require(bytes.size <= AnnotationGeoJson.MAX_BYTES)
                Pending(emptyList(), AnnotationImport.decode(text).map { it.copy(id = Uuid.random().toString()) }, target)
            }.also { imported ->
                require(imported.routes.isNotEmpty() || imported.annotations.isNotEmpty())
                require(imported.routes.all { ActivityValidation.route(it) == null })
                require(imported.annotations.all { AnnotationValidation.error(it) == null })
            }
        }
        if (value.annotations.isNotEmpty() && target == null) {
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
        if (value.annotations.isNotEmpty() && value.packageId != packageId) {
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
        if (value.routes.size == 1) select(value.routes.single().id)
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
