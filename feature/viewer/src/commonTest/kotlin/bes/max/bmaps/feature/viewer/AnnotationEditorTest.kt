package bes.max.bmaps.feature.viewer

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.datastore.*
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationEditorTest {
    @Test fun persistedClusteringPreferenceUpdatesOpenViewerWithoutLosingObjects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = MemoryAnnotations().apply {
                values = listOf(Annotation("pin", AnnotationKind.MARKER, listOf(GeographicCoordinate(0.0, 0.0))))
            }
            val preferences = MemoryPreferences()
            val model = AnnotationEditorViewModel(repository, preferences)
            owner.put("editor", model)
            model.open(PackageId("one")); runCurrent()
            assertTrue(model.state.value.catalogReady)
            preferences.setDisplayPreferences(ThemePreference.SYSTEM, false); runCurrent()
            assertFalse(model.state.value.clusteringEnabled)
            assertEquals(1, annotationOverlays(model.state.value, TilePyramid(ZoomRange(0, 0))).markers.size)
            preferences.setDisplayPreferences(ThemePreference.SYSTEM, true); runCurrent()
            assertTrue(model.state.value.clusteringEnabled)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun catalogLimitAndRetryNeverExposeIncompleteCounts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = MemoryAnnotations().apply {
                values = (0..1000).map { Annotation("p$it", AnnotationKind.MARKER, listOf(GeographicCoordinate(0.0, 0.0))) }
            }
            val model = AnnotationEditorViewModel(repository, MemoryPreferences())
            owner.put("editor", model)
            model.open(PackageId("one")); runCurrent()
            assertFalse(model.state.value.catalogReady)
            assertTrue(model.state.value.catalogTooMany)
            repository.values = repository.values.take(1000)
            model.retry(); runCurrent()
            assertTrue(model.state.value.catalogReady)
            assertEquals(1000, model.state.value.catalog.size)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun drawingUndoEditingAndCancelPreserveCommittedGeometry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = MemoryAnnotations()
            val model = AnnotationEditorViewModel(repository, MemoryPreferences())
            owner.put("editor", model)
            model.open(PackageId("one")); runCurrent()
            model.start(AnnotationKind.POLYGON)
            val a = GeographicCoordinate(0.0, 0.0)
            val b = GeographicCoordinate(0.0, 1.0)
            val c = GeographicCoordinate(1.0, 0.0)
            model.addPoint(a); model.addPoint(b); model.addPoint(c)
            model.undo()
            assertEquals(listOf(a, b), model.state.value.draft?.coordinates)
            model.save()
            assertNotNull(model.state.value.error)
            assertTrue(repository.values.isEmpty())
            model.addPoint(c); model.color("#1E88E5"); model.undo()
            assertEquals("#E53935", model.state.value.draft?.color)
            model.save(); runCurrent()
            val saved = repository.values.single()
            model.select(saved.id); model.edit(); model.replaceVertex(1); model.addPoint(GeographicCoordinate(0.0, 2.0))
            model.undo()
            assertEquals(saved.coordinates, model.state.value.draft?.coordinates)
            model.removeVertex(0); model.cancel()
            assertEquals(saved, repository.values.single())
            assertNull(model.state.value.draft)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun failedSaveRetainsDraftAndImportAddsFreshIds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = MemoryAnnotations()
            val model = AnnotationEditorViewModel(repository, MemoryPreferences())
            owner.put("editor", model)
            model.open(PackageId("one")); runCurrent()
            model.start(AnnotationKind.MARKER); model.addPoint(GeographicCoordinate(1.0, 2.0)); model.color("#43A047")
            repository.fail = true
            model.save(); runCurrent()
            assertEquals("#43A047", model.state.value.draft?.color)
            repository.fail = false
            model.save(); runCurrent()
            val original = repository.values.single()
            model.geoJson(true); model.geoJsonText(AnnotationGeoJson.encode(listOf(original)))
            model.importGeoJson(); model.state.first { !it.busy }; runCurrent()
            assertEquals(2, repository.values.size)
            assertEquals(2, repository.values.map { it.id }.distinct().size)
            model.open(PackageId("two")); runCurrent()
            assertNull(model.state.value.draft)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun svgCatalogUsesTintableVectorPaths() {
        val vector = parseMarkerSvg("""<svg viewBox="0 0 24 24"><path d="M0 0L24 0L12 24Z"/></svg>""", "fixture")
        assertEquals(24f, vector.viewportWidth)
        assertEquals(1, vector.root.size)
    }
}

private class MemoryAnnotations : AnnotationRepository {
    var values = emptyList<Annotation>()
    var fail = false
    override suspend fun annotations(packageId: PackageId, bounds: BoundingBox?, after: String?) = PackageResult.Success(AnnotationPage(values, null))
    override suspend fun saveAnnotations(packageId: PackageId, annotations: List<Annotation>): PackageResult<Unit> {
        if (fail) return PackageResult.Failure(PackageFailure.Io)
        values = values.filterNot { old -> annotations.any { it.id == old.id } } + annotations
        return PackageResult.Success(Unit)
    }
    override suspend fun deleteAnnotation(packageId: PackageId, id: String): PackageResult<Unit> {
        values = values.filterNot { it.id == id }
        return PackageResult.Success(Unit)
    }
}

private class MemoryPreferences : UserPreferencesRepository {
    override val preferences = MutableStateFlow(UserPreferences())
    override suspend fun setTheme(theme: ThemePreference) { preferences.value = preferences.value.copy(theme = theme) }
    override suspend fun setDefaultCoordinateSystem(identifier: String) { preferences.value = preferences.value.copy(defaultCoordinateSystem = identifier) }
    override suspend fun setDisplayPreferences(theme: ThemePreference, clusterMapObjects: Boolean) {
        preferences.value = preferences.value.copy(theme = theme, clusterMapObjects = clusterMapObjects)
    }
}
