/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.wearable

import bes.max.bmaps.domain.activity.model.ActivityLimits
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.ActivityValidation
import bes.max.bmaps.domain.activity.model.RouteMatchQuality
import kotlinx.serialization.Serializable

@Serializable
enum class WearableCommandAction { PAUSE, RESUME }

@Serializable
enum class WearableCommandOutcome { APPLIED, REVISION_CONFLICT, STALE_SESSION, EXPIRED, INVALID_TRANSITION }

@Serializable
data class WearableCommand(
    val commandId: String,
    val activityId: String,
    val sessionGeneration: String,
    val expectedControlRevision: Long,
    val action: WearableCommandAction,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val protocolVersion: Int = 1,
)

@Serializable
data class WearableCommandAck(
    val commandId: String,
    val activityId: String,
    val sessionGeneration: String,
    val outcome: WearableCommandOutcome,
    val appliedControlRevision: Long,
    val status: ActivityStatus,
    val protocolVersion: Int = 1,
)

@Serializable
data class WearableHeartRateSample(
    val sampleId: String,
    val acquiredAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val beatsPerMinute: Double,
)

@Serializable
data class WearableHeartRateBatch(
    val activityId: String,
    val sessionGeneration: String,
    val sourceId: String,
    val sourceSessionId: String,
    val samples: List<WearableHeartRateSample>,
    val protocolVersion: Int = 1,
)

@Serializable
data class WearableBatchAck(
    val activityId: String,
    val sourceId: String,
    val sourceSessionId: String,
    val sampleIds: List<String>,
    val protocolVersion: Int = 1,
    val ignoredSampleIds: List<String> = emptyList(),
)

@Serializable
data class WearableActivitySnapshot(
    val activityId: String,
    val sessionGeneration: String,
    val revision: Long,
    val status: ActivityStatus,
    val generatedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val distanceMeters: Double,
    val controlRevision: Long = 0,
    val speedMetersPerSecond: Double? = null,
    val speedAcquiredAtEpochMillis: Long? = null,
    val remainingRouteMeters: Double? = null,
    val routeMatchQuality: RouteMatchQuality? = null,
    val nextControlPointName: String? = null,
    val protocolVersion: Int = 1,
)

object WearableValidation {
    fun command(value: WearableCommand): String? {
        if (value.protocolVersion != 1) return "protocolVersion"
        if (!ActivityValidation.id(value.commandId) || !ActivityValidation.id(value.activityId) ||
            !ActivityValidation.id(value.sessionGeneration) || value.expectedControlRevision < 0) return "identity"
        if (value.issuedAtEpochMillis < 0 || value.expiresAtEpochMillis < value.issuedAtEpochMillis ||
            value.expiresAtEpochMillis - value.issuedAtEpochMillis !in 1..ActivityLimits.WEARABLE_COMMAND_LIFETIME_MILLIS) return "expiry"
        return null
    }

    fun heartRate(value: WearableHeartRateBatch): String? {
        if (value.protocolVersion != 1) return "protocolVersion"
        if (!ActivityValidation.id(value.activityId) || !ActivityValidation.id(value.sessionGeneration) ||
            !ActivityValidation.id(value.sourceId) || !ActivityValidation.id(value.sourceSessionId)) return "identity"
        if (value.samples.isEmpty() || value.samples.size > ActivityLimits.MAX_WEARABLE_SAMPLES ||
            value.samples.map { it.sampleId }.distinct().size != value.samples.size) return "samples"
        if (value.samples.any {
            !ActivityValidation.id(it.sampleId) || it.acquiredAtEpochMillis < 0 ||
                it.endedAtEpochMillis < it.acquiredAtEpochMillis || !it.beatsPerMinute.isFinite() || it.beatsPerMinute <= 0
        }) return "heartRate"
        return null
    }
}
