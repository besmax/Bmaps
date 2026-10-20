/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.presentation

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.domain.mapbuilder.model.MapAvatar
import bes.max.bmaps.domain.mapbuilder.model.PackageFailure
import bes.max.bmaps.domain.mapbuilder.model.PackageId
import bes.max.bmaps.domain.mapbuilder.model.PackageResult
import bes.max.bmaps.domain.mapbuilder.model.PackageSummary
import bes.max.bmaps.domain.mapbuilder.repository.LayerPresentation
import bes.max.bmaps.domain.mapbuilder.repository.OpenedPackage
import bes.max.bmaps.domain.mapbuilder.repository.PackagePage
import bes.max.bmaps.domain.mapbuilder.repository.PackageQuery
import bes.max.bmaps.domain.mapbuilder.repository.PackageRepository
import bes.max.bmaps.domain.mapbuilder.repository.PackageVerificationProgress
import bes.max.bmaps.domain.mapbuilder.repository.PackageVerificationStage
import bmaps.feature.library.generated.resources.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PackageVerificationViewModelTest {
    @Test fun reportsCompletionAndFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = VerificationRepository().apply { wait = false }
            val model = PackageVerificationViewModel(repository)
            owner.put("verification", model)
            model.start(repository.id)
            runCurrent()
            assertFalse(model.state.value.running)
            assertEquals(Res.string.verification_success, model.state.value.result)
            repository.result = PackageResult.Failure(PackageFailure.CorruptData)
            model.start(repository.id)
            runCurrent()
            assertEquals(Res.string.verification_damaged, model.state.value.result)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun cancellationStopsWorkAndAllowsRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = VerificationRepository()
            val model = PackageVerificationViewModel(repository)
            owner.put("verification", model)
            model.start(repository.id)
            model.start(repository.id)
            assertEquals(1, repository.calls)
            assertTrue(model.state.value.running)
            assertEquals(PackageVerificationStage.DATABASE, model.state.value.progress.stage)
            model.cancel()
            assertTrue(model.state.value.cancelling)
            runCurrent()
            assertFalse(model.state.value.running)
            assertEquals(1, repository.released)
            assertEquals(Res.string.verification_cancelled, model.state.value.result)
            repository.wait = false
            model.start(repository.id)
            runCurrent()
            assertEquals(2, repository.calls)
            assertEquals(Res.string.verification_success, model.state.value.result)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun dismissingDialogCancelsItsVerification() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = VerificationRepository()
            val model = PackageVerificationViewModel(repository)
            owner.put("verification", model)
            model.start(repository.id)
            owner.clear()
            runCurrent()
            assertEquals(1, repository.released)
            assertFalse(model.state.value.running)
            assertEquals(Res.string.verification_cancelled, model.state.value.result)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

private class VerificationRepository : PackageRepository {
    val id = PackageId("verification")
    var wait = true
    var result: PackageResult<Unit> = PackageResult.Success(Unit)
    var calls = 0
    var released = 0

    override suspend fun verifyFully(id: PackageId, onProgress: suspend (PackageVerificationProgress) -> Unit): PackageResult<Unit> {
        calls++
        try {
            onProgress(PackageVerificationProgress(PackageVerificationStage.DATABASE))
            if (wait) awaitCancellation()
            return result
        } finally { released++ }
    }

    override fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>> = error("Not used")
    override fun observe(id: PackageId): Flow<PackageResult<PackageSummary>> = error("Not used")
    override suspend fun open(id: PackageId): PackageResult<OpenedPackage> = error("Not used")
    override suspend fun delete(id: PackageId): PackageResult<Unit> = error("Not used")
    override suspend fun setLayerPresentation(id: PackageId, layers: List<LayerPresentation>): PackageResult<Unit> = error("Not used")
    override suspend fun setFavourite(id: PackageId, favourite: Boolean): PackageResult<Unit> = error("Not used")
    override suspend fun setAvatar(id: PackageId, avatar: MapAvatar): PackageResult<Unit> = error("Not used")
}
