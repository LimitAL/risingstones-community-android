package top.cxmeow.risingstones.feature.recruitment.presentation

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.BeginnerRecruitmentCard
import top.cxmeow.risingstones.feature.recruitment.domain.BeginnerRecruitmentIdentity
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentPage
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentCatalogs
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDutyConfig
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListPage
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentPosition
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentRoleCounts
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentInteractionService
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentCard
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentMember
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentRating
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReview
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentSubcomment
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentSubcommentPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentType

@OptIn(ExperimentalCoroutinesApi::class)
class DutyRecruitmentViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun dutyBoardLoadsCatalogFiltersAndUniqueNextPage() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.state.value.dutyItems.map { it.id })
        assertEquals(listOf("High-end"), viewModel.state.value.catalogs.dutyTypes)

        viewModel.applyDutyFilter("High-end", "Omega", DutyRecruitmentPosition.Healer1)
        advanceUntilIdle()
        assertEquals("Omega", service.dutyQueries.last().dutyName)
        assertEquals(DutyRecruitmentPosition.Healer1, service.dutyQueries.last().position)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), viewModel.state.value.dutyItems.map { it.id })
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun beginnerBoardLoadsDedicatedDetailAndSubmitsResponse() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()

        viewModel.selectBoard(RecruitmentBoardKind.Beginner)
        advanceUntilIdle()
        assertEquals(CommunityRecruitmentKind.Beginner, service.communityQueries.last().kind)
        assertEquals(1, service.filterKinds.count { it == CommunityRecruitmentKind.Beginner })

        viewModel.selectDetail(11)
        advanceUntilIdle()
        assertEquals("Learning party", viewModel.state.value.communityDetail?.summary?.title)

        viewModel.respond("QQ 123")
        advanceUntilIdle()
        assertEquals("QQ 123", service.beginnerResponses.single().second)
        assertEquals("Recruiter contact", viewModel.state.value.responseContactInfo)
        assertTrue(viewModel.state.value.communityDetail?.summary?.beginner?.isResponded == true)
        assertEquals(RecruitmentNotice.ResponseSubmitted, viewModel.state.value.notice)
    }

    @Test
    fun rolePlayDetailLoadsMembersRatingsReviewsLikesRepliesAndNextPage() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        viewModel.selectBoard(RecruitmentBoardKind.RolePlay)
        advanceUntilIdle()
        viewModel.selectDetail(14)
        advanceUntilIdle()

        assertEquals("Maid One", viewModel.state.value.members.single().name)
        assertEquals(3.0, viewModel.state.value.rating?.averageScore ?: 0.0, 0.001)
        assertEquals(listOf("review-1"), viewModel.state.value.reviews.map { it.id })
        assertTrue(viewModel.state.value.hasMoreReviews)

        val review = viewModel.state.value.reviews.single()
        viewModel.likeReview(review)
        viewModel.loadSubcomments(review)
        viewModel.loadMoreReviews()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.reviews.first().isLiked)
        assertEquals(3, viewModel.state.value.reviews.first().likeCount)
        assertEquals("Thanks", viewModel.state.value.subcomments.getValue("review-1").single().content)
        assertEquals(listOf("review-1", "review-2"), viewModel.state.value.reviews.map { it.id })
        assertFalse(viewModel.state.value.hasMoreReviews)
        assertTrue(viewModel.state.value.likingReviewIds.isEmpty())
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun refreshFailureRetainsConfirmedListAndReportsInlineError() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()

        service.failDutyList = true
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.state.value.dutyItems.map { it.id })
        assertEquals("Failed", viewModel.state.value.listError)
        assertFalse(viewModel.state.value.isRefreshing)
        assertNull(viewModel.state.value.detailError)
    }

    @Test
    fun detailRefreshFailureRetainsConfirmedDetailWithoutPollutingListState() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        viewModel.selectDetail(1)
        advanceUntilIdle()

        service.failDutyDetail = true
        viewModel.refreshDetail()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.dutyDetail?.summary?.id)
        assertEquals("Failed", viewModel.state.value.detailError)
        assertNull(viewModel.state.value.listError)
    }

    @Test
    fun responseFailureKeepsEditorStateAndDoesNotMarkRecruitmentResponded() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        viewModel.selectBoard(RecruitmentBoardKind.Beginner)
        advanceUntilIdle()
        viewModel.selectDetail(11)
        advanceUntilIdle()

        service.failBeginnerResponse = true
        viewModel.respond("QQ 123")
        advanceUntilIdle()

        assertEquals("Failed", viewModel.state.value.responseError)
        assertFalse(viewModel.state.value.communityDetail?.summary?.beginner?.isResponded == true)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun standaloneDetailDoesNotFetchItsBoardList() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service), autoLoadList = false)
        advanceUntilIdle()
        viewModel.selectBoard(RecruitmentBoardKind.Duty, loadList = false)
        viewModel.selectDetail(42)
        advanceUntilIdle()

        assertTrue(service.dutyQueries.isEmpty())
        assertEquals(42, viewModel.state.value.dutyDetail?.summary?.id)
    }

    @Test
    fun clearingSelectionRemovesDetailWithoutDiscardingTheList() = runTest {
        val service = FakeDutyRecruitmentService()
        val viewModel = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        viewModel.selectDetail(1)
        advanceUntilIdle()

        viewModel.clearSelection()

        assertNull(viewModel.state.value.selectedId)
        assertNull(viewModel.state.value.dutyDetail)
        assertEquals(listOf(1, 2), viewModel.state.value.dutyItems.map { it.id })
    }
}

