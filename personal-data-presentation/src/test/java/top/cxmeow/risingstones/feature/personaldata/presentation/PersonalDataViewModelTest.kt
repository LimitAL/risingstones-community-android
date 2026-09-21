package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.FishKingCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetric
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataEntry
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateJobStatistic
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateTeammate
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDashboard
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterDetail
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterSummary

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun structuredSelectionDoesNotReadLegacyContentAndLegacyOverloadStillLoadsIt() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Glamour, loadContent = false)
        advanceUntilIdle()
        assertEquals(PersonalDataBoard.Glamour, model.state.value.selectedBoard)
        assertNull(model.state.value.content)
        assertTrue(service.boardRequests.isEmpty())
        model.selectBoard(PersonalDataBoard.Glamour)
        advanceUntilIdle()
        assertEquals(listOf(PersonalDataBoard.Glamour), service.boardRequests)
        assertTrue(model.state.value.content != null)
        model.selectBoard(PersonalDataBoard.Glamour, loadContent = false)
        assertNull(model.state.value.content)
    }

    @Test
    fun rootAndBoardDataAreCachedUntilRefresh() = runTest {
        val service = FakePersonalDataService()
        val viewModel = PersonalDataViewModel(service)
        advanceUntilIdle()

        assertEquals("Hero", viewModel.state.value.identity?.characterName)
        assertTrue(viewModel.state.value.availability?.hasData(PersonalDataBoard.Frontline) == true)

        viewModel.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        viewModel.selectBoard(PersonalDataBoard.Frontline)
        advanceUntilIdle()
        viewModel.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()

        assertEquals(listOf(PersonalDataBoard.Fishing, PersonalDataBoard.Frontline), service.boardRequests)
        assertEquals("1", viewModel.state.value.content?.metrics?.single()?.value)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, service.boardRequests.count { it == PersonalDataBoard.Fishing })
        assertEquals(2, service.identityRequests)
    }

    @Test
    fun ultimateSelectionLoadsDashboardAndAllDetailSections() = runTest {
        val service = FakePersonalDataService()
        val viewModel = PersonalDataViewModel(service)
        advanceUntilIdle()

        viewModel.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        val summary = requireNotNull(viewModel.state.value.dashboard?.summaries?.single())
        viewModel.selectEncounter(summary)
        advanceUntilIdle()

        assertEquals(968, viewModel.state.value.encounterDetail?.summary?.territoryType)
        assertEquals(listOf(968), service.detailRequests)
        assertNull(viewModel.state.value.detailError)
    }

    @Test
    fun compactNavigationCanReturnFromEncounterAndBoardWithoutDroppingCaches() = runTest {
        val service = FakePersonalDataService()
        val viewModel = PersonalDataViewModel(service)
        advanceUntilIdle()

        viewModel.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        val summary = requireNotNull(viewModel.state.value.dashboard?.summaries?.single())
        viewModel.selectEncounter(summary)
        advanceUntilIdle()

        viewModel.clearEncounterSelection()
        assertNull(viewModel.state.value.selectedEncounter)
        assertNull(viewModel.state.value.encounterDetail)
        assertEquals(1, service.detailRequests.size)

        viewModel.clearBoardSelection()
        assertNull(viewModel.state.value.selectedBoard)
        assertNull(viewModel.state.value.dashboard)

        viewModel.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        assertEquals(1, service.dashboardRequests)
    }

    @Test
    fun refreshFailuresRetainConfirmedBoardAndEncounterContent() = runTest {
        val service = FakePersonalDataService()
        val viewModel = PersonalDataViewModel(service)
        advanceUntilIdle()

        viewModel.selectBoard(PersonalDataBoard.Frontline)
        advanceUntilIdle()
        val confirmedBoard = viewModel.state.value.content
        service.failBoard = true
        viewModel.loadBoard(force = true)
        advanceUntilIdle()

        assertEquals(confirmedBoard, viewModel.state.value.content)
        assertEquals("load_failed", viewModel.state.value.boardError)

        service.failBoard = false
        viewModel.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        val summary = requireNotNull(viewModel.state.value.dashboard?.summaries?.single())
        viewModel.selectEncounter(summary)
        advanceUntilIdle()
        val confirmedDetail = viewModel.state.value.encounterDetail
        service.failDetail = true
        viewModel.loadEncounterDetail(force = true)
        advanceUntilIdle()

        assertEquals(confirmedDetail, viewModel.state.value.encounterDetail)
        assertEquals("load_failed", viewModel.state.value.detailError)
    }

    @Test
    fun rootFailureCancelsItsSiblingAndPreservesTheLastCompletePair() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        val confirmed = model.state.value
        var availabilityCancelled = false
        val failedIdentity = CompletableDeferred<Unit>()
        service.identityLoader = { failedIdentity.await(); throw IllegalStateException("private response content") }
        service.availabilityLoader = {
            try { CompletableDeferred<Unit>().await(); availability() }
            finally { availabilityCancelled = true }
        }
        model.loadRoot(force = true)
        runCurrent()
        failedIdentity.complete(Unit)
        advanceUntilIdle()
        assertTrue(availabilityCancelled)
        assertEquals(confirmed.identity, model.state.value.identity)
        assertEquals(confirmed.availability, model.state.value.availability)
        assertEquals("load_failed", model.state.value.rootError)
        assertFalse(model.state.value.isLoadingRoot)
        // A failed async child must not terminate the reusable ViewModel scope.
        service.identityLoader = { identity("Recovered") }
        service.availabilityLoader = { availability() }
        model.loadRoot(force = true)
        advanceUntilIdle()
        assertEquals("Recovered", model.state.value.identity?.characterName)
        assertNull(model.state.value.rootError)
    }

    @Test
    fun replacementRootRejectsNonCooperativeLatePairAndDoesNotEndNewLoading() = runTest {
        val old = UncooperativeResult<PersonalDataIdentity>()
        val fresh = UncooperativeResult<PersonalDataIdentity>()
        val service = FakePersonalDataService().apply { identityLoader = { old.await() } }
        val model = PersonalDataViewModel(service)
        runCurrent()
        service.identityLoader = { fresh.await() }
        model.loadRoot(force = true)
        runCurrent()
        old.complete(identity("Old"))
        runCurrent()
        assertTrue(model.state.value.isLoadingRoot)
        assertNull(model.state.value.identity)
        fresh.complete(identity("Fresh"))
        advanceUntilIdle()
        assertEquals("Fresh", model.state.value.identity?.characterName)
        assertFalse(model.state.value.isLoadingRoot)
    }

    @Test
    fun boardAbaRejectsLateResponseInStateAndCache() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        val old = UncooperativeResult<PersonalDataBoardContent>()
        service.boardLoader = { if (it == PersonalDataBoard.Fishing) old.await() else content(it, "Other") }
        model.selectBoard(PersonalDataBoard.Fishing)
        runCurrent()
        model.selectBoard(PersonalDataBoard.Frontline)
        advanceUntilIdle()
        service.boardLoader = { content(it, "Fresh") }
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        old.complete(content(PersonalDataBoard.Fishing, "Old"))
        advanceUntilIdle()
        assertEquals("Fresh", model.state.value.content?.metrics?.single()?.value)
        model.clearBoardSelection()
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        assertEquals("Fresh", model.state.value.content?.metrics?.single()?.value)
        assertEquals(2, service.boardRequests.count { it == PersonalDataBoard.Fishing })
    }

    @Test
    fun encounterAbaAndBoardReturnRejectLateDetailsAndKeepFreshCache() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        val first = summary(968)
        val old = UncooperativeResult<UltimateEncounterDetail>()
        service.detailLoader = { if (it.territoryType == 968) old.await() else detail(it) }
        model.selectEncounter(first)
        runCurrent()
        model.selectEncounter(summary(777))
        advanceUntilIdle()
        service.detailLoader = { detail(it.copy(clearTimes = 9)) }
        model.selectEncounter(first)
        advanceUntilIdle()
        old.complete(detail(first.copy(clearTimes = 1)))
        advanceUntilIdle()
        assertEquals(9, model.state.value.encounterDetail?.summary?.clearTimes)
        model.clearBoardSelection()
        model.selectBoard(PersonalDataBoard.Ultimate)
        model.selectEncounter(first)
        advanceUntilIdle()
        assertEquals(9, model.state.value.encounterDetail?.summary?.clearTimes)
        assertEquals(2, service.detailRequests.count { it == 968 })
    }

    @Test
    fun cancellingAnActiveBoardWithoutSwitchingStopsLoadingWithoutCachingItsLateResult() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        val request = UncooperativeResult<PersonalDataBoardContent>()
        service.boardLoader = { request.await() }
        model.selectBoard(PersonalDataBoard.Fishing)
        runCurrent()
        request.job.cancel()
        request.complete(content(PersonalDataBoard.Fishing, "Cancelled"))
        advanceUntilIdle()
        assertFalse(model.state.value.isLoadingBoard)
        assertNull(model.state.value.boardError)
        assertNull(model.state.value.content)
        service.boardLoader = { content(it, "Retry") }
        model.loadBoard()
        advanceUntilIdle()
        assertEquals("Retry", model.state.value.content?.metrics?.single()?.value)
    }

    @Test
    fun independentCancellationFinishesRootDetailAndCatalogLoadingWithoutOrdinaryErrors() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        val root = UncooperativeResult<PersonalDataIdentity>()
        val encounter = UncooperativeResult<UltimateEncounterDetail>()
        val catalogRequest = UncooperativeResult<PersonalDataOfficialCatalogs>()
        service.identityLoader = { root.await() }
        service.detailLoader = { encounter.await() }
        service.catalogLoader = { catalogRequest.await() }
        model.loadRoot(force = true)
        model.selectEncounter(summary(968))
        model.loadCatalogs(force = true)
        runCurrent()
        root.job.cancel(); encounter.job.cancel(); catalogRequest.job.cancel()
        root.complete(identity("Cancelled"))
        encounter.complete(detail(summary(968)))
        catalogRequest.complete(catalogs(9))
        advanceUntilIdle()
        assertFalse(model.state.value.isLoadingRoot)
        assertFalse(model.state.value.isLoadingDetail)
        assertFalse(model.state.value.isLoadingCatalogs)
        assertNull(model.state.value.rootError)
        assertNull(model.state.value.detailError)
        assertNull(model.state.value.catalogsError)
        assertEquals("Hero", model.state.value.identity?.characterName)
        assertNull(model.state.value.encounterDetail)
        assertTrue(model.state.value.catalogs.fish.isEmpty())
    }

    @Test
    fun catalogFailureIsRetryableAndConcurrentOldFailureCannotReplaceSuccess() = runTest {
        val service = FakePersonalDataService().apply { catalogLoader = { throw IllegalArgumentException("private catalogue payload") } }
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        assertEquals("load_failed", model.state.value.catalogsError)
        val old = UncooperativeResult<PersonalDataOfficialCatalogs>()
        service.catalogLoader = { old.await() }
        model.loadCatalogs()
        runCurrent()
        service.catalogLoader = { catalogs(2) }
        model.loadCatalogs(force = true)
        advanceUntilIdle()
        old.fail(IllegalStateException("private late failure"))
        advanceUntilIdle()
        assertEquals(setOf(2), model.state.value.catalogs.fish.keys)
        assertNull(model.state.value.catalogsError)
        val requests = service.catalogRequests
        model.loadCatalogs()
        advanceUntilIdle()
        assertEquals(requests, service.catalogRequests)
    }

    @Test
    fun catalogRefreshFailureRetainsContentAndExplicitRetryLoadsAgain() = runTest {
        val service = FakePersonalDataService().apply { catalogLoader = { catalogs(1) } }
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        service.catalogLoader = { throw PersonalDataException.Business(99, "private server reason") }
        model.loadCatalogs(force = true)
        advanceUntilIdle()
        assertEquals(setOf(1), model.state.value.catalogs.fish.keys)
        assertEquals("load_failed", model.state.value.catalogsError)
        service.catalogLoader = { catalogs(2) }
        model.loadCatalogs()
        advanceUntilIdle()
        assertEquals(setOf(2), model.state.value.catalogs.fish.keys)
        assertNull(model.state.value.catalogsError)
    }

    @Test
    fun clearProtectedContentCancelsAllRequestsAndAllowsFreshReentryWithoutOldCaches() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        model.selectEncounter(summary(968))
        advanceUntilIdle()
        val oldRoot = UncooperativeResult<PersonalDataIdentity>()
        val oldBoard = UncooperativeResult<UltimateDashboard>()
        val oldDetail = UncooperativeResult<UltimateEncounterDetail>()
        val oldCatalog = UncooperativeResult<PersonalDataOfficialCatalogs>()
        service.identityLoader = { oldRoot.await() }
        service.dashboardLoader = { oldBoard.await() }
        service.detailLoader = { oldDetail.await() }
        service.catalogLoader = { oldCatalog.await() }
        model.refresh(); model.loadCatalogs(force = true)
        runCurrent()
        model.clearProtectedContent()
        assertEquals(PersonalDataUiState(), model.state.value)
        oldRoot.complete(identity("Old")); oldBoard.complete(UltimateDashboard(listOf(summary(968))))
        oldDetail.complete(detail(summary(968))); oldCatalog.complete(catalogs(99))
        advanceUntilIdle()
        assertEquals(PersonalDataUiState(), model.state.value)
        service.identityLoader = { identity("New") }
        service.dashboardLoader = { UltimateDashboard(listOf(summary(968))) }
        service.detailLoader = { detail(it.copy(clearTimes = 8)) }
        service.catalogLoader = { catalogs(2) }
        model.loadRoot(); model.loadCatalogs()
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        assertEquals(2, service.boardRequests.count { it == PersonalDataBoard.Fishing })
        model.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        model.selectEncounter(summary(968))
        advanceUntilIdle()
        assertEquals(8, model.state.value.encounterDetail?.summary?.clearTimes)
        assertEquals("New", model.state.value.identity?.characterName)
        assertEquals(setOf(2), model.state.value.catalogs.fish.keys)
    }

    @Test
    fun capabilityRevocationOnEntryOrCompletionClearsEveryProtectedValue() = runTest {
        val service = FakePersonalDataService().apply { catalogLoader = { catalogs(1) } }
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        val late = UncooperativeResult<PersonalDataBoardContent>()
        service.boardLoader = { late.await() }
        model.loadBoard(force = true)
        runCurrent()
        service.hasCommunityIdentity = false
        late.complete(content(PersonalDataBoard.Fishing, "Must not appear"))
        advanceUntilIdle()
        assertEquals(PersonalDataUiState(rootError = "authentication_required"), model.state.value)
        val requests = service.identityRequests + service.boardRequests.size + service.catalogRequests
        model.loadRoot(); model.loadCatalogs(); model.selectBoard(PersonalDataBoard.Ultimate); model.refresh()
        advanceUntilIdle()
        assertEquals(requests, service.identityRequests + service.boardRequests.size + service.catalogRequests)
        service.hasCommunityIdentity = true
        service.boardLoader = { content(it, "New") }
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        assertEquals("New", model.state.value.content?.metrics?.single()?.value)
    }

    @Test
    fun authenticationFailureFromAnyRequestClearsCachesAndKeepsOnlyStableError() = runTest {
        for (target in listOf("root", "board", "detail", "catalog")) {
            val service = FakePersonalDataService().apply { catalogLoader = { catalogs(1) } }
            val model = PersonalDataViewModel(service)
            advanceUntilIdle()
            model.selectBoard(PersonalDataBoard.Ultimate)
            advanceUntilIdle()
            model.selectEncounter(summary(968))
            advanceUntilIdle()
            when (target) {
                "root" -> { service.identityLoader = { throw PersonalDataException.AuthenticationRequired }; model.loadRoot(true) }
                "board" -> { service.dashboardLoader = { throw PersonalDataException.AuthenticationRequired }; model.loadBoard(true) }
                "detail" -> { service.detailLoader = { throw PersonalDataException.AuthenticationRequired }; model.loadEncounterDetail(force = true) }
                else -> { service.catalogLoader = { throw PersonalDataException.AuthenticationRequired }; model.loadCatalogs(true) }
            }
            advanceUntilIdle()
            assertEquals(target, PersonalDataUiState(rootError = "authentication_required"), model.state.value)
            model.clearProtectedContent()
        }
    }

    @Test
    fun viewModelStoreClearRejectsLateResultsAndAllFutureReads() = runTest {
        val service = FakePersonalDataService()
        val old = UncooperativeResult<PersonalDataIdentity>()
        service.identityLoader = { old.await() }
        val model = PersonalDataViewModel(service)
        val store = ViewModelStore().apply { put("personal-data", model) }
        runCurrent()
        store.clear()
        old.complete(identity("Old"))
        advanceUntilIdle()
        assertEquals(PersonalDataUiState(), model.state.value)
        assertFalse(model.hasCommunityIdentity)
        val before = service.identityRequests + service.catalogRequests
        model.loadRoot(); model.loadCatalogs(); model.selectBoard(PersonalDataBoard.Fishing); model.refresh()
        advanceUntilIdle()
        assertEquals(before, service.identityRequests + service.catalogRequests)
        assertEquals(PersonalDataUiState(), model.state.value)
    }

    @Test
    fun returningToCachedBoardCancelsOtherBoardBeforeItsResponseCanPopulateCache() = runTest {
        val service = FakePersonalDataService()
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        val old = UncooperativeResult<PersonalDataBoardContent>()
        service.boardLoader = { old.await() }
        model.selectBoard(PersonalDataBoard.Frontline)
        runCurrent()
        model.selectBoard(PersonalDataBoard.Fishing)
        assertTrue(old.job.isCancelled)
        assertFalse(model.state.value.isLoadingBoard)
        old.complete(content(PersonalDataBoard.Frontline, "Abandoned"))
        advanceUntilIdle()
        service.boardLoader = { content(it, "Reentered") }
        model.selectBoard(PersonalDataBoard.Frontline)
        advanceUntilIdle()
        assertEquals(2, service.boardRequests.count { it == PersonalDataBoard.Frontline })
        assertEquals("Reentered", model.state.value.content?.metrics?.single()?.value)
    }

    @Test
    fun partialBoardRefreshRetainsFailedSectionAndNormalizesItsError() = runTest {
        val row = PersonalDataEntry("one", "Synthetic entry", emptyList())
        val service = FakePersonalDataService().apply {
            boardLoader = { content(it).copy(sections = listOf(PersonalDataSection("summary", listOf(row)))) }
        }
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Fishing)
        advanceUntilIdle()
        service.boardLoader = { content(it, "Updated metric").copy(sections =
            listOf(PersonalDataSection("summary", error = "private response error"))) }
        model.loadBoard(force = true)
        advanceUntilIdle()
        assertEquals("Updated metric", model.state.value.content?.metrics?.single()?.value)
        assertEquals(listOf(row), model.state.value.content?.sections?.single()?.entries)
        assertEquals("load_failed", model.state.value.content?.sections?.single()?.error)
        assertNull(model.state.value.boardError)
    }

    @Test
    fun partialEncounterRefreshKeepsFailedSectionButAcceptsOtherFreshSections() = runTest {
        val teammate = UltimateTeammate("Synthetic teammate", "DC", "World", "Paladin")
        val service = FakePersonalDataService().apply { detailLoader = { detail(it).copy(teammates = listOf(teammate)) } }
        val model = PersonalDataViewModel(service)
        advanceUntilIdle()
        model.selectBoard(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        model.selectEncounter(summary(968))
        advanceUntilIdle()
        val jobs = listOf(UltimateJobStatistic("Paladin", 7))
        service.detailLoader = { detail(it).copy(jobs = jobs, sectionErrors = mapOf("team" to "private server message")) }
        model.loadEncounterDetail(force = true)
        advanceUntilIdle()
        assertEquals(listOf(teammate), model.state.value.encounterDetail?.teammates)
        assertEquals(jobs, model.state.value.encounterDetail?.jobs)
        assertEquals(mapOf("team" to "load_failed"), model.state.value.encounterDetail?.sectionErrors)
        assertNull(model.state.value.detailError)
    }

}

