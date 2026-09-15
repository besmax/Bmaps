package bes.max.bmaps.core.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Entity(tableName = "packages", indices = [Index(value = ["updatedAtEpochMillis", "id"])])
data class PackageRecord(
    @PrimaryKey val id: String,
    val name: String,
    val state: String,
    val manifestJson: String,
    val sizeBytes: Long,
    val updatedAtEpochMillis: Long,
    val hasElevationData: Boolean = false,
)

@Entity(
    tableName = "download_jobs",
    indices = [Index(value = ["packageId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = PackageRecord::class, parentColumns = ["id"], childColumns = ["packageId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DownloadJobRecord(
    @PrimaryKey val id: String,
    val packageId: String,
    val requestJson: String,
    val state: String,
    val totalTiles: Long,
    val completedTiles: Long = 0,
    val failedTiles: Long = 0,
    val receivedBytes: Long = 0,
    val packageBytes: Long = 0,
    val failure: String? = null,
)

@Entity(tableName = "package_preferences", foreignKeys = [ForeignKey(
    entity = PackageRecord::class, parentColumns = ["id"], childColumns = ["packageId"],
    onDelete = ForeignKey.CASCADE,
)])
data class PackagePreferencesRecord(
    @PrimaryKey val packageId: String,
    val favourite: Boolean = false,
    val avatar: String = "map",
)

@Dao
abstract class PackageDao {
    @Insert protected abstract suspend fun insertPackage(record: PackageRecord)
    @Insert protected abstract suspend fun insertJob(record: DownloadJobRecord)
    @Upsert abstract suspend fun putPackage(record: PackageRecord)
    @Upsert abstract suspend fun putPreferences(record: PackagePreferencesRecord)

    @Query("SELECT * FROM package_preferences WHERE packageId=:id")
    abstract suspend fun preferences(id: String): PackagePreferencesRecord?

    @Update abstract suspend fun updateJob(record: DownloadJobRecord)

    @Query("SELECT * FROM packages WHERE id=:id")
    abstract suspend fun get(id: String): PackageRecord?

    @Query("SELECT packages.* FROM packages LEFT JOIN package_preferences ON package_preferences.packageId = packages.id WHERE packages.id=:id")
    abstract fun observe(id: String): Flow<PackageRecord?>

    @Query("SELECT * FROM download_jobs WHERE packageId=:packageId")
    abstract suspend fun job(packageId: String): DownloadJobRecord?

    @Query("SELECT * FROM download_jobs WHERE id=:id")
    abstract fun observeJob(id: String): Flow<DownloadJobRecord?>

    @Query("SELECT * FROM download_jobs WHERE state != 'COMPLETED' ORDER BY id")
    abstract fun observeUnfinishedJobs(): Flow<List<DownloadJobRecord>>

    @Query("SELECT * FROM packages ORDER BY id")
    abstract suspend fun all(): List<PackageRecord>

    @Query("DELETE FROM packages WHERE id=:id")
    abstract suspend fun delete(id: String)

    @Query("""
        SELECT * FROM packages
        WHERE instr(lower(name), lower(:name)) > 0 AND (:allStates OR state IN (:states))
        AND (:favouritesOnly = 0 OR EXISTS (SELECT 1 FROM package_preferences WHERE packageId = packages.id AND favourite = 1))
        AND (:firstPage OR updatedAtEpochMillis < :updated OR (updatedAtEpochMillis = :updated AND id > :id))
        ORDER BY updatedAtEpochMillis DESC, id ASC LIMIT :count
    """)
    abstract fun page(
        name: String, states: List<String>, allStates: Boolean, favouritesOnly: Boolean,
        firstPage: Boolean, updated: Long, id: String, count: Int,
    ): Flow<List<PackageRecord>>

    @Transaction
    open suspend fun create(record: PackageRecord, job: DownloadJobRecord) {
        check(record.id == job.packageId)
        insertPackage(record)
        insertJob(job)
    }

    @Transaction
    open suspend fun checkpoint(record: PackageRecord, job: DownloadJobRecord) {
        check(record.id == job.packageId && get(record.id) != null && this.job(record.id)?.id == job.id)
        require(job.totalTiles > 0 && job.completedTiles in 0..job.totalTiles &&
            job.failedTiles in 0..(job.totalTiles - job.completedTiles))
        require(job.receivedBytes >= 0 && job.packageBytes >= 0)
        if (record.state == "READY" || job.state == "COMPLETED") {
            check(record.state == "READY" && job.state == "COMPLETED" &&
                job.completedTiles == job.totalTiles && job.failedTiles == 0L)
        }
        putPackage(record)
        updateJob(job)
    }
}

@Database(entities = [PackageRecord::class, DownloadJobRecord::class, PackagePreferencesRecord::class], version = 2, exportSchema = true)
@ConstructedBy(PackageDatabaseConstructor::class)
abstract class PackageDatabase : RoomDatabase() {
    abstract fun packages(): PackageDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object PackageDatabaseConstructor : RoomDatabaseConstructor<PackageDatabase> {
    override fun initialize(): PackageDatabase
}

fun packageDatabase(builder: RoomDatabase.Builder<PackageDatabase>): PackageDatabase = builder
    .addMigrations(object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS package_preferences (packageId TEXT NOT NULL, favourite INTEGER NOT NULL, avatar TEXT NOT NULL, PRIMARY KEY(packageId), FOREIGN KEY(packageId) REFERENCES packages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    })
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()

data class PackageFilter(val nameContains: String = "", val states: Set<String> = emptySet(), val favouritesOnly: Boolean = false)
data class PackageRecordPage(val items: List<PackageRecord>, val nextCursor: String?)

@Inject
@SingleIn(AppScope::class)
class PackageCatalog(database: PackageDatabase) {
    val records: PackageDao = database.packages()

    fun observe(filter: PackageFilter, limit: Int, cursor: String?): Flow<PackageRecordPage> {
        require(limit in 1..200)
        val states = filter.states.sorted()
        val previous = cursor?.let {
            require(it.length <= 4096)
            Json.decodeFromString<PackageCursor>(Base64.UrlSafe.decode(it).decodeToString()).also { decoded ->
                require(decoded.version == 1 && decoded.name == filter.nameContains && decoded.states == states && decoded.favouritesOnly == filter.favouritesOnly)
            }
        }
        return records.page(filter.nameContains, states, states.isEmpty(), filter.favouritesOnly, previous == null,
            previous?.updated ?: 0, previous?.id ?: "", limit + 1).map { rows ->
            val page = rows.take(limit)
            val next = if (rows.size > limit) page.last().let {
                Base64.UrlSafe.encode(Json.encodeToString(PackageCursor(
                    name = filter.nameContains, states = states, favouritesOnly = filter.favouritesOnly, updated = it.updatedAtEpochMillis, id = it.id,
                )).encodeToByteArray())
            } else null
            PackageRecordPage(page, next)
        }
    }
}

@Serializable
private data class PackageCursor(
    val favouritesOnly: Boolean = false, val version: Int = 1, val name: String, val states: List<String>, val updated: Long, val id: String,
)
