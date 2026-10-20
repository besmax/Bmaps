/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.repository

import androidx.room.Room
import bes.max.bmaps.core.database.PackageDatabase
import bes.max.bmaps.core.database.packageDatabase
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class ActivityStorageIosTest {
    private val scenarios = ActivityStorageScenarios { packageDatabase(Room.databaseBuilder<PackageDatabase>(name = it)) }
    @Test fun followingRecordedRoutePreservesBothActivitiesAfterReopen() = runBlocking { scenarios.followingRecordedRoutePreservesBothActivitiesAfterReopen() }
    @Test fun ownerStatisticsAndCommandPayloadSurviveReopen() = runBlocking { scenarios.ownerStatisticsAndCommandPayloadSurviveReopen() }
    @Test fun navigationSnapshotAndOrderedPositionsSurviveReopen() = runBlocking { scenarios.navigationSnapshotAndOrderedPositionsSurviveReopen() }
    @Test fun singleOwnerAndRestart() = runBlocking { scenarios.singleOwnerAndRestart() }
    @Test fun atomicRollbackAndIdempotentCommands() = runBlocking { scenarios.atomicRollbackAndIdempotentCommands() }
    @Test fun pauseIntervalsGapsAndDerivedRoutes() = runBlocking { scenarios.pauseIntervalsGapsAndDerivedRoutes() }
    @Test fun routeSnapshotsAndImportRollback() = runBlocking { scenarios.routeSnapshotsAndImportRollback() }
    @Test fun catalogRecoveryAndPackageIsolation() = runBlocking { scenarios.catalogRecoveryAndPackageIsolation() }
    @Test fun boundedBatchAndHistoryCursor() = runBlocking { scenarios.boundedBatchAndHistoryCursor() }
    @Test fun wallClockRollbackKeepsPositionOrder() = runBlocking { scenarios.wallClockRollbackKeepsPositionOrder() }
}
