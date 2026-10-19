/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import bes.max.bmaps.core.database.PackageDatabase
import bes.max.bmaps.core.database.packageDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityStorageDeviceTest {
    private val scenarios = ActivityStorageScenarios { path ->
        packageDatabase(Room.databaseBuilder<PackageDatabase>(InstrumentationRegistry.getInstrumentation().targetContext, path))
    }
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
