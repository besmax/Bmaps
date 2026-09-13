package bes.max.bmaps.domain.mapbuilder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.map_builder.R
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.guava.await

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDownloadScheduler(private val context: Context, private val storage: PackageBuildStorage) : DownloadScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun initialize() {
        scope.launch {
            val result = storage.observeUnfinished().first()
            if (result is PackageResult.Success) for (progress in result.value) {
                if (progress.state == BuildJobState.QUEUED) {
                    try { schedule(progress.jobId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { storage.setState(progress.packageId, BuildJobState.FAILED, PackageFailure.Io) }
                }
            }
        }
    }

    override suspend fun schedule(id: BuildJobId) {
        val work = OneTimeWorkRequestBuilder<MapDownloadWorker>()
            .setInputData(workDataOf("job" to id.value))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("map-${id.value}", ExistingWorkPolicy.KEEP, work).result.await()
    }

    override suspend fun cancel(id: BuildJobId) {
        WorkManager.getInstance(context).cancelUniqueWork("map-${id.value}").result.await()
    }
}

@Inject
@SingleIn(AppScope::class)
class MapDownloadWorkerFactory(private val runner: DownloadRunner, private val storage: PackageBuildStorage) : WorkerFactory() {
    override fun createWorker(context: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == MapDownloadWorker::class.java.name) MapDownloadWorker(context, parameters, runner, storage) else null
}

class MapDownloadWorker(
    context: Context, parameters: WorkerParameters,
    private val runner: DownloadRunner, private val storage: PackageBuildStorage,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val job = inputData.getString("job")?.let(::BuildJobId) ?: return@coroutineScope Result.failure()
        try {
            val initial = storage.observeProgress(job).first().valueOrThrow()
            if (initial.state == BuildJobState.COMPLETED) return@coroutineScope Result.success()
            if (initial.state in setOf(BuildJobState.PAUSED, BuildJobState.CANCELLED, BuildJobState.FAILED)) return@coroutineScope Result.success()
            val name = storage.request(initial.packageId).valueOrThrow().name
            setForeground(notification(job, name, initial))
            val updates = launch {
                storage.observeProgress(job).filterIsInstance<PackageResult.Success<BuildProgress>>()
                    .conflate().collect {
                        setForeground(notification(job, name, it.value))
                        delay(500)
                    }
            }
            try {
                when (runner.run(job)) {
                    is PackageResult.Success -> Result.success()
                    is PackageResult.Failure -> Result.failure()
                }
            } finally { updates.cancelAndJoin() }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val requeue = Build.VERSION.SDK_INT >= 31 && isStopped && stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP
                storage.setState(PackageId(job.value), if (requeue) BuildJobState.QUEUED else BuildJobState.PAUSED)
            }
            throw cancelled
        }
        catch (_: Exception) {
            storage.setState(PackageId(job.value), BuildJobState.FAILED, PackageFailure.Io)
            Result.failure()
        }
    }

    private fun notification(job: BuildJobId, name: String, progress: BuildProgress): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("map-downloads", context.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content = intent?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val percentage = if (progress.totalTiles > 0) (progress.completedTiles.toDouble() / progress.totalTiles * 100).toInt() else 0
        val notification = NotificationCompat.Builder(context, "map-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setContentText(context.getString(R.string.download_progress, progress.completedTiles, progress.totalTiles))
            .setProgress(100, percentage, false).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(content)
            .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.download_pause),
                WorkManager.getInstance(context).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(job.value.hashCode() and Int.MAX_VALUE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
