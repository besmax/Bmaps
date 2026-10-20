/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import bes.max.bmaps.domain.activity.model.ActivityEvent
import bes.max.bmaps.domain.activity.model.ActivityEventKind
import bes.max.bmaps.domain.activity.repository.ActivityEventQuery
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.navigation.ActivityPositionQuery
import bes.max.bmaps.domain.activity.repository.ActivityRepository
import bes.max.bmaps.domain.activity.route.ActivityRoutePreview
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.ActivityTime
import bes.max.bmaps.domain.activity.model.ElevationMeasurement
import bes.max.bmaps.domain.activity.model.HeartRateMeasurement
import bes.max.bmaps.domain.activity.model.PositionMeasurement
import bes.max.bmaps.domain.activity.model.SpeedMeasurement
import bes.max.bmaps.domain.activity.repository.TelemetryCursor
import bes.max.bmaps.domain.activity.repository.TelemetryQuery
import bes.max.bmaps.domain.activity.model.TelemetrySample
import bes.max.bmaps.feature.viewer.activity.presentation.TrackCoordinate
import bes.max.bmaps.feature.viewer.activity.presentation.TrackLine
import bes.max.bmaps.feature.viewer.activity.presentation.trackDuration
import bes.max.bmaps.feature.viewer.activity.presentation.trackNumber
import bmaps.feature.viewer.generated.resources.Res
import bmaps.feature.viewer.generated.resources.history_alerts_changed
import bmaps.feature.viewer.generated.resources.history_control_marked
import bmaps.feature.viewer.generated.resources.history_control_reached
import bmaps.feature.viewer.generated.resources.history_corrected
import bmaps.feature.viewer.generated.resources.history_elevation
import bmaps.feature.viewer.generated.resources.history_finish_requested
import bmaps.feature.viewer.generated.resources.history_gap
import bmaps.feature.viewer.generated.resources.history_heart_rate
import bmaps.feature.viewer.generated.resources.history_plan_restored
import bmaps.feature.viewer.generated.resources.history_resumed
import bmaps.feature.viewer.generated.resources.history_return_started
import bmaps.feature.viewer.generated.resources.history_route_end
import bmaps.feature.viewer.generated.resources.history_route_gap
import bmaps.feature.viewer.generated.resources.history_speed
import bmaps.feature.viewer.generated.resources.history_started
import bmaps.feature.viewer.generated.resources.history_wearable_event
import bmaps.feature.viewer.generated.resources.track_completed
import bmaps.feature.viewer.generated.resources.track_gps_conflict
import bmaps.feature.viewer.generated.resources.track_gps_live
import bmaps.feature.viewer.generated.resources.track_interrupted
import bmaps.feature.viewer.generated.resources.track_off_route
import bmaps.feature.viewer.generated.resources.track_paused
import bmaps.feature.viewer.generated.resources.track_rejoined
import dev.zacsweers.metro.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs

