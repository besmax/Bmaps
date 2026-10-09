/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationKind

internal data class AnnotationRenderData(
    val values: List<Annotation>,
    val visible: Set<AnnotationKind>,
    val selectedId: String?,
    val draft: Annotation?,
    val cluster: Boolean,
)

internal fun AnnotationEditorState.renderData() = AnnotationRenderData(
    source, layers.filter { it.visible }.map { it.kind }.toSet(),
    activeObjectId, draft, catalogReady && clusteringEnabled && draft == null,
)

internal data class AnnotationDisplay(val pyramid: TilePyramid? = null, val camera: MapCameraSnapshot? = null, val density: Float = 1f)
internal data class AnnotationPresentation(
    val data: AnnotationRenderData,
    val display: AnnotationDisplay,
    val overlays: AnnotationOverlays,
)

internal data class ClusterExpansion(val requestId: Long, val ids: List<String>, val data: AnnotationRenderData, val display: AnnotationDisplay)