internal class FakeDutyRecruitmentService : RecruitmentInteractionService {
    override val canPerformAuthenticatedWrites = true
    override val hasCommunityIdentity = true
    val dutyQueries = mutableListOf<DutyRecruitmentListQuery>()
    val communityQueries = mutableListOf<CommunityRecruitmentQuery>()
    val filterKinds = mutableListOf<CommunityRecruitmentKind>()
    val beginnerResponses = mutableListOf<Pair<Int, String>>()
    var failDutyList = false
    var failDutyDetail = false
    var failBeginnerResponse = false

    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage {
        if (failDutyList) error("list unavailable")
        dutyQueries += query
        return if (query.page == 1) {
            DutyRecruitmentListPage(listOf(dutySummary(1), dutySummary(2)), 3, 1)
        } else {
            DutyRecruitmentListPage(listOf(dutySummary(2), dutySummary(3)), 3, 2)
        }
    }

    override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail {
        if (failDutyDetail) error("detail unavailable")
        return DutyRecruitmentDetail(
            dutySummary(id), null, "Team", "Requirements", "Strategy", "", 7,
            null, null, false, true, null, null,
        )
    }

    override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String) = contactInfo

    override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String): String {
        if (failBeginnerResponse) error("response unavailable")
        beginnerResponses += id to contactInfo
        return "Recruiter contact"
    }

    override suspend fun fetchCatalogs() = DutyRecruitmentCatalogs(
        duties = listOf(DutyRecruitmentDutyConfig("1", "High-end", "Omega", "8-player", 1)),
    )

    override suspend fun fetchCommunityRecruitments(query: CommunityRecruitmentQuery): CommunityRecruitmentPage {
        communityQueries += query
        val item = if (query.kind == CommunityRecruitmentKind.RolePlay) rolePlaySummary() else beginnerSummary()
        return CommunityRecruitmentPage(listOf(item), 1, query.page)
    }

    override suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind) =
        CommunityRecruitmentDetail(
            if (kind == CommunityRecruitmentKind.RolePlay) rolePlaySummary() else beginnerSummary(),
            emptyList(),
            emptyList(),
        )

    override suspend fun fetchCommunityFilterCatalog(kind: CommunityRecruitmentKind): CommunityRecruitmentFilterCatalog {
        filterKinds += kind
        return CommunityRecruitmentFilterCatalog()
    }

    override suspend fun fetchRolePlayMembers(id: Int) =
        listOf(RolePlayRecruitmentMember(1, "Maid One", "Maid", null, "Hello", emptyList()))

    override suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int): RolePlayRecruitmentReviewPage =
        if (page == 1) RolePlayRecruitmentReviewPage(listOf(review("review-1")), 1, true)
        else RolePlayRecruitmentReviewPage(listOf(review("review-1"), review("review-2")), 2, false)

    override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int) =
        RolePlayRecruitmentSubcommentPage(
            listOf(RolePlayRecruitmentSubcomment("reply", "Owner", null, null, "Thanks", emptyList())),
            page,
            false,
        )

    override suspend fun fetchRolePlayRating(id: Int) = RolePlayRecruitmentRating(listOf(1, 1, 1, 1, 1))
    override suspend fun likeRolePlayReview(id: String) = 1
}

internal fun dutySummary(id: Int) = DutyRecruitmentSummary(
    id, "author", null, "Hero", "陆行鸟", "红玉海", "陆行鸟", "High-end", "Omega",
    "8-player", "P2", "20:00", "Standard", null, null, 2, 1, emptyList(), emptyList(),
    emptyList(), DutyRecruitmentRoleCounts(mt = 1, h1 = 1), Instant.parse("2026-07-21T00:00:00Z"),
)

internal fun beginnerSummary(): CommunityRecruitmentSummary {
    val card = BeginnerRecruitmentCard(
        "New Hero", "陆行鸟 · 红玉海", BeginnerRecruitmentIdentity.Newcomer, "Learning party",
        "Welcome", emptyList(), "陆行鸟 · 红玉海", null, null, false, null,
    )
    return CommunityRecruitmentSummary(
        11, CommunityRecruitmentKind.Beginner, card.title, card.publisherName, null,
        card.publisherServer, card.targetServer, card.description, null, beginner = card,
    )
}

internal fun rolePlaySummary(): CommunityRecruitmentSummary {
    val card = RolePlayRecruitmentCard("Cafe Moon", listOf(RolePlayRecruitmentType.Light), "Cafe", "20:00", emptyList(), null)
    return CommunityRecruitmentSummary(
        14, CommunityRecruitmentKind.RolePlay, card.name, "Owner", null, null, null, card.profile, null,
        rolePlay = card,
    )
}

internal fun review(id: String) = RolePlayRecruitmentReview(
    id, "Visitor", null, null, "Good", "5", 2, false, null, emptyList(), 1,
)
