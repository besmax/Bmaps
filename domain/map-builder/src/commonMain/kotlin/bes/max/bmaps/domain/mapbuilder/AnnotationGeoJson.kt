package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.serialization.json.*

object AnnotationGeoJson {
    const val MAX_BYTES = 4_000_000
    const val MAX_FEATURES = 1000
    private val json = Json { encodeDefaults = true }
    private val reserved = setOf("name", "description", "marker-color", "marker-symbol")

    fun encode(values: List<Annotation>): String {
        require(values.size <= MAX_FEATURES)
        return buildJsonObject {
            put("type", "FeatureCollection")
            put("features", JsonArray(values.map(::feature)))
        }.toString().also { require(it.encodeToByteArray().size <= MAX_BYTES) }
    }

    fun decode(text: String): List<Annotation> {
        require(text.length <= MAX_BYTES && text.encodeToByteArray().size <= MAX_BYTES)
        val root = json.parseToJsonElement(text).jsonObject
        require("crs" !in root)
        val features = when (root["type"]?.jsonPrimitive?.content) {
            "FeatureCollection" -> root.getValue("features").jsonArray.toList()
            "Feature" -> listOf(root)
            else -> error("Only GeoJSON features are supported")
        }
        require(features.size <= MAX_FEATURES)
        return features.map { require(it.toString().encodeToByteArray().size <= 256_000); parseFeature(it.jsonObject) }.also { values ->
            require(values.map { it.id }.distinct().size == values.size)
        }
    }

    internal fun feature(value: Annotation): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("id", value.id)
        put("geometry", buildJsonObject {
            fun coordinate(point: GeographicCoordinate) = JsonArray(listOf(JsonPrimitive(point.longitude), JsonPrimitive(point.latitude)))
            val vertices = if (value.kind == AnnotationKind.POLYGON && annotationSignedArea(value.coordinates) < 0) {
                value.coordinates.take(1) + value.coordinates.drop(1).reversed()
            } else value.coordinates
            val points = vertices.map(::coordinate)
            put("type", when (value.kind) { AnnotationKind.MARKER -> "Point"; AnnotationKind.LINE -> "LineString"; AnnotationKind.POLYGON -> "Polygon" })
            put("coordinates", when (value.kind) {
                AnnotationKind.MARKER -> points.single()
                AnnotationKind.LINE -> JsonArray(points)
                AnnotationKind.POLYGON -> JsonArray(listOf(JsonArray(points + points.first())))
            })
        })
        put("properties", buildJsonObject {
            value.properties.filterKeys { it !in reserved }.forEach { (key, item) -> put(key, item) }
            put("name", value.name); put("description", value.description)
            put("marker-color", value.color); put("marker-symbol", value.icon)
        })
    }

    internal fun parseFeature(feature: JsonObject): Annotation {
        require(feature["type"]?.jsonPrimitive?.content == "Feature" && "crs" !in feature)
        val geometry = feature.getValue("geometry").jsonObject
        require("crs" !in geometry)
        val kind = when (geometry["type"]?.jsonPrimitive?.content) {
            "Point" -> AnnotationKind.MARKER
            "LineString" -> AnnotationKind.LINE
            "Polygon" -> AnnotationKind.POLYGON
            else -> error("Unsupported geometry")
        }
        fun coordinate(value: JsonElement): GeographicCoordinate {
            val pair = value.jsonArray
            require(pair.size == 2 && pair.all { !it.jsonPrimitive.isString })
            return GeographicCoordinate(pair[1].jsonPrimitive.double, pair[0].jsonPrimitive.double)
        }
        val raw = geometry.getValue("coordinates").jsonArray
        val coordinates = when (kind) {
            AnnotationKind.MARKER -> listOf(coordinate(raw))
            AnnotationKind.LINE -> { require(raw.size <= AnnotationValidation.MAX_VERTICES); raw.map(::coordinate) }
            AnnotationKind.POLYGON -> {
                require(raw.size == 1) { "Polygon holes are not supported" }
                require(raw.single().jsonArray.size <= AnnotationValidation.MAX_VERTICES + 1)
                val ring = raw.single().jsonArray.map(::coordinate)
                require(ring.size >= 4 && ring.first() == ring.last())
                ring.dropLast(1)
            }
        }
        val properties = feature["properties"].let { if (it == null || it == JsonNull) JsonObject(emptyMap()) else it.jsonObject }
        fun string(key: String, fallback: String): String = properties[key]?.let { require(it.jsonPrimitive.isString); it.jsonPrimitive.content } ?: fallback
        val id = feature["id"]?.let { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
        val value = Annotation(kind = kind, coordinates = coordinates, name = string("name", ""), description = string("description", ""),
            color = string("marker-color", "#E53935"), icon = string("marker-symbol", "place"),
            properties = JsonObject(properties.filterKeys { it !in reserved }))
        return (if (id == null) value else value.copy(id = id)).also { require(AnnotationValidation.error(it) == null) }
    }
}
