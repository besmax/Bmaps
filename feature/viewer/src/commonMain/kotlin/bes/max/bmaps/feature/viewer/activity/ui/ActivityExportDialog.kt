/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ActivityExportDialog(busy: Boolean, error: org.jetbrains.compose.resources.StringResource?,
    onExport: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.history_export)) },
        text = { Column {
            Text(stringResource(Res.string.history_gpx_payload))
            TextButton({ onExport(0) }, enabled = !busy) { Text(stringResource(Res.string.history_export_gpx)) }
            Text(stringResource(Res.string.history_json_payload))
            TextButton({ onExport(1) }, enabled = !busy) { Text(stringResource(Res.string.history_export_json)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onDismiss) { Text(stringResource(if (busy) Res.string.history_cancel_export else Res.string.layers_cancel)) } })
}
