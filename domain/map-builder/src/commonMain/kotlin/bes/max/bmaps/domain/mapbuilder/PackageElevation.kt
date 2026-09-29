/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

sealed interface PackageElevation {
    data class Value(val meters: Double, val verticalReference: String) : PackageElevation
    data object Missing : PackageElevation
    data object NoData : PackageElevation
    data object OutsideCoverage : PackageElevation
    data object Unsupported : PackageElevation
    data object Unavailable : PackageElevation
}
