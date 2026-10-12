/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

data class ActivityStorageLocation(val path: String)

@Inject
@SingleIn(AppScope::class)
class ActivityFileStorage(private val location: ActivityStorageLocation) {
    private val mutex = Mutex()

    suspend fun <T> access(verify: Boolean = false, block: suspend ActivitySpatialStores.() -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = Path(location.path)
            require(!isSymbolicLink(root.toString())) { "Unsafe activity storage root" }
            SystemFileSystem.createDirectories(root)
            val resolved = SystemFileSystem.resolve(root)
            fun databasePath(directory: String, name: String): String {
                val parent = Path(resolved, directory)
                if (isSymbolicLink(parent.toString())) throw UnsafePackagePath()
                SystemFileSystem.createDirectories(parent)
                if (SystemFileSystem.resolve(parent) != parent) throw UnsafePackagePath()
                val path = Path(parent, name)
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    if (isSymbolicLink(path.toString() + suffix)) throw UnsafePackagePath()
                }
                return path.toString()
            }
            val activities = SpatialSql.open(databasePath("activities", "activity.sqlite"), verify, ActivityDatabase.SCHEMA)
            try {
                val routes = SpatialSql.open(databasePath("routes", "routes.sqlite"), verify, ActivityRouteDatabase.SCHEMA)
                try {
                    ActivitySpatialStores(ActivityDatabase(activities), ActivityRouteDatabase(routes)) { bytes ->
                        require(bytes >= 0)
                        if (availableStorageBytes(resolved.toString()) < bytes) throw StorageCapacityExceeded(bytes)
                    }.block()
                    .also {
                        syncPath(Path(resolved, "activities").toString(), true)
                        syncPath(Path(resolved, "routes").toString(), true)
                        syncPath(resolved.toString(), true)
                    }
                } finally { routes.close() }
            } finally { activities.close() }
        }
    }
}

class ActivitySpatialStores internal constructor(
    val activities: ActivityDatabase,
    val routes: ActivityRouteDatabase,
    private val capacity: (Long) -> Unit,
) {
    fun requireCapacity(bytes: Long) = capacity(bytes)
}
