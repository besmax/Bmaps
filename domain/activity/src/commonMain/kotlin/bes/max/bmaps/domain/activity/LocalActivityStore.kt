/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bes.max.bmaps.domain.activity

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import bes.max.bmaps.core.database.ActivityCatalog
import bes.max.bmaps.core.database.ActivityCatalogRecord
import bes.max.bmaps.core.database.ActivityRouteCatalogRecord
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.storage.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@Inject
@SingleIn(AppScope::class)
class LocalActivityStore(private val storage: ActivityFileStorage, private val catalog: ActivityCatalog) {
    internal val active = MutableStateFlow<ActivityRecord?>(null)
    internal val records get() = catalog.records
    internal val json = Json { encodeDefaults = true }
    private val mutex = Mutex()
    private var initialized = false
    private var projectionGeneration = ""
    private var cachedSnapshotId: String? = null
    private var cachedNavigationKey: Pair<String, String>? = null
    private var cachedNavigationRoute: ActivityRoute? = null
    private var cachedSnapshot: RouteSelection? = null

    internal suspend fun <T> operation(forceReconcile: Boolean = false, block: suspend ActivitySpatialStores.() -> T): ActivityResult<T> = try {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    storage.access(verify = !initialized || forceReconcile) {
                        if (!initialized || forceReconcile) { cachedNavigationKey = null; cachedNavigationRoute = null; reconcileProjection(); initialized = true }
                        val result = block()
                        active.value = activities.active()?.let { read(it) }
                        ActivityResult.Success(result)
                    }
                } catch (error: Exception) { initialized = false; throw error }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        ActivityResult.Failure(when (error) {
            is ActivityStorageFailure -> error.reason
            is SpatialUnsupportedVersion -> ActivityFailure.UnsupportedVersion
            is SpatialCorruptData -> ActivityFailure.CorruptData
            is SpatialRevisionConflict -> ActivityFailure.RevisionConflict
            is SpatialNotFound -> ActivityFailure.NotFound
            is SpatialOwnerBusy -> ActivityFailure.AnotherActivityActive
            is StorageCapacityExceeded -> ActivityFailure.CapacityExceeded
            is IllegalArgumentException -> ActivityFailure.InvalidInput("storage")
            else -> ActivityFailure.StorageUnavailable
        })
    }

    internal suspend fun reconcile(): ActivityResult<Unit> = operation(forceReconcile = true) { Unit }

    internal fun ActivitySpatialStores.read(value: StoredActivity): ActivityRecord {
        val metadata = decodeRecord(value)
        val snapshot = if (cachedSnapshotId == value.id) cachedSnapshot else {
            val decoded = activities.snapshot(value.id)?.let {
                decode<RouteSelection>(it).also { selection ->
                    if (ActivityValidation.selection(selection) != null) throw SpatialCorruptData()
                }
            }
            if ((decoded?.snapshot?.id) != value.routeId) throw SpatialCorruptData()
            if (activities.active()?.id == value.id) { cachedSnapshotId = value.id; cachedSnapshot = decoded?.let { it.copy(snapshot = it.snapshot.owned()) } }
            decoded?.let { it.copy(snapshot = it.snapshot.owned()) }
        }
        return metadata.copy(route = snapshot).also { record ->
            record.navigation?.takeIf { it.mode == ActivityNavigationMode.PLANNED_ROUTE }?.let { nav ->
                if (record.route == null || ActivityValidation.navigation(nav, record.route.snapshot) != null || nav.direction != record.route.direction || nav.origin != record.route.start) throw SpatialCorruptData()
            }
        }
    }

    internal fun decodeRecord(value: StoredActivity): ActivityRecord = decode<ActivityRecord>(value.payload).also {
        if (ActivityValidation.recordMetadata(it) != null || it.id != value.id || it.revision != value.revision ||
            it.sessionGeneration != value.generation || it.startedAt.epochMillis != value.startedAt || it.route != null) throw SpatialCorruptData()
    }

    internal fun ActivitySpatialStores.navigationRoute(record: ActivityRecord): ActivityRoute? {
        val nav = record.navigation ?: return record.route?.snapshot
        if (nav.mode == ActivityNavigationMode.PLANNED_ROUTE) return record.route?.snapshot ?: throw SpatialCorruptData()
        val key = record.id to nav.routeId
        val route = if (key == cachedNavigationKey) checkNotNull(cachedNavigationRoute) else {
            val payload = activities.navigationSnapshot(record.id, nav.routeId) ?: throw SpatialCorruptData()
            decode<ActivityRoute>(payload).also { if (ActivityValidation.route(it) != null) throw SpatialCorruptData() }.owned()
        }
        if (ActivityValidation.navigation(nav, route) != null) throw SpatialCorruptData()
        cachedNavigationKey = key; cachedNavigationRoute = route
        return route
    }

    internal inline fun <reified T> decode(payload: String): T = try { json.decodeFromString<T>(payload) }
    catch (error: Exception) { throw SpatialCorruptData() }

    internal fun stored(value: ActivityRecord): StoredActivity = StoredActivity(value.id, value.revision, value.sessionGeneration,
        value.startedAt.epochMillis, json.encodeToString(value.copy(route = null)), value.route?.snapshot?.id)

    internal suspend fun project(value: StoredActivity) {
        val record = decodeRecord(value)
        records.putActivities(listOf(ActivityCatalogRecord(record.id, record.name, record.profile.name, record.status.name,
            record.revision, record.startedAt.epochMillis, record.finishedAt?.epochMillis, record.packageId?.let { "map:$it:${value.routeId.orEmpty()}" } ?: value.routeId, projectionGeneration)))
    }

    internal suspend fun project(value: StoredActivityRoute) {
        if (routePackageId(value.id) != null) return
        records.putRoutes(listOf(ActivityRouteCatalogRecord(
        value.id, value.name, value.revision, value.vertexCount, value.segmentCount, projectionGeneration,
    )))
    }

    private suspend fun ActivitySpatialStores.reconcileProjection() {
        projectionGeneration = Uuid.random().toString()
        cachedSnapshotId = null
        cachedSnapshot = null
        var after = ""
        while (true) {
            val page = activities.page(after, 500)
            if (page.isEmpty()) break
            for (item in page) project(item)
            after = page.last().id
        }
        after = ""
        while (true) {
            val page = routes.page(after, 500)
            if (page.isEmpty()) break
            for (item in page) project(item)
            after = page.last().id
        }
        records.finishReconciliation(projectionGeneration)
        active.value = activities.active()?.let { read(it) }
    }
}

internal class ActivityStorageFailure(val reason: ActivityFailure) : Exception()
internal fun activityFail(reason: ActivityFailure): Nothing = throw ActivityStorageFailure(reason)
internal fun activityRequire(error: String?) { if (error != null) activityFail(ActivityFailure.InvalidInput(error)) }
internal fun validateActivityId(value: String) { if (!ActivityValidation.id(value)) activityFail(ActivityFailure.InvalidInput("id")) }

internal fun ActivityRoute.owned(): ActivityRoute = if (segments is PersistentList<*> && controlPoints is PersistentList<*> && segments.all { it.vertices is PersistentList<*> }) this else
    copy(segments = segments.map { it.copy(vertices = it.vertices.toPersistentList()) }.toPersistentList(), controlPoints = controlPoints.toPersistentList())
internal fun ActivityRecord.owned() = copy(route = route?.let { it.copy(snapshot = it.snapshot.owned()) })
internal fun activityDigest(value: String): String = TransferDigest().apply { update(value.encodeToByteArray()) }.finish()
