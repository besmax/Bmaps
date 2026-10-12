/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.mapengine.GeographicCoordinate

object ActivityValidation {
    fun route(value: ActivityRoute): String? {
        if (value.schemaVersion != 1) return "schemaVersion"
        if (!id(value.id) || value.revision < 0 || value.name.length > 120 || value.description.length > 4000) return "routeProperties"
        if (value.segments.isEmpty() || value.segments.size > ActivityLimits.MAX_ROUTE_SEGMENTS) return "segments"
        if (value.segments.sumOf { it.vertices.size.toLong() } > ActivityLimits.MAX_ROUTE_VERTICES) return "vertices"
        if (value.segments.map { it.id }.distinct().size != value.segments.size) return "segmentIds"
        for (segment in value.segments) {
            if (!id(segment.id) || segment.vertices.size < 2) return "segment"
            if (segment.vertices.any { !coordinate(it.coordinate) || it.elevation?.let(::measurement) != null ||
                it.sourceTimeEpochMillis?.let { time -> time < 0 } == true }) return "vertex"
            if (segment.vertices.zipWithNext().any { (a, b) -> a.coordinate == b.coordinate }) return "edge"
        }
        if (value.controlPoints.size > ActivityLimits.MAX_CONTROL_POINTS ||
            value.controlPoints.map { it.id }.distinct().size != value.controlPoints.size) return "controlPoints"
        if (value.controlPoints.any { !id(it.id) || it.name.length > 120 || !position(value, it.position) }) return "controlPoint"
        return null
    }

    fun start(value: ActivityStartRequest): String? {
        if (!id(value.id) || value.name.length > 120) return "activityProperties"
        return value.route?.let(::selection)
    }

    fun selection(value: RouteSelection): String? = route(value.snapshot)
        ?: if (position(value.snapshot, value.start)) null else "startPosition"

    fun record(value: ActivityRecord): String? = recordMetadata(value) ?: value.route?.let(::selection)
        ?: value.navigation?.takeIf { it.mode == ActivityNavigationMode.PLANNED_ROUTE }?.let {
            value.route?.let { selection ->
                if (it.direction != selection.direction || it.origin != selection.start) "navigationSelection" else navigation(it, selection.snapshot)
            } ?: "navigationRoute"
        }

    fun validRoutePosition(route: ActivityRoute, value: RoutePosition): Boolean = position(route, value)

    fun navigationCommand(value: ActivityNavigationCommand): String? {
        if (!id(value.id) || !id(value.activityId) || !id(value.sessionGeneration) || value.expectedNavigationRevision < 0) return "navigationCommand"
        return when (value.action) {
            ActivityNavigationAction.CORRECT_POSITION -> if (value.position == null || value.alertsEnabled != null) "navigationPosition" else null
            ActivityNavigationAction.SET_ALERTS_ENABLED -> if (value.position != null || value.alertsEnabled == null) "navigationAlerts" else null
            ActivityNavigationAction.RETURN_ALONG_RECORDING, ActivityNavigationAction.FOLLOW_PLANNED -> if (value.position != null || value.alertsEnabled != null) "navigationArguments" else null
        }
    }

    fun navigation(value: ActivityNavigationState, route: ActivityRoute): String? = navigationMetadata(value)
        ?: if (value.routeId != route.id || value.routeRevision != route.revision || !position(route, value.origin) || !position(route, value.checkpoint) ||
            value.gapAnnouncedSegmentId?.let { route.segments[value.checkpoint.segmentIndex].id != it } == true) "navigationRoute" else null

    private fun navigationMetadata(value: ActivityNavigationState): String? = if (
        !id(value.routeId) || value.routeRevision < 0 || value.revision < 0 ||
        listOf(value.origin, value.checkpoint).any { it.segmentIndex < 0 || it.edgeIndex < 0 || !it.fraction.isFinite() || it.fraction !in 0.0..1.0 } ||
        value.lastDeviationAlertAt?.let { !time(it) || it.monotonicMillis == null } == true ||
        value.gapAnnouncedSegmentId?.let { !id(it) } == true
    ) "navigationState" else null

