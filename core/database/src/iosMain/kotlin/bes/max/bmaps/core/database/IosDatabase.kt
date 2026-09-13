package bes.max.bmaps.core.database

import androidx.room.Room
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

@BindingContainer
@ContributesTo(AppScope::class)
object IosDatabaseBindings {
    @OptIn(ExperimentalForeignApi::class)
    @Provides
    @SingleIn(AppScope::class)
    fun database(): PackageDatabase {
        val support = checkNotNull(NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        )?.path)
        return packageDatabase(Room.databaseBuilder<PackageDatabase>(name = "$support/packages.db"))
    }
}
