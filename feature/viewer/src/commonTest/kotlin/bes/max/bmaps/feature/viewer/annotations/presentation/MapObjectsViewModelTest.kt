/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.sharing.PickedDocument
import bes.max.bmaps.domain.activity.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationElevationResolver
import bes.max.bmaps.domain.mapbuilder.AnnotationPage
import bes.max.bmaps.domain.mapbuilder.AnnotationRepository
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.domain.mapbuilder.PackageResult
import bes.max.bmaps.domain.mapbuilder.PackageFailure
import bes.max.bmaps.core.mapengine.BoundingBox
import bmaps.feature.viewer.generated.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.io.Buffer
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MapObjectsViewModelTest {
    @Test fun detailedGpxAndMarkersRequireConfirmationAndKeepGeometryAndSourceHeights() = runTest {
        fixture { model, routes, objects ->
            model.context(PackageId("map"))
            val points = (0..3_000).joinToString("") { index ->
                """<trkpt lat="50" lon="${30.0 + index * 0.00001}"><ele>${100 + index}</ele><time>2026-10-07T13:59:42Z</time></trkpt>"""
            }
            model.pick(document("<gpx version=\"1.1\"><wpt lat=\"50\" lon=\"30\"><name>Camp</name><ele>-2.5</ele></wpt><trk><trkseg>$points</trkseg></trk></gpx>"))
            model.await { it.prepared && !it.busy }
            assertEquals(3_002, model.state.value.pointCount)
            assertEquals(1, model.state.value.routeCount)
            assertEquals(1, model.state.value.objectCount)
            assertTrue(routes.imports.isEmpty()); assertTrue(objects.writes.isEmpty())
            model.confirmImport()
            model.await { !it.importOpen && !it.busy }
            val stored = routes.imports.single().single().segments.single().vertices
            assertEquals(3_001, stored.size)
            assertEquals(GeographicCoordinate(50.0, 30.03), stored.last().coordinate)
            assertEquals(3_100.0, stored.last().elevation?.meters)
            assertNotNull(stored.last().sourceTimeEpochMillis)
            assertEquals("Camp", objects.writes.single().single().name)
            assertEquals(-2.5, objects.writes.single().single().elevations.single()?.meters)
        }
    }
    @Test fun mixedImportWithoutMapRejectsBeforeAnyWrite() = runTest {
        fixture { model, routes, objects ->
            model.pick(document(mixed()))
            model.await { !it.busy && it.error != null }
            assertEquals(Res.string.objects_import_needs_map, model.state.value.error)
            assertFalse(model.state.value.prepared)
            assertTrue(routes.imports.isEmpty()); assertTrue(objects.writes.isEmpty())
        }
    }
    @Test fun routeImportRequiresMapAndCancelBeforeConfirmationSavesNothing() = runTest {
        fixture { model, routes, objects ->
            model.pick(document("<gpx version=\"1.1\">${track()}</gpx>"))
            model.await { !it.busy && it.error != null }
            assertEquals(Res.string.objects_import_needs_map, model.state.value.error)
            assertTrue(routes.imports.isEmpty())
            model.context(PackageId("map"))
            model.pick(document("<gpx version=\"1.1\">${track()}</gpx>"))
            model.await { it.prepared && !it.busy }
            model.closeImport()
            assertTrue(routes.imports.isEmpty())
            model.pick(document("<gpx version=\"1.1\">${track()}</gpx>"))
            model.await { it.prepared && !it.busy }
            model.confirmImport()
            model.await { !it.importOpen && !it.busy }
            assertEquals(1, routes.imports.size)
            assertTrue(objects.writes.isEmpty())
        }
    }
    @Test fun partialMixedImportRetriesOnlyMarkersWithStableIds() = runTest {
        fixture { model, routes, objects ->
            model.context(PackageId("map")); objects.fail = true
            model.pick(document(mixed()))
            model.await { it.prepared && !it.busy }
            model.confirmImport()
            model.await { it.partial && !it.busy }
            assertEquals(Res.string.objects_import_partial, model.state.value.error)
            val ids = objects.writes.single().map { it.id }
            objects.fail = false
            model.confirmImport()
            model.await { !it.importOpen && !it.busy }
            assertEquals(1, routes.imports.size)
            assertEquals(ids, objects.writes.last().map { it.id })
        }
    }
    private fun document(xml: String) = PickedDocument("objects.gpx", { Buffer().apply { write(xml.encodeToByteArray()) } })
    private fun track() = "<trk><trkseg><trkpt lat=\"50\" lon=\"30\"/><trkpt lat=\"50.1\" lon=\"30.1\"/></trkseg></trk>"
    private fun mixed() = "<gpx version=\"1.1\"><wpt lat=\"50\" lon=\"30\"/>${track()}</gpx>"
    private suspend fun TestScope.fixture(block: suspend (MapObjectsViewModel, Routes, Objects) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val routes = Routes(); val objects = Objects()
            val model = MapObjectsViewModel(routes, objects, AnnotationElevationResolver { _, values -> values })
            store.put("objects", model)
            block(model, routes, objects)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private suspend fun MapObjectsViewModel.await(predicate: (MapObjectsState) -> Boolean) {
        yield()
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first(predicate) } }
    }
    private class Routes : ActivityRouteRepository {
        val imports = mutableListOf<List<ActivityRoute>>()
        override suspend fun saveImported(routes: List<ActivityRoute>): ActivityResult<Unit> {
            imports += routes; return ActivityResult.Success(Unit)
        }
        override suspend fun get(id: String): ActivityResult<ActivityRoute> = ActivityResult.Success(imports.flatten().first { it.id == id })
        override suspend fun routes(query: ActivityRouteQuery) = ActivityResult.Success(ActivityRoutePage(emptyList(), null))
        override suspend fun save(route: ActivityRoute, expectedRevision: Long?): ActivityResult<ActivityRoute> = error("unused")
        override suspend fun derive(activityId: String, routeId: String): ActivityResult<ActivityRoute?> = error("unused")
        override suspend fun delete(id: String): ActivityResult<Unit> = error("unused")
    }
    private class Objects : AnnotationRepository {
        var fail = false
        val writes = mutableListOf<List<Annotation>>()
        override suspend fun saveAnnotations(packageId: PackageId, annotations: List<Annotation>): PackageResult<Unit> {
            writes += annotations
            return if (fail) PackageResult.Failure(PackageFailure.CorruptData) else PackageResult.Success(Unit)
        }
        override suspend fun annotations(packageId: PackageId, bounds: BoundingBox?, after: String?): PackageResult<AnnotationPage> = error("unused")
        override suspend fun deleteAnnotation(packageId: PackageId, id: String): PackageResult<Unit> = error("unused")
    }
}
