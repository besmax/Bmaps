/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation

import androidx.compose.runtime.Immutable
import bes.max.bmaps.domain.mapbuilder.annotations.Annotation
import bes.max.bmaps.domain.mapbuilder.annotations.AnnotationKind
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.StringResource

@Immutable
internal data class MapObjectCalloutDisplay(
    val id: String,
    val name: String,
    val description: String,
    val fallbackTitle: StringResource,
    val marker: Boolean = false,
)

internal fun Annotation.calloutDisplay() = MapObjectCalloutDisplay(id, name, description, when (kind) {
    AnnotationKind.MARKER -> Res.string.annotations_markers
    AnnotationKind.LINE -> Res.string.annotations_lines
    AnnotationKind.POLYGON -> Res.string.annotations_polygons
}, kind == AnnotationKind.MARKER)