@Inject
class ActivityReviewReader(private val activities: ActivityRepository) {
    internal suspend fun read(id: String): ReviewState {
        val record = activities.get(id).valueOrThrow()
        val events = mutableListOf<ActivityEvent>()
        var eventCursor: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = activities.events(id, ActivityEventQuery(eventCursor, 500)).valueOrThrow()
            events.addAll(page.items)
            if (events.size > 10_000) throw ReviewFailure(ActivityFailure.CapacityExceeded)
            eventCursor = page.nextSequence ?: break
        }
        val breaks = events.filter {
            it.kind in setOf(
                ActivityEventKind.PAUSED,
                ActivityEventKind.INTERRUPTED,
                ActivityEventKind.POSITION_LOST,
                ActivityEventKind.SOURCE_CONFLICT,
                ActivityEventKind.TELEMETRY_GAP
            )
        }.map { it.time.epochMillis }.sorted()
        val series = linkedMapOf<String, ReviewSeriesBuffer>()
        var cursor: TelemetryCursor? = null
        var maximumSpeed: Double? = null
        var count = 0L
        var fromTime = record.startedAt.epochMillis
        var toTime = record.updatedAt.epochMillis
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = activities.telemetry(id, TelemetryQuery(0, Long.MAX_VALUE, cursor, 500))
                .valueOrThrow()
            for (sample in page.items) {
                fromTime = minOf(fromTime, sample.acquiredAt.epochMillis)
                toTime = maxOf(toTime, sample.acquiredAt.epochMillis)
                if (++count > 2_000_000) throw ReviewFailure(ActivityFailure.CapacityExceeded)
                (sample.measurement as? SpeedMeasurement)?.metersPerSecond?.let { value ->
                    maximumSpeed = maxOf(maximumSpeed ?: value, value)
                }
                val measurement = when (val value = sample.measurement) {
                    is SpeedMeasurement -> Triple(
                        "speed",
                        Res.string.history_speed,
                        value.metersPerSecond * 3.6
                    )

                    is HeartRateMeasurement -> Triple(
                        "heart",
                        Res.string.history_heart_rate,
                        value.beatsPerMinute
                    )

                    is ElevationMeasurement -> Triple(
                        "elevation:${value.verticalReference}",
                        Res.string.history_elevation,
                        value.meters
                    )

                    else -> null
                } ?: continue
                val key = "${sample.source.id}:${measurement.first}"
                if (series.size >= 32 && key !in series) throw ReviewFailure(ActivityFailure.CapacityExceeded)
                val reference = (sample.measurement as? ElevationMeasurement)?.verticalReference
                val buffer = series.getOrPut(key) {
                    ReviewSeriesBuffer(
                        key, measurement.second,
                        listOfNotNull(
                            sample.source.kind.name,
                            sample.source.provider ?: sample.source.id,
                            reference
                        ).joinToString(" · "),
                        when (sample.measurement) {
                            is SpeedMeasurement -> "km/h"; is HeartRateMeasurement -> "bpm"; else -> "m"
                        }, breaks
                    )
                }
                buffer.add(sample, measurement.third)
            }
            cursor = page.next ?: break
        }
        val positions = ReviewPositionBuffer()
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = activities.positions(id, ActivityPositionQuery(after)).valueOrThrow()
            page.items.forEach(positions::add)
            after = page.nextSequence ?: break
        }
        if (activities.get(id).valueOrThrow().revision != record.revision) throw ReviewFailure(
            ActivityFailure.RevisionConflict
        )
        val planned = record.route?.snapshot?.let { route ->
            ActivityRoutePreview.create(route).segments.map { segment ->
                TrackLine(
                    segment.sourceSegmentId,
                    segment.coordinates.map { TrackCoordinate(it.latitude, it.longitude) }
                        .toImmutableList()
                )
            }
        }.orEmpty().toImmutableList()
        events.forEach {
            fromTime = minOf(fromTime, it.time.epochMillis); toTime =
            maxOf(toTime, it.time.epochMillis)
        }
        val statistics = record.statistics
        val sampled = positions.snapshot()
        val lines = sampled.groupBy { it.segment }.map { (segment, points) ->
            TrackLine(
                segment,
                points.map { it.coordinate }.toImmutableList()
            )
        }.toImmutableList()
        return ReviewState(
            activityId = id,
            revision = record.revision,
            date = activityTimeText(record.startedAt.epochMillis),
            profile = historyProfile(record.profile),
            packageId = record.packageId,
            averageSpeed = if (statistics.recordingMillis > 0) reviewNumber(statistics.distanceMeters * 3600 / statistics.recordingMillis) else null,
            maximumSpeed = maximumSpeed?.let { reviewNumber(it * 3.6) },
            name = record.name,
            status = historyStatus(record.status),
            distance = trackNumber(statistics.distanceMeters / 1000),
            elapsed = trackDuration(statistics.elapsedMillis),
            recording = trackDuration(statistics.recordingMillis),
            moving = trackDuration(statistics.movingMillis),
            interrupted = trackDuration(statistics.interruptedMillis),
            estimated = statistics.elapsedIsEstimated,
            heartRateAvailable = series.keys.any { it.endsWith(":heart") },
            fromTime = fromTime,
            toTime = maxOf(fromTime + 1, toTime),
            exportable = record.status == ActivityStatus.COMPLETED || record.status == ActivityStatus.INTERRUPTED,
            editable = record.status == ActivityStatus.COMPLETED,
            planned = planned,
            recorded = lines,
            positions = sampled,
            series = series.values.map { it.snapshot() }.toImmutableList(),
            events = events.map {
                ReviewEvent(
                    it.id,
                    it.time.epochMillis,
                    eventLabel(it.kind, it.navigationRouteId != null),
                    it.relatedId
                )
            }.toImmutableList()
        )
    }
}

internal class ReviewPositionBuffer {
    private var points = mutableListOf<ReviewPoint>()
    private val segments = mutableSetOf<String>()
    private var count = 0
    fun add(sample: TelemetrySample) {
        if (++count > 2_000_000) throw ReviewFailure(ActivityFailure.CapacityExceeded)
        val position = sample.measurement as? PositionMeasurement ?: return
        val segment = sample.pathSegmentId ?: return
        segments += segment
        if (segments.size > 2000) throw ReviewFailure(ActivityFailure.CapacityExceeded)
        points += ReviewPoint(
            sample.id, sample.acquiredAt.epochMillis,
            TrackCoordinate(position.coordinate.latitude, position.coordinate.longitude), segment
        )
        if (points.size > 20_000) points = points.filterIndexed { index, point ->
            index == 0 || index == points.lastIndex || index % 2 == 0 ||
                    points[index - 1].segment != point.segment || points[index + 1].segment != point.segment
        }.toMutableList()
    }

