package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.BoundingBox
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
enum class ElevationDataset(val apiValue: String?, val arcSeconds: Double, val maxAreaKm2: Double) {
    SRTM15Plus("SRTM15Plus", 15.0, 125_000_000.0),
    NASADEM("NASADEM", 1.0, 450_000.0),
    COP30("COP30", 1.0, 450_000.0),
    COP90("COP90", 3.0, 4_050_000.0),
    EU_DTM("EU_DTM", 1.0, 450_000.0),
    NONE(null, 0.0, 0.0);

    fun supportsRequest(bounds: BoundingBox): Boolean {
        if (this == NONE) return true
        if (!listOf(bounds.west, bounds.east, bounds.south, bounds.north).all { it.isFinite() } ||
            bounds.west !in -180.0..180.0 || bounds.east !in -180.0..180.0 ||
            bounds.south !in -90.0..90.0 || bounds.north !in -90.0..90.0 ||
            bounds.west >= bounds.east || bounds.south >= bounds.north) return false
        val radians = PI / 180.0
        val area = 6371.0088.pow(2) * (bounds.east - bounds.west) * radians *
            (sin(bounds.north * radians) - sin(bounds.south * radians))
        return area <= maxAreaKm2
    }

    fun estimatedBytes(bounds: BoundingBox): Long {
        if (this == NONE) return 0
        val columns = ceil((bounds.east - bounds.west).coerceAtLeast(0.0) * 3600 / arcSeconds) + 2
        val rows = ceil((bounds.north - bounds.south).coerceAtLeast(0.0) * 3600 / arcSeconds) + 2
        return (columns * rows * 4 + 65_536).toLong()
    }
}

const val OPENTOPOGRAPHY_CREDENTIAL = "opentopography"
