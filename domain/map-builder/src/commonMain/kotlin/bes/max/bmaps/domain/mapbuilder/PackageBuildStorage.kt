package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.TileKey
import bes.max.bmaps.core.mapengine.TileReadFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.io.bytestring.ByteString

data class DownloadedTile(val key: TileKey, val bytes: ByteString)
data class FailedTile(val key: TileKey, val reason: TileReadFailure? = null)

interface PackageBuildStorage {
    suspend fun availableBytes(): PackageResult<Long>
    suspend fun prepare(request: BuildRequest, manifest: PackageManifest): PackageResult<BuildJobId>
    suspend fun request(id: PackageId): PackageResult<BuildRequest>
    suspend fun write(
        id: PackageId, layerId: LayerId, tiles: List<DownloadedTile>, failures: List<FailedTile>, receivedBytes: Long,
    ): PackageResult<BuildProgress>
    suspend fun contains(id: PackageId, layerId: LayerId, key: TileKey): PackageResult<Boolean>
    suspend fun layerComplete(id: PackageId, layerId: LayerId): PackageResult<Boolean> {
        val request = when (val result = request(id)) {
            is PackageResult.Success -> result.value
            is PackageResult.Failure -> return result
        }
        val layer = request.layers.firstOrNull { it.id == layerId } ?: return PackageResult.Failure(PackageFailure.NotFound)
        for (key in PackageTileCoverage(request.bounds, layer.zoomRange, layer.zoomLevels).tiles()) {
            when (val result = contains(id, layerId, key)) {
                is PackageResult.Success -> if (!result.value) return PackageResult.Success(false)
                is PackageResult.Failure -> return result
            }
        }
        return PackageResult.Success(true)
    }
    suspend fun elevationComplete(id: PackageId): PackageResult<Boolean> = PackageResult.Success(false)
    suspend fun beginElevation(id: PackageId): PackageResult<Unit> = PackageResult.Failure(PackageFailure.UnsupportedContent)
    suspend fun appendElevation(id: PackageId, bytes: ByteArray, count: Int): PackageResult<Unit> = PackageResult.Failure(PackageFailure.UnsupportedContent)
    suspend fun finishElevation(id: PackageId): PackageResult<Unit> = PackageResult.Failure(PackageFailure.UnsupportedContent)
    suspend fun discardElevation(id: PackageId): PackageResult<Unit> = PackageResult.Success(Unit)
    suspend fun setState(id: PackageId, state: BuildJobState, failure: PackageFailure? = null): PackageResult<Unit>
    suspend fun finalize(id: PackageId): PackageResult<Unit>
    suspend fun reconcile(): PackageResult<Unit>
    fun observeProgress(jobId: BuildJobId): Flow<PackageResult<BuildProgress>>
    fun observeUnfinished(): Flow<PackageResult<List<BuildProgress>>>
}
