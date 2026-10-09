/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import kotlinx.serialization.Serializable

@Serializable
enum class ElevationPalette(val low: Int, val high: Int) {
    GREEN(0xffe8f5e9.toInt(), 0xff1b5e20.toInt()),
    BLUE(0xffe3f2fd.toInt(), 0xff0d47a1.toInt()),
    AMBER(0xfffff8e1.toInt(), 0xffbf360c.toInt()),
    PURPLE(0xfff3e5f5.toInt(), 0xff4a148c.toInt());

    fun color(position: Double): Int {
        val fraction = position.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val start = (low ushr shift) and 255
            val end = (high ushr shift) and 255
            return (start + (end - start) * fraction).toInt()
        }
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

@Serializable
data class ElevationReliefOptions(
    val palette: ElevationPalette = ElevationPalette.GREEN,
    val minimumMeters: Double? = null,
    val maximumMeters: Double? = null,
    val reversed: Boolean = false,
    val minimumColorPosition: Double? = null,
    val maximumColorPosition: Double? = null,
) {
    val minimumColor: Double get() = minimumColorPosition ?: if (reversed) 1.0 else 0.0
    val maximumColor: Double get() = maximumColorPosition ?: if (reversed) 0.0 else 1.0

    fun valid(): Boolean {
        val colorsValid = (minimumColorPosition == null && maximumColorPosition == null) ||
            (minimumColorPosition?.isFinite() == true && maximumColorPosition?.isFinite() == true &&
                minimumColorPosition in 0.0..1.0 && maximumColorPosition in 0.0..1.0)
        val heightsValid = (minimumMeters == null && maximumMeters == null) ||
            (minimumMeters?.isFinite() == true && maximumMeters?.isFinite() == true && minimumMeters < maximumMeters && (maximumMeters - minimumMeters).isFinite())
        return colorsValid && heightsValid
    }
}

@Serializable
data class ElevationReliefStyle(
    val options: ElevationReliefOptions,
    val minimumMeters: Double,
    val maximumMeters: Double,
) {
    fun color(height: Double): Int {
        if (!height.isFinite()) return 0
        val fraction = ((height - minimumMeters) / (maximumMeters - minimumMeters)).coerceIn(0.0, 1.0)
        return options.palette.color(options.minimumColor + (options.maximumColor - options.minimumColor) * fraction)
    }
}

@Serializable
enum class ElevationGenerationState { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }

@Serializable
data class ElevationGenerationJob(
    val packageId: PackageId,
    val token: String,
    val options: ElevationReliefOptions,
    val state: ElevationGenerationState = ElevationGenerationState.QUEUED,
    val completedTiles: Long = 0,
    val totalTiles: Long = 0,
    val style: ElevationReliefStyle? = null,
    val failure: PackageFailure? = null,
) {
    val active: Boolean get() = state == ElevationGenerationState.QUEUED || state == ElevationGenerationState.RUNNING
}

interface ElevationGenerationScheduler {
    fun initialize()
    suspend fun schedule(id: PackageId)
    suspend fun cancel(id: PackageId)
}
