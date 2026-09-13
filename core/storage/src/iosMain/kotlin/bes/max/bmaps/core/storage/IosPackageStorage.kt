@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.io.IOException
import platform.Foundation.*
import platform.posix.*

@BindingContainer
@ContributesTo(AppScope::class)
object IosPackageStorageBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun location(): PackageStorageLocation {
        val support = checkNotNull(NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        )?.path)
        return PackageStorageLocation("$support/packages")
    }
}

internal actual fun availableStorageBytes(path: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)
        ?: throw IOException("Storage capacity unavailable")
    return (attributes[NSFileSystemFreeSize] as NSNumber).longLongValue
}

internal actual fun isSymbolicLink(path: String): Boolean =
    NSFileManager.defaultManager.destinationOfSymbolicLinkAtPath(path, null) != null

internal actual fun syncPath(path: String, directory: Boolean) {
    val fd = open(path, O_RDONLY)
    if (fd < 0) throw IOException("Cannot open path for synchronization")
    try { if (fsync(fd) != 0) throw IOException("Cannot synchronize storage") }
    finally { close(fd) }
}
