package bes.max.bmaps.feature.viewer

import androidx.compose.ui.unit.IntSize
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import kotlin.test.*

class AnnotationClustererTest {
    private val pyramid = TilePyramid(ZoomRange(0, 5))
    private val camera = MapCameraSnapshot(1, pyramid, MapViewport(), MapWindow(0.0, 0.0, 1.0, 1.0),
        IntSize(1024, 1024), 0.125, 4096.0)

    private fun marker(id: String, x: Double, y: Double = 0.5) = Annotation(id, AnnotationKind.MARKER,
        listOf(pyramid.coordinateAt(MapPoint(x, y))!!))
    private fun clusterer(values: List<Annotation>, selected: String? = null) =
        AnnotationClusterer(values, pyramid, AnnotationKind.entries.toSet(), selected)

    @Test fun distancesUseFullMapSizeNotLayoutSize() {
        val near = clusterer(listOf(marker("a", 0.25), marker("b", 0.25 + 63.0 / 1024)))
        assertEquals(listOf(2), near.groups(camera, 1f).map { it.ids.size })
        val far = clusterer(listOf(marker("a", 0.25), marker("b", 0.25 + 65.0 / 1024)))
        assertEquals(listOf(1, 1), far.groups(camera, 1f).map { it.ids.size })
        val exact = clusterer(listOf(marker("a", 0.25), marker("b", 0.3125)))
        assertEquals(listOf(2), exact.groups(camera, 1f).map { it.ids.size })
    }

    @Test fun clickOn26FindsAScaleThatActuallySplitsAllOriginalMembers() {
        val values = (0 until 26).map { marker("p$it", 0.4 + it * 0.0001) }
        val engine = clusterer(values)
        val ids = values.map { it.id }
        assertEquals(26, engine.groups(camera, 1f).single().ids.size)
        assertEquals(26, engine.groups(camera.copy(viewport = MapViewport(scale = 4.0)), 1f).single().ids.size)
        val scale = assertNotNull(engine.splittingScale(ids, camera, 1f))
        assertTrue(scale > 4.0)
        assertTrue(scale <= camera.maxScale)
        val next = camera.copy(viewport = MapViewport(scale = scale))
        val groups = engine.groups(next, 1f)
        assertTrue(groups.none { it.ids.containsAll(ids) })
        val overlays = engine.render(groups, next, 1f)
        assertEquals(26, overlays.targets.values.filterIsInstance<AnnotationHit.Object>().size)
        assertEquals(values.map { pyramid.positionOf(it.coordinates.single()) }.toSet(), overlays.markers.map { it.position }.toSet())
    }

    @Test fun offscreenClusterCentroidDoesNotHideVisibleMembers() {
        val values = (0..16).map { marker("p$it", 0.05 + it * 0.055) }
        val engine = clusterer(values)
        val panned = camera.copy(visibleWindow = MapWindow(0.0, 0.4, 0.2, 0.6))
        val groups = engine.groups(panned, 1f)
        assertEquals(17, groups.single().ids.size)
        val overlays = engine.render(groups, panned, 1f)
        assertEquals("17", overlays.markers.single().label)
        assertTrue(overlays.markers.single().position.x in 0.0..0.2)
        assertEquals(engine.groups(camera, 1f).map { it.ids }, groups.map { it.ids })
    }

    @Test fun densityAndInputOrderDoNotChangeGroups() {
        val values = listOf(marker("a", 0.3), marker("b", 0.35), marker("c", 0.7))
        val expected = clusterer(values).groups(camera, 1f).map { it.ids }
        val highDensity = camera.copy(layout = IntSize(3072, 3072), fitScale = 0.375)
        assertEquals(expected, clusterer(values.reversed()).groups(highDensity, 3f).map { it.ids })
    }

