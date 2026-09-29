/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

object ElevationDiagnostics {
    fun info(message: String) {
        println("[BmapsElevation] INFO $message")
    }

    fun error(message: String, cause: Throwable? = null) {
        println("[BmapsElevation] ERROR $message")
        cause?.printStackTrace()
    }
}
