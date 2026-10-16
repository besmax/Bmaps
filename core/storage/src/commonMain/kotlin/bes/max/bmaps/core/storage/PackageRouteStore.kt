/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

interface PackageRouteStore {
    suspend fun read(packageId: String): String?
    suspend fun write(packageId: String, expected: String?, payload: String)
}

enum class PackageRouteFailure { NOT_FOUND, CONFLICT, CAPACITY, INVALID, UNAVAILABLE }
class PackageRouteUnavailable(val reason: PackageRouteFailure = PackageRouteFailure.UNAVAILABLE) : kotlinx.io.IOException("Package routes unavailable")
