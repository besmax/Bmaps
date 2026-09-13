@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import platform.BackgroundTasks.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UserNotifications.*
import platform.darwin.dispatch_get_main_queue

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosDownloadScheduler(private val runner: DownloadRunner, private val storage: PackageBuildStorage) : DownloadScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<BuildJobId, Deferred<PackageResult<Unit>>>()
    private val wanted = mutableSetOf<BuildJobId>()
    private var initialized = false
    private var registered = false

    override fun initialize() {
        if (initialized) return
        initialized = true
        registered = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(IDENTIFIER, dispatch_get_main_queue()) { task ->
            val owned = mutableListOf<Deferred<PackageResult<Unit>>>()
            val execution = scope.launch(start = CoroutineStart.LAZY) {
                var success = false
                try {
                    val queued = storage.observeUnfinished().first().valueOrThrow().filter { it.state == BuildJobState.QUEUED }
                    success = true
                    for (progress in queued) {
                        wanted.add(progress.jobId)
                        val download = launchDownload(progress.jobId, foreground = false)
                        owned.add(download)
                        if (download.await() is PackageResult.Failure) success = false
                    }
                } catch (cancelled: CancellationException) { success = false; throw cancelled }
                catch (_: Exception) { success = false }
                finally {
                    withContext(NonCancellable) {
                        owned.filter { it.isActive }.forEach { it.cancelAndJoin() }
                        task?.setTaskCompletedWithSuccess(success)
                    }
                }
            }
            task?.expirationHandler = { scope.launch { execution.cancel() } }
            execution.start()
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue,
        ) { scope.launch { resumeQueued() } }
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground) {
            scope.launch { resumeQueued() }
        }
    }

    override suspend fun schedule(id: BuildJobId) = withContext(Dispatchers.Main.immediate) {
        wanted.add(id)
        submitBackgroundRequest()
        launchDownload(id, foreground = true)
        Unit
    }

    override suspend fun cancel(id: BuildJobId) = withContext(Dispatchers.Main.immediate) {
        wanted.remove(id)
        jobs[id]?.cancelAndJoin()
        if (wanted.isEmpty()) BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(IDENTIFIER)
    }

    private suspend fun resumeQueued() {
        val result = storage.observeUnfinished().first()
        if (result is PackageResult.Success) result.value.filter { it.state == BuildJobState.QUEUED }.forEach {
            wanted.add(it.jobId)
            submitBackgroundRequest()
            launchDownload(it.jobId, foreground = true)
        }
    }

    private fun launchDownload(id: BuildJobId, foreground: Boolean): Deferred<PackageResult<Unit>> {
        jobs[id]?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) {
            var token = UIBackgroundTaskInvalid
            try {
                if (foreground) token = UIApplication.sharedApplication.beginBackgroundTaskWithName("Map download") {
                    scope.launch { jobs[id]?.cancel() }
                }
                val result = runner.run(id)
                wanted.remove(id)
                if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
                    notifyCompletion(id, result is PackageResult.Success)
                }
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                wanted.remove(id)
                storage.setState(PackageId(id.value), BuildJobState.FAILED, PackageFailure.Io)
                PackageResult.Failure(PackageFailure.Io)
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (id in wanted) {
                            val scheduled = submitBackgroundRequest()
                            storage.setState(PackageId(id.value), if (scheduled) BuildJobState.QUEUED else BuildJobState.PAUSED)
                            if (!scheduled) wanted.remove(id)
                        }
                    } finally {
                        if (token != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(token)
                        jobs.remove(id)
                        if (wanted.isEmpty()) BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(IDENTIFIER)
                    }
                }
            }
        }
        jobs[id] = job
        job.start()
        return job
    }

    private fun submitBackgroundRequest(): Boolean {
        if (!registered) return false
        val request = BGProcessingTaskRequest(IDENTIFIER)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        return BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    private suspend fun notifyCompletion(id: BuildJobId, success: Boolean) {
        val request = storage.request(PackageId(id.value))
        val content = UNMutableNotificationContent()
        content.setTitle((request as? PackageResult.Success)?.value?.name ?: "Bmaps")
        content.setBody(NSBundle.mainBundle.localizedStringForKey(if (success) "download_complete" else "download_needs_attention", null, null))
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(id.value, content, null), null,
        )
    }

    private companion object { const val IDENTIFIER = "bes.max.bmaps.downloads" }
}
