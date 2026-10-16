/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import kotlin.math.*

internal fun ActivityRecord.statisticsAt(time: ActivityTime): ActivityStatistics {
    if (status == ActivityStatus.COMPLETED || status == ActivityStatus.FINALIZING) return statistics
    val sameClock = updatedAt.continuityId == time.continuityId && updatedAt.monotonicMillis != null && time.monotonicMillis != null
    val delta = (if (sameClock) time.monotonicMillis!! - updatedAt.monotonicMillis!! else time.epochMillis - updatedAt.epochMillis).coerceAtLeast(0)
    return statistics.copy(
        elapsedMillis = statistics.elapsedMillis + delta,
        recordingMillis = statistics.recordingMillis + if (status == ActivityStatus.RECORDING && sameClock) delta else 0,
        interruptedMillis = statistics.interruptedMillis + if (status == ActivityStatus.INTERRUPTED || status == ActivityStatus.RECORDING && !sameClock) delta else 0,
        elapsedIsEstimated = statistics.elapsedIsEstimated || !sameClock,
    )
}

internal class ActivityMovement(private val profile: ActivityProfile) {
    private var previous: TelemetrySample? = null
    private var moving = false

    fun reset() { previous = null; moving = false }

    fun accept(sample: TelemetrySample, speed: Double?, statistics: ActivityStatistics): ActivityStatistics {
        val prior = previous
        if (prior == null || prior.pathSegmentId != sample.pathSegmentId) { previous = sample; moving = false; return statistics }
        val delta = activityTimeDelta(prior.acquiredAt, sample.acquiredAt)
        if (delta != null && delta <= 0) return statistics
        previous = sample
        if (delta == null || delta > ActivityQualityPolicy().pathGapMillis) { moving = false; return statistics }
        val a = prior.measurement as PositionMeasurement
        val b = sample.measurement as PositionMeasurement
        val lat = (b.coordinate.latitude - a.coordinate.latitude) * PI / 180
        val lon = (b.coordinate.longitude - a.coordinate.longitude) * PI / 180
        val h = sin(lat / 2).pow(2) + cos(a.coordinate.latitude * PI / 180) * cos(b.coordinate.latitude * PI / 180) * sin(lon / 2).pow(2)
        val distance = 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        val startSpeed = if (profile == ActivityProfile.HIKING) 0.5 else 1.0
        val stopSpeed = startSpeed / 2
        val significant = distance >= max(3.0, (a.horizontalAccuracyMeters + b.horizontalAccuracyMeters) / 2)
        moving = if (speed != null) speed >= (if (moving) stopSpeed else startSpeed) else significant
        if (!moving || distance < 1) return statistics
        return statistics.copy(distanceMeters = statistics.distanceMeters + distance,
            movingMillis = (statistics.movingMillis + delta).coerceAtMost(statistics.recordingMillis))
    }
}

internal fun activityTimeDelta(from: ActivityTime, to: ActivityTime): Long? =
    if (from.continuityId == to.continuityId && from.monotonicMillis != null && to.monotonicMillis != null)
        to.monotonicMillis - from.monotonicMillis else null
