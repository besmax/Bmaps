/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.storage.ActivitySpatialStores
import bes.max.bmaps.core.storage.StoredActivityRoute
import bes.max.bmaps.core.storage.PackageRouteStore
import bes.max.bmaps.core.storage.PackageRouteUnavailable
import bes.max.bmaps.core.storage.PackageRouteFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
class LocalActivityRouteRepository(private val store: LocalActivityStore, private val packages: PackageRouteStore) : ActivityRouteRepository {
    override suspend fun get(id: String): ActivityResult<ActivityRoute> {
        routePackageId(id)?.let { owner -> return packageOperation {
            readPackage(owner).second.firstOrNull { it.id == id }?.owned() ?: activityFail(ActivityFailure.NotFound)
        } }
        return store.operation {
            validateActivityId(id)
            readRoute(id).owned()
        }
    }

    override suspend fun save(route: ActivityRoute, expectedRevision: Long?): ActivityResult<ActivityRoute> {
        routePackageId(route.id)?.let { owner -> return packageOperation {
            val (text, values) = readPackage(owner)
            activityRequire(ActivityValidation.route(route))
            if (expectedRevision == null && route.revision != 0L) activityFail(ActivityFailure.InvalidInput("revision"))
            val previous = values.firstOrNull { it.id == route.id }
            if ((previous?.revision) != expectedRevision || expectedRevision != null && route.revision != expectedRevision + 1) activityFail(ActivityFailure.RevisionConflict)
            writePackage(owner, text, values.filterNot { it.id == route.id } + route)
            route.owned()
        } }
        return store.operation {
            val ownedRoute = route.owned()
            activityRequire(ActivityValidation.route(ownedRoute))
            if (expectedRevision != null && expectedRevision < 0) activityFail(ActivityFailure.InvalidInput("revision"))
            val encoded = encode(ownedRoute)
            requireCapacity(encoded.payload.encodeToByteArray().size.toLong() * 2 + 1_048_576)
            val saved = routes.save(encoded, expectedRevision)
            store.project(saved)
            store.decode<ActivityRoute>(saved.payload).owned()
        }
    }

    override suspend fun saveImported(routes: List<ActivityRoute>): ActivityResult<Unit> {
        val owner = routes.firstOrNull()?.id?.let(::routePackageId)
        if (owner != null) return packageOperation {
            require(routes.isNotEmpty() && routes.all { routePackageId(it.id) == owner && it.revision == 0L })
            require(routes.size <= ActivityLimits.MAX_IMPORT_ROUTES)
            require(routes.sumOf { it.segments.sumOf { segment -> segment.vertices.size.toLong() } } <= ActivityLimits.MAX_ROUTE_VERTICES)
            require(routes.sumOf { it.segments.size } <= ActivityLimits.MAX_ROUTE_SEGMENTS)
            require(routes.sumOf { it.controlPoints.size } <= ActivityLimits.MAX_CONTROL_POINTS)
            routes.forEach { activityRequire(ActivityValidation.route(it)) }
            val (text, existing) = readPackage(owner)
            require(routes.map { it.id }.distinct().size == routes.size)
            if (routes.all { value -> existing.any { it == value } }) return@packageOperation Unit
            if (existing.any { old -> routes.any { it.id == old.id } }) activityFail(ActivityFailure.RevisionConflict)
            writePackage(owner, text, existing + routes)
        }
        return store.operation {
            if (routes.size !in 1..ActivityLimits.MAX_IMPORT_ROUTES) activityFail(ActivityFailure.InvalidInput("importLimits"))
            val ownedRoutes = routes.map { it.owned() }
            if (ownedRoutes.any { routePackageId(it.id) != null }) activityFail(ActivityFailure.InvalidInput("routeOwner"))
            if (ownedRoutes.map { it.id }.distinct().size != ownedRoutes.size ||
                ownedRoutes.sumOf { it.segments.sumOf { segment -> segment.vertices.size.toLong() } } > ActivityLimits.MAX_ROUTE_VERTICES ||
                ownedRoutes.sumOf { it.segments.size } > ActivityLimits.MAX_ROUTE_SEGMENTS ||
                ownedRoutes.sumOf { it.controlPoints.size } > ActivityLimits.MAX_CONTROL_POINTS) activityFail(ActivityFailure.InvalidInput("importLimits"))
            ownedRoutes.forEach { activityRequire(ActivityValidation.route(it)); if (it.revision != 0L) activityFail(ActivityFailure.InvalidInput("revision")) }
            val encoded = ownedRoutes.map(::encode)
            val bytes = encoded.sumOf { it.payload.encodeToByteArray().size.toLong() }
            if (bytes > ActivityLimits.MAX_ROUTE_STORAGE_BYTES) activityFail(ActivityFailure.CapacityExceeded)
            requireCapacity(bytes * 2 + 1_048_576)
            this.routes.importRoutes(encoded)
            for (row in encoded) store.project(row)
        }
    }

