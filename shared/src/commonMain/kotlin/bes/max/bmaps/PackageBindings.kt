package bes.max.bmaps

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.mapbuilder.LocalPackageRepository
import bes.max.bmaps.domain.mapbuilder.PackageBuildStorage
import bes.max.bmaps.domain.mapbuilder.PackageRepository
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@BindingContainer
@ContributesTo(AppScope::class)
object PackageBindings {
    @Provides
    fun repository(repository: LocalPackageRepository): PackageRepository = repository

    @Provides
    fun buildStorage(repository: LocalPackageRepository): PackageBuildStorage = repository
}
