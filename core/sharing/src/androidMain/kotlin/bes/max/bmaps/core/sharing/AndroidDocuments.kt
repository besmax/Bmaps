/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.io.asSource
import java.io.File

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidSharingBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun location(context: Context): SharingLocation = SharingLocation(File(context.cacheDir, "bmaps-share").absolutePath)
}

actual fun sharingFreeBytes(path: String): Long = StatFs(path).availableBytes

class BmapsFileProvider : FileProvider()

@Composable
actual fun rememberNativeDocuments(onPicked: (PickedDocument) -> Unit, onFailure: () -> Unit): NativeDocuments {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailure)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "import"
            picked(PickedDocument(name, open = {
                checkNotNull(context.contentResolver.openInputStream(uri)).asSource()
            }))
        } catch (_: Exception) { failed() }
    }
    return remember(context, launcher) {
        object : NativeDocuments {
            override fun pick() {
                try { launcher.launch(arrayOf("*/*")) } catch (_: Exception) { failed() }
            }
            override fun share(document: SharedDocument) {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.transfers", File(document.path))
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = document.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { failed() }
            }
        }
    }
}
