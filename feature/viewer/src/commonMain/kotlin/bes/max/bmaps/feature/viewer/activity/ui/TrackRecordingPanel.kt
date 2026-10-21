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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.activity.presentation.*
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TrackRecordingPanel(state: TrackState, actions: TrackActions, onCollapse: () -> Unit,
    onHide: () -> Unit, modifier: Modifier = Modifier, mapStatus: String? = null, compact: Boolean = false) {
    val metrics = state.metrics ?: return
    Surface(modifier.widthIn(max = if (compact) 320.dp else 360.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TrackPanelHeader(metrics, state.busy, state.panelCollapsed, state.menu, actions, onCollapse, onHide, compact)
            Text(stringResource(metrics.status), style = MaterialTheme.typography.labelMedium)
            if (mapStatus != null) Text(mapStatus, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.panelCollapsed) {
                TrackPanelDetails(metrics, state.busy, actions.recover, actions.finish,
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun TrackPanelHeader(metrics: TrackMetrics, busy: Boolean, collapsed: Boolean, menu: Boolean,
    actions: TrackActions, onCollapse: () -> Unit, onHide: () -> Unit, compact: Boolean) {
    val panelDescription = stringResource(if (collapsed) Res.string.track_expand else Res.string.track_collapse)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(metrics.speed, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
            Text(metrics.recordingTime, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(actions.pauseResume, Modifier.size(48.dp),
            enabled = !busy && !metrics.failed && (metrics.recording || metrics.resumable)) {
            Icon(painterResource(if (metrics.recording) Res.drawable.ic_pause else Res.drawable.ic_play),
                stringResource(if (metrics.recording) Res.string.track_pause else Res.string.track_resume), Modifier.size(24.dp))
        }
        Box {
            IconButton({ actions.menu(true) }, Modifier.size(48.dp), enabled = !busy) {
                Icon(painterResource(Res.drawable.ic_more_vert), stringResource(Res.string.track_actions), Modifier.size(24.dp))
            }
            TrackOverflow(menu, metrics, actions, onHide)
        }
        IconButton(onCollapse, Modifier.size(48.dp).semantics { stateDescription = panelDescription }) {
            Icon(painterResource(Res.drawable.ic_expand_more), panelDescription,
                Modifier.size(24.dp).rotate(if (collapsed) 0f else 180f))
        }
    }
}

@Composable
private fun TrackPanelDetails(metrics: TrackMetrics, busy: Boolean, onRecover: () -> Unit,
    onRetryFinish: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.track_actual_distance, metrics.distance), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.track_azimuth, metrics.course), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
        }
        metrics.routeProgress?.let { Text(stringResource(Res.string.track_plan_progress, it), style = MaterialTheme.typography.bodyMedium) }
        if (metrics.heartRate != "—") Text(stringResource(Res.string.track_heart_rate, metrics.heartRate), style = MaterialTheme.typography.bodyMedium)
        if (metrics.endReached) Text(stringResource(Res.string.track_route_arrived))
        if (metrics.failed || metrics.status == Res.string.track_interrupted) {
            TextButton(onRecover, enabled = !busy) { Text(stringResource(Res.string.track_recover)) }
        }
        if (metrics.finalizing) TextButton(onRetryFinish, enabled = !busy) { Text(stringResource(Res.string.viewer_retry)) }
    }
}
