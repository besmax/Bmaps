/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.sharing

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.*
import kotlinx.io.files.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class SharingLocation(val path: String)

@Inject
@SingleIn(AppScope::class)
class SharedDocumentStore(private val location: SharingLocation) {
    private val mutex = Mutex()
    private var initialized = false

    suspend fun create(extension: String, mimeType: String, write: suspend (RawSink) -> Unit): SharedDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(extension in setOf("bmaps", "geojson"))
            val fs = SystemFileSystem
            val root = Path(location.path)
            fs.createDirectories(root)
            val now = Clock.System.now().toEpochMilliseconds()
            if (!initialized) {
                fs.list(root).forEach { path ->
                    val created = path.name.substringBefore('-').toLongOrNull()
                    if (path.name.endsWith(".part") || (created != null && now - created > 86_400_000)) fs.delete(path)
                }
                initialized = true
            }
            val path = Path(root, "$now-${Uuid.random()}.$extension")
            val temporary = Path("$path.part")
            try {
                fs.sink(temporary).use { destination ->
                    var count = 0L
                    val bounded = object : RawSink {
                        override fun write(source: Buffer, byteCount: Long) {
                            if (byteCount > 32_002_000_000L - count || sharingFreeBytes(location.path) < byteCount + 65_536) {
                                throw IOException("Insufficient transfer storage")
                            }
                            destination.write(source, byteCount)
                            count += byteCount
                        }
                        override fun flush() = destination.flush()
                        override fun close() = Unit
                    }
                    write(bounded)
                    destination.flush()
                }
                fs.atomicMove(temporary, path)
                SharedDocument(path.toString(), mimeType)
            } finally { fs.delete(temporary, mustExist = false) }
        }
    }
}

expect fun sharingFreeBytes(path: String): Long
