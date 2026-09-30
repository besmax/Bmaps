/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.mapbuilder.AnnotationRepository
import bes.max.bmaps.domain.mapbuilder.LocalPackageRepository
import bes.max.bmaps.domain.mapbuilder.PackageBuildStorage
import bes.max.bmaps.domain.mapbuilder.PackageTransfer
import bes.max.bmaps.domain.mapbuilder.PackageRepository
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@BindingContainer
@ContributesTo(AppScope::class)
object PackageBindings {
    @Provides
    fun transfer(repository: LocalPackageRepository): PackageTransfer = repository

    @Provides
    fun repository(repository: LocalPackageRepository): PackageRepository = repository

    @Provides
    fun annotations(repository: LocalPackageRepository): AnnotationRepository = repository

    @Provides
    fun buildStorage(repository: LocalPackageRepository): PackageBuildStorage = repository
}
