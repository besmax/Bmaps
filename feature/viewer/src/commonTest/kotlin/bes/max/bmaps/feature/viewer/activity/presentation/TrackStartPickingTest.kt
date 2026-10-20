/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.compose.ui.unit.IntSize
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.RouteSegment
import bes.max.bmaps.domain.activity.model.RouteVertex
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class TrackStartPickingTest {
    private val camera = MapCameraSnapshot(1, TilePyramid(ZoomRange(0, 0)), MapViewport(),
        MapWindow(0.0, 0.0, 1.0, 1.0), IntSize(1000, 1000), 1.0, 16.0)

    private fun segment(id: String, vararg points: MapPoint) = RouteSegment(id,
        points.map { RouteVertex(requireNotNull(WebMercator.geographic(it))) })

    @Test fun selectsInteriorFractionAndRejectsDistantTap() = runTest {
        val route = ActivityRoute("route", 0, "Route", listOf(segment("line", MapPoint(0.2, 0.5), MapPoint(0.8, 0.5))))
        val pick = pickRouteStart(route, MapPoint(0.35, 0.51), camera, 24.0).single()
        assertEquals(0.25, pick.fraction, 0.00001)
        assertEquals(-54.0, pick.coordinate.longitude, 0.00001)
        assertTrue(pick.distance > 0)
        assertTrue(pickRouteStart(route, MapPoint(0.35, 0.55), camera, 24.0).isEmpty())
    }

    @Test fun crossingHasDistinctPassagesAndGapIsNotSelectable() = runTest {
        val route = ActivityRoute("route", 0, "Route", listOf(
            segment("horizontal", MapPoint(0.2, 0.5), MapPoint(0.8, 0.5)),
            segment("vertical", MapPoint(0.5, 0.2), MapPoint(0.5, 0.8)),
        ))
        val picks = pickRouteStart(route, MapPoint(0.5, 0.5), camera, 24.0)
        assertEquals(setOf(0, 1), picks.map { it.segment }.toSet())
        assertTrue(picks[1].distance > picks[0].distance)
        assertTrue(pickRouteStart(route, MapPoint(0.65, 0.35), camera, 24.0).isEmpty())
    }

    @Test fun sharedVertexDoesNotProduceTwoChoices() = runTest {
        val route = ActivityRoute("route", 0, "Route", listOf(segment("line",
            MapPoint(0.2, 0.5), MapPoint(0.5, 0.5), MapPoint(0.8, 0.5))))
        assertEquals(1, pickRouteStart(route, MapPoint(0.5, 0.5), camera, 24.0).size)
    }
}
