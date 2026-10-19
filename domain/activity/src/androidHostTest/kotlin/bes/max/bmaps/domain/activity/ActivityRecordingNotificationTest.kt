/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS")
class ActivityRecordingNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun matchedRouteShowsRecordingTimeDistanceRemainingSpeedAndProfile() {
        val notification = ActivityRecordingNotification(context).create(live().notificationState())
        assertEquals("Morning walk", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Recording · Hiking · On route", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("01:24:08 · 6.3 km · To finish 2.1 km", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Speed 7.2 km/h"))
        assertEquals(listOf("Pause", "Finish"), notification.actions.map { it.title.toString() })
    }

    @Test fun freeRecordingHasNoRemainingAndMissingSpeedIsOmitted() {
        val live = live().let { it.copy(record = it.record.copy(route = null, navigation = null), speed = null) }
        val state = assertNotNull(live.notificationState())
        assertNull(state.remainingMeters)
        val notification = ActivityRecordingNotification(context).create(state)
        assertEquals("Recording · Hiking · Free recording", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("01:24:08 · 6.3 km", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Speed"))
    }

    @Test fun pausedRecordingRetainsMetricsAndOffersResumeWithoutLiveMeasurements() {
        val original = live()
        val state = assertNotNull(original.copy(record = original.record.copy(status = ActivityStatus.PAUSED),
            durations = original.durations.copy(elapsedMillis = 99_000_000)).notificationState())
        assertEquals(5048L, state.recordingSeconds)
        assertNull(state.speedKmh)
        assertNull(state.remainingMeters)
        val notification = ActivityRecordingNotification(context).create(state)
        assertEquals("01:24:08 · 6.3 km", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString().startsWith("Recording paused"))
        assertEquals("Resume", notification.actions[0].title.toString())
    }

    @Test fun unavailableAndUncertainPositionsNeverShowTrustedRemainingDistance() {
        val original = live()
        for (availability in ActivityDataAvailability.entries.filter { it != ActivityDataAvailability.LIVE }) {
            val state = assertNotNull(original.copy(positionAvailability = availability).notificationState())
            assertEquals(R.string.activity_recording_no_gps, state.navigationText)
            assertNull(state.speedKmh)
            assertNull(state.remainingMeters)
        }
        for ((quality, text) in listOf(RouteMatchQuality.OFF_ROUTE to R.string.activity_recording_off_route,
            RouteMatchQuality.UNCERTAIN to R.string.activity_recording_uncertain,
            RouteMatchQuality.SEGMENT_GAP to R.string.activity_recording_gap)) {
            val state = assertNotNull(original.copy(progress = original.progress!!.copy(quality = quality,
                remainingRouteMeters = if (quality == RouteMatchQuality.OFF_ROUTE) 2100.0 else null)).notificationState())
            assertEquals(text, state.navigationText)
            if (quality != RouteMatchQuality.OFF_ROUTE) assertNull(state.remainingMeters)
        }
        assertNull(original.copy(record = original.record.copy(status = ActivityStatus.COMPLETED)).notificationState())
    }

    @Test fun russianNotificationUsesLocalizedLabelsAndDecimalSeparator() {
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru-RU")) })
        val notification = ActivityRecordingNotification(localized).create(live().notificationState())
        assertEquals("Запись · Пешком · По маршруту", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("01:24:08 · 6,3 км · До финиша 2,1 км", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Пауза", "Завершить"), notification.actions.map { it.title.toString() })
    }

    @Test fun controlsRetainOriginalActivityGenerationRevisionAndCommandIdentity() {
        val state = assertNotNull(live().notificationState())
        val notifications = ActivityRecordingNotification(context)
        val original = notifications.create(state)
        val newer = notifications.create(state.copy(activityId = "another", generation = "new-generation", controlRevision = 4))
        val intent = shadowOf(original.actions[0].actionIntent).savedIntent
        val command = assertNotNull(ActivityRecordingNotification.command(intent))
        assertEquals(state.activityId, command.activityId)
        assertEquals(state.generation, command.sessionGeneration)
        assertEquals(state.controlRevision, command.expectedControlRevision)
        assertEquals(ActivityAction.PAUSE, command.action)
        assertNotEquals(original.actions[0].actionIntent, newer.actions[0].actionIntent)
        val repeated = notifications.create(state)
        assertEquals(command, ActivityRecordingNotification.command(shadowOf(repeated.actions[0].actionIntent).savedIntent))
        val resumed = notifications.create(state.copy(paused = true, controlRevision = 1))
        assertEquals(ActivityAction.RESUME, ActivityRecordingNotification.command(shadowOf(resumed.actions[0].actionIntent).savedIntent)?.action)
        assertEquals(ActivityAction.FINISH, ActivityRecordingNotification.command(shadowOf(original.actions[1].actionIntent).savedIntent)?.action)
        assertNull(ActivityRecordingNotification.command(Intent(intent).putExtra("control", "COMPLETE")))
        assertNull(ActivityRecordingNotification.command(Intent(intent).putExtra("revision", -1L)))
        assertNull(ActivityRecordingNotification.command(null))
    }

    @Test fun notificationTapTargetsTheActiveActivityEntry() {
        val info = ActivityInfo().apply { packageName = context.packageName; name = "test.MainActivity" }
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context.packageName, info.name))
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName), ResolveInfo().apply { activityInfo = info })
        val notification = ActivityRecordingNotification(context).create(live().notificationState())
        val intent = shadowOf(assertNotNull(notification.contentIntent)).savedIntent
        assertEquals(ACTIVITY_RECORDING_OPEN_ACTION, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    private fun live(): ActivityLiveState {
        val time = ActivityTime(1000, 1000, "clock")
        val route = ActivityRouteAdapter.line("route", "Trail", listOf(
            RouteVertex(bes.max.bmaps.core.mapengine.GeographicCoordinate(50.0, 30.0)),
            RouteVertex(bes.max.bmaps.core.mapengine.GeographicCoordinate(50.1, 30.1))))
        val record = ActivityRecord("activity", 0, "generation", "Morning walk", ActivityProfile.HIKING,
            ActivityStatus.RECORDING, time, time, RouteSelection(route, RouteDirection.FORWARD, RoutePosition(0, 0, 0.0)))
        val speed = TelemetrySample("speed", MeasurementSource("gps", MeasurementSourceKind.PHONE_LOCATION), time, time,
            MeasurementPhase.RECORDING, SpeedMeasurement(2.0))
        return ActivityLiveState(record, 6300.0, ActivityDurations(6_000_000, 5_048_000, 0, 0), speed = speed,
            progress = RouteProgress(RouteMatchQuality.MATCHED, remainingRouteMeters = 2100.0), positionAvailability = ActivityDataAvailability.LIVE)
    }
}
