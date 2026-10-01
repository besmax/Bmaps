/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.*
import kotlinx.io.files.*
import okio.HashingSink
import okio.blackholeSink

class InvalidTransfer : IOException("Invalid transfer")
class UnsupportedTransferVersion(val version: Int) : IOException("Unsupported transfer version")

class TransferDigest {
    private val digest = HashingSink.sha256(blackholeSink())
    fun update(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val buffer = okio.Buffer().write(bytes)
        digest.write(buffer, bytes.size.toLong())
    }
    fun finish(): String = digest.hash.hex()
}

object TransferStreams {
    const val MAX_HEADER = 1_048_576
    const val MAX_BYTES = 32_000_000_000L
    const val MAX_ASSETS = 1024
    private val magic = "BMAPS\r\n".encodeToByteArray()

    fun writeHeader(sink: Sink, header: ByteArray) {
        require(header.size in 1..MAX_HEADER)
        sink.write(magic)
        sink.writeInt(1)
        sink.writeInt(header.size)
        sink.write(header)
        sink.write(TransferDigest().apply { update(header) }.finish().encodeToByteArray())
    }

    fun readHeader(source: Source): ByteArray {
        if (!source.readByteArray(magic.size).contentEquals(magic)) throw InvalidTransfer()
        val version = source.readInt()
        if (version != 1) throw UnsupportedTransferVersion(version)
        val size = source.readInt()
        if (size !in 1..MAX_HEADER) throw InvalidTransfer()
        val header = source.readByteArray(size)
        if (source.readByteArray(64).decodeToString() != TransferDigest().apply { update(header) }.finish()) throw InvalidTransfer()
        return header
    }

    suspend fun copy(source: Source, sink: RawSink?, bytes: Long, capacity: (Long) -> Unit = {}): String {
        require(bytes in 0..MAX_BYTES)
        val digest = TransferDigest()
        var remaining = bytes
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val chunk = source.readByteArray(minOf(remaining, 65_536).toInt())
            digest.update(chunk)
            if (sink != null) {
                capacity(chunk.size.toLong() + 65_536)
                val buffer = Buffer().apply { write(chunk) }
                sink.write(buffer, chunk.size.toLong())
            }
            remaining -= chunk.size
        }
        return digest.finish()
    }

    suspend fun digest(path: Path): String = SystemFileSystem.source(path).buffered().use {
        copy(it, null, checkNotNull(SystemFileSystem.metadataOrNull(path)).size)
    }
}
