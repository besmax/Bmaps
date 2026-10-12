/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@SingleIn(AppScope::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SharedLocationService(private val native: LocationService) : LocationService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val refreshVersion = MutableStateFlow(0L)
    private val requests = MutableStateFlow<Map<Any, LocationRequest>>(emptyMap())
    private val shared = requests.map { values ->
        values.values.takeIf { it.isNotEmpty() }?.let { all ->
            LocationRequest(all.minOf { it.intervalMillis }, all.minOf { it.minimumDistanceMeters }, all.any { it.background })
        }
    }.combine(refreshVersion) { request, version -> request to version }
        .distinctUntilChanged().flatMapLatest { (request, _) ->
            if (request == null) flowOf(LocationState()) else native.observe(request).catch { error ->
                if (error is CancellationException) throw error
                locationDiagnostic("Shared location subscription failed", error)
                emit(LocationState(LocationStatus.UNAVAILABLE, deliveryGap = true))
            }
        }.buffer(256).shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 1)

    fun refresh() { refreshVersion.update { it + 1 } }

    override fun observe(request: LocationRequest): Flow<LocationState> = flow {
        val token = Any()
        mutex.withLock { requests.value = requests.value + (token to request) }
        try {
            emitAll(shared)
        } finally {
            withContext(NonCancellable) { mutex.withLock { requests.value = requests.value - token } }
        }
    }
}
