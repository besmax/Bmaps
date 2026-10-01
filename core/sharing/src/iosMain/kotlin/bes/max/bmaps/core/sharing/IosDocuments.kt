/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.core.sharing

import androidx.compose.runtime.*
import androidx.compose.ui.uikit.LocalUIViewController
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.io.*
import kotlinx.io.files.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject

@BindingContainer
@ContributesTo(AppScope::class)
object IosSharingBindings {
    @Provides @SingleIn(AppScope::class)
    fun location(): SharingLocation {
        val cache = checkNotNull(NSFileManager.defaultManager.URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)?.path)
        return SharingLocation("$cache/bmaps-share")
    }
}

actual fun sharingFreeBytes(path: String): Long =
    (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0

@Composable
actual fun rememberNativeDocuments(onPicked: (PickedDocument) -> Unit, onFailure: () -> Unit): NativeDocuments {
    val controller = LocalUIViewController.current
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailure)
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                picked(PickedDocument(url.lastPathComponent ?: "import", open = {
                    val scoped = url.startAccessingSecurityScopedResource()
                    try {
                        val source = SystemFileSystem.source(Path(checkNotNull(url.path)))
                        object : RawSource {
                            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = source.readAtMostTo(sink, byteCount)
                            override fun close() {
                                try { source.close() } finally { if (scoped) url.stopAccessingSecurityScopedResource() }
                            }
                        }
                    } catch (error: Throwable) {
                        if (scoped) url.stopAccessingSecurityScopedResource()
                        throw error
                    }
                }, dispose = { NSFileManager.defaultManager.removeItemAtURL(url, null); Unit }))
            }
        }
    }
    return remember(controller, delegate) {
        object : NativeDocuments {
            override fun pick() {
                try {
                    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
                    picker.delegate = delegate
                    picker.allowsMultipleSelection = false
                    controller.transferPresenter().presentViewController(picker, true, null)
                } catch (_: Exception) { failed() }
            }
            override fun share(document: SharedDocument) {
                try {
                    val activity = UIActivityViewController(listOf(NSURL.fileURLWithPath(document.path)), null)
                    activity.popoverPresentationController?.let { popover ->
                        popover.sourceView = controller.view
                        popover.sourceRect = controller.view.bounds
                    }
                    controller.transferPresenter().presentViewController(activity, true, null)
                } catch (_: Exception) { failed() }
            }
        }
    }
}

private fun UIViewController.transferPresenter(): UIViewController {
    var presenter = this
    while (true) presenter = presenter.presentedViewController ?: return presenter
}
