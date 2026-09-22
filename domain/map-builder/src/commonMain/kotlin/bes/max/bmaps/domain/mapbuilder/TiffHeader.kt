package bes.max.bmaps.domain.mapbuilder

internal const val ELEVATION_PATH = "elevation.geotiff"

internal object TiffHeader {
    fun isValid(bytes: ByteArray, size: Long): Boolean {
        if (bytes.size < 8) return false
        val little = bytes[0] == 73.toByte() && bytes[1] == 73.toByte()
        if (!little && !(bytes[0] == 77.toByte() && bytes[1] == 77.toByte())) return false
        fun number(start: Int, count: Int): ULong {
            var value = 0uL
            repeat(count) { index ->
                val position = if (little) start + count - 1 - index else start + index
                value = (value shl 8) or bytes[position].toUByte().toULong()
            }
            return value
        }
        val magic = number(2, 2)
        val offset = when (magic) {
            42uL -> number(4, 4)
            43uL -> {
                if (bytes.size < 16 || number(4, 2) != 8uL || number(6, 2) != 0uL) return false
                number(8, 8)
            }
            else -> return false
        }
        val header = if (magic == 42uL) 8 else 16
        val minimumDirectory = if (magic == 42uL) 6 else 16
        return size >= header + minimumDirectory && offset >= header.toULong() && offset <= (size - minimumDirectory).toULong()
    }
}
