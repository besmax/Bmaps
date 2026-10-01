/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import kotlin.test.*

class TiffHeaderTest {
    @Test fun acceptsBothByteOrdersAndBigTiff() {
        assertTrue(TiffHeader.isValid(byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0), 100))
        assertTrue(TiffHeader.isValid(byteArrayOf(77, 77, 0, 42, 0, 0, 0, 8), 100))
        assertTrue(TiffHeader.isValid(byteArrayOf(73, 73, 43, 0, 8, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0), 100))
    }
    @Test fun rejectsErrorBodiesTruncationAndOutOfRangeDirectory() {
        assertFalse(TiffHeader.isValid("{error: invalid key}".encodeToByteArray(), 20))
        assertFalse(TiffHeader.isValid(byteArrayOf(73, 73, 42, 0), 4))
        assertFalse(TiffHeader.isValid(byteArrayOf(73, 73, 42, 0, 0, 0, 0, 0), 100))
        assertFalse(TiffHeader.isValid(byteArrayOf(73, 73, 42, 0, 99, 0, 0, 0), 100))
    }
}
