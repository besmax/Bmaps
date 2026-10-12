/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Entity(tableName = "activity_catalog", indices = [Index(value = ["startedAtEpochMillis", "id"])])
data class ActivityCatalogRecord(
    @PrimaryKey val id: String,
    val name: String,
    val profile: String,
    val status: String,
    val revision: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val routeId: String?,
    val generation: String,
)

@Entity(tableName = "activity_route_catalog")
data class ActivityRouteCatalogRecord(
    @PrimaryKey val id: String,
    val name: String,
    val revision: Long,
    val vertexCount: Int,
    val segmentCount: Int,
    val generation: String,
)

@Dao
abstract class ActivityCatalogDao {
    @Upsert abstract suspend fun putActivities(rows: List<ActivityCatalogRecord>)
    @Upsert abstract suspend fun putRoutes(rows: List<ActivityRouteCatalogRecord>)

    @Query("DELETE FROM activity_catalog WHERE id=:id")
    abstract suspend fun deleteActivity(id: String)

    @Query("DELETE FROM activity_route_catalog WHERE id=:id")
    abstract suspend fun deleteRoute(id: String)

    @Query("DELETE FROM activity_catalog WHERE generation != :generation")
    protected abstract suspend fun pruneActivities(generation: String)

    @Query("DELETE FROM activity_route_catalog WHERE generation != :generation")
    protected abstract suspend fun pruneRoutes(generation: String)

    @Transaction
    open suspend fun finishReconciliation(generation: String) {
        pruneActivities(generation)
        pruneRoutes(generation)
    }

    @Query("""
        SELECT * FROM activity_catalog
        WHERE instr(lower(name), lower(:name)) > 0
        AND (:allProfiles OR profile IN (:profiles)) AND (:allStatuses OR status IN (:statuses))
        AND (:firstPage OR startedAtEpochMillis < :started OR (startedAtEpochMillis = :started AND id > :id))
        ORDER BY startedAtEpochMillis DESC, id ASC LIMIT :count
    """)
    abstract suspend fun activities(
        name: String, profiles: List<String>, allProfiles: Boolean, statuses: List<String>, allStatuses: Boolean,
        firstPage: Boolean, started: Long, id: String, count: Int,
    ): List<ActivityCatalogRecord>

    @Query("SELECT * FROM activity_route_catalog WHERE id > :after AND instr(lower(name), lower(:name)) > 0 ORDER BY id LIMIT :count")
    abstract suspend fun routes(after: String, name: String, count: Int): List<ActivityRouteCatalogRecord>
}

@Inject
@SingleIn(AppScope::class)
class ActivityCatalog(database: PackageDatabase) {
    val records: ActivityCatalogDao = database.activities()
}
