/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlin.test.*

class RouteNavigatorTest {
    @Test fun forwardAndReverseIntermediateStartsKeepKnownDistanceSeparate() {
        val route = line(0.0, 0.001, 0.002, 0.003)
        val start = RoutePosition(0, 1, 0.5)
        val forward = navigator(route, start)
        forward.fix(0, 0.0015)
        forward.fix(1, 0.0016)
        assertEquals(RouteMatchQuality.MATCHED, forward.progress.quality)
        assertTrue(forward.progress.completedRouteMeters!! in 10.0..12.0)
        assertTrue(forward.progress.plannedDistanceMeters!! in 166.0..168.0)
        val reverse = navigator(route, start, RouteDirection.REVERSE)
        reverse.fix(0, 0.0015, course = 270.0)
        reverse.fix(1, 0.0014, course = 270.0)
        assertEquals(RouteMatchQuality.MATCHED, reverse.progress.quality)
        assertTrue(reverse.progress.completedRouteMeters!! in 10.0..12.0)
        assertTrue(reverse.progress.remainingRouteMeters!! in 155.0..157.0)
    }

    @Test fun intersectionDoesNotJumpToTheLaterCrossing() {
        val route = shape(listOf(point(-0.001, -0.001), point(0.001, 0.001), point(-0.001, 0.001), point(0.001, -0.001), point(0.002, 0.0)))
        val nav = navigator(route, RoutePosition(0, 0, 0.4))
        nav.fix(0, -0.0002, -0.0002, course = 45.0)
        nav.fix(1, -0.0001, -0.0001, course = 45.0)
        nav.fix(2, 0.0, 0.0, course = 45.0)
        assertEquals(RouteMatchQuality.MATCHED, nav.progress.quality)
        assertEquals(0, nav.progress.position?.edgeIndex)
        assertTrue(nav.progress.remainingRouteMeters!! > 500)
    }

    @Test fun closedLoopStartIsNotItsFinish() {
        val route = shape(listOf(point(0.0, 0.0), point(0.0, 0.001), point(0.001, 0.001), point(0.001, 0.0), point(0.0, 0.0)))
        val nav = navigator(route)
        nav.fix(0, 0.0)
        nav.fix(1, 0.00005)
        for (second in 2..6) nav.fix(second, 0.0)
        assertEquals(0, nav.progress.position?.edgeIndex)
        assertFalse(nav.progress.endReached)
        assertTrue(nav.progress.remainingRouteMeters!! > 400)
    }

    @Test fun sharedReturnSectionUsesOrderAndExplicitCorrection() {
        val route = line(0.0, 0.001, 0.0)
        val nav = navigator(route)
        nav.fix(0, 0.0001)
        nav.fix(1, 0.0002)
        assertEquals(0, nav.progress.position?.edgeIndex)
        nav.reanchor(nav.state.copy(checkpoint = RoutePosition(0, 1, 0.8), revision = 1))
        nav.fix(2, 0.0002, course = 270.0)
        nav.fix(3, 0.00018, course = 270.0)
        assertEquals(1, nav.progress.position?.edgeIndex)
        assertTrue(nav.progress.remainingRouteMeters!! < 25)
    }

    @Test fun movementAgainstDirectionIsUncertainUntilReliableReacquisition() {
        val nav = navigator(line(0.0, 0.001))
        nav.fix(0, 0.0001, course = 270.0)
        assertEquals(RouteUncertainty.WRONG_DIRECTION, nav.progress.uncertainty)
        nav.fix(1, 0.0001, course = 90.0)
        nav.fix(2, 0.0002, course = 90.0)
        assertEquals(RouteMatchQuality.MATCHED, nav.progress.quality)
    }

    @Test fun parallelTrailDoesNotReplaceTheExpectedLeg() {
        val route = shape(listOf(point(0.0, 0.0), point(0.0, 0.001), point(0.00009, 0.001), point(0.00009, 0.0)))
        val nav = navigator(route)
        nav.fix(0, 0.0001)
        nav.fix(1, 0.0002)
        nav.fix(2, 0.0003, latitude = 0.00008)
        assertEquals(RouteMatchQuality.MATCHED, nav.progress.quality)
        assertEquals(0, nav.progress.position?.edgeIndex)
        assertTrue(nav.progress.completedRouteMeters!! < 40)
    }