    fun snapshot() = points.toImmutableList()
}

internal class ReviewSeriesBuffer(
    private val id: String, private val metric: StringResource,
    private val source: String, private val unit: String, private val breaks: List<Long>
) {
    private var points = mutableListOf<ChartPoint>()
    private var count = 0L
    private var sum = 0.0
    private var minimum = Double.POSITIVE_INFINITY
    private var maximum = Double.NEGATIVE_INFINITY
    private var previous: ActivityTime? = null
    private var run = 0
    fun add(sample: TelemetrySample, value: Double) {
        val time = sample.acquiredAt
        val last = previous
        val delta =
            if (last != null && last.continuityId == time.continuityId && last.monotonicMillis != null && time.monotonicMillis != null) {
                (time.monotonicMillis ?: 0L) - (last.monotonicMillis ?: 0L)
            } else {
                null
            }
        val from = last?.epochMillis ?: time.epochMillis
        val boundary = breaks.binarySearch(from + 1).let { if (it >= 0) it else -it - 1 }
        if (last == null || delta == null || delta < 0 || delta > 15_000 || breaks.getOrNull(
                boundary
            )?.let { it <= time.epochMillis } == true
        ) {
            run++
        }
        points += ChartPoint(sample.id, time.epochMillis, value, run)
        count++; sum += value; minimum = minOf(minimum, value); maximum = maxOf(maximum, value)
        previous = time
        if (points.size > 1200) {
            val reduced = mutableListOf(points.first())
            points.chunked(4).forEach { window ->
                val low = window.minBy { it.value }
                val high = window.maxBy { it.value }
                window.filter { it.id == low.id || it.id == high.id }
                    .forEach { if (reduced.last().id != it.id) reduced += it }
            }
            if (reduced.last().id != points.last().id) reduced += points.last()
            points = reduced
        }
    }

    fun snapshot() = ReviewSeries(
        id, metric, source, unit, points.toImmutableList(), count,
        reviewNumber(minimum), reviewNumber(maximum), reviewNumber(sum / count)
    )
}

internal fun reviewNumber(value: Double) = (if (value < 0) "−" else "") + trackNumber(abs(value))
internal fun eventLabel(kind: ActivityEventKind, navigation: Boolean = false): StringResource =
    when (kind) {
        ActivityEventKind.STARTED -> Res.string.history_started
        ActivityEventKind.PAUSED -> Res.string.track_paused
        ActivityEventKind.RESUMED -> Res.string.history_resumed
        ActivityEventKind.INTERRUPTED -> Res.string.track_interrupted
        ActivityEventKind.FINISH_REQUESTED -> Res.string.history_finish_requested
        ActivityEventKind.COMPLETED -> Res.string.track_completed
        ActivityEventKind.CONTROL_POINT -> if (navigation) Res.string.history_control_reached else Res.string.history_control_marked
        ActivityEventKind.POSITION_LOST, ActivityEventKind.TELEMETRY_GAP -> Res.string.history_gap
        ActivityEventKind.POSITION_RECOVERED -> Res.string.track_gps_live
        ActivityEventKind.SOURCE_CONFLICT -> Res.string.track_gps_conflict
        ActivityEventKind.ROUTE_DEVIATED, ActivityEventKind.ROUTE_DEVIATION_REMINDER -> Res.string.track_off_route
        ActivityEventKind.ROUTE_REJOINED -> Res.string.track_rejoined
        ActivityEventKind.ROUTE_GAP -> Res.string.history_route_gap
        ActivityEventKind.ROUTE_END -> Res.string.history_route_end
        ActivityEventKind.NAVIGATION_CORRECTED -> Res.string.history_corrected
        ActivityEventKind.RETURN_STARTED -> Res.string.history_return_started
        ActivityEventKind.PLANNED_GUIDANCE_RESTORED -> Res.string.history_plan_restored
        ActivityEventKind.NAVIGATION_ALERTS_CHANGED -> Res.string.history_alerts_changed
        ActivityEventKind.WEARABLE_DISCONNECTED, ActivityEventKind.WEARABLE_RECONNECTED -> Res.string.history_wearable_event
    }
