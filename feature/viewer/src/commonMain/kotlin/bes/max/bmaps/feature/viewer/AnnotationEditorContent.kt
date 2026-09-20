package bes.max.bmaps.feature.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AnnotationEditorContent(model: AnnotationEditorViewModel, state: AnnotationEditorState, modifier: Modifier = Modifier) {
    if (state.draft != null && !state.propertiesOpen) Surface(modifier, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(8.dp)) {
            Text(stringResource(if (state.replacingVertex == null) Res.string.annotations_drawing_hint else Res.string.annotations_replace_hint))
            Text(stringResource(Res.string.annotations_vertex_count, state.draft.coordinates.size))
            Row {
                TextButton(model::undo, enabled = state.history.isNotEmpty() && !state.busy) { Text(stringResource(Res.string.annotations_undo)) }
                TextButton({ model.properties(true) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_finish)) }
                TextButton(model::cancel, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) }
            }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (state.panel) AlertDialog(
        onDismissRequest = { model.panel(false) }, title = { Text(stringResource(Res.string.annotations_title)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            AnnotationKind.entries.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(kind.label()), Modifier.weight(1f))
                    Checkbox(state.layers.first { it.kind == kind }.visible, { model.visibility(kind, it) })
                    TextButton({ model.start(kind) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_add)) }
                }
            }
            Text(stringResource(Res.string.annotations_viewport_list), style = MaterialTheme.typography.labelMedium)
            if (state.catalogTooMany) Text(stringResource(Res.string.annotations_cluster_too_many), color = MaterialTheme.colorScheme.error)
            if (state.catalogFailed) Text(stringResource(Res.string.annotations_cluster_unavailable), color = MaterialTheme.colorScheme.error)
            if (state.items.isEmpty()) Text(stringResource(Res.string.annotations_empty))
            state.items.forEach { item ->
                TextButton({ model.select(item.id) }, enabled = !state.busy) {
                    Text(item.name.ifBlank { stringResource(item.kind.label()) })
                }
            }
            if (state.truncated) Text(stringResource(Res.string.annotations_truncated))
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(model::retry) { Text(stringResource(Res.string.viewer_retry)) }
            }
            TextButton({ model.geoJson(true) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_import)) }
            TextButton(model::exportGeoJson, enabled = !state.busy) { Text(stringResource(Res.string.annotations_export)) }
        } },
        confirmButton = { TextButton({ model.panel(false) }) { Text(stringResource(Res.string.viewer_done)) } },
    )
    state.selected?.let { selected ->
        AlertDialog(onDismissRequest = model::dismissSelection,
            title = { Text(selected.name.ifBlank { stringResource(selected.kind.label()) }) },
            text = { Column {
                Text(selected.description)
                Text(stringResource(Res.string.annotations_vertex_count, selected.coordinates.size))
            } },
            confirmButton = { TextButton(model::edit, enabled = !state.busy) { Text(stringResource(Res.string.annotations_edit)) } },
            dismissButton = { Row {
                TextButton({ model.confirmDelete(true) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_delete)) }
                TextButton(model::dismissSelection, enabled = !state.busy) { Text(stringResource(Res.string.viewer_done)) }
            } },
        )
        if (state.deleteConfirmation) AlertDialog(onDismissRequest = { model.confirmDelete(false) },
            title = { Text(stringResource(Res.string.annotations_delete)) },
            text = { Text(stringResource(Res.string.annotations_delete_confirm, selected.name.ifBlank { stringResource(selected.kind.label()) })) },
            confirmButton = { TextButton(model::delete, enabled = !state.busy) { Text(stringResource(Res.string.annotations_delete)) } },
            dismissButton = { TextButton({ model.confirmDelete(false) }, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } },
        )
    }
    val draft = state.draft
    if (draft != null && state.propertiesOpen) AlertDialog(
        onDismissRequest = { if (!state.busy) model.properties(false) },
        title = { Text(stringResource(draft.kind.label())) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.name, model::name, label = { Text(stringResource(Res.string.annotations_name)) }, enabled = !state.busy, singleLine = true)
            OutlinedTextField(draft.description, model::description, label = { Text(stringResource(Res.string.annotations_description)) }, enabled = !state.busy)
            Text(stringResource(Res.string.annotations_color))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("#E53935", "#FB8C00", "#FDD835", "#43A047", "#1E88E5", "#8E24AA", "#212121", "#FFFFFF").forEach { color ->
                    FilterChip(draft.color.equals(color, ignoreCase = true), { model.color(color) }, enabled = !state.busy,
                        label = { Text(color, color = if (color == "#FFFFFF") MaterialTheme.colorScheme.onSurface else Color((0xFF000000L or color.drop(1).toLong(16)).toInt())) })
                }
            }
            if (draft.kind == AnnotationKind.MARKER) {
                Text(stringResource(Res.string.annotations_icon))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MarkerIcons.entries.forEach { icon ->
                        FilterChip(draft.icon == icon.id, { model.icon(icon.id) }, enabled = !state.busy,
                            label = { Text(stringResource(icon.label)) },
                            leadingIcon = { MarkerIcon(icon.id, MaterialTheme.colorScheme.primary, stringResource(icon.label), Modifier.size(24.dp)) })
                    }
                }
            }
            TextButton({ model.properties(false) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_draw)) }
            draft.coordinates.forEachIndexed { index, coordinate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}: ${coordinate.latitude}, ${coordinate.longitude}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ model.replaceVertex(index) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_move)) }
                    TextButton({ model.removeVertex(index) }, enabled = !state.busy) { Text(stringResource(Res.string.annotations_remove)) }
                }
            }
            TextButton(model::undo, enabled = state.history.isNotEmpty() && !state.busy) { Text(stringResource(Res.string.annotations_undo)) }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(model::save, enabled = !state.busy) { Text(stringResource(Res.string.layers_save)) } },
        dismissButton = { TextButton(model::cancel, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } },
    )
    if (state.geoJsonOpen) AlertDialog(onDismissRequest = { model.geoJson(false) },
        title = { Text(stringResource(if (state.geoJsonExport) Res.string.annotations_export else Res.string.annotations_import)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(if (state.geoJsonExport) Res.string.annotations_export_hint else Res.string.annotations_import_hint))
            if (state.geoJsonExport) SelectionContainer { Text(state.geoJson) }
            else OutlinedTextField(state.geoJson, model::geoJsonText, enabled = !state.busy, label = { Text(stringResource(Res.string.annotations_geojson)) })
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(if (state.geoJsonExport) ({ model.geoJson(false) }) else model::importGeoJson, enabled = !state.busy) {
            Text(stringResource(if (state.geoJsonExport) Res.string.viewer_done else Res.string.annotations_import))
        } },
        dismissButton = { if (!state.geoJsonExport) TextButton({ model.geoJson(false) }, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } },
    )
}

internal fun AnnotationKind.label() = when (this) {
    AnnotationKind.MARKER -> Res.string.annotations_markers
    AnnotationKind.LINE -> Res.string.annotations_lines
    AnnotationKind.POLYGON -> Res.string.annotations_polygons
}
