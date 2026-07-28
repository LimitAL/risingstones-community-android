package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataField
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetric
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDashboard
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDeathPoint
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterDetail
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterSummary
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateJobStatistic
import top.cxmeow.risingstones.feature.personaldata.domain.UltimatePartnerStatistic
import top.cxmeow.risingstones.feature.personaldata.domain.UltimatePhaseProgress
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateTeammate

class RisingStonesPersonalDataScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactUsesHubThenBoardNavigation() {
        setScreen(400.dp)

        composeRule.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-data-board-Frontline").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("personal-data-board-pane").assertIsDisplayed()
    }

    @Test
    fun mediumKeepsHubAndDetailPanesVisible() {
        setScreen(700.dp)

        composeRule.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-data-detail-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-data-board-Fishing").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-data-board-pane").assertIsDisplayed()
    }

    @Test
    fun expandedCanOpenUltimateEncounterWithoutDroppingHub() {
        setScreen(900.dp)

        composeRule.onNodeWithTag("personal-data-board-Ultimate").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("personal-data-encounter-968").assertExists()
            }.isSuccess
        }
        composeRule.onNodeWithTag("personal-data-encounter-968").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-data-encounter-detail").assertIsDisplayed()
    }

    private fun setScreen(width: Dp) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(800.dp),
                ) {
                    RisingStonesPersonalDataScreen(
                        service = FakePersonalDataService(),
                        onNavigateBack = {},
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("personal-data-hub-pane").assertExists()
            }.isSuccess
        }
    }
}

private class FakePersonalDataService : PersonalDataService {
    override val hasCommunityIdentity = true
    private val summary = UltimateEncounterSummary(
        territoryType = 968,
        clearTimes = 4,
        entersBeforeFirstClear = 20,
        firstClearJobName = "Paladin",
        firstClearAt = Instant.parse("2026-07-27T10:00:00Z"),
        firstClearElapsedSeconds = 7_200,
        deathsBeforeFirstClear = 30,
    )

    override suspend fun fetchIdentity() = PersonalDataIdentity(
        characterName = "Test Hero",
        areaName = "陆行鸟",
        groupName = "红玉海",
        avatarUrl = null,
    )

    override suspend fun fetchAvailability() = PersonalDataAvailability(
        PersonalDataBoard.entries
            .flatMap(PersonalDataBoard::statusKeys)
            .associateWith { "1" },
    )

    override suspend fun fetchBoardContent(board: PersonalDataBoard) =
        PersonalDataBoardContent(
            board = board,
            metrics = listOf(PersonalDataMetric("fight_times", "12")),
            sections = listOf(
                PersonalDataSection(
                    id = "weekly",
                    entries = listOf(
                        PersonalDataEntry(
                            id = "row",
                            title = "Test row",
                            fields = listOf(PersonalDataField("value", "1")),
                        ),
                    ),
                ),
            ),
        )

    override suspend fun fetchUltimateDashboard() = UltimateDashboard(listOf(summary))

    override suspend fun fetchUltimateEncounterDetail(
        summary: UltimateEncounterSummary,
    ) = UltimateEncounterDetail(
        summary = summary,
        teammates = listOf(UltimateTeammate("Ally", "陆行鸟", "红玉海", "White Mage")),
        jobs = listOf(UltimateJobStatistic("Paladin", 4)),
        partners = listOf(UltimatePartnerStatistic("Ally", "陆行鸟", "红玉海", 4)),
        phases = listOf(UltimatePhaseProgress("finish", Instant.parse("2026-07-27T10:00:00Z"))),
        deathPoints = listOf(
            UltimateDeathPoint(1.0, 2.0, "P2", Instant.parse("2026-07-27T09:00:00Z")),
        ),
    )

    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
}
