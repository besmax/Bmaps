/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.text.NumberFormat

internal data class RecordingNotificationState(
    val activityId: String,
    val generation: String,
    val controlRevision: Long,
    val name: String,
    val profile: ActivityProfile,
    val paused: Boolean,
    val recordingSeconds: Long,
    val distanceMeters: Double,
    val remainingMeters: Double?,
    val speedKmh: Double?,
    val navigationText: Int,
)

internal fun ActivityLiveState.notificationState(): RecordingNotificationState? {
    if (record.status != ActivityStatus.RECORDING && record.status != ActivityStatus.PAUSED) return null
    val paused = record.status == ActivityStatus.PAUSED
    val fresh = !paused && failure == null && positionAvailability == ActivityDataAvailability.LIVE
    val hasRoute = record.route != null || record.navigation != null
    val navigationText = when {
        paused -> R.string.activity_recording_navigation_paused
        !fresh -> R.string.activity_recording_no_gps
        !hasRoute -> R.string.activity_recording_free
        progress?.quality == RouteMatchQuality.SEGMENT_GAP -> R.string.activity_recording_gap
        progress?.quality == RouteMatchQuality.OFF_ROUTE -> R.string.activity_recording_off_route
        progress?.quality == RouteMatchQuality.MATCHED -> if (record.navigation?.deviated == true)
            R.string.activity_recording_off_route else R.string.activity_recording_on_route
        else -> R.string.activity_recording_uncertain
    }
    val remaining = progress?.remainingRouteMeters?.takeIf {
        hasRoute && fresh && progress.quality in setOf(RouteMatchQuality.MATCHED, RouteMatchQuality.OFF_ROUTE) && it.isFinite() && it >= 0
    }
    val speed = (speed?.measurement as? SpeedMeasurement)?.metersPerSecond?.takeIf { fresh && it.isFinite() && it >= 0 }
    return RecordingNotificationState(record.id, record.sessionGeneration, record.controlRevision, record.name, record.profile,
        paused, durations.recordingMillis.coerceAtLeast(0) / 1000, distanceMeters, remaining, speed?.times(3.6), navigationText)
}

internal class ActivityRecordingNotification(private val context: Context) {
    fun create(state: RecordingNotificationState?): Notification {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = ACTIVITY_RECORDING_OPEN_ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }?.let { PendingIntent.getActivity(context, 7401, it, FLAGS) }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (state == null) return builder.setContentTitle(context.getString(R.string.activity_recording_title))
            .setContentText(context.getString(R.string.activity_recording_text)).build()
        val number = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
            minimumFractionDigits = 1; maximumFractionDigits = 1; isGroupingUsed = false
        }
        val status = context.getString(if (state.paused) R.string.activity_recording_paused else R.string.activity_recording_running)
        val profile = context.getString(when (state.profile) {
            ActivityProfile.HIKING -> R.string.activity_recording_hiking
            ActivityProfile.OFF_ROAD_MOTORCYCLE -> R.string.activity_recording_motorcycle
            ActivityProfile.ROAD_TRAVEL -> R.string.activity_recording_road
        })
        val navigation = context.getString(state.navigationText)
        val summary = listOf(status, profile, navigation).distinct().joinToString(" · ")
        val seconds = state.recordingSeconds
        val time = "${(seconds / 3600).toString().padStart(2, '0')}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
        val distance = context.getString(R.string.activity_recording_distance, number.format(state.distanceMeters / 1000))
        val metrics = listOfNotNull(time, distance, state.remainingMeters?.let {
            context.getString(R.string.activity_recording_remaining, number.format(it / 1000))
        }).joinToString(" · ")
        val speed = state.speedKmh?.let { context.getString(R.string.activity_recording_speed, number.format(it)) }
        val expanded = listOfNotNull(summary, metrics, speed).joinToString("\n")
        val action = if (state.paused) ActivityAction.RESUME else ActivityAction.PAUSE
        return builder.setContentTitle(state.name.ifBlank { context.getString(R.string.activity_recording_title) })
            .setContentText(metrics).setSubText(summary).setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .addAction(0, context.getString(if (state.paused) R.string.activity_recording_resume else R.string.activity_recording_pause), control(state, action))
            .addAction(0, context.getString(R.string.activity_recording_finish), control(state, ActivityAction.FINISH)).build()
    }

    private fun control(state: RecordingNotificationState, action: ActivityAction): PendingIntent {
        val command = activityDigest("notification:${state.activityId}:${state.generation}:${state.controlRevision}:$action")
        val intent = Intent(context, ActivityRecordingService::class.java).setAction(CONTROL)
            .setData(Uri.parse("bmaps-internal://activity-command/$command"))
            .putExtra("command", command).putExtra("activity", state.activityId).putExtra("generation", state.generation)
            .putExtra("revision", state.controlRevision).putExtra("control", action.name)
        return PendingIntent.getService(context, 0, intent, FLAGS)
    }

    companion object {
        const val CHANNEL = "activity-recording"
        const val CONTROL = "bes.max.bmaps.activity.CONTROL"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun command(intent: Intent?): ActivityCommand? {
            if (intent?.action != CONTROL) return null
            val action = when (intent.getStringExtra("control")) {
                ActivityAction.PAUSE.name -> ActivityAction.PAUSE
                ActivityAction.RESUME.name -> ActivityAction.RESUME
                ActivityAction.FINISH.name -> ActivityAction.FINISH
                else -> return null
            }
            val id = intent.getStringExtra("activity") ?: return null
            val generation = intent.getStringExtra("generation") ?: return null
            val command = intent.getStringExtra("command") ?: return null
            val revision = intent.getLongExtra("revision", -1)
            if (!ActivityValidation.id(id) || !ActivityValidation.id(generation) || !ActivityValidation.id(command) || revision < 0) return null
            return ActivityCommand(command, id, generation, revision, action)
        }
    }
}
