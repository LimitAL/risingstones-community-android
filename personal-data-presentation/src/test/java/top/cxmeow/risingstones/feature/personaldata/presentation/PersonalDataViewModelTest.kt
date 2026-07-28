package top.cxmeow.risingstones.feature.personaldata.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetric
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDashboard
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterDetail
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterSummary

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

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
        assertEquals("board refresh failed", viewModel.state.value.boardError)

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
        assertEquals("detail refresh failed", viewModel.state.value.detailError)
    }
}

private class FakePersonalDataService : PersonalDataService {
    override val hasCommunityIdentity = true
    var identityRequests = 0
    var dashboardRequests = 0
    val boardRequests = mutableListOf<PersonalDataBoard>()
    val detailRequests = mutableListOf<Int>()
    var failBoard = false
    var failDetail = false
    private val summary = UltimateEncounterSummary(968, 2, 10, "Paladin", null, 200, 5)

    override suspend fun fetchIdentity(): PersonalDataIdentity {
        identityRequests += 1
        return PersonalDataIdentity("Hero", "DC", "World", null)
    }

    override suspend fun fetchAvailability() = PersonalDataAvailability(
        mapOf("pvp" to "1", "jue4" to "1", "fishing" to "1"),
    )

    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent {
        boardRequests += board
        if (failBoard) error("board refresh failed")
        return PersonalDataBoardContent(board, listOf(PersonalDataMetric("value", "1")), emptyList())
    }

    override suspend fun fetchUltimateDashboard(): UltimateDashboard {
        dashboardRequests += 1
        return UltimateDashboard(listOf(summary))
    }

    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail {
        detailRequests += summary.territoryType
        if (failDetail) error("detail refresh failed")
        return UltimateEncounterDetail(summary, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
}
