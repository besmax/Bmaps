/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import kotlin.math.*

internal data class NavigationEvent(val kind: ActivityEventKind, val relatedId: String? = null)
internal data class NavigationUpdate(val events: List<NavigationEvent> = emptyList())

internal class RouteNavigator(
    private val index: RouteNavigationIndex,
    initial: ActivityNavigationState,
    private val policy: RouteNavigationPolicy,
) {
    var state = initial
        private set
    var progress = unavailable()
        private set
    private val controls = index.route.controlPoints.sortedBy { directed(index.measure(it.position)) }
    private val controlIds = controls.map { it.id }.toSet()
    private val passedControls = mutableSetOf<String>()
    private var lastInput: ActivityTime? = null
    private var lastPathSegment: String? = null
    private var confirmations = 0
    private var proposedMeasure: Double? = null
    private var offSince: ActivityTime? = null
    private var rejoinSince: ActivityTime? = null
    private var endSince: ActivityTime? = null
    private var previouslyMatched: Double? = null

    fun restoreControlPoint(id: String) { if (id in controlIds) passedControls += id }

    fun suspendPosition() {
        confirmations = 0; proposedMeasure = null; lastInput = null; previouslyMatched = null
        offSince = null; rejoinSince = null; endSince = null
        progress = unavailable()
    }

    fun reanchor(next: ActivityNavigationState) {
        state = next
        suspendPosition()
    }

    fun update(sample: TelemetrySample, now: ActivityTime, speed: Double?, course: Double?): NavigationUpdate {
        val measurement = sample.measurement as? PositionMeasurement
        val age = activityTimeDelta(sample.acquiredAt, now)
        if (measurement == null || measurement.sourceConflict || !measurement.horizontalAccuracyMeters.isFinite() ||
            measurement.horizontalAccuracyMeters !in 0.0..policy.onRouteMeters ||
            !measurement.coordinate.latitude.isFinite() || measurement.coordinate.latitude !in -90.0..90.0 ||
            !measurement.coordinate.longitude.isFinite() || measurement.coordinate.longitude !in -180.0..180.0 ||
            age == null || age !in 0..ActivityQualityPolicy().maxPositionAgeMillis) {
            suspendPosition()
            return NavigationUpdate()
        }
        val delta = lastInput?.let { activityTimeDelta(it, sample.acquiredAt) }
        if (lastInput != null && delta == null) suspendPosition()
        if (delta != null && delta <= 0) return NavigationUpdate()
        if (delta != null && delta > ActivityQualityPolicy().maxPositionAgeMillis ||
            lastPathSegment != null && lastPathSegment != sample.pathSegmentId) suspendPosition()
        lastInput = sample.acquiredAt; lastPathSegment = sample.pathSegmentId
        val segment = state.checkpoint.segmentIndex
        if (state.gapAnnouncedSegmentId == index.route.segments[segment].id) {
            progress = matchedProgress(state.checkpoint, RouteMatchQuality.SEGMENT_GAP, null, endReached = false).copy(position = null)
            return NavigationUpdate()
        }
        val anchor = directed(index.measure(state.checkpoint))
        val seconds = (delta?.coerceIn(0, 5_000) ?: 0) / 1_000.0
        val forward = policy.forwardWindowMeters + policy.maximumSpeedMetersPerSecond * seconds
        val rawA = undirected((anchor - policy.backwardWindowMeters).coerceAtLeast(0.0))
        val rawB = undirected((anchor + forward).coerceAtMost(index.totalMeters))
        val local = index.search(measurement.coordinate, policy.searchRadiusMeters, policy, segment, min(rawA, rawB), max(rawA, rawB))
        if (local.exhausted) return uncertain(RouteUncertainty.SEARCH_LIMIT)
        val usableCourse = course?.takeIf { it.isFinite() && it in 0.0..<360.0 && (speed ?: 0.0) >= 1.0 }
        fun heading(candidate: RouteCandidate) = if (state.direction == RouteDirection.FORWARD) candidate.course else (candidate.course + 180) % 360
        fun angle(candidate: RouteCandidate): Double = usableCourse?.let { abs((heading(candidate) - it + 540) % 360 - 180) } ?: 0.0
        fun score(candidate: RouteCandidate): Double {
            val advance = directed(candidate.measure) - anchor
            val expected = (speed?.takeIf { it.isFinite() && it >= 0 } ?: 0.0) * seconds
            return candidate.distance + abs(advance - expected) * 0.12 + angle(candidate) / 180 * 35
        }
        val best = local.candidates.minByOrNull(::score)
        if (best == null) {
            val elsewhere = index.search(measurement.coordinate, policy.searchRadiusMeters, policy)
            if (elsewhere.exhausted) return uncertain(RouteUncertainty.SEARCH_LIMIT)
            if (elsewhere.candidates.isNotEmpty()) return uncertain(RouteUncertainty.CONTINUITY_LOST)
            return offRoute(now, null)
        }
        if (best.distance > policy.offRouteMeters + measurement.horizontalAccuracyMeters * 2) return offRoute(now, best.distance)
        if (best.distance > policy.onRouteMeters + measurement.horizontalAccuracyMeters) return uncertain(RouteUncertainty.POSITION_QUALITY)
        if (usableCourse != null && angle(best) > 100) return uncertain(RouteUncertainty.WRONG_DIRECTION)
        val ambiguity = max(5.0, measurement.horizontalAccuracyMeters / 2)
        if (local.candidates.any { abs(it.measure - best.measure) > 25 && score(it) <= score(best) + ambiguity })
            return uncertain(RouteUncertainty.AMBIGUOUS_SECTION)
        val candidateMeasure = directed(best.measure)
        val priorProposal = proposedMeasure
        confirmations = if (priorProposal == null || abs(candidateMeasure - priorProposal) <= forward + policy.backwardWindowMeters) confirmations + 1 else 1
        proposedMeasure = candidateMeasure
        if (confirmations < policy.confirmationFixes) return uncertain(RouteUncertainty.ACQUIRING, resetConfirmation = false)
        offSince = null
        val emitted = mutableListOf<NavigationEvent>()
        if (state.deviated) {
            if (rejoinSince == null) rejoinSince = now
            if ((activityTimeDelta(rejoinSince!!, now) ?: 0) >= policy.rejoinHoldMillis) {
                state = state.copy(deviated = false)
                emitted += NavigationEvent(ActivityEventKind.ROUTE_REJOINED)
                rejoinSince = null
            }
        }
        val previous = previouslyMatched
        previouslyMatched = candidateMeasure
        state = state.copy(checkpoint = best.position)
        val origin = directed(index.measure(state.origin))
        for (point in controls) {
            if (emitted.size >= 8) break
            val at = directed(index.measure(point.position))
            if (point.id !in passedControls && point.position.segmentIndex == segment && at >= origin &&
                (abs(at - candidateMeasure) <= policy.endpointMeters || previous != null && at in previous..candidateMeasure)) {
                passedControls += point.id
                emitted += NavigationEvent(ActivityEventKind.CONTROL_POINT, point.id)
            }
        }
        val legRemaining = legRemaining(best.position)
        val nextSegment = segment + if (state.direction == RouteDirection.FORWARD) 1 else -1
        val gap = legRemaining <= policy.endpointMeters && nextSegment in index.route.segments.indices
        var end = false
        if (gap) {
            state = state.copy(gapAnnouncedSegmentId = index.route.segments[segment].id)
            emitted += NavigationEvent(ActivityEventKind.ROUTE_GAP, index.route.segments[segment].id)
            endSince = null
        } else if (nextSegment !in index.route.segments.indices && legRemaining <= policy.endpointMeters) {
            if (endSince == null) endSince = now
            end = (activityTimeDelta(endSince!!, now) ?: 0) >= policy.rejoinHoldMillis
            if (end && !state.endAnnounced) { state = state.copy(endAnnounced = true); emitted += NavigationEvent(ActivityEventKind.ROUTE_END) }
        } else endSince = null
        progress = matchedProgress(best.position, if (gap) RouteMatchQuality.SEGMENT_GAP else RouteMatchQuality.MATCHED, best.distance, end)
        return NavigationUpdate(emitted)
    }

    private fun offRoute(now: ActivityTime, distance: Double?): NavigationUpdate {
        confirmations = 0; proposedMeasure = null; previouslyMatched = null; rejoinSince = null; endSince = null
        if (offSince == null) offSince = now
        val emitted = mutableListOf<NavigationEvent>()
        if ((activityTimeDelta(offSince!!, now) ?: 0) >= policy.deviationHoldMillis) {
            if (!state.deviated) {
                state = state.copy(deviated = true, lastDeviationAlertAt = now)
                emitted += NavigationEvent(ActivityEventKind.ROUTE_DEVIATED)
            } else if (state.alertsEnabled && (state.lastDeviationAlertAt?.let { activityTimeDelta(it, now) }
                    ?: (activityTimeDelta(offSince!!, now) ?: 0)) >= policy.repeatAlertMillis) {
                state = state.copy(lastDeviationAlertAt = now)
                emitted += NavigationEvent(ActivityEventKind.ROUTE_DEVIATION_REMINDER)
            }
        }
        progress = RouteProgress(RouteMatchQuality.OFF_ROUTE, deviationMeters = distance, plannedDistanceMeters = plannedMeters())
        return NavigationUpdate(emitted)
    }

    private fun uncertain(reason: RouteUncertainty, resetConfirmation: Boolean = true): NavigationUpdate {
        if (resetConfirmation) { confirmations = 0; proposedMeasure = null }
        offSince = null; rejoinSince = null; endSince = null; previouslyMatched = null
        progress = RouteProgress(RouteMatchQuality.UNCERTAIN, plannedDistanceMeters = plannedMeters(), uncertainty = reason)
        return NavigationUpdate()
    }

    private fun unavailable() = RouteProgress(RouteMatchQuality.POSITION_UNAVAILABLE,
        plannedDistanceMeters = plannedMeters(), uncertainty = RouteUncertainty.POSITION_QUALITY)
    private fun plannedMeters() = index.totalMeters - directed(index.measure(state.origin))
    private fun directed(value: Double) = if (state.direction == RouteDirection.FORWARD) value else index.totalMeters - value
    private fun undirected(value: Double) = if (state.direction == RouteDirection.FORWARD) value else index.totalMeters - value
    private fun legRemaining(position: RoutePosition): Double = if (state.direction == RouteDirection.FORWARD)
        index.segmentEnds[position.segmentIndex] - index.measure(position) else index.measure(position) - index.segmentStarts[position.segmentIndex]

    private fun matchedProgress(position: RoutePosition, quality: RouteMatchQuality, deviation: Double?, endReached: Boolean): RouteProgress {
        val measure = directed(index.measure(position))
        val nextControl = controls.firstOrNull { it.id !in passedControls && directed(index.measure(it.position)) >= max(measure, directed(index.measure(state.origin))) - policy.endpointMeters }
        val segment = position.segmentIndex
        val next = segment + if (state.direction == RouteDirection.FORWARD) 1 else -1
        val gap = if (quality == RouteMatchQuality.SEGMENT_GAP && next in index.route.segments.indices) {
            val from = index.route.segments[segment]; val to = index.route.segments[next]
            RouteGap(from.id, to.id,
                (if (state.direction == RouteDirection.FORWARD) from.vertices.last() else from.vertices.first()).coordinate,
                (if (state.direction == RouteDirection.FORWARD) to.vertices.first() else to.vertices.last()).coordinate)
        } else null
        return RouteProgress(quality, position, (measure - directed(index.measure(state.origin))).coerceAtLeast(0.0),
            index.totalMeters - measure, deviation, nextControl?.id, plannedMeters(), legRemaining(position).coerceAtLeast(0.0), gap, endReached)
    }
}
