/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import kotlinx.io.RawSink
import kotlinx.io.RawSource

interface PackageTransfer {
    suspend fun exportPackage(id: PackageId, destination: RawSink): PackageResult<Unit>
    suspend fun importPackage(source: RawSource): PackageResult<PackageId>
    suspend fun importMbTiles(source: RawSource, name: String): PackageResult<PackageId>
}
