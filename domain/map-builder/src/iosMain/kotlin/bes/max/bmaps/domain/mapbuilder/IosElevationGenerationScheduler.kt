/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import platform.BackgroundTasks.*
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.dispatch_get_main_queue

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosElevationGenerationScheduler(private val generator: ElevationLayerGenerator) : ElevationGenerationScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<PackageId, Deferred<PackageResult<Unit>>>()
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
                    success = true
                    generator.pending().forEach { job ->
                        val running = launchGeneration(job.packageId, foreground = false)
                        owned.add(running)
                        if (running.await() is PackageResult.Failure) success = false
                    }
                } catch (cancelled: CancellationException) { success = false; throw cancelled }
                catch (_: Exception) { success = false }
                finally { withContext(NonCancellable) {
                    owned.filter { it.isActive }.forEach { it.cancelAndJoin() }
                    task?.setTaskCompletedWithSuccess(success)
                    if (generator.pending().isNotEmpty()) submit()
                } }
            }
            task?.expirationHandler = { execution.cancel() }
            execution.start()
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue,
        ) { scope.launch { resume() } }
        scope.launch { resume() }
    }

    override suspend fun schedule(id: PackageId) = withContext(Dispatchers.Main.immediate) {
        submit()
        launchGeneration(id, foreground = true)
        Unit
    }

    override suspend fun cancel(id: PackageId) = withContext(Dispatchers.Main.immediate) {
        jobs[id]?.cancelAndJoin()
        if (generator.pending().isEmpty()) BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(IDENTIFIER)
    }

    private suspend fun resume() {
        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) return
        generator.pending().forEach { submit(); launchGeneration(it.packageId, foreground = true) }
    }

    private fun launchGeneration(id: PackageId, foreground: Boolean): Deferred<PackageResult<Unit>> {
        jobs[id]?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) {
            var token = UIBackgroundTaskInvalid
            try {
                if (foreground) token = UIApplication.sharedApplication.beginBackgroundTaskWithName("Elevation generation") {
                    jobs[id]?.cancel()
                }
                val current = generator.job(id) ?: return@async PackageResult.Success(Unit)
                generator.run(id, current.token)
            } finally { withContext(NonCancellable) {
                if (token != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(token)
                jobs.remove(id)
                if (generator.pending().isNotEmpty()) submit()
            } }
        }
        jobs[id] = job
        job.start()
        return job
    }

    private fun submit(): Boolean {
        if (!registered) return false
        val request = BGProcessingTaskRequest(IDENTIFIER)
        request.requiresNetworkConnectivity = false
        request.requiresExternalPower = false
        return BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    private companion object { const val IDENTIFIER = "bes.max.bmaps.elevation-generation" }
}
