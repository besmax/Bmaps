/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

object AnnotationGpx {
    fun decode(text: String): List<Annotation> {
        require(text.length <= AnnotationGeoJson.MAX_BYTES && text.encodeToByteArray().size <= AnnotationGeoJson.MAX_BYTES)
        require("<!DOCTYPE" !in text && "<!ENTITY" !in text) { "DTD declarations are not supported" }
        val root = readDocument(text.removePrefix("\uFEFF"))
        require(root.name == "gpx")
        val version = root.attributes["version"]
        require(version == "1.0" || version == "1.1")
        require(root.namespace == "" || root.namespace == "http://www.topografix.com/GPX/" + version.replace('.', '/'))
        val values = mutableListOf<Annotation>()
        fun add(kind: AnnotationKind, points: List<GeographicCoordinate>, owner: Element) {
            require(values.size < AnnotationGeoJson.MAX_FEATURES)
            val value = Annotation(
                kind = kind,
                coordinates = points,
                name = owner.label("name"),
                description = owner.label("desc").ifEmpty { owner.label("cmt") },
            )
            require(AnnotationValidation.error(value) == null)
            values += value
        }
        root.children.filter { it.namespace == root.namespace }.forEach { element ->
            when (element.name) {
                "wpt" -> add(AnnotationKind.MARKER, listOf(element.coordinate()), element)
                "rte" -> add(AnnotationKind.LINE, element.points("rtept"), element)
                "trk" -> {
                    val segments = element.elements("trkseg")
                    require(segments.isNotEmpty()) { "Track has no segments" }
                    segments.forEach { add(AnnotationKind.LINE, it.points("trkpt"), element) }
                }
            }
        }
        require(values.isNotEmpty()) { "GPX has no supported objects" }
        return values
    }

    private class Element(
        val name: String,
        val namespace: String,
        val attributes: Map<String, String>,
        val children: MutableList<Element> = mutableListOf(),
        val text: StringBuilder = StringBuilder(),
    ) {
        fun elements(name: String) = children.filter { it.name == name && it.namespace == namespace }
        fun label(name: String): String {
            val matches = elements(name)
            require(matches.size <= 1)
            return matches.singleOrNull()?.let {
                require(it.children.isEmpty())
                it.text.toString().trim()
            }.orEmpty()
        }
        fun coordinate(): GeographicCoordinate {
            val latitude = attributes.getValue("lat").toDouble()
            val longitude = attributes.getValue("lon").toDouble()
            require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
            return GeographicCoordinate(latitude, longitude)
        }
        fun points(name: String): List<GeographicCoordinate> {
            val elements = elements(name)
            require(elements.size <= AnnotationValidation.MAX_VERTICES)
            val points = mutableListOf<GeographicCoordinate>()
            elements.forEach {
                val point = it.coordinate()
                if (points.lastOrNull() != point) points += point
            }
            return points
        }
    }

    private fun readDocument(text: String): Element {
        val reader = xmlStreaming.newGenericReader(text, expandEntities = false)
        val stack = mutableListOf<Element>()
        var root: Element? = null
        var count = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        require(stack.size < 64 && ++count <= 100_000) { "XML complexity limit exceeded" }
                        val attributes = buildMap {
                            for (index in 0 until reader.attributeCount) {
                                if (reader.getAttributeNamespace(index).isEmpty()) {
                                    val name = reader.getAttributeLocalName(index)
                                    require(name !in this)
                                    put(name, reader.getAttributeValue(index))
                                }
                            }
                        }
                        val element = Element(reader.localName, reader.namespaceURI, attributes)
                        if (stack.isEmpty()) {
                            require(root == null) { "Multiple XML roots" }
                            root = element
                        } else stack.last().children += element
                        stack += element
                    }
                    EventType.END_ELEMENT -> {
                        val element = stack.removeLast()
                        require(element.name == reader.localName && element.namespace == reader.namespaceURI)
                    }
                    EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE, EventType.ENTITY_REF -> {
                        if (reader.eventType == EventType.ENTITY_REF) require(reader.isKnownEntity)
                        if (stack.isEmpty()) require(reader.text.isBlank())
                        else stack.last().text.append(reader.text)
                    }
                    EventType.DOCDECL -> error("DTD declarations are not supported")
                    else -> Unit
                }
            }
            require(stack.isEmpty()) { "Incomplete XML" }
            return requireNotNull(root)
        } finally {
            reader.close()
        }
    }
}

object AnnotationImport {
    fun decode(text: String): List<Annotation> {
        require(text.length <= AnnotationGeoJson.MAX_BYTES && text.encodeToByteArray().size <= AnnotationGeoJson.MAX_BYTES)
        val normalized = text.removePrefix("\uFEFF").trimStart()
        return if (normalized.startsWith("<")) AnnotationGpx.decode(normalized) else AnnotationGeoJson.decode(normalized)
    }
}
