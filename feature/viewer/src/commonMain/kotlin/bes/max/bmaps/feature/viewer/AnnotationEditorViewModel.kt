@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bes.max.bmaps.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

internal data class AnnotationEditorState(
    val items: List<Annotation> = emptyList(),
    val layers: List<AnnotationLayerState> = AnnotationKind.entries.map { AnnotationLayerState(it) },
    val panel: Boolean = false,
    val draft: Annotation? = null,
    val history: List<Annotation> = emptyList(),
    val propertiesOpen: Boolean = false,
    val selected: Annotation? = null,
    val replacingVertex: Int? = null,
    val deleteConfirmation: Boolean = false,
    val busy: Boolean = false,
    val truncated: Boolean = false,
    val error: StringResource? = null,
    val geoJsonOpen: Boolean = false,
    val geoJson: String = "",
    val geoJsonExport: Boolean = false,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AnnotationEditorViewModel(private val repository: AnnotationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AnnotationEditorState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<StringResource>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val managers = listOf(MarkersLayer(), RouteLayer(), PolygonLayer())
    private var packageId: PackageId? = null
    private var bounds: BoundingBox? = null
    private var mapPyramid: TilePyramid? = null
    private var query: Job? = null
    private var mutation: Job? = null
    private var pendingImport: Pair<String, List<Annotation>>? = null

    fun open(id: PackageId) {
        if (id == packageId) return
        query?.cancel(); mutation?.cancel()
        packageId = id
        pendingImport = null
        bounds = null
        mapPyramid = null
        managers.forEach { it.visibility(true); it.select(null) }
        mutableState.value = AnnotationEditorState()
        refresh()
    }

    fun mapEvent(pyramid: TilePyramid, event: MapEvent) {
        mapPyramid = pyramid
        when (event) {
            is MapEvent.Tap -> pyramid.coordinateAt(event.position)?.let(::addPoint)
            is MapEvent.ViewportChanged -> {
                val window = event.visibleWindow ?: return
                val world = pyramid.worldWindow(window)
                val nw = WebMercator.geographic(
                    MapPoint(
                        world.left.coerceIn(0.0, 1.0),
                        world.top.coerceIn(0.0, 1.0)
                    )
                ) ?: return
                val se = WebMercator.geographic(
                    MapPoint(
                        world.right.coerceIn(0.0, 1.0),
                        world.bottom.coerceIn(0.0, 1.0)
                    )
                ) ?: return
                bounds = BoundingBox(nw.longitude, se.latitude, se.longitude, nw.latitude)
                refresh(debounce = true)
            }

            else -> Unit
        }
    }

    private fun refresh(debounce: Boolean = false) {
        val id = packageId ?: return
        val box = bounds
        query?.cancel()
        query = viewModelScope.launch {
            if (debounce) delay(180)
            try {
                val items = mutableListOf<Annotation>()
                var cursor: String? = null
                do {
                    when (val result = repository.annotations(id, box, cursor)) {
                        is PackageResult.Failure -> {
                            mutableState.update { it.copy(error = Res.string.annotations_load_error) }; return@launch
                        }

                        is PackageResult.Success -> {
                            items += result.value.items; cursor = result.value.nextCursor
                        }
                    }
                } while (cursor != null && items.size < AnnotationGeoJson.MAX_FEATURES)
                mutableState.update {
                    it.copy(
                        items = items,
                        truncated = cursor != null,
                        error = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = Res.string.annotations_load_error) }
            }
        }
    }

    fun panel(show: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(panel = show) }
    }

    fun retry() = refresh()
    fun visibility(kind: AnnotationKind, visible: Boolean) {
        managers.first { it.kind == kind }.visibility(visible)
        mutableState.update {
            it.copy(
                layers = managers.map { layer -> layer.state },
                selected = it.selected?.takeUnless { item -> item.kind == kind && !visible })
        }
    }

    fun select(id: String, position: MapPoint? = null) {
        if (state.value.busy) return
        if (state.value.draft != null) {
            val index =
                id.takeIf { it.startsWith("vertex:") }?.removePrefix("vertex:")?.toIntOrNull()
            if (index != null && index in state.value.draft!!.coordinates.indices) {
                mutableState.update { it.copy(replacingVertex = index) }
            } else if (position != null) mapPyramid?.coordinateAt(position)?.let(::addPoint)
            return
        }
        val value = state.value.items.firstOrNull { it.id == id } ?: return
        managers.forEach { it.select(value) }
        mutableState.update {
            it.copy(
                selected = value,
                layers = managers.map { layer -> layer.state },
                panel = false
            )
        }
    }

    fun dismissSelection() {
        managers.forEach { it.select(null) }
        mutableState.update {
            it.copy(
                selected = null,
                deleteConfirmation = false,
                layers = managers.map { layer -> layer.state })
        }
    }

    fun start(kind: AnnotationKind) {
        if (state.value.busy) return
        managers.forEach { it.select(null) }
        mutableState.update {
            it.copy(
                layers = managers.map { layer -> layer.state },
                draft = Annotation(kind = kind, coordinates = emptyList()),
                history = emptyList(),
                panel = false,
                selected = null,
                propertiesOpen = false,
                replacingVertex = null,
                error = null
            )
        }
    }

    fun edit() {
        val value = state.value.selected ?: return
        mutableState.update {
            it.copy(
                draft = value,
                selected = null,
                history = emptyList(),
                propertiesOpen = true,
                replacingVertex = null
            )
        }
    }

    fun addPoint(point: GeographicCoordinate) {
        if (state.value.busy || state.value.propertiesOpen) return
        val draft = state.value.draft ?: return
        val index = state.value.replacingVertex
        val points = when {
            index != null -> draft.coordinates.mapIndexed { i, old -> if (i == index) point else old }
            draft.kind == AnnotationKind.MARKER -> listOf(point)
            draft.coordinates.size < AnnotationValidation.MAX_VERTICES -> draft.coordinates + point
            else -> {
                channel.trySend(Res.string.annotations_vertex_limit); return
            }
        }
        change(draft.copy(coordinates = points))
        mutableState.update { it.copy(replacingVertex = null) }
    }

    private fun change(value: Annotation) {
        val previous = state.value.draft ?: return
        if (state.value.busy || previous == value) return
        mutableState.update {
            it.copy(
                draft = value,
                history = (it.history + previous).takeLast(1000),
                error = null
            )
        }
    }

    fun name(value: String) {
        state.value.draft?.let { change(it.copy(name = value.take(120))) }
    }

    fun description(value: String) {
        state.value.draft?.let { change(it.copy(description = value.take(4000))) }
    }

    fun color(value: String) {
        if (Regex("#[0-9a-fA-F]{6}").matches(value)) state.value.draft?.let { change(it.copy(color = value)) }
    }

    fun icon(value: String) {
        if (MarkerIcons.entries.any { it.id == value }) state.value.draft?.let { change(it.copy(icon = value)) }
    }

    fun undo() {
        if (state.value.busy) return
        val previous = state.value.history.lastOrNull() ?: return
        mutableState.update {
            it.copy(
                draft = previous,
                history = it.history.dropLast(1),
                replacingVertex = null,
                error = null
            )
        }
    }

    fun removeVertex(index: Int) {
        state.value.draft?.let { value -> change(value.copy(coordinates = value.coordinates.filterIndexed { i, _ -> i != index })) }
    }

    fun replaceVertex(index: Int) {
        if (state.value.busy || index !in state.value.draft?.coordinates.orEmpty().indices) return
        mutableState.update { it.copy(replacingVertex = index, propertiesOpen = false) }
    }

    fun properties(show: Boolean) {
        mutableState.update { it.copy(propertiesOpen = show, replacingVertex = null) }
    }

    fun cancel() {
        if (state.value.busy) return
        mutableState.update {
            it.copy(
                draft = null,
                history = emptyList(),
                propertiesOpen = false,
                replacingVertex = null,
                error = null
            )
        }
    }

    fun save() {
        val draft = state.value.draft ?: return
        if (AnnotationValidation.error(draft) != null) {
            mutableState.update { it.copy(error = Res.string.annotations_invalid) }; return
        }
        mutate { id -> repository.saveAnnotations(id, listOf(draft)) }
    }

    fun confirmDelete(show: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(deleteConfirmation = show) }
    }

    fun delete() {
        val value = state.value.selected ?: return; mutate {
            repository.deleteAnnotation(
                it,
                value.id
            )
        }
    }

    private fun mutate(action: suspend (PackageId) -> PackageResult<Unit>) {
        val id = packageId ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        mutation = viewModelScope.launch {
            try {
                when (action(id)) {
                    is PackageResult.Success -> {
                        pendingImport = null
                        managers.forEach { it.select(null) }
                        mutableState.update {
                            it.copy(
                                draft = null,
                                history = emptyList(),
                                selected = null,
                                propertiesOpen = false,
                                deleteConfirmation = false,
                                geoJsonOpen = false,
                                geoJson = "",
                                replacingVertex = null,
                                layers = managers.map { layer -> layer.state })
                        }
                        refresh()
                    }

                    is PackageResult.Failure -> channel.send(Res.string.annotations_save_error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                channel.send(Res.string.annotations_save_error)
            } finally {
                if (packageId == id) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun geoJson(show: Boolean) {
        if (!state.value.busy) mutableState.update {
            it.copy(
                geoJsonOpen = show,
                geoJson = "",
                geoJsonExport = false,
                panel = false
            )
        }
    }

    fun geoJsonText(value: String) {
        if (!state.value.busy && value.length <= AnnotationGeoJson.MAX_BYTES) mutableState.update {
            it.copy(
                geoJson = value,
                error = null
            )
        }
    }

    fun importGeoJson() {
        val text = state.value.geoJson
        if (state.value.busy) return
        mutate { id ->
            val values = try {
                pendingImport?.takeIf { it.first == text }?.second
                    ?: withContext(Dispatchers.Default) {
                        AnnotationGeoJson.decode(text).also {
                            require(it.isNotEmpty()); require(it.all { value ->
                            AnnotationValidation.error(value) == null
                        })
                        }
                            .map { it.copy(id = Uuid.random().toString()) }
                    }.also { pendingImport = text to it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = Res.string.annotations_geojson_error) }
                return@mutate PackageResult.Failure(PackageFailure.CorruptData)
            }
            repository.saveAnnotations(id, values)
        }
    }

    fun exportGeoJson() {
        val id = packageId ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        mutation = viewModelScope.launch {
            try {
                val values = mutableListOf<Annotation>()
                var cursor: String? = null
                do {
                    val page = (repository.annotations(
                        id,
                        after = cursor
                    ) as? PackageResult.Success)?.value ?: error("Read failed")
                    values += page.items
                    require(values.size <= AnnotationGeoJson.MAX_FEATURES)
                    cursor = page.nextCursor
                } while (cursor != null)
                val text = withContext(Dispatchers.Default) { AnnotationGeoJson.encode(values) }
                mutableState.update {
                    it.copy(
                        geoJson = text,
                        geoJsonOpen = true,
                        geoJsonExport = true,
                        panel = false
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                channel.send(Res.string.annotations_export_error)
            } finally {
                if (packageId == id) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
