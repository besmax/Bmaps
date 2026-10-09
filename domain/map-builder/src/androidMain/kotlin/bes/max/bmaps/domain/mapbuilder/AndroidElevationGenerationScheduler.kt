/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.map_builder.R
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.conflate

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidElevationGenerationScheduler(private val context: Context, private val generator: ElevationLayerGenerator) : ElevationGenerationScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun initialize() {
        scope.launch {
            generator.pending().forEach { job ->
                try { schedule(job.packageId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { generator.fail(job.packageId, PackageFailure.Io) }
            }
        }
    }

    override suspend fun schedule(id: PackageId) {
        val job = generator.job(id) ?: return
        val request = OneTimeWorkRequestBuilder<ElevationGenerationWorker>()
            .setInputData(workDataOf("package" to id.value, "token" to job.token))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("elevation-${id.value}", ExistingWorkPolicy.REPLACE, request).result.await()
    }

    override suspend fun cancel(id: PackageId) {
        WorkManager.getInstance(context).cancelUniqueWork("elevation-${id.value}").result.await()
    }
}

class ElevationGenerationWorker(context: Context, parameters: WorkerParameters, private val generator: ElevationLayerGenerator) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val packageId = inputData.getString("package")?.let(::PackageId) ?: return@coroutineScope Result.failure()
        val token = inputData.getString("token") ?: return@coroutineScope Result.failure()
        val initial = generator.job(packageId)?.takeIf { it.token == token && it.active } ?: return@coroutineScope Result.success()
        try {
            setForeground(notification(initial))
            val updates = launch {
                generator.observe(packageId).conflate().collect { job ->
                    if (job?.token == token && job.active) { setForeground(notification(job)); delay(500) }
                }
            }
            try {
                when (generator.run(packageId, token)) {
                    is PackageResult.Success -> Result.success()
                    is PackageResult.Failure -> if (generator.job(packageId)?.active == true) Result.retry() else Result.failure()
                }
            } finally { updates.cancelAndJoin() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { generator.fail(packageId, PackageFailure.Io); Result.failure() }
    }

    private fun notification(job: ElevationGenerationJob): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("elevation-generation", context.getString(R.string.elevation_channel), NotificationManager.IMPORTANCE_LOW))
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content = intent?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val progress = if (job.totalTiles > 0) (job.completedTiles.toDouble() / job.totalTiles * 100).toInt() else 0
        val notification = NotificationCompat.Builder(context, "elevation-generation")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.elevation_channel))
            .setContentText(context.getString(R.string.elevation_progress, job.completedTiles, job.totalTiles))
            .setProgress(100, progress, job.style == null).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(content).build()
        return ForegroundInfo(("elevation-${job.packageId.value}".hashCode() and Int.MAX_VALUE), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
