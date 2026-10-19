/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation

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

    @Test fun clickOn26FindsAScaleThatActuallySplitsOriginalGroup() {
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
        val overlays = engine.render(groups)
        val representedIds = overlays.targets.values.flatMap {
            when (it) {
                is AnnotationHit.Object -> listOf(it.id)
                is AnnotationHit.Cluster -> it.ids
                is AnnotationHit.Vertex -> error("Unexpected draft vertex")
            }
        }
        assertEquals(ids.sorted(), representedIds.sorted())
        assertTrue(overlays.markers.size >= 2)
    }

    @Test fun longChainsAreSplitIntoCompactGroupsIndependentOfPan() {
        val values = (0..16).map { marker("p$it", 0.05 + it * 0.055) }
        val engine = clusterer(values)
        val groups = engine.groups(camera, 1f)
        assertTrue(groups.size > 1)
        assertEquals(values.map { it.id }.sorted(), groups.flatMap { it.ids }.sorted())
        for (group in groups) {
            val span = group.members.maxOf { it.anchor.x } - group.members.minOf { it.anchor.x }
            assertTrue(span * 1024 <= 64.0)
        }
        val original = engine.render(groups)
        for (window in listOf(MapWindow(0.0, 0.4, 0.2, 0.6), MapWindow(0.8, 0.4, 1.0, 0.6))) {
            val panned = camera.copy(visibleWindow = window)
            val next = engine.groups(panned, 1f)
            assertEquals(groups.map { it.ids }, next.map { it.ids })
            assertEquals(original, engine.render(next))
        }
    }

    @Test fun clusterPositionStaysFixedAcrossEveryViewportEdge() {
        val engine = clusterer(listOf(marker("a", 0.49, 0.49), marker("b", 0.51, 0.51)))
        val expected = engine.render(engine.groups(camera, 1f)).markers.single()
        assertEquals(0.5, expected.position.x, 1e-9)
        assertEquals(0.5, expected.position.y, 1e-9)
        val windows = listOf(
            MapWindow(0.5, 0.4, 0.7, 0.6), MapWindow(0.3, 0.4, 0.5, 0.6),
            MapWindow(0.4, 0.5, 0.6, 0.7), MapWindow(0.4, 0.3, 0.6, 0.5),
            MapWindow(0.8, 0.8, 1.0, 1.0),
        )
        for (window in windows) {
            val panned = camera.copy(visibleWindow = window)
            assertEquals(expected, engine.render(engine.groups(panned, 1f)).markers.single())
        }
    }

    @Test fun densityAndInputOrderDoNotChangeGroups() {
        val values = listOf(marker("a", 0.3), marker("b", 0.35), marker("c", 0.7))
        val expected = clusterer(values).groups(camera, 1f).map { it.ids }
        val highDensity = camera.copy(layout = IntSize(3072, 3072), fitScale = 0.375)
        assertEquals(expected, clusterer(values.reversed()).groups(highDensity, 3f).map { it.ids })
    }

    @Test fun edgeClusterKeepsItsGeographicCenterAndFocusesAnActualMember() {
        val values = listOf(marker("a", 0.0001), marker("b", 0.0002))
        val engine = clusterer(values)
        val badge = engine.render(engine.groups(camera, 1f)).markers.single().position
        assertEquals(0.00015, badge.x, 1e-9)
        val focus = engine.focus(values.map { it.id }, badge, camera)
        assertTrue(focus.x < 0.001)
        assertTrue(focus in values.map { pyramid.positionOf(it.coordinates.single()) })
    }

    @Test fun distantEndsOfAChainDoNotClusterAndSelectedObjectIsExempt() {
        val values = listOf(marker("a", 0.3), marker("b", 0.3 + 50.0 / 1024), marker("c", 0.3 + 100.0 / 1024))
        assertEquals(listOf(1, 2), clusterer(values).groups(camera, 1f).map { it.ids.size })
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
        val overlays = engine.render(engine.groups(zoomed, 1f))
        assertEquals(2, overlays.paths.size)
        val polygon = overlays.paths.single { it.filled }
        assertEquals(polygon.points.first(), polygon.points.last())
        assertEquals(4, polygon.points.size)
    }

    @Test fun zoomOnlySubdividesGroupsAndEveryNoncoincidentClusterCanExpand() {
        val values = (0 until 80).map { index ->
            marker("p$index", 0.2 + (index % 10) * 0.007, 0.3 + (index / 10) * 0.009)
        } + Annotation("line", AnnotationKind.LINE, listOf(point(0.24, 0.32), point(0.25, 0.33)))
        val engine = clusterer(values)
        var previous = engine.groups(camera.copy(viewport = MapViewport(scale = 0.5)), 1f)
        for (scale in listOf(1.0, 1.5, 2.0, 4.0, 8.0, 16.0, 64.0)) {
            val currentCamera = camera.copy(viewport = MapViewport(scale = scale))
            val groups = engine.groups(currentCamera, 1f)
            assertEquals(values.map { it.id }.sorted(), groups.flatMap { it.ids }.sorted())
            for (group in groups) {
                assertTrue(previous.any { it.ids.containsAll(group.ids) })
                if (group.ids.size > 1) {
                    val split = assertNotNull(engine.splittingScale(group.ids, currentCamera, 1f))
                    assertTrue(split > scale && split <= camera.maxScale)
                    assertTrue(engine.groups(currentCamera.copy(viewport = MapViewport(scale = split)), 1f)
                        .none { it.ids.containsAll(group.ids) })
                }
            }
            previous = groups
        }
    }

    @Test fun groupExtentIncludesShapeGeometryAndBothAxes() {
        val values = listOf(
            marker("a", 0.4, 0.4), marker("b", 0.4 + 50.0 / 1024, 0.4 + 50.0 / 1024),
            Annotation("line", AnnotationKind.LINE, listOf(point(0.39, 0.4), point(0.39, 0.46))),
        )
        val engine = clusterer(values)
        for (group in engine.groups(camera, 1f).filter { it.ids.size > 1 }) {
            val width = group.members.maxOf { it.bounds.right } - group.members.minOf { it.bounds.left }
            val height = group.members.maxOf { it.bounds.bottom } - group.members.minOf { it.bounds.top }
            assertTrue(kotlin.math.hypot(width * 1024, height * 1024) <= 64.0)
        }
        assertTrue(engine.groups(camera, 1f).size > 1)
    }

    @Test fun rectangularMapsUsePhysicalAspectRatioForCompactness() {
        val rectangle = TilePyramid(ZoomRange(1, 6), columns = 2, rows = 1)
        val rectangularCamera = camera.copy(pyramid = rectangle)
        fun value(id: String, x: Double, y: Double) = Annotation(id, AnnotationKind.MARKER,
            listOf(rectangle.coordinateAt(MapPoint(x, y))!!))
        val horizontal = AnnotationClusterer(listOf(value("a", 0.4, 0.4), value("b", 0.44, 0.4)),
            rectangle, AnnotationKind.entries.toSet(), null)
        val vertical = AnnotationClusterer(listOf(value("a", 0.4, 0.4), value("b", 0.4, 0.44)),
            rectangle, AnnotationKind.entries.toSet(), null)
        assertEquals(listOf(1, 1), horizontal.groups(rectangularCamera, 1f).map { it.ids.size })
        assertEquals(listOf(2), vertical.groups(rectangularCamera, 1f).map { it.ids.size })
    }

    @Test fun spatialPartitionsDoNotSeparateCoincidentMarkers() {
        val coincident = (0 until 6).map { marker("middle$it", 0.5) }
        val values = (0 until 5).map { marker("left$it", 0.1) } + coincident +
            (0 until 5).map { marker("right$it", 0.9) }
        val engine = clusterer(values)
        val zoomed = camera.copy(viewport = MapViewport(scale = camera.maxScale))
        val group = engine.groups(zoomed, 1f).single { "middle0" in it.ids }
        assertEquals(coincident.map { it.id }, group.ids)
        assertNull(engine.splittingScale(group.ids, camera, 1f))
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