    @Test fun reachableOverlappingSectionsAreUncertain() {
        val route = shape(listOf(point(0.0, 0.0), point(0.0, 0.00009), point(0.00009, 0.00009), point(0.00009, 0.0), point(0.0, 0.0)))
        val nav = navigator(route)
        nav.fix(0, 0.0, course = null)
        nav.fix(1, 0.0, course = null)
        assertEquals(RouteMatchQuality.UNCERTAIN, nav.progress.quality)
        assertEquals(RouteUncertainty.AMBIGUOUS_SECTION, nav.progress.uncertainty)
        assertNull(nav.progress.position)
        assertEquals(RoutePosition(0, 0, 0.0), nav.state.checkpoint)
    }

    @Test fun deviationRequiresSustainedQualityAndRepeatsAreThrottled() {
        val nav = navigator(line(0.0, 0.003))
        nav.fix(0, 0.0001)
        nav.fix(1, 0.0002)
        for (second in 2..8) assertTrue(nav.fix(second, 0.0003, latitude = 0.001).events.isEmpty())
        nav.fix(9, 0.0003, latitude = 0.001, conflict = true)
        assertEquals(RouteMatchQuality.POSITION_UNAVAILABLE, nav.progress.quality)
        for (second in 10..17) assertTrue(nav.fix(second, 0.0003, latitude = 0.001).events.isEmpty())
        assertEquals(listOf(ActivityEventKind.ROUTE_DEVIATED), nav.fix(18, 0.0003, latitude = 0.001).events.map { it.kind })
        for (second in 19..77) assertTrue(nav.fix(second, 0.0003, latitude = 0.001).events.isEmpty())
        assertEquals(listOf(ActivityEventKind.ROUTE_DEVIATION_REMINDER), nav.fix(78, 0.0003, latitude = 0.001).events.map { it.kind })
        val rejoin = (79..85).flatMap { nav.fix(it, 0.0003).events }
        assertEquals(1, rejoin.count { it.kind == ActivityEventKind.ROUTE_REJOINED })
        assertFalse(nav.state.deviated)
    }

    @Test fun poorAccuracyPauseAndSourceGapsResetConfirmationAndAlerts() {
        val nav = navigator(line(0.0, 0.003))
        nav.fix(0, 0.0001); nav.fix(1, 0.0002)
        nav.fix(2, 0.0003, accuracy = 50.0)
        assertEquals(RouteMatchQuality.POSITION_UNAVAILABLE, nav.progress.quality)
        nav.fix(3, 0.0003)
        assertEquals(RouteUncertainty.ACQUIRING, nav.progress.uncertainty)
        nav.suspendPosition()
        nav.fix(4, 0.0003)
        assertEquals(RouteUncertainty.ACQUIRING, nav.progress.uncertainty)
        nav.fix(5, 0.0003)
        nav.fix(20, 0.0003)
        assertEquals(RouteUncertainty.ACQUIRING, nav.progress.uncertainty)
        assertFalse(nav.state.deviated)
    }

    @Test fun segmentGapRequiresAnExplicitSectionChoiceAndExcludesConnectorDistance() {
        val route = ActivityRoute("gap-route", 0, "Gap", listOf(segment("a", 0.0, 0.001), segment("b", 0.002, 0.003)))
        val nav = navigator(route, RoutePosition(0, 0, 0.95))
        nav.fix(0, 0.00095)
        val reached = nav.fix(1, 0.00098)
        assertEquals(RouteMatchQuality.SEGMENT_GAP, nav.progress.quality)
        assertEquals(1, reached.events.count { it.kind == ActivityEventKind.ROUTE_GAP })
        assertTrue(nav.progress.plannedDistanceMeters!! in 116.0..118.0)
        assertEquals("b", nav.progress.gap?.toSegmentId)
        assertTrue(nav.fix(2, 0.0021).events.isEmpty())
        assertEquals(RouteMatchQuality.SEGMENT_GAP, nav.progress.quality)
        nav.reanchor(nav.state.copy(checkpoint = RoutePosition(1, 0, 0.1), revision = 1, gapAnnouncedSegmentId = null))
        nav.fix(3, 0.0021); nav.fix(4, 0.00211)
        assertEquals(RouteMatchQuality.MATCHED, nav.progress.quality)
        assertEquals(1, nav.progress.position?.segmentIndex)
    }

