@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.uuid.Uuid

@Serializable
enum class AnnotationKind { MARKER, LINE, POLYGON }

@Serializable
data class Annotation(
    val id: String = Uuid.random().toString(),
    val kind: AnnotationKind,
    val coordinates: List<GeographicCoordinate>,
    val name: String = "",
    val description: String = "",
    val color: String = "#E53935",
    val icon: String = "place",
    val properties: JsonObject = JsonObject(emptyMap()),
)

data class AnnotationPage(val items: List<Annotation>, val nextCursor: String?)

interface AnnotationRepository {
    suspend fun annotations(packageId: PackageId, bounds: BoundingBox? = null, after: String? = null): PackageResult<AnnotationPage>
    suspend fun saveAnnotations(packageId: PackageId, annotations: List<Annotation>): PackageResult<Unit>
    suspend fun deleteAnnotation(packageId: PackageId, id: String): PackageResult<Unit>
}

object AnnotationValidation {
    const val MAX_VERTICES = 1000
    fun error(value: Annotation): String? {
        if (value.id.isBlank() || value.id.length > 128 || value.name.length > 120 || value.description.length > 4000 ||
            !Regex("#[0-9a-fA-F]{6}").matches(value.color) || !Regex("[a-zA-Z0-9_-]{1,64}").matches(value.icon)) return "properties"
        val points = value.coordinates
        if (points.size > MAX_VERTICES || points.any { !it.latitude.isFinite() || !it.longitude.isFinite() ||
                it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) return "coordinates"
        if (value.kind == AnnotationKind.MARKER) return if (points.size == 1) null else "point"
        if (points.size < if (value.kind == AnnotationKind.LINE) 2 else 3) return "vertices"
        if (points.zipWithNext().any { (a, b) -> a == b }) return "edge"
        if (value.kind == AnnotationKind.LINE) return null
        if (points.distinct().size != points.size) return "ring"
        val edges = (points + points.first()).zipWithNext()
        val area = annotationSignedArea(points)
        if (abs(area) < 1e-12) return "area"
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            if (j == i + 1 || i == 0 && j == edges.lastIndex) continue
            if (intersects(edges[i].first, edges[i].second, edges[j].first, edges[j].second)) return "intersection"
        }
        return null
    }

    private fun intersects(a: GeographicCoordinate, b: GeographicCoordinate, c: GeographicCoordinate, d: GeographicCoordinate): Boolean {
        fun cross(p: GeographicCoordinate, q: GeographicCoordinate, r: GeographicCoordinate) =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) - (q.latitude - p.latitude) * (r.longitude - p.longitude)
        fun overlaps(a: Double, b: Double, c: Double, d: Double) = maxOf(minOf(a, b), minOf(c, d)) <= minOf(maxOf(a, b), maxOf(c, d))
        return overlaps(a.longitude, b.longitude, c.longitude, d.longitude) && overlaps(a.latitude, b.latitude, c.latitude, d.latitude) &&
            cross(a, b, c) * cross(a, b, d) <= 0 && cross(c, d, a) * cross(c, d, b) <= 0
    }
}

fun Annotation.boundingBox(): BoundingBox = BoundingBox(
    coordinates.minOf { it.longitude }, coordinates.minOf { it.latitude },
    coordinates.maxOf { it.longitude }, coordinates.maxOf { it.latitude },
)

internal fun annotationSignedArea(points: List<GeographicCoordinate>): Double {
    val origin = points.first()
    return (points + origin).zipWithNext().sumOf { (a, b) ->
        (a.longitude - origin.longitude) * (b.latitude - origin.latitude) -
            (b.longitude - origin.longitude) * (a.latitude - origin.latitude)
    }
}
