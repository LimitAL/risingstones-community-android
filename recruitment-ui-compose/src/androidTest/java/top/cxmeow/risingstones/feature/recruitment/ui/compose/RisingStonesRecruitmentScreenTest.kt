package top.cxmeow.risingstones.feature.recruitment.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentPage
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentCatalogs
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListPage
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentRoleCounts
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentService
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentMember
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentRating
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentSubcommentPage

class RisingStonesRecruitmentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactSelectionReplacesTheListWithDetail() {
        showAtWidth(400.dp)
        waitFor("Duty Test")

        composeRule.onNodeWithText("Duty Test").performClick()
        waitFor("Team detail")

        composeRule.onNodeWithText("Team detail").assertExists()
        composeRule.onNodeWithText("List excerpt", substring = true).assertDoesNotExist()
    }

    @Test
    fun mediumSelectionKeepsTheListBesideDetail() {
        showAtWidth(700.dp)
        waitFor("Duty Test")

        composeRule.onNodeWithText("Duty Test").performClick()
        waitFor("Team detail")

        composeRule.onNodeWithText("List excerpt", substring = true).assertExists()
        composeRule.onNodeWithText("Team detail").assertExists()
    }

    @Test
    fun expandedSelectionKeepsTheListBesideDetail() {
        showAtWidth(900.dp)
        waitFor("Duty Test")

        composeRule.onNodeWithText("Duty Test").performClick()
        waitFor("Team detail")

        composeRule.onNodeWithText("List excerpt", substring = true).assertExists()
        composeRule.onNodeWithText("Team detail").assertExists()
    }


    @Test fun compactStandaloneDetailReturnsToCaller() = standaloneDetail(599)
    @Test fun mediumStandaloneDetailReturnsToCaller() = standaloneDetail(600)
    @Test fun upperMediumStandaloneDetailReturnsToCaller() = standaloneDetail(839)
    @Test fun expandedStandaloneDetailReturnsToCaller() = standaloneDetail(840)

    private fun standaloneDetail(width: Int) {
        var loadedId: Int? = null
        var returned = false
        val service = object : DutyRecruitmentService by FakeRecruitmentService {
            override suspend fun fetchDutyRecruitmentDetail(id: Int) = FakeRecruitmentService.fetchDutyRecruitmentDetail(id).also { loadedId = id }
        }
        composeRule.setContent { MaterialTheme {
            Box(Modifier.width(width.dp).height(1000.dp)) {
                RisingStonesRecruitmentDetailScreen(service, 42, RecruitmentBoardKind.Duty, { returned = true })
            }
        } }
        waitFor("Team detail")
        assertEquals(42, loadedId)
        composeRule.onNodeWithText("List excerpt", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Back").performClick()
        assertTrue(returned)
    }

    private fun showAtWidth(width: Dp) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .width(width)
                        .height(1_000.dp),
                ) {
                    RisingStonesRecruitmentScreen(
                        service = FakeRecruitmentService,
                        onNavigateBack = {},
                    )
                }
            }
        }
    }

    private fun waitFor(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

private object FakeRecruitmentService : DutyRecruitmentService {
    override val hasCommunityIdentity = false

    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery) =
        DutyRecruitmentListPage(listOf(TestDuty), 1, query.page)

    override suspend fun fetchDutyRecruitmentDetail(id: Int) =
        DutyRecruitmentDetail(
            summary = TestDuty,
            targetGroupName = null,
            teamDetail = "Team detail",
            recruitRequirements = "Requirements",
            strategyDescription = "Detail strategy",
            contactInfo = "",
            dueDay = 7,
            createdAt = null,
            lastResponseTime = null,
            isResponded = false,
            isShare = true,
            relation = null,
            createdBy = null,
        )

    override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String) = error("unused")
    override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String) = error("unused")
    override suspend fun fetchCatalogs() = DutyRecruitmentCatalogs()
    override suspend fun fetchCommunityRecruitments(query: CommunityRecruitmentQuery) =
        CommunityRecruitmentPage(emptyList(), 0, query.page)
    override suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind):
        CommunityRecruitmentDetail = error("unused")
    override suspend fun fetchCommunityFilterCatalog(kind: CommunityRecruitmentKind) =
        CommunityRecruitmentFilterCatalog()
    override suspend fun fetchRolePlayMembers(id: Int): List<RolePlayRecruitmentMember> = emptyList()
    override suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int):
        RolePlayRecruitmentReviewPage = error("unused")
    override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int):
        RolePlayRecruitmentSubcommentPage = error("unused")
    override suspend fun fetchRolePlayRating(id: Int): RolePlayRecruitmentRating = error("unused")
    override suspend fun likeRolePlayReview(id: String): Int = error("unused")
}

private val TestDuty = DutyRecruitmentSummary(
    id = 42,
    uuid = "test",
    avatarUrl = null,
    characterName = "Hero",
    areaName = "Area",
    groupName = "World",
    targetAreaName = "Target",
    dutyType = "High-end",
    dutyName = "Duty Test",
    teamComposition = "8-player",
    progress = "P2",
    schedule = "20:00",
    strategy = "List excerpt",
    beginTime = Instant.parse("2026-07-27T12:00:00Z"),
    endTime = Instant.parse("2026-07-27T14:00:00Z"),
    responseCount = 0,
    status = 1,
    labels = emptyList(),
    jobs = emptyList(),
    requiredJobCodes = emptyList(),
    roleCounts = DutyRecruitmentRoleCounts(),
    updatedAt = Instant.parse("2026-07-27T12:00:00Z"),
)
