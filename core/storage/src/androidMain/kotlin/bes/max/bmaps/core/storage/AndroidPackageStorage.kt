package bes.max.bmaps.core.storage

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidPackageStorageBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun location(context: Context): PackageStorageLocation =
        PackageStorageLocation(java.io.File(context.applicationContext.filesDir, "packages").absolutePath)
}

internal actual fun availableStorageBytes(path: String): Long = StatFs(path).availableBytes
internal actual fun isSymbolicLink(path: String): Boolean = java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(path))

internal actual fun syncPath(path: String, directory: Boolean) {
    val fd = Os.open(path, OsConstants.O_RDONLY, 0)
    try {
        if (directory && !OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw UnsafePackagePath()
        Os.fsync(fd)
    } finally { Os.close(fd) }
}
