/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.activity.ActivityRepository
import bes.max.bmaps.domain.activity.ActivityRouteRepository
import bes.max.bmaps.domain.activity.LocalActivityRepository
import bes.max.bmaps.domain.activity.LocalActivityRouteRepository
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@BindingContainer
@ContributesTo(AppScope::class)
object ActivityBindings {
    @Provides
    fun clock(clock: bes.max.bmaps.domain.activity.PhoneActivityClock): bes.max.bmaps.domain.activity.ActivityClock = clock

    @Provides
    fun session(session: bes.max.bmaps.domain.activity.PhoneActivitySession): bes.max.bmaps.domain.activity.ActivitySession = session

    @Provides
    fun activities(repository: LocalActivityRepository): ActivityRepository = repository

    @Provides
    fun routes(repository: LocalActivityRouteRepository): ActivityRouteRepository = repository
}
