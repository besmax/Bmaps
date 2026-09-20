@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.FlowPreview::class)

package bes.max.bmaps.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.datastore.UserPreferencesRepository
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
    val catalog: List<Annotation> = emptyList(),
    val catalogReady: Boolean = false,
    val catalogTooMany: Boolean = false,
    val catalogFailed: Boolean = false,
    val clusteringEnabled: Boolean = true,
    val presentation: AnnotationPresentation? = null,
    val layers: List<AnnotationLayerState> = AnnotationKind.entries.map { AnnotationLayerState(it) },
    val panel: Boolean = false,
    val draft: Annotation? = null,
    val history: List<Annotation> = emptyList(),
    val propertiesOpen: Boolean = false,
    val selected: Annotation? = null,
    val clusterMembers: List<Annotation> = emptyList(),
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
class AnnotationEditorViewModel(
    private val repository: AnnotationRepository,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AnnotationEditorState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<StringResource>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val managers = listOf(MarkersLayer(), RouteLayer(), PolygonLayer())
    private var packageId: PackageId? = null
    private var bounds: BoundingBox? = null
    private var mapPyramid: TilePyramid? = null
    private var query: Job? = null
    private var catalogQuery: Job? = null
    private var mutation: Job? = null
    private var pendingImport: Pair<String, List<Annotation>>? = null
    private val display = MutableStateFlow(AnnotationDisplay())
    private var expansionJob: Job? = null
    private var expansion: ClusterExpansion? = null
    private var expansionController: RasterMapController? = null
    private var requestId = 0L

    init {
        viewModelScope.launch {
            preferences.preferences.retryWhen { _, _ -> delay(1000); true }.collect { saved ->
                if (saved.clusterMapObjects != state.value.clusteringEnabled) cancelExpansion()
                mutableState.update { it.copy(clusteringEnabled = saved.clusterMapObjects, clusterMembers = emptyList()) }
            }
        }
        viewModelScope.launch {
            var preparedKey: Pair<AnnotationRenderData, TilePyramid>? = null
            var prepared: AnnotationClusterer? = null
            var groupKey: Triple<Double, Double, Float>? = null
            var groups = emptyList<AnnotationGroup>()
            combine(state.map { it.renderData() }.distinctUntilChanged(), display) { data, view -> data to view }
                .sample(50).collectLatest { (data, view) ->
                    val pyramid = view.pyramid ?: return@collectLatest
                    val camera = view.camera
                    val overlays = withContext(Dispatchers.Default) {
                        if (data.cluster && camera != null && camera.pyramid == pyramid) {
                            val key = data to pyramid
                            if (preparedKey != key) {
                                prepared = AnnotationClusterer(data.values, pyramid, data.visible, data.selectedId)
                                preparedKey = key
                                groupKey = null
                            }
                            val nextGroupKey = Triple(camera.viewport.scale, camera.fitScale, view.density)
                            if (groupKey != nextGroupKey) {
                                val context = currentCoroutineContext()
                                groups = prepared!!.groups(camera, view.density) { context.ensureActive() }
                                groupKey = nextGroupKey
                            }
                            prepared!!.render(groups, camera, view.density)
                        } else {
                            annotationOverlays(AnnotationEditorState(items = data.values,
                                layers = AnnotationKind.entries.map { AnnotationLayerState(it, it in data.visible) },
                                selected = data.values.firstOrNull { it.id == data.selectedId }, draft = data.draft), pyramid)
                        }
                    }
                    if (state.value.renderData() == data && display.value.camera?.session == camera?.session && display.value.pyramid == pyramid) {
                        mutableState.update { it.copy(presentation = AnnotationPresentation(data, view, overlays)) }
                    }
                }
        }
    }

    internal fun cameraChanged(pyramid: TilePyramid?, camera: MapCameraSnapshot?, density: Float) {
        if (display.value.pyramid != pyramid || display.value.camera?.session != camera?.session) {
            cancelExpansion()
            mutableState.update { it.copy(presentation = null, clusterMembers = emptyList()) }
        }
        display.value = AnnotationDisplay(pyramid, camera?.takeIf { it.pyramid == pyramid }, density)
    }

    internal fun clickOverlay(target: AnnotationHit, position: MapPoint, controller: RasterMapController) {
        if (state.value.busy) return
        when (target) {
            is AnnotationHit.Object -> select(target.id, position)
            is AnnotationHit.Vertex -> replaceVertex(target.index)
            is AnnotationHit.Cluster -> {
                if (expansion != null || state.value.draft != null || !state.value.clusteringEnabled) return
                val view = display.value
                val camera = view.camera ?: return
                val data = state.value.renderData()
                if (!data.cluster || !data.values.map { it.id }.containsAll(target.ids)) return
                val pending = ClusterExpansion(++requestId, target.ids, data, view)
                expansion = pending
                expansionController = controller
                expansionJob = viewModelScope.launch {
                    val destination = withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        val clusterer = AnnotationClusterer(data.values, camera.pyramid, data.visible, data.selectedId)
                        clusterer.splittingScale(target.ids, camera, view.density) { context.ensureActive() }
                            ?.let { MapViewport(clusterer.focus(target.ids, position, camera), it) }
                    }
                    if (expansion !== pending || state.value.renderData() != data || display.value != view) {
                        cancelExpansion(); return@launch
                    }
                    if (destination == null) {
                        expansion = null
                        showClusterMembers(target.ids)
                    } else controller.moveTo(camera.session, pending.requestId, destination)
                }
            }
        }
    }

    internal fun cameraResult(result: CameraMoveResult) {
        val pending = expansion?.takeIf { it.requestId == result.requestId && it.display.camera?.session == result.session } ?: return
        expansion = null
        if (result.outcome == CameraMoveOutcome.CANCELLED || state.value.renderData() != pending.data) return
        val camera = result.camera
        expansionJob = viewModelScope.launch {
            val stillGrouped = camera == null || withContext(Dispatchers.Default) {
                AnnotationClusterer(pending.data.values, camera.pyramid, pending.data.visible, pending.data.selectedId)
                    .groups(camera, pending.display.density).any { it.ids.containsAll(pending.ids) }
            }
            if (state.value.renderData() == pending.data && display.value.camera?.session == result.session && stillGrouped) showClusterMembers(pending.ids)
        }
    }

    private fun showClusterMembers(ids: List<String>) {
        mutableState.update { it.copy(clusterMembers = it.catalog.filter { item -> item.id in ids }.sortedBy { item -> item.id }) }
    }

    internal fun cancelExpansion() {
        expansionJob?.cancel()
        expansion = null
        expansionController?.cancelMove()
        expansionController = null
    }

    fun open(id: PackageId) {
        if (id == packageId) return
        cancelExpansion()
        display.value = AnnotationDisplay()
        query?.cancel(); catalogQuery?.cancel(); mutation?.cancel()
        packageId = id
        pendingImport = null
        bounds = null
        mapPyramid = null
        managers.forEach { it.visibility(true); it.select(null) }
        mutableState.value = AnnotationEditorState(clusteringEnabled = state.value.clusteringEnabled)
        refresh()
        loadCatalog()
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

    private fun loadCatalog() {
        val id = packageId ?: return
        catalogQuery?.cancel()
        cancelExpansion()
        mutableState.update { it.copy(catalog = emptyList(), catalogReady = false, catalogFailed = false, catalogTooMany = false, clusterMembers = emptyList(), presentation = null) }
        catalogQuery = viewModelScope.launch {
            try {
                val values = mutableListOf<Annotation>()
                var cursor: String? = null
                val cursors = mutableSetOf<String>()
                do {
                    when (val result = repository.annotations(id, after = cursor)) {
                        is PackageResult.Failure -> {
                            mutableState.update { it.copy(catalog = emptyList(), catalogReady = false, catalogTooMany = false, catalogFailed = true) }
                            return@launch
                        }
                        is PackageResult.Success -> {
                            values += result.value.items
                            require(values.map { it.id }.distinct().size == values.size)
                            require(result.value.nextCursor == null || result.value.nextCursor != cursor)
                            if (values.size > MAX_CLUSTER_OBJECTS || (result.value.nextCursor != null && values.size == MAX_CLUSTER_OBJECTS)) {
                                mutableState.update { it.copy(catalog = emptyList(), catalogReady = false, catalogTooMany = true, catalogFailed = false) }
                                return@launch
                            }
                            cursor = result.value.nextCursor
                            require(cursor == null || cursors.add(cursor))
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (packageId != id) return@launch
                } while (cursor != null)
                mutableState.update { it.copy(catalog = values, catalogReady = true, catalogTooMany = false, catalogFailed = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(catalog = emptyList(), catalogReady = false, catalogTooMany = false, catalogFailed = true) }
            }
        }
    }

    fun panel(show: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(panel = show) }
    }

    fun retry() { refresh(); loadCatalog() }
    fun visibility(kind: AnnotationKind, visible: Boolean) {
        cancelExpansion()
        managers.first { it.kind == kind }.visibility(visible)
        mutableState.update {
            it.copy(
                layers = managers.map { layer -> layer.state },
                clusterMembers = emptyList(),
                selected = it.selected?.takeUnless { item -> item.kind == kind && !visible })
        }
    }

    fun select(id: String, position: MapPoint? = null) {
        if (state.value.busy) return
        if (state.value.draft != null) {
            if (position != null) mapPyramid?.coordinateAt(position)?.let(::addPoint)
            return
        }
        cancelExpansion()
        val source = if (state.value.catalogReady) state.value.catalog else state.value.items
        val value = source.firstOrNull { it.id == id } ?: return
        managers.forEach { it.select(value) }
        mutableState.update {
            it.copy(
                selected = value,
                clusterMembers = emptyList(),
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
                clusterMembers = emptyList(),
                deleteConfirmation = false,
                layers = managers.map { layer -> layer.state })
        }
    }

    fun start(kind: AnnotationKind) {
        if (state.value.busy) return
        cancelExpansion()
        managers.forEach { it.select(null) }
        mutableState.update {
            it.copy(
                layers = managers.map { layer -> layer.state },
                draft = Annotation(kind = kind, coordinates = emptyList()),
                history = emptyList(),
                panel = false,
                selected = null,
                clusterMembers = emptyList(),
                propertiesOpen = false,
                replacingVertex = null,
                error = null
            )
        }
    }

    fun edit() {
        val value = state.value.selected ?: return
        cancelExpansion()
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
                                clusterMembers = emptyList(),
                                propertiesOpen = false,
                                deleteConfirmation = false,
                                geoJsonOpen = false,
                                geoJson = "",
                                replacingVertex = null,
                                layers = managers.map { layer -> layer.state })
                        }
                        refresh()
                        loadCatalog()
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