    override suspend fun routes(query: ActivityRouteQuery): ActivityResult<ActivityRoutePage> {
        query.packageId?.let { owner ->
            val indexed = packageOperation { packages.read(owner) }
            if (indexed is ActivityResult.Failure) return indexed
        }
        return store.operation {
            if (query.limit !in 1..ActivityLimits.MAX_PAGE_SIZE || query.nameContains.length > 120 ||
                query.afterId?.let { !ActivityValidation.id(it) } == true) activityFail(ActivityFailure.InvalidInput("routeQuery"))
            val rows = store.records.routes(query.afterId ?: "", query.nameContains, query.limit + 1, query.packageId?.let { "map:$it:" }.orEmpty())
            val page = rows.take(query.limit)
            ActivityRoutePage(page.map { ActivityRouteSummary(it.id, it.name, it.revision, it.vertexCount, it.segmentCount) },
                if (rows.size > query.limit) page.last().id else null)
        }
    }

    override suspend fun derive(activityId: String, routeId: String): ActivityResult<ActivityRoute?> {
        val result = deriveSnapshot(activityId, routeId)
        if (result is ActivityResult.Failure) return result
        val route = (result as ActivityResult.Success<ActivityRoute?>).value ?: return result
        val owner = routePackageId(route.id) ?: return result
        return packageOperation {
            val (text, existing) = readPackage(owner)
            if (existing.none { it.id == route.id }) writePackage(owner, text, existing + route)
            route
        }
    }

