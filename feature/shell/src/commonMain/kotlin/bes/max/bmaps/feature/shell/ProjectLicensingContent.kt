/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import bmaps.feature.shell.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProjectLicensingContent() {
    val versionCode = appVersionCode()
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.project_licensing), style = MaterialTheme.typography.titleMedium)
                Text(if (versionCode != null) stringResource(Res.string.app_version_code, versionCode)
                    else stringResource(Res.string.app_version_unavailable), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.project_copyright), style = MaterialTheme.typography.bodySmall)
                Text("https://github.com/besmax/Bmaps", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.project_license_summary), style = MaterialTheme.typography.bodySmall)
                Text("https://polyformproject.org/licenses/noncommercial/1.0.0", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.project_commercial_contact), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
