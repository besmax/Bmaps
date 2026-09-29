package bes.max.bmaps.domain.mapbuilder

sealed interface PackageElevation {
    data class Value(val meters: Double, val verticalReference: String) : PackageElevation
    data object Missing : PackageElevation
    data object NoData : PackageElevation
    data object OutsideCoverage : PackageElevation
    data object Unsupported : PackageElevation
    data object Unavailable : PackageElevation
}
