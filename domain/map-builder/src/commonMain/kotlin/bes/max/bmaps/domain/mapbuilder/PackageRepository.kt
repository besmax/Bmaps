package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.TileSource
import kotlinx.coroutines.flow.Flow

data class PackageQuery(
    val nameContains: String = "",
    val states: Set<PackageState> = PackageState.entries.toSet(),
    val limit: Int = 50,
    val cursor: String? = null,
    val favouritesOnly: Boolean = false,
)

data class PackagePage(val items: List<PackageSummary>, val nextCursor: String?)

interface PackageRepository {
    fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>>
    fun observe(id: PackageId): Flow<PackageResult<PackageSummary>>
    suspend fun open(id: PackageId): PackageResult<OpenedPackage>
    suspend fun delete(id: PackageId): PackageResult<Unit>
    suspend fun setFavourite(id: PackageId, favourite: Boolean): PackageResult<Unit>
    suspend fun setAvatar(id: PackageId, avatar: MapAvatar): PackageResult<Unit>
}

interface OpenedPackage {
    val manifest: PackageManifest
    suspend fun openTiles(layerId: LayerId): PackageResult<TileSource>
    suspend fun close()
}