    @Test fun controlPointsAndEndEmitDistinctTransitionsWithoutAutoFinish() {
        val route = line(0.0, 0.0002).copy(controlPoints = listOf(RouteControlPoint("cp", "Point", RoutePosition(0, 0, 0.5))))
        val nav = navigator(route)
        nav.fix(0, 0.0001)
        val controls = nav.fix(1, 0.0001).events
        assertEquals(1, controls.count { it.kind == ActivityEventKind.CONTROL_POINT })
        assertTrue(nav.fix(2, 0.0001).events.isEmpty())
        val end = (3..8).flatMap { nav.fix(it, 0.0002).events }
        assertEquals(1, end.count { it.kind == ActivityEventKind.ROUTE_END })
        assertTrue(nav.progress.endReached)
    }

    @Test fun sphericalIndexHandlesDatelinePolarAndLongEdges() {
        val policy = RouteNavigationPolicy.forProfile(ActivityProfile.HIKING)
        val dateline = RouteNavigationIndex(line(179.999, -179.999))
        val candidate = dateline.search(point(0.0, 180.0), 100.0, policy).candidates.minBy { it.distance }
        assertTrue(candidate.distance < 0.01)
        assertTrue(candidate.position.fraction in 0.49..0.51)
        assertTrue(dateline.totalMeters in 222.0..223.0)
        val polar = RouteNavigationIndex(shape(listOf(point(89.999, 0.0), point(89.999, 90.0))))
        assertTrue(polar.search(point(89.9992928932, 45.0), 10.0, policy).candidates.minOf { it.distance } < 0.01)
        val long = RouteNavigationIndex(line(-30.0, 30.0))
        assertTrue(long.search(point(0.0, 0.0), 10.0, policy).candidates.minOf { it.distance } < 0.01)
        assertFailsWith<IllegalArgumentException> { RouteNavigationIndex(line(0.0, 180.0)) }
    }

    @Test fun detailedRouteIsIndexedAndSearchBudgetReportsUncertainty() {
        val route = shape(List(200_000) { point(0.0, it * 0.00000135) })
        val index = RouteNavigationIndex(route)
        val at = RoutePosition(0, 10_000, 0.0)
        val policy = RouteNavigationPolicy.forProfile(ActivityProfile.HIKING)
        val meter = index.measure(at)
        val search = index.search(point(0.0, 0.0135), 200.0, policy, 0, meter - 15, meter + 35)
        assertFalse(search.exhausted)
        assertTrue(search.candidates.minOf { it.distance } < 0.01)
        val nav = RouteNavigator(index, state(route, at), policy.copy(maxProjectedEdges = 1))
        nav.fix(0, 0.0135)
        assertEquals(RouteUncertainty.SEARCH_LIMIT, nav.progress.uncertainty)
    }

    private fun navigator(route: ActivityRoute, start: RoutePosition = RoutePosition(0, 0, 0.0), direction: RouteDirection = RouteDirection.FORWARD) =
        RouteNavigator(RouteNavigationIndex(route), state(route, start, direction), RouteNavigationPolicy.forProfile(ActivityProfile.HIKING))
    private fun state(route: ActivityRoute, start: RoutePosition, direction: RouteDirection = RouteDirection.FORWARD) =
        ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE, route.id, route.revision, direction, start, start)
    private fun line(vararg longitude: Double) = shape(longitude.map { point(0.0, it) })
    private fun shape(points: List<GeographicCoordinate>) = ActivityRoute("route", 0, "Route", listOf(RouteSegment("segment", points.map { RouteVertex(it) })))
    private fun segment(id: String, vararg longitude: Double) = RouteSegment(id, longitude.map { RouteVertex(point(0.0, it)) })
    private fun point(latitude: Double, longitude: Double) = GeographicCoordinate(latitude, longitude)
    private fun RouteNavigator.fix(second: Int, longitude: Double, latitude: Double = 0.0, accuracy: Double = 3.0,
        conflict: Boolean = false, course: Double? = 90.0): NavigationUpdate {
        val time = ActivityTime(second * 1_000L, second * 1_000L, "clock")
        return update(TelemetrySample("p-$second", MeasurementSource("gps", MeasurementSourceKind.PHONE_LOCATION), time, time,
            MeasurementPhase.RECORDING, PositionMeasurement(point(latitude, longitude), accuracy, conflict), "path"), time, if (course == null) 0.0 else 2.0, course)
    }
}