    internal fun recordMetadata(value: ActivityRecord): String? {
        if (value.schemaVersion != 1) return "schemaVersion"
        if (!id(value.id) || !id(value.sessionGeneration) || value.revision < 0 || value.controlRevision < 0 ||
            value.controlRevision > value.revision || value.name.length > 120) return "activityProperties"
        if (!time(value.startedAt) || !time(value.updatedAt) || value.finishedAt?.let { !time(it) } == true) return "time"
        if (value.startedAt.monotonicMillis == null || value.updatedAt.monotonicMillis == null ||
            value.finishedAt?.monotonicMillis == null && value.finishedAt != null) return "lifecycleClock"
        val terminal = value.status == ActivityStatus.FINALIZING || value.status == ActivityStatus.COMPLETED
        if (terminal != (value.finishedAt != null)) return "finishedAt"
        if (value.derivedRouteId?.let { !id(it) } == true || value.derivedRouteId != null && value.route != null) return "derivedRouteId"
        value.lastCommand?.let { command ->
            if (!id(command.id) || command.activityId != value.id || !id(command.sessionGeneration) ||
                command.expectedControlRevision < 0 || command.expectedControlRevision > value.controlRevision) return "lastCommand"
        }
        value.navigation?.let { if (it.revision > value.revision) return "navigationRevision"; navigationMetadata(it)?.let { error -> return error } }
        value.lastNavigationCommand?.let { if (navigationCommand(it) != null || it.activityId != value.id || value.navigation == null || it.expectedNavigationRevision > value.navigation.revision) return "lastNavigationCommand" }
        if (!nonnegative(value.statistics.distanceMeters) || durations(value.statistics.durations()) != null) return "statistics"
        return null
    }

    fun sample(value: TelemetrySample): String? {
        if (!id(value.id) || !id(value.source.id) || value.source.deviceId?.let { !id(it) } == true ||
            value.source.provider?.let { !id(it) } == true) return "source"
        if (!time(value.acquiredAt) || !time(value.receivedAt)) return "time"
        if (value.phase == MeasurementPhase.PAUSED) return "phase"
        val positional = value.measurement is PositionMeasurement
        if (positional != (value.pathSegmentId != null) || value.pathSegmentId?.let { !id(it) } == true) return "pathSegmentId"
        if (positional && value.phase != MeasurementPhase.RECORDING) return "phase"
        return measurement(value.measurement)
    }

    fun measurement(value: ActivityMeasurement): String? = when (value) {
        is PositionMeasurement -> if (!coordinate(value.coordinate) || !nonnegative(value.horizontalAccuracyMeters)) "position" else null
        is SpeedMeasurement -> if (!nonnegative(value.metersPerSecond) || value.accuracyMetersPerSecond?.let { !nonnegative(it) } == true) "speed" else null
        is HeartRateMeasurement -> if (!value.beatsPerMinute.isFinite() || value.beatsPerMinute <= 0) "heartRate" else null
        is ElevationMeasurement -> if (!value.meters.isFinite() || value.verticalReference.isBlank() ||
            value.verticalReference.length > 64 || value.accuracyMeters?.let { !nonnegative(it) } == true) "elevation" else null
        is CourseMeasurement -> if (!value.degrees.isFinite() || value.degrees < 0 || value.degrees >= 360) "course" else null
    }

    fun qualityPolicy(value: ActivityQualityPolicy): String? = if (
        value.maxPositionAgeMillis <= 0 || value.futureToleranceMillis < 0 ||
        !nonnegative(value.maxPositionAccuracyMeters) || value.pathGapMillis <= value.maxPositionAgeMillis ||
        value.maxLiveHeartRateAgeMillis <= 0
    ) "qualityPolicy" else null

    fun history(value: ActivityHistoryQuery): String? = if (
        value.limit !in 1..ActivityLimits.MAX_PAGE_SIZE || value.nameContains.length > 120 ||
        value.before?.let { it.startedAtEpochMillis < 0 || !id(it.id) } == true
    ) "historyQuery" else null

    fun telemetry(value: TelemetryQuery): String? = if (
        value.fromEpochMillis < 0 || value.toEpochMillis < value.fromEpochMillis ||
        value.limit !in 1..ActivityLimits.MAX_PAGE_SIZE || value.after?.let {
            it.acquiredAtEpochMillis !in value.fromEpochMillis..value.toEpochMillis || !id(it.sourceId) || !id(it.sampleId)
        } == true
    ) "telemetryQuery" else null

    fun durations(value: ActivityDurations): String? = if (
        value.elapsedMillis < 0 || value.recordingMillis !in 0..value.elapsedMillis ||
        value.movingMillis !in 0..value.recordingMillis || value.interruptedMillis !in 0..value.elapsedMillis ||
        value.recordingMillis > value.elapsedMillis - value.interruptedMillis
    ) "durations" else null

    fun time(value: ActivityTime): Boolean = value.epochMillis >= 0 &&
        (value.monotonicMillis == null || value.monotonicMillis >= 0) && id(value.continuityId)

    fun id(value: String): Boolean = value.length in 1..128 && value != "." && value != ".." &&
        value.all { it.isLetterOrDigit() && it.code < 128 || it in "-_.:" }

    private fun nonnegative(value: Double) = value.isFinite() && value >= 0
    private fun coordinate(value: GeographicCoordinate) = value.latitude.isFinite() && value.longitude.isFinite() &&
        value.latitude in -90.0..90.0 && value.longitude in -180.0..180.0

    private fun position(route: ActivityRoute, value: RoutePosition): Boolean {
        val segment = route.segments.getOrNull(value.segmentIndex) ?: return false
        return value.edgeIndex in 0 until segment.vertices.lastIndex && value.fraction.isFinite() && value.fraction in 0.0..1.0
    }
}
