package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow

@Inject
@ContributesBinding(AppScope::class)
class TileDownloadPlanner : DownloadPlanner {
    override suspend fun estimate(request: BuildRequest): PackageResult<BuildEstimate> = downloadResult {
        var largestCount = 0L
        val count = request.layers.fold(0L) { total, layer ->
            val next = PackageTileCoverage(request.bounds, layer.zoomRange, layer.zoomLevels).count
            largestCount = maxOf(largestCount, next)
            require(next <= Long.MAX_VALUE - total)
            total + next
        }
        val estimate = TileAreaEstimate.estimate(count)
        val elevation = request.elevationDataset.estimatedBytes(request.bounds)
        estimate.copy(estimatedPackageBytes = estimate.estimatedPackageBytes?.takeIf { it <= Long.MAX_VALUE - elevation }?.plus(elevation),
            estimatedLargestLayerBytes = TileAreaEstimate.estimate(largestCount).estimatedPackageBytes)
    }

    override fun tiles(request: BuildRequest) = flow {
        for (layer in request.layers) {
            for (key in PackageTileCoverage(request.bounds, layer.zoomRange, layer.zoomLevels).tiles()) {
                currentCoroutineContext().ensureActive()
                emit(PlannedTile(layer.id, key))
            }
        }
    }
}
