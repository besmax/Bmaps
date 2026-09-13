package bes.max.bmaps.core.mapengine

object RasterTileValidation {
    fun hasExpectedDimensions(bytes: ByteArray, tileSize: Int): Boolean =
        tileSize > 0 && bytes.size in 1..2_000_000 && hasExpectedRasterDimensions(bytes, tileSize)
}
