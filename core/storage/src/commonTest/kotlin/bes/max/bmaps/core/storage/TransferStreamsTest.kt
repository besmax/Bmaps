/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.*
import kotlin.test.*

class TransferStreamsTest {
    @Test fun sha256KnownVectorAndChunking() {
        val digest = TransferDigest()
        digest.update("a".encodeToByteArray())
        digest.update("bc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.finish())
    }

    @Test fun rejectsChangedHeaderAndFutureVersion() {
        fun encoded(): ByteArray = Buffer().apply { TransferStreams.writeHeader(this, "{}".encodeToByteArray()) }.readByteArray()
        val changed = encoded().apply { this[15] = '['.code.toByte() }
        assertFailsWith<InvalidTransfer> { TransferStreams.readHeader(Buffer().apply { write(changed) }) }
        val future = encoded().apply { this[10] = 2 }
        assertFailsWith<UnsupportedTransferVersion> { TransferStreams.readHeader(Buffer().apply { write(future) }) }
        assertFailsWith<EOFException> { TransferStreams.readHeader(Buffer().apply { write(encoded().copyOf(16)) }) }
        assertContentEquals("{}".encodeToByteArray(), TransferStreams.readHeader(Buffer().apply { write(encoded()) }))
    }

    @Test fun copiesOnlyDeclaredBytesAndFailsOnTruncation() = runTest {
        val source = Buffer().apply { write("abcTAIL".encodeToByteArray()) }
        val output = Buffer()
        TransferStreams.copy(source, output, 3)
        assertEquals("abc", output.readByteArray().decodeToString())
        assertEquals("TAIL", source.readByteArray().decodeToString())
        assertFailsWith<EOFException> { TransferStreams.copy(Buffer(), Buffer(), 1) }
        assertFailsWith<CancellationException> {
            TransferStreams.copy(Buffer().apply { write(ByteArray(70_000)) }, Buffer(), 70_000) { throw CancellationException() }
        }
    }
}
