/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.database

import android.content.Context
import androidx.room.Room
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun database(context: Context): PackageDatabase = packageDatabase(
        Room.databaseBuilder<PackageDatabase>(
            context.applicationContext,
            context.applicationContext.getDatabasePath("packages.db").absolutePath,
        ),
    )
}