private fun summary(id: Int) = UltimateEncounterSummary(id, 2, 10, "Paladin", null, 200, 5)
private fun identity(name: String = "Hero") = PersonalDataIdentity(name, "DC", "World", null)
private fun availability() = PersonalDataAvailability(mapOf("pvp" to "1", "jue4" to "1", "fishing" to "1"))
private fun content(board: PersonalDataBoard, value: String = "1") =
    PersonalDataBoardContent(board, listOf(PersonalDataMetric("value", value)), emptyList())
private fun detail(summary: UltimateEncounterSummary) = UltimateEncounterDetail(summary, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
private fun catalogs(id: Int) = PersonalDataOfficialCatalogs(fish = mapOf(id to FishKingCatalogEntry(id, id, "Synthetic fish", "1")))

/** suspendCoroutine intentionally ignores cancellation to exercise the real late-return boundary. */
private class UncooperativeResult<T> {
    private lateinit var continuation: Continuation<T>
    lateinit var job: Job
    suspend fun await(): T {
        job = checkNotNull(currentCoroutineContext()[Job])
        return suspendCoroutine { continuation = it }
    }
    fun complete(value: T) { continuation.resume(value) }
    fun fail(error: Throwable) { continuation.resumeWithException(error) }
}

private class FakePersonalDataService : PersonalDataService {
    override var hasCommunityIdentity = true
    var identityRequests = 0
    var dashboardRequests = 0
    var catalogRequests = 0
    val boardRequests = mutableListOf<PersonalDataBoard>()
    val detailRequests = mutableListOf<Int>()
    var failBoard = false
    var failDetail = false
    var identityLoader: suspend () -> PersonalDataIdentity = { identity() }
    var availabilityLoader: suspend () -> PersonalDataAvailability = { availability() }
    var boardLoader: suspend (PersonalDataBoard) -> PersonalDataBoardContent = { content(it) }
    var dashboardLoader: suspend () -> UltimateDashboard = { UltimateDashboard(listOf(summary(968))) }
    var detailLoader: suspend (UltimateEncounterSummary) -> UltimateEncounterDetail = { detail(it) }
    var catalogLoader: suspend () -> PersonalDataOfficialCatalogs = { PersonalDataOfficialCatalogs() }

    override suspend fun fetchIdentity(): PersonalDataIdentity { identityRequests++; return identityLoader() }
    override suspend fun fetchAvailability() = availabilityLoader()
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent {
        boardRequests += board
        if (failBoard) error("board refresh failed")
        return boardLoader(board)
    }
    override suspend fun fetchUltimateDashboard(): UltimateDashboard { dashboardRequests++; return dashboardLoader() }
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail {
        detailRequests += summary.territoryType
        if (failDetail) error("detail refresh failed")
        return detailLoader(summary)
    }
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs { catalogRequests++; return catalogLoader() }
}
