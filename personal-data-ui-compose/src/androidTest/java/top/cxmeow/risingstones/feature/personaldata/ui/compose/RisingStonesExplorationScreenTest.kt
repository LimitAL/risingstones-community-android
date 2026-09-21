package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.ExplorationViewModel

class RisingStonesExplorationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val width = mutableStateOf(840)
    private val service = ExplorationUiFixture()
    private lateinit var model: ExplorationViewModel

    @Test fun width599UsesSinglePane() { show(599); compose.onNodeWithTag("exploration-compact").assertIsDisplayed() }
    @Test fun width600UsesTwoPanes() { show(600); compose.onNodeWithTag("exploration-wide").assertIsDisplayed() }
    @Test fun width839UsesTwoPanes() { show(839); compose.onNodeWithTag("exploration-wide").assertIsDisplayed() }
    @Test fun width840UsesTwoPanes() { show(840); compose.onNodeWithTag("exploration-wide").assertIsDisplayed() }

    @Test fun resizeKeepsSelectionAndDoesNotRefetchHistory() {
        show(840)
        compose.onNodeWithTag("exploration-section-TreasureHistory").performScrollTo().performClick()
        compose.onNodeWithTag("exploration-content-TreasureHistory").assertIsDisplayed()
        compose.runOnIdle { width.value = 599 }
        compose.onNodeWithTag("exploration-compact").assertIsDisplayed()
        compose.onNodeWithTag("exploration-content-TreasureHistory").assertIsDisplayed()
        assertEquals(1, service.historyCalls)
    }

    @Test fun partialRefreshShowsFailureAndRetainsPreviousData() {
        show(840)
        compose.runOnIdle { service.fail = true }
        compose.onNodeWithTag("exploration-refresh").performClick()
        compose.onNodeWithTag("exploration-error").assertIsDisplayed()
        compose.onNodeWithTag("exploration-record-summary").assertIsDisplayed()
    }

    @Test fun revocationRemovesRecordsFromComposition() {
        show(840)
        compose.runOnIdle { model.clearProtectedContent() }
        compose.onNodeWithTag("exploration-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("exploration-record-summary").assertDoesNotExist()
    }

    @Test fun personalDataHubOpensExplorationAndReturnsToHub() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(599.dp).height(900.dp)) {
                RisingStonesPersonalDataScreen(service, {})
            } }
        }
        compose.onNodeWithTag("personal-data-exploration-OccultCrescent").performScrollTo().performClick()
        compose.onNodeWithTag("exploration-content-Overview").assertIsDisplayed()
        compose.onNodeWithTag("exploration-back").performClick()
        compose.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
    }

    private fun show(initialWidth: Int) {
        width.value = initialWidth
        compose.setContent {
            MaterialTheme { Box(Modifier.width(width.value.dp).height(900.dp)) {
                model = remember { ExplorationViewModel(service, ExplorationBoard.OccultCrescent) }
                RisingStonesExplorationScreen(model, {})
            } }
        }
        compose.onNodeWithTag("exploration-record-summary").assertIsDisplayed()
    }
}

private class ExplorationUiFixture : PersonalDataExplorationService {
    var fail = false
    var historyCalls = 0
    override suspend fun fetchExplorationOverview(board: ExplorationBoard) = ExplorationOverview(board, true, emptyList(),
        listOf(if (fail) ExplorationSection(ExplorationSectionKind.Overview, failure = ExplorationFailure.Network)
        else ExplorationSection(ExplorationSectionKind.Overview, listOf(ExplorationRecord("summary", "Fixture",
            listOf(ExplorationField(ExplorationFieldKind.GoldCoins, "10")))))))
    override suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind): ExplorationSection {
        historyCalls++
        return ExplorationSection(section)
    }
    override val hasCommunityIdentity = true
    override suspend fun fetchIdentity() = PersonalDataIdentity("Fixture", "", "", null)
    override suspend fun fetchAvailability() = PersonalDataAvailability(emptyMap())
    override suspend fun fetchBoardContent(board: PersonalDataBoard) = PersonalDataBoardContent(board, emptyList(), emptyList())
    override suspend fun fetchUltimateDashboard() = UltimateDashboard(emptyList())
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused")
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
}
