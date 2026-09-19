package bes.max.bmaps.feature.viewer

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationEditorTest {
    @Test fun drawingUndoEditingAndCancelPreserveCommittedGeometry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = MemoryAnnotations()
            val model = AnnotationEditorViewModel(repository)
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
            val model = AnnotationEditorViewModel(repository)
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