    @Test fun insetCircleZoomsToActualMemberRatherThanEmptyMapAtTheEdge() {
        val values = listOf(marker("a", 0.0001), marker("b", 0.0002))
        val engine = clusterer(values)
        val badge = engine.render(engine.groups(camera, 1f), camera, 1f).markers.single().position
        assertTrue(badge.x > 0.03)
        val focus = engine.focus(values.map { it.id }, badge, camera)
        assertTrue(focus.x < 0.001)
        assertTrue(focus in values.map { pyramid.positionOf(it.coordinates.single()) })
    }

    @Test fun connectedNeighborsAreTransitiveAndSelectedObjectIsExempt() {
        val values = listOf(marker("a", 0.3), marker("b", 0.3 + 50.0 / 1024), marker("c", 0.3 + 100.0 / 1024))
        assertEquals(3, clusterer(values).groups(camera, 1f).single().ids.size)
        assertEquals(listOf(1, 1, 1), clusterer(values, "b").groups(camera, 1f).map { it.ids.size })
    }

    @Test fun smallShapesClusterAndReappearAsPathsWhenZoomed() {
        val values = listOf(marker("pin", 0.5),
            Annotation("line", AnnotationKind.LINE, listOf(point(0.50, 0.50), point(0.52, 0.50))),
            Annotation("polygon", AnnotationKind.POLYGON, listOf(point(0.50, 0.50), point(0.52, 0.50), point(0.51, 0.52))))
        val engine = clusterer(values)
        val groups = engine.groups(camera, 1f)
        assertEquals(3, groups.single().ids.size)
        val zoomed = camera.copy(viewport = MapViewport(scale = 10.0))
        val overlays = engine.render(engine.groups(zoomed, 1f), zoomed, 1f)
        assertEquals(2, overlays.paths.size)
        val polygon = overlays.paths.single { it.filled }
        assertEquals(polygon.points.first(), polygon.points.last())
        assertEquals(4, polygon.points.size)
    }

    @Test fun coincidentObjectsAndZoomLimitUseMemberListOutcome() {
        val coincident = clusterer(listOf(marker("a", 0.5), marker("b", 0.5)))
        assertNull(coincident.splittingScale(listOf("a", "b"), camera, 1f))
        val close = clusterer(listOf(marker("a", 0.5), marker("b", 0.5001)))
        assertNull(close.splittingScale(listOf("a", "b"), camera.copy(maxScale = 2.0), 1f))
        assertNull(close.splittingScale(listOf("a", "b"), camera.copy(zoomEnabled = false), 1f))
    }

    @Test fun disablingClusteringAndDraftingPreserveAllCatalogObjects() {
        val values = listOf(marker("a", 0.5), marker("b", 0.5))
        val state = AnnotationEditorState(catalog = values, catalogReady = true, clusteringEnabled = false)
        assertFalse(state.renderData().cluster)
        assertEquals(2, annotationOverlays(state, pyramid).markers.size)
        val draft = state.copy(clusteringEnabled = true, draft = values.first())
        assertFalse(draft.renderData().cluster)
        val overlays = annotationOverlays(draft, pyramid)
        assertEquals(3, overlays.markers.size)
        assertEquals(1, overlays.targets.values.filterIsInstance<AnnotationHit.Vertex>().size)
    }

    @Test fun overlayIdsCannotCollideWithDomainIdsOrAmbiguousMemberLists() {
        assertNotEquals(clusterOverlayId(listOf("a", "bc")), clusterOverlayId(listOf("ab", "c")))
        assertNotEquals(clusterOverlayId(listOf("a\u0000b", "c")), clusterOverlayId(listOf("a", "b\u0000c")))
        val value = marker("cluster:2:aa", 0.5)
        val overlays = annotationOverlays(AnnotationEditorState(items = listOf(value)), pyramid)
        assertEquals(AnnotationHit.Object(value.id), overlays.targets.values.single())
    }

    private fun point(x: Double, y: Double) = pyramid.coordinateAt(MapPoint(x, y))!!
}
