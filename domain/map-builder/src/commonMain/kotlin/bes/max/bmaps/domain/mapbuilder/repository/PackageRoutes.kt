/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder.repository

import bes.max.bmaps.core.storage.PackageFiles
import kotlinx.serialization.json.*

internal const val ROUTES_ASSET = "routes.json"
internal const val ROUTES_BYTES = 64_000_000
internal fun routePrefix(packageId: String) = "map:$packageId:"

internal fun packageRoutes(text: String, packageId: String): JsonArray {
    val document = PackageManifestCodec.json.parseToJsonElement(text).jsonObject
    require(document["schemaVersion"]?.jsonPrimitive?.int == 1)
    val routes = document.getValue("routes").jsonArray
    val ids = mutableSetOf<String>()
    require(routes.size <= 1_000)
    fun validId(id: String) = id.length in 1..128 && id != "." && id != ".." && id.all { it.code < 128 && (it.isLetterOrDigit() || it in "-_.:") }
    routes.forEach { item ->
        val route = item.jsonObject
        val id = route.getValue("id").jsonPrimitive.content
        require(validId(id) && id.startsWith(routePrefix(packageId)) && ids.add(id))
        require(route["schemaVersion"]?.jsonPrimitive?.int == 1)
        require(route.getValue("revision").jsonPrimitive.long >= 0)
        require(route.getValue("name").jsonPrimitive.content.length <= 120)
        require((route["description"]?.jsonPrimitive?.content?.length ?: 0) <= 4_000)
        val segments = route.getValue("segments").jsonArray
        require(segments.size in 1..2_000)
        val segmentIds = mutableSetOf<String>()
        var count = 0L
        segments.forEach { element ->
            val segment = element.jsonObject
            val segmentId = segment.getValue("id").jsonPrimitive.content
            require(validId(segmentId) && segmentIds.add(segmentId))
            val vertices = segment.getValue("vertices").jsonArray
            count += vertices.size
            require(vertices.size >= 2 && count <= 200_000)
            var previous: Pair<Double, Double>? = null
            vertices.forEach { entry ->
                val vertex = entry.jsonObject
                val coordinate = vertex.getValue("coordinate").jsonObject
                val lat = coordinate.getValue("latitude").jsonPrimitive.double
                val lon = coordinate.getValue("longitude").jsonPrimitive.double
                require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
                require(previous != (lat to lon)); previous = lat to lon
                vertex["sourceTimeEpochMillis"]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.long >= 0) }
                vertex["elevation"]?.takeUnless { it == JsonNull }?.jsonObject?.let { height ->
                    require(height.getValue("meters").jsonPrimitive.double.isFinite())
                    val reference = height.getValue("verticalReference").jsonPrimitive.content
                    require(reference.isNotBlank() && reference.length <= 64)
                    height["accuracyMeters"]?.takeUnless { it == JsonNull }?.let {
                        require(it.jsonPrimitive.double.isFinite() && it.jsonPrimitive.double >= 0)
                    }
                }
            }
        }
        val controls = route["controlPoints"]?.jsonArray ?: JsonArray(emptyList())
        require(controls.size <= 2_000)
        val controlIds = mutableSetOf<String>()
        controls.forEach { entry ->
            val control = entry.jsonObject
            val controlId = control.getValue("id").jsonPrimitive.content
            require(validId(controlId) && controlIds.add(controlId) && control.getValue("name").jsonPrimitive.content.length <= 120)
            val position = control.getValue("position").jsonObject
            val segment = position.getValue("segmentIndex").jsonPrimitive.int
            require(segment in segments.indices)
            val edge = position.getValue("edgeIndex").jsonPrimitive.int
            val vertices = segments[segment].jsonObject.getValue("vertices").jsonArray
            require(edge in 0 until vertices.lastIndex)
            val fraction = position.getValue("fraction").jsonPrimitive.double
            require(fraction.isFinite() && fraction in 0.0..1.0)
        }
    }
    return routes
}

internal suspend fun PackageFiles.rebindRoutes(oldId: String, newId: String) {
    val text = read(newId, true, ROUTES_ASSET, ROUTES_BYTES).decodeToString(throwOnInvalidSequence = true)
    val routes = packageRoutes(text, oldId).map { item ->
        val route = item.jsonObject
        JsonObject(route + ("id" to JsonPrimitive(routePrefix(newId) + route.getValue("id").jsonPrimitive.content.removePrefix(routePrefix(oldId)))))
    }
    val rebound = buildJsonObject { put("schemaVersion", 1); put("routes", JsonArray(routes)) }.toString()
    write(newId, ROUTES_ASSET, kotlinx.io.Buffer().apply { write(rebound.encodeToByteArray()) }, ROUTES_BYTES.toLong(), Long.MAX_VALUE)
}
