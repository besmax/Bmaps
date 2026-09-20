package bes.max.bmaps.feature.viewer

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
    if (catalogReady) catalog else items, layers.filter { it.visible }.map { it.kind }.toSet(),
    selected?.id, draft, catalogReady && clusteringEnabled && draft == null,
)

internal data class AnnotationDisplay(val pyramid: TilePyramid? = null, val camera: MapCameraSnapshot? = null, val density: Float = 1f)
internal data class AnnotationPresentation(
    val data: AnnotationRenderData,
    val display: AnnotationDisplay,
    val overlays: AnnotationOverlays,
)

internal data class ClusterExpansion(val requestId: Long, val ids: List<String>, val data: AnnotationRenderData, val display: AnnotationDisplay)
