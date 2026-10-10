/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.TileKey
import bes.max.bmaps.core.mapengine.TileReadResult
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.providers.ProviderStyleId
import bes.max.bmaps.domain.providers.ProviderConfig
import bes.max.bmaps.domain.providers.ElevationDataset
import bes.max.bmaps.domain.providers.OnlineSourceResult
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil

fun interface DownloadSizeEstimator {
    fun estimates(request: BuildRequest): Flow<BuildEstimate>
}

@Inject
@SingleIn(AppScope::class)
@dev.zacsweers.metro.ContributesBinding(AppScope::class)
class SampledDownloadEstimator(private val sources: DownloadSourceOpener) : DownloadSizeEstimator {
    private data class SampleId(val source: ProviderStyleId, val config: ProviderConfig,
        val parameters: Map<String, String>, val tile: TileKey)
    private val samples = linkedMapOf<SampleId, Long>()
    private val lock = Mutex()
    private val requests = Semaphore(2)

    override fun estimates(request: BuildRequest): Flow<BuildEstimate> = flow {
        val levels = request.layers.map { layer ->
            layer.zoomLevels.ifEmpty { (layer.zoomRange.min..layer.zoomRange.max).toSet() }.sorted()
        }
        val counts = request.layers.mapIndexed { index, layer -> levels[index].associateWith { zoom ->
            PackageTileCoverage(request.bounds, ZoomRange(zoom, zoom), setOf(zoom)).count
        } }
        val averages = counts.map { it.keys.associateWith { 32_000L }.toMutableMap() }
        fun estimate(): BuildEstimate {
            val sizes = counts.mapIndexed { index, zooms ->
                zooms.entries.fold(65_536L as Long?) { size, (zoom, count) ->
                    val perTile = ceil(averages[index].getValue(zoom) * 1.08).toLong() + 128
                    size?.takeIf { count <= (Long.MAX_VALUE - it) / perTile }?.plus(count * perTile)
                }
            }
            val tiles = counts.flatMap { it.values }.fold(0L) { total, count ->
                if (count > Long.MAX_VALUE - total) Long.MAX_VALUE else total + count
            }
            val elevation = if (request.elevationDataset == ElevationDataset.NONE) 0L
                else request.elevationDataset.estimatedTileBytes(request.bounds, levels.flatten().toSet())
            val total = sizes.fold(elevation) { sum, size ->
                if (sum == null || size == null || size > Long.MAX_VALUE - sum) null else sum + size
            }
            return BuildEstimate(tiles, total, if (sizes.any { it == null }) null else sizes.filterNotNull().maxOrNull())
        }
        emit(estimate())
        withTimeoutOrNull(20_000) {
            request.layers.forEachIndexed { index, layer ->
                val opened = sources.open(layer)
                if (opened !is OnlineSourceResult.Available) return@forEachIndexed
                try {
                    for (zoom in levels[index].sortedByDescending { counts[index].getValue(it) }) {
                        val keys = PackageTileCoverage(request.bounds, ZoomRange(zoom, zoom), setOf(zoom)).sampleTiles()
                        val measured = coroutineScope {
                            keys.map { key -> async {
                                val id = SampleId(layer.source, layer.config, layer.endpointParameters, key)
                                lock.withLock { samples[id] } ?: requests.withPermit {
                                    val result = try { withTimeoutOrNull(3_000) { opened.source.read(key) } }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { null }
                                    (result as? TileReadResult.Available)?.bytes?.size?.toLong()?.also { bytes ->
                                        lock.withLock {
                                            samples[id] = bytes
                                            while (samples.size > 2048) samples.remove(samples.keys.first())
                                        }
                                    }
                                }
                            } }.awaitAll().filterNotNull()
                        }
                        if (measured.isNotEmpty()) {
                            averages[index][zoom] = measured.sum() / measured.size
                            emit(estimate())
                        }
                    }
                } finally {
                    withContext(NonCancellable) { opened.source.close() }
                }
            }
        }
    }.flowOn(Dispatchers.Default)
}
