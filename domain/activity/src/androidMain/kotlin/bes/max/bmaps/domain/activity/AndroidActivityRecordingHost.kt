/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

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
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.activity_recording_channel), NotificationManager.IMPORTANCE_LOW))
            val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val finish = PendingIntent.getService(this, 1, Intent(this, ActivityRecordingService::class.java).setAction(FINISH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.activity_recording_title))
                .setContentText(getString(R.string.activity_recording_text))
                .setContentIntent(open).setOngoing(true).setSilent(true)
                .addAction(0, getString(R.string.activity_recording_finish), finish).build()
            ServiceCompat.startForeground(this, 7401, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            owner.activityRecordingHost.attached(this)
            scope.launch {
                owner.activitySession.active.map { record -> record?.let { it.name to it.status } }.distinctUntilChanged().collect { display ->
                    if (display != null) {
                        val text = if (display.second == ActivityStatus.PAUSED) R.string.activity_recording_paused else R.string.activity_recording_text
                        manager.notify(7401, NotificationCompat.Builder(this@ActivityRecordingService, CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(display.first.ifBlank { getString(R.string.activity_recording_title) })
                            .setContentText(getString(text)).setContentIntent(open).setOngoing(true).setSilent(true)
                            .addAction(0, getString(R.string.activity_recording_finish), finish).build())
                    }
                }
            }
        } catch (_: Exception) { owner.activityRecordingHost.failed(); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FINISH) scope.launch {
            val record = owner.activitySession.active.first()
            if (record == null) { owner.activityRecordingHost.stop(); return@launch }
            owner.activitySession.apply(ActivityCommand(activityIdentity(), record.id, record.sessionGeneration, record.controlRevision, ActivityAction.FINISH))
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
    private companion object {
        const val CHANNEL = "activity-recording"
        const val FINISH = "bes.max.bmaps.activity.FINISH"
    }
}

internal actual fun activityEpochMillis(): Long = System.currentTimeMillis()
internal actual fun activityMonotonicMillis(): Long = SystemClock.elapsedRealtime()
internal actual fun activityIdentity(): String = UUID.randomUUID().toString()
