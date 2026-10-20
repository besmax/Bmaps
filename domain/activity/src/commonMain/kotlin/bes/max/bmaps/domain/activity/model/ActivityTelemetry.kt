/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.model

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MeasurementSourceKind { PHONE_LOCATION, WATCH_HEALTH, DEM, BAROMETER, DERIVED }

@Serializable
data class MeasurementSource(
    val id: String,
    val kind: MeasurementSourceKind,
    val deviceId: String? = null,
    val provider: String? = null,
)

@Serializable
enum class MeasurementPhase { RECORDING, PAUSED, INTERRUPTED }

@Serializable
enum class ActivityDataAvailability { WAITING, LIVE, STALE, UNRELIABLE, UNAVAILABLE, SOURCE_CONFLICT }

@Serializable
sealed interface ActivityMeasurement

@Serializable
@SerialName("position")
data class PositionMeasurement(
    val coordinate: GeographicCoordinate,
    val horizontalAccuracyMeters: Double,
    val sourceConflict: Boolean = false,
) : ActivityMeasurement

@Serializable
@SerialName("speed")
data class SpeedMeasurement(val metersPerSecond: Double, val accuracyMetersPerSecond: Double? = null) : ActivityMeasurement

@Serializable
@SerialName("heart_rate")
data class HeartRateMeasurement(val beatsPerMinute: Double) : ActivityMeasurement

@Serializable
@SerialName("elevation")
data class ElevationMeasurement(
    val meters: Double,
    val verticalReference: String,
    val accuracyMeters: Double? = null,
) : ActivityMeasurement

@Serializable
@SerialName("course")
data class CourseMeasurement(val degrees: Double) : ActivityMeasurement

@Serializable
data class TelemetrySample(
    val id: String,
    val source: MeasurementSource,
    val acquiredAt: ActivityTime,
    val receivedAt: ActivityTime,
    val phase: MeasurementPhase,
    val measurement: ActivityMeasurement,
    val pathSegmentId: String? = null,
)

@Serializable
data class ActivityQualityPolicy(
    val maxPositionAgeMillis: Long = 5_000,
    val futureToleranceMillis: Long = 2_000,
    val maxPositionAccuracyMeters: Double = 50.0,
    val pathGapMillis: Long = 10_000,
    val maxLiveHeartRateAgeMillis: Long = 15_000,
)

object ActivityLimits {
    const val MAX_IMPORT_BYTES = 32_000_000
    const val MAX_IMPORT_ROUTES = 100
    const val MAX_ROUTE_STORAGE_BYTES = 64_000_000
    const val MAX_ACTIVITY_HEADER_BYTES = 65_536
    const val MAX_WRITE_EVENTS = 256
    const val MAX_ROUTE_VERTICES = 200_000
    const val MAX_ROUTE_SEGMENTS = 2_000
    const val MAX_CONTROL_POINTS = 2_000
    const val MAX_WRITE_SAMPLES = 256
    const val MAX_WRITE_BYTES = 262_144
    const val MAX_COMMIT_INTERVAL_MILLIS = 1_000L
    const val MAX_PAGE_SIZE = 500
    const val MAX_WEARABLE_PACKET_BYTES = 65_536
    const val MAX_WEARABLE_SAMPLES = 128
    const val MAX_WEARABLE_BUFFER_BYTES = 16_000_000
    const val MAX_WEARABLE_RETENTION_MILLIS = 86_400_000L
    const val WEARABLE_COMMAND_LIFETIME_MILLIS = 5_000L
}
