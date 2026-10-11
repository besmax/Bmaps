/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.*
import bes.max.bmaps.feature.library.catalog.presentation.PackageVerificationViewModel
import bmaps.feature.library.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PackageDetailsDialog(initialMap: PackageSummary, busy: Boolean, onAvatar: (MapAvatar) -> Unit, onDismiss: () -> Unit) {
    val owner = remember(initialMap.id) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model = metroViewModel<PackageVerificationViewModel>(owner)
    val verification by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model, initialMap.id) { model.load(initialMap.id) }
    val map = verification.summary ?: initialMap
    AlertDialog(
        onDismissRequest = { model.cancel(); onDismiss() },
        title = { Text(map.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(stateLabel(map.state)))
                if (map.state in setOf(PackageState.READY, PackageState.CORRUPT, PackageState.MISSING)) {
                    Button(onClick = { model.start(map.id) }, enabled = !busy && !verification.running) {
                        Text(stringResource(Res.string.verification_start))
                    }
                }
                if (verification.running) {
                    if (verification.progress.stage == PackageVerificationStage.TILES && verification.progress.totalTiles > 0) {
                        LinearProgressIndicator(
                            progress = { (verification.progress.checkedTiles.toDouble() / verification.progress.totalTiles).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(if (verification.cancelling) Res.string.verification_cancelling
                        else verification.progress.stage.label()))

                    if (verification.progress.totalTiles > 0) Text(stringResource(
                        Res.string.verification_tiles, verification.progress.checkedTiles, verification.progress.totalTiles))
                    TextButton(onClick = model::cancel, enabled = !verification.cancelling) {
                        Text(stringResource(Res.string.verification_cancel))
                    }
                }
                verification.result?.let { Text(stringResource(it)) }

                Text(stringResource(Res.string.library_size, megabytes(map.sizeBytes)))
                Text(stringResource(if (map.hasElevationData) Res.string.library_elevation_present else Res.string.library_no_elevation))
                map.bounds?.let { bounds ->
                    Text(
                        stringResource(
                            Res.string.library_bounds,
                            bounds.south.toString(),
                            bounds.west.toString(),
                            bounds.north.toString(),
                            bounds.east.toString()
                        )
                    )
                }
                if (map.zoomLevels.isNotEmpty()) Text(
                    stringResource(
                        Res.string.library_levels,
                        map.zoomLevels.sorted().joinToString()
                    )
                )
                Text(
                    stringResource(
                        Res.string.library_tile_progress,
                        map.downloadedTiles,
                        map.totalTiles
                    )
                )
                Text(
                    stringResource(Res.string.library_avatar),
                    style = MaterialTheme.typography.titleSmall
                )
                MapAvatar.entries.chunked(2).forEach { avatars ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        avatars.forEach { avatar ->
                            FilterChip(
                                selected = map.avatarKey == avatar.storageKey,
                                enabled = !busy && !verification.running,
                                onClick = { onAvatar(avatar) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(avatarIcon(avatar)),
                                        null,
                                        Modifier.size(20.dp)
                                    )
                                },
                                label = { Text(stringResource(avatarLabel(avatar))) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { model.cancel(); onDismiss() }) {
                Text(
                    stringResource(
                        Res.string.library_done
                    )
                )
            }
        })
}

private fun PackageVerificationStage.label() = when (this) {
    PackageVerificationStage.WAITING -> Res.string.verification_waiting
    PackageVerificationStage.FILES -> Res.string.verification_files
    PackageVerificationStage.CHECKSUM -> Res.string.verification_checksum
    PackageVerificationStage.ANNOTATIONS -> Res.string.verification_annotations
    PackageVerificationStage.DATABASE -> Res.string.verification_database
    PackageVerificationStage.TILES -> Res.string.verification_tile_stage
}
