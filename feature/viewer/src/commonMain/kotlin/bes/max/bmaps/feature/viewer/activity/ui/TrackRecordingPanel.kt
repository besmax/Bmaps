/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.activity.presentation.*
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TrackRecordingPanel(state: TrackState, actions: TrackActions, onCollapse: () -> Unit,
    modifier: Modifier = Modifier, mapStatus: String? = null) {
    val metrics = state.metrics ?: return
    val actionsLabel = stringResource(Res.string.track_actions)
    Surface(modifier.widthIn(max = 520.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(metrics.speed, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    Text(metrics.recordingTime, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(actions.pauseResume, enabled = !state.busy && !metrics.failed && (metrics.recording || metrics.resumable)) {
                    Text(stringResource(if (metrics.recording) Res.string.track_pause else Res.string.track_resume))
                }
                Box {
                    TextButton({ actions.menu(true) }, Modifier.semantics { contentDescription = actionsLabel }, enabled = !state.busy) { Text("⋯") }
                    TrackOverflow(state.menu, metrics, actions)
                }
            }
            Text(stringResource(metrics.status), style = MaterialTheme.typography.labelMedium)
            if (mapStatus != null) Text(mapStatus, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!state.panelCollapsed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.track_actual_distance, metrics.distance), Modifier.weight(1f))
                    Text(stringResource(Res.string.track_azimuth, metrics.course), Modifier.weight(1f))
                }
                metrics.routeProgress?.let { Text(stringResource(Res.string.track_plan_progress, it)) }
                if (metrics.heartRate != "—") Text(stringResource(Res.string.track_heart_rate, metrics.heartRate))
                if (metrics.endReached) Text(stringResource(Res.string.track_route_arrived))
                if (metrics.failed || metrics.status == Res.string.track_interrupted) {
                    TextButton(actions.recover, enabled = !state.busy) { Text(stringResource(Res.string.track_recover)) }
                }
                if (metrics.finalizing) TextButton(actions.finish, enabled = !state.busy) { Text(stringResource(Res.string.viewer_retry)) }
            }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onCollapse, Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.panelCollapsed) Res.string.track_expand else Res.string.track_collapse))
            }
        }
    }
}
