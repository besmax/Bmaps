package bes.max.bmaps.feature.viewer

import bes.max.bmaps.core.datastore.CoordinateFormat
import bes.max.bmaps.core.datastore.UserPreferences
import bes.max.bmaps.core.mapengine.*
import kotlin.math.abs
import kotlin.math.roundToLong

internal fun fixedDecimal(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val rounded = (abs(value) * factor).roundToLong()
    val sign = if (value < 0 && rounded != 0L) "−" else ""
    return "$sign${rounded / factor}.${(rounded % factor).toString().padStart(decimals, '0')}"
}

internal fun formatCoordinate(point: GeographicCoordinate, preferences: UserPreferences): String? =
    when (preferences.defaultCoordinateSystem) {
        "EPSG:4326" -> "${angle(point.latitude, preferences.coordinateFormat)}  ${angle(point.longitude, preferences.coordinateFormat)}"
        "EPSG:3857" -> (WebMercator.transform(
            ProjectedCoordinate(point.longitude, point.latitude, CoordinateSystemId.Wgs84), CoordinateSystemId.WebMercator,
        ) as? TransformResult.Success)?.coordinate?.let { "X ${fixedDecimal(it.x, 1)} · Y ${fixedDecimal(it.y, 1)} m" }
        else -> null
    }

private fun angle(value: Double, format: CoordinateFormat): String {
    if (format == CoordinateFormat.DECIMAL_DEGREES) return "${fixedDecimal(value, 6)}°"
    val unitsPerDegree = if (format == CoordinateFormat.DEGREES_MINUTES) 60_000L else 36_000L
    val units = (abs(value) * unitsPerDegree).roundToLong()
    val sign = if (value < 0 && units != 0L) "−" else ""
    val degrees = units / unitsPerDegree
    val remainder = units % unitsPerDegree
    return if (format == CoordinateFormat.DEGREES_MINUTES) {
        "$sign$degrees° ${(remainder / 1000).toString().padStart(2, '0')}.${(remainder % 1000).toString().padStart(3, '0')}′"
    } else {
        "$sign$degrees° ${(remainder / 600).toString().padStart(2, '0')}′ ${((remainder % 600) / 10).toString().padStart(2, '0')}.${remainder % 10}″"
    }
}
