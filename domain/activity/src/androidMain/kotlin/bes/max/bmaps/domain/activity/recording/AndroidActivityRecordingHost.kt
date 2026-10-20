/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.recording

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.activity.R
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityProfile
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

const val ACTIVITY_RECORDING_OPEN_ACTION = "bes.max.bmaps.activity.OPEN"

interface ActivityRecordingServiceOwner {
    val activityRecordingHost: AndroidActivityRecordingHost
    val activitySession: ActivitySession
}

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidActivityRecordingBindings {
    @Provides
    fun host(host: AndroidActivityRecordingHost): ActivityRecordingHost = host
}

@Inject
@SingleIn(AppScope::class)
class AndroidActivityRecordingHost(private val context: Context) : ActivityRecordingHost, Application.ActivityLifecycleCallbacks {
    private var resumed = 0
    private var service: ActivityRecordingService? = null
    private var pending: CompletableDeferred<ActivityFailure?>? = null
    private val lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val interruptions: Flow<Unit> = lost.asSharedFlow()

    init { (context.applicationContext as Application).registerActivityLifecycleCallbacks(this) }

    override suspend fun start(profile: ActivityProfile): ActivityFailure? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                ActivityFailure.PreciseLocationRequired else ActivityFailure.PermissionRequired
        }
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) return ActivityFailure.LocationDisabled
        if (service != null) return null
        if (resumed == 0) return ActivityFailure.ForegroundRequired
        val ready = CompletableDeferred<ActivityFailure?>()
        pending = ready
        val result = try {
            ContextCompat.startForegroundService(context, Intent(context, ActivityRecordingService::class.java))
            withTimeout(5_000) { ready.await() }
        } catch (_: TimeoutCancellationException) { ActivityFailure.RecordingUnavailable }
        catch (_: SecurityException) { ActivityFailure.PermissionRequired }
        catch (_: IllegalStateException) { ActivityFailure.ForegroundRequired }
        finally { pending = null }
        if (result != null) stop()
        return result
    }

    internal fun attached(value: ActivityRecordingService) { service = value; pending?.complete(null) }
    internal fun failed() { pending?.complete(ActivityFailure.RecordingUnavailable) }
    internal fun detached(value: ActivityRecordingService) {
        if (service === value) { service = null; lost.tryEmit(Unit) }
    }
    override fun stop() {
        val running = service
        service = null
        running?.stopSelf()
        context.stopService(Intent(context, ActivityRecordingService::class.java))
    }
    override fun onActivityResumed(activity: Activity) { resumed++ }
    override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class ActivityRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner get() = application as ActivityRecordingServiceOwner
    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(ActivityRecordingNotification.CHANNEL, getString(R.string.activity_recording_channel), NotificationManager.IMPORTANCE_LOW))
            val notifications = ActivityRecordingNotification(this)
            ServiceCompat.startForeground(this, 7401, notifications.create(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            owner.activityRecordingHost.attached(this)
            scope.launch {
                owner.activitySession.live.map { it?.notificationState() }.distinctUntilChanged().collect { state ->
                    if (state != null) manager.notify(7401, notifications.create(state))
                }
            }
        } catch (_: Exception) { owner.activityRecordingHost.failed(); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ActivityRecordingNotification.command(intent)?.let { command ->
            scope.launch {
                if (owner.activitySession.active.first() == null) owner.activityRecordingHost.stop()
                else owner.activitySession.apply(command)
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        owner.activityRecordingHost.detached(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }
}

internal actual fun activityEpochMillis(): Long = System.currentTimeMillis()
internal actual fun activityMonotonicMillis(): Long = SystemClock.elapsedRealtime()
internal actual fun activityIdentity(): String = UUID.randomUUID().toString()
