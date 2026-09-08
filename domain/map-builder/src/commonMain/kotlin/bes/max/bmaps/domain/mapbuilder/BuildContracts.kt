package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.TileKey
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.providers.ProviderConfig
import bes.max.bmaps.domain.providers.ProviderStyleId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class BuildLayerRequest(
    val id: LayerId,
    val source: ProviderStyleId,
    val config: ProviderConfig,
    val zoomRange: ZoomRange,
    val endpointParameters: Map<String, String> = emptyMap(),
)

@Serializable
data class BuildRequest(
    val packageId: PackageId,
    val name: String,
    val bounds: BoundingBox,
    val layers: List<BuildLayerRequest>,
    val sizePolicy: PackageSizePolicy = PackageSizePolicy(),
)

data class PlannedTile(val layerId: LayerId, val key: TileKey)

data class BuildEstimate(val tileCount: Long, val estimatedPackageBytes: Long?)

interface DownloadPlanner {
    suspend fun estimate(request: BuildRequest): PackageResult<BuildEstimate>
    fun tiles(request: BuildRequest): Flow<PlannedTile>
}

@Serializable
data class BuildJobId(val value: String)

enum class BuildJobState { QUEUED, RUNNING, PAUSED, FINALIZING, COMPLETED, CANCELLED, FAILED }

data class BuildProgress(
    val jobId: BuildJobId,
    val packageId: PackageId,
    val state: BuildJobState,
    val totalTiles: Long,
    val completedTiles: Long,
    val failedTiles: Long,
    val receivedBytes: Long,
    val packageBytes: Long,
    val failure: PackageFailure? = null,
)

enum class PartialPackageRetention { KEEP_FOR_RESUME, DELETE }

interface DownloadExecutor {
    suspend fun start(request: BuildRequest): PackageResult<BuildJobId>
    fun observe(jobId: BuildJobId): Flow<PackageResult<BuildProgress>>
    suspend fun pause(jobId: BuildJobId): PackageResult<Unit>
    suspend fun resume(jobId: BuildJobId): PackageResult<Unit>
    suspend fun cancel(jobId: BuildJobId, retention: PartialPackageRetention): PackageResult<Unit>
}
