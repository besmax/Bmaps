package bes.max.bmaps.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidPreferencesBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun dataStore(context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.applicationContext.preferencesDataStoreFile(PREFERENCES_NAME)
    }
    @Provides
    @SingleIn(AppScope::class)
    @CredentialStorage
    fun credentialsStore(context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        java.io.File(context.applicationContext.noBackupFilesDir, "provider-credentials.preferences_pb")
    }
}
