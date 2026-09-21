package top.cxmeow.risingstones.feature.personaldata.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationSectionKind.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun historyIsExplicitAndCachedAndSelectionSurvivesOverviewRefresh() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertTrue(service.historyCalls.isEmpty())
        model.selectSection(TreasureHistory); advanceUntilIdle()
        model.selectSection(Overview); model.selectSection(TreasureHistory); advanceUntilIdle()
        assertEquals(listOf(TreasureHistory), service.historyCalls)
        model.loadOverview(); advanceUntilIdle()
        assertEquals(TreasureHistory, model.state.value.selectedSection)
        model.refresh(); advanceUntilIdle()
        assertEquals(2, service.historyCalls.size)
    }

    @Test fun partialRefreshKeepsGoodRecordsAndMarksOnlyFailedSection() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        service.overview = { fixtureOverview().copy(sections = listOf(ExplorationSection(Overview, failure = ExplorationFailure.Network),
            ExplorationSection(Aether, listOf(record("new"))))) }
        model.refresh(); advanceUntilIdle()
        assertEquals("initial", model.state.value.section?.records?.single()?.title)
        assertEquals(ExplorationFailure.Network, model.state.value.section?.failure)
        model.selectSection(Aether)
        assertEquals("new", model.state.value.section?.records?.single()?.title)
    }

    @Test fun failedHistoryRefreshKeepsEarlierRecordsAndAllowsRetry() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        model.selectSection(TreasureHistory); advanceUntilIdle()
        service.history = { ExplorationSection(it, failure = ExplorationFailure.InvalidResponse) }
        model.refresh(); advanceUntilIdle()
        assertEquals("history", model.state.value.section?.records?.single()?.title)
        assertEquals(ExplorationFailure.InvalidResponse, model.state.value.section?.failure)
        service.history = { ExplorationSection(it) }
        model.refresh(); advanceUntilIdle()
        assertTrue(model.state.value.section?.records?.isEmpty() == true)
        assertNull(model.state.value.section?.failure)
    }

    @Test fun authenticationFailureClearsAllCachedPrivateContent() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        model.selectSection(TreasureHistory); advanceUntilIdle()
        service.history = { throw ExplorationException.AuthenticationRequired }
        model.refresh(); advanceUntilIdle()
        assertNull(model.state.value.overview)
        assertTrue(model.state.value.histories.isEmpty())
        assertEquals(ExplorationError.AuthenticationRequired, model.state.value.error)
    }

    @Test fun olderUncooperativeOverviewCannotReplaceFreshResults() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        service.overview = { withContext(NonCancellable) { gate.await() }; fixtureOverview("late") }
        model.refresh(); runCurrent()
        service.overview = { fixtureOverview("fresh") }
        model.refresh(); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("fresh", model.state.value.section?.records?.single()?.title)
    }

    @Test fun changingHistorySelectionInvalidatesOldResultEvenIfCancellationIsIgnored() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        service.history = { kind -> withContext(NonCancellable) { gate.await() }; ExplorationSection(kind, listOf(record("late"))) }
        model.selectSection(TreasureHistory); runCurrent()
        service.history = { ExplorationSection(it, listOf(record("fresh"))) }
        model.selectSection(RelicHistory); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(RelicHistory, model.state.value.selectedSection)
        assertEquals("fresh", model.state.value.section?.records?.single()?.title)
        assertFalse(model.state.value.histories.containsKey(TreasureHistory))
    }

    @Test fun closedAvailabilityAndExplicitCapabilityRevocationClearHistory() = runTest {
        val service = ExplorationFixtureService()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        model.selectSection(TreasureHistory); advanceUntilIdle()
        service.overview = { fixtureOverview().copy(available = false, sections = emptyList()) }
        model.loadOverview(); advanceUntilIdle()
        assertTrue(model.state.value.histories.isEmpty())
        model.selectSection(TreasureHistory)
        assertEquals(1, service.historyCalls.size)
        model.clearProtectedContent()
        assertNull(model.state.value.overview)
        assertEquals(ExplorationError.Unavailable, model.state.value.error)
    }

    @Test fun cancellationIsNotPresentedAsNetworkFailure() = runTest {
        val service = ExplorationFixtureService().apply { overview = { throw CancellationException() } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertFalse(model.state.value.isLoadingOverview)
    }
}

private fun record(title: String) = ExplorationRecord("record", title, listOf(ExplorationField(ExplorationFieldKind.GoldCoins, "10")))
private fun fixtureOverview(title: String = "initial") = ExplorationOverview(ExplorationBoard.OccultCrescent, true,
    emptyList(), listOf(ExplorationSection(Overview, listOf(record(title))), ExplorationSection(Aether)))

private class ExplorationFixtureService : PersonalDataExplorationService {
    var overview: suspend () -> ExplorationOverview = { fixtureOverview() }
    var history: suspend (ExplorationSectionKind) -> ExplorationSection = { ExplorationSection(it, listOf(record("history"))) }
    val historyCalls = mutableListOf<ExplorationSectionKind>()
    override suspend fun fetchExplorationOverview(board: ExplorationBoard) = overview()
    override suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind): ExplorationSection {
        historyCalls += section
        return history(section)
    }
    override val hasCommunityIdentity = true
    override suspend fun fetchIdentity(): PersonalDataIdentity = error("Unused")
    override suspend fun fetchAvailability(): PersonalDataAvailability = error("Unused")
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent = error("Unused")
    override suspend fun fetchUltimateDashboard(): UltimateDashboard = error("Unused")
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused")
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
}
