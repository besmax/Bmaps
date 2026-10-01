/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.sharing

import androidx.compose.runtime.Composable
import kotlinx.io.RawSource

class PickedDocument(val name: String, val open: () -> RawSource, val dispose: () -> Unit = {})
data class SharedDocument(val path: String, val mimeType: String)

interface NativeDocuments {
    fun pick()
    fun share(document: SharedDocument)
}

@Composable
expect fun rememberNativeDocuments(onPicked: (PickedDocument) -> Unit, onFailure: () -> Unit): NativeDocuments
