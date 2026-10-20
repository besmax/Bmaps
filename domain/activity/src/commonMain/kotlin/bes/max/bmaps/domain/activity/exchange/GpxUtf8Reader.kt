/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(nl.adaptivity.xmlutil.XmlUtilInternal::class)

package bes.max.bmaps.domain.activity.exchange

import bes.max.bmaps.domain.activity.model.ActivityLimits
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Source
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader

internal class GpxUtf8Reader(private val source: Source, private val context: CoroutineContext) : Reader() {
    private var bytes = 0
    private var pending: Char? = null
    private var first = true
    private var doctype = 0
    private var entity = 0

    override fun read(buf: CharArray, offset: Int, len: Int): Int {
        require(offset >= 0 && len >= 0 && offset <= buf.size - len)
        context.ensureActive()
        if (len == 0) return 0
        var count = 0
        while (count < len) {
            val char = nextChar() ?: break
            doctype = advance("<!DOCTYPE", doctype, char)
            entity = advance("<!ENTITY", entity, char)
            require(doctype != 9 && entity != 8) { "DTD declarations are not supported" }
            buf[offset + count++] = char
        }
        return if (count == 0) -1 else count
    }

    override fun close() {}

    private fun nextChar(): Char? {
        pending?.let { pending = null; return it }
        if (source.exhausted()) return null
        val lead = byte()
        val length: Int
        var code: Int
        when (lead) {
            in 0..0x7F -> { length = 1; code = lead }
            in 0xC2..0xDF -> { length = 2; code = lead and 0x1F }
            in 0xE0..0xEF -> { length = 3; code = lead and 0x0F }
            in 0xF0..0xF4 -> { length = 4; code = lead and 0x07 }
            else -> error("Invalid UTF-8")
        }
        repeat(length - 1) {
            val continuation = byte()
            require(continuation in 0x80..0xBF) { "Invalid UTF-8" }
            code = (code shl 6) or (continuation and 0x3F)
        }
        require((length < 2 || code >= 0x80) && (length < 3 || code >= 0x800) &&
            (length < 4 || code >= 0x10000) && code <= 0x10FFFF && code !in 0xD800..0xDFFF) { "Invalid UTF-8" }
        if (first) {
            first = false
            if (code == 0xFEFF) return nextChar()
        }
        require(code >= 0x20 || code == 9 || code == 10 || code == 13) { "Invalid XML character" }
        if (code <= 0xFFFF) return code.toChar()
        val adjusted = code - 0x10000
        pending = (0xDC00 + (adjusted and 0x3FF)).toChar()
        return (0xD800 + (adjusted shr 10)).toChar()
    }

    private fun byte(): Int {
        if (bytes >= ActivityLimits.MAX_IMPORT_BYTES) error("GPX byte limit exceeded")
        bytes++
        return source.readByte().toInt() and 0xFF
    }

    private fun advance(prefix: String, matched: Int, char: Char): Int =
        if (matched < prefix.length && char == prefix[matched]) matched + 1 else if (char == '<') 1 else 0
}
