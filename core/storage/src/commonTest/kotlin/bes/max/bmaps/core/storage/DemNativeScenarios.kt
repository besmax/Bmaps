package bes.max.bmaps.core.storage

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal object DemNativeScenarios {
    suspend fun sampleReferenceFiles() {
        val directory = Path(SystemTemporaryDirectory, "bmaps-dem-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(directory)
        try {
            for ((name, base64) in DemFixtureBytes.files) {
                val path = Path(directory, name)
                SystemFileSystem.sink(path).buffered().use { it.write(Base64.decode(base64)) }
                val reader = DemReaderFactory().open(path.toString())
                try {
                    if (name == "int16-be-lzw.tif") {
                        assertEquals(7, reader.metadata.width)
                        assertEquals(DemSample.Value(-10.0), reader.sample(49.875, 10.125))
                        assertEquals(DemSample.Value(24.0), reader.sampleCell(6, 4))
                        assertEquals(DemSample.NoData, reader.sampleCell(3, 2))
                        assertEquals(DemSample.OutsideCoverage, reader.sampleCell(7, 0))
                    } else {
                        assertEquals(true, reader.metadata.pixelIsPoint)
                        assertEquals(DemSample.Value(-25.0), reader.sample(50.0, 10.0))
                        assertEquals(DemSample.Value(55.5), reader.sampleCell(18, 16))
                        assertEquals(DemSample.NoData, reader.sampleCell(4, 3))
                    }
                } finally { reader.close() }
                assertEquals(DemFailure.CLOSED,
                    assertFailsWith<DemReadException> { reader.sampleCell(0, 0) }.reason)
            }
        } finally {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }
}
