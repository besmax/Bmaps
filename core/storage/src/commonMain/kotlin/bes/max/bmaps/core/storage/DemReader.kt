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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.math.floor

enum class DemFailure { UNSUPPORTED, INVALID_OR_UNREADABLE, MEMORY_LIMIT, CLOSED }

class DemReadException(val reason: DemFailure, val nativeStatus: Int? = null) :
    IOException("DEM read failed: $reason${nativeStatus?.let { " (nativeStatus=$it)" }.orEmpty()}")

sealed interface DemSample {
    data class Value(val rawValue: Double) : DemSample
    data object NoData : DemSample
    data object OutsideCoverage : DemSample
}

data class DemMetadata(
    val width: Int,
    val height: Int,
    val originLongitude: Double,
    val originLatitude: Double,
    val longitudeStep: Double,
    val latitudeStep: Double,
    val pixelIsPoint: Boolean,
    val bitsPerSample: Int,
    val sampleFormat: Int,
    val compression: Int,
    val tiled: Boolean,
    val decodedBlockBytes: Long,
    val hasGdalMetadata: Boolean = false,
) {
    val crs: String get() = "EPSG:4326"

    internal fun cell(latitude: Double, longitude: Double): Pair<Int, Int>? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val shift = if (pixelIsPoint) 0.5 else 0.0
        val column = floor((longitude - originLongitude) / longitudeStep + shift)
        val row = floor((latitude - originLatitude) / latitudeStep + shift)
        if (column < 0 || column >= width || row < 0 || row >= height) return null
        return column.toInt() to row.toInt()
    }
}

@Inject
@SingleIn(AppScope::class)
class DemReaderFactory {
    suspend fun open(path: String): DemReader {
        require(path.isNotEmpty() && '\u0000' !in path)
        var opened: NativeDemHandle? = null
        try {
            return withContext(Dispatchers.IO) {
                val handle = openNativeDem(path).also { opened = it }
                DemReader(handle, handle.metadata())
            }
        } catch (error: Throwable) {
            opened?.close()
            throw error
        }
    }
}

class DemReader internal constructor(
    private val handle: NativeDemHandle,
    val metadata: DemMetadata,
) {
    private val mutex = Mutex()
    private var closed = false

    suspend fun sample(latitude: Double, longitude: Double): DemSample = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            val cell = metadata.cell(latitude, longitude) ?: return@withLock DemSample.OutsideCoverage
            handle.sample(cell.first, cell.second)
        }
    }

    suspend fun sampleCell(column: Int, row: Int): DemSample = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            if (column !in 0 until metadata.width || row !in 0 until metadata.height) {
                DemSample.OutsideCoverage
            } else handle.sample(column, row)
        }
    }

    suspend fun readRow(columns: IntArray, row: Int): DoubleArray = withContext(Dispatchers.IO) {
        require(columns.size in 1..8192)
        mutex.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureOpen()
            handle.samples(columns, row).also { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
        }
    }

    suspend fun enableRasterCache() = withContext(Dispatchers.IO) {
        mutex.withLock { ensureOpen(); handle.enableCache() }
    }

    suspend fun readGrid(columns: IntArray, rows: IntArray): DoubleArray = withContext(Dispatchers.IO) {
        require(columns.size in 1..8192 && rows.size in 1..1024)
        require(columns.size.toLong() * rows.size <= 131072)
        mutex.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureOpen()
            handle.grid(columns, rows).also { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
        }
    }

    suspend fun rangeChunk(firstBlock: Long): DemRangeChunk = withContext(Dispatchers.IO) {
        require(firstBlock in 0..UInt.MAX_VALUE.toLong())
        mutex.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ensureOpen()
            val values = handle.range(firstBlock)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            DemRangeChunk(values[0].toLong(), values[1].toLong(), values[2], values[3], values[4].toLong())
        }
    }

    suspend fun metrics(): DoubleArray = withContext(Dispatchers.IO) {
        mutex.withLock { ensureOpen(); handle.metrics() }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                closed = true
                handle.close()
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw DemReadException(DemFailure.CLOSED)
    }
}

data class DemRangeChunk(val nextBlock: Long, val totalBlocks: Long, val minimum: Double, val maximum: Double, val validSamples: Long)

internal interface NativeDemHandle {
    fun metadata(): DemMetadata
    fun sample(column: Int, row: Int): DemSample
    fun samples(columns: IntArray, row: Int): DoubleArray = DoubleArray(columns.size) {
        (sample(columns[it], row) as? DemSample.Value)?.rawValue ?: Double.NaN
    }
    fun enableCache() {}
    fun grid(columns: IntArray, rows: IntArray): DoubleArray = DoubleArray(columns.size * rows.size).also { output ->
        rows.forEachIndexed { index, row -> samples(columns, row).copyInto(output, index * columns.size) }
    }
    fun range(firstBlock: Long): DoubleArray = demFailure(3)
    fun metrics(): DoubleArray = DoubleArray(5)
    fun close()
}

internal expect fun openNativeDem(path: String): NativeDemHandle

internal fun demFailure(status: Int): Nothing = throw DemReadException(when (status) {
    3 -> DemFailure.UNSUPPORTED
    5 -> DemFailure.MEMORY_LIMIT
    else -> DemFailure.INVALID_OR_UNREADABLE
}, nativeStatus = status)

internal fun demSample(status: Int, value: Double): DemSample = when (status) {
    0 -> DemSample.Value(value)
    1 -> DemSample.NoData
    2 -> DemSample.OutsideCoverage
    else -> demFailure(status)
}

internal fun demMetadata(values: DoubleArray): DemMetadata {
    check(values.size == 13)
    return DemMetadata(values[0].toInt(), values[1].toInt(), values[2], values[3], values[4], values[5],
        values[6] != 0.0, values[7].toInt(), values[8].toInt(), values[9].toInt(), values[10] != 0.0,
        values[11].toLong(), values[12] != 0.0)
}