    private suspend fun deriveSnapshot(activityId: String, routeId: String): ActivityResult<ActivityRoute?> = store.operation {
        validateActivityId(activityId); validateActivityId(routeId)
        val activity = with(store) { read(activities.get(activityId) ?: activityFail(ActivityFailure.NotFound)) }
        if (activity.status != ActivityStatus.FINALIZING && activity.status != ActivityStatus.COMPLETED) activityFail(ActivityFailure.ActivityBusy)
        val existing = routes.derived(activityId)
        if (existing != null && routes.get(existing) != null) return@operation readRoute(existing).owned()
        val segments = mutableListOf<RouteSegment>()
        var currentId: String? = null
        var vertices = mutableListOf<RouteVertex>()
        var count = 0
        fun finishSegment() {
            if (vertices.size >= 2) {
                if (segments.size >= ActivityLimits.MAX_ROUTE_SEGMENTS) activityFail(ActivityFailure.CapacityExceeded)
                segments += RouteSegment(checkNotNull(currentId), vertices.toList())
            }
            vertices = mutableListOf()
        }
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = activities.positions(activityId, after, 500)
            if (page.isEmpty()) break
            for (row in page) {
                val sample = store.decode<TelemetrySample>(row.payload)
                if (ActivityValidation.sample(sample) != null) activityFail(ActivityFailure.CorruptData)
                val position = sample.measurement as? PositionMeasurement ?: activityFail(ActivityFailure.CorruptData)
                if (row.segmentId != currentId) { finishSegment(); currentId = checkNotNull(row.segmentId) }
                if (vertices.lastOrNull()?.coordinate != position.coordinate) {
                    if (++count > ActivityLimits.MAX_ROUTE_VERTICES) activityFail(ActivityFailure.CapacityExceeded)
                    vertices += RouteVertex(position.coordinate, sourceTimeEpochMillis = sample.acquiredAt.epochMillis)
                }
            }
            after = page.last().sequence
        }
        finishSegment()
        if (segments.isEmpty()) return@operation null
        val route = ActivityRoute(activity.packageId?.let { packageRouteId(it, routeId) } ?: routeId, 0, activity.name.ifBlank { Instant.fromEpochMilliseconds(activity.startedAt.epochMillis).toString().substringBefore('T') }, segments)
        activityRequire(ActivityValidation.route(route))
        val encoded = encode(route)
        requireCapacity(encoded.payload.encodeToByteArray().size.toLong() * 2 + 1_048_576)
        val savedId = routes.derive(activityId, encoded)
        val saved = routes.get(savedId) ?: activityFail(ActivityFailure.CorruptData)
        store.project(saved)
        store.decode<ActivityRoute>(saved.payload).owned()
    }

    override suspend fun delete(id: String): ActivityResult<Unit> {
        routePackageId(id)?.let { owner -> return packageOperation {
            val (text, values) = readPackage(owner)
            writePackage(owner, text, values.filterNot { it.id == id })
            when (val deleted = store.operation { routes.delete(id); store.records.deleteRoute(id) }) {
                is ActivityResult.Failure -> activityFail(deleted.reason)
                is ActivityResult.Success -> Unit
            }
        } }
        return store.operation {
            validateActivityId(id)
            requireCapacity(1_048_576)
            routes.delete(id)
            store.records.deleteRoute(id)
        }
    }

    private val packageMutex = Mutex()
    private var cachedPackageText: String? = null
    private var cachedPackageRoutes: List<ActivityRoute> = emptyList()

    private suspend fun readPackage(owner: String): Pair<String?, List<ActivityRoute>> {
        val text = packages.read(owner) ?: return null to emptyList()
        if (text == cachedPackageText) return text to cachedPackageRoutes
        val root = store.json.parseToJsonElement(text).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
        val values = store.json.decodeFromJsonElement(ListSerializer(ActivityRoute.serializer()), root.getValue("routes")).map { it.owned() }
        require(values.all { routePackageId(it.id) == owner && ActivityValidation.route(it) == null })
        cachedPackageText = text; cachedPackageRoutes = values
        return text to values
    }

    private suspend fun writePackage(owner: String, expected: String?, values: List<ActivityRoute>) {
        val text = buildJsonObject {
            put("schemaVersion", 1)
            put("routes", store.json.encodeToJsonElement(ListSerializer(ActivityRoute.serializer()), values))
        }.toString()
        require(text.encodeToByteArray().size <= ActivityLimits.MAX_ROUTE_STORAGE_BYTES)
        packages.write(owner, expected, text)
    }

    private suspend fun <T> packageOperation(block: suspend () -> T): ActivityResult<T> = try {
        ActivityResult.Success(withContext(Dispatchers.IO) { packageMutex.withLock { block() } })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { ActivityResult.Failure(when (error) {
        is ActivityStorageFailure -> error.reason
        is PackageRouteUnavailable -> when (error.reason) {
            PackageRouteFailure.NOT_FOUND -> ActivityFailure.NotFound
            PackageRouteFailure.CONFLICT -> ActivityFailure.RevisionConflict
            PackageRouteFailure.CAPACITY -> ActivityFailure.CapacityExceeded
            PackageRouteFailure.INVALID -> ActivityFailure.CorruptData
            PackageRouteFailure.UNAVAILABLE -> ActivityFailure.StorageUnavailable
        }
        is IllegalArgumentException -> ActivityFailure.InvalidInput("packageRoute")
        else -> ActivityFailure.StorageUnavailable
    }) }

    private fun ActivitySpatialStores.readRoute(id: String): ActivityRoute {
        val row = routes.get(id) ?: activityFail(ActivityFailure.NotFound)
        return store.decode<ActivityRoute>(row.payload).also {
            if (ActivityValidation.route(it) != null || it.id != row.id || it.revision != row.revision || it.name != row.name ||
                it.segments.size != row.segmentCount || it.segments.sumOf { segment -> segment.vertices.size } != row.vertexCount) activityFail(ActivityFailure.CorruptData)
        }
    }

    private fun encode(route: ActivityRoute): StoredActivityRoute {
        val encoded = store.json.encodeToString(route)
        if (encoded.encodeToByteArray().size > ActivityLimits.MAX_ROUTE_STORAGE_BYTES) activityFail(ActivityFailure.CapacityExceeded)
        return StoredActivityRoute(route.id, route.revision, route.name, route.segments.sumOf { it.vertices.size }, route.segments.size, encoded)
    }
}
