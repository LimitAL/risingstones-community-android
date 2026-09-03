package top.cxmeow.risingstones.feature.forum.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumAuthor
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumComment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumListQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPart
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPartFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostDetail
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostSummary
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVote
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVoteOption
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSubCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteResult
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OfficialForumViewModelsTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun listLoadsPartsSubmitsSearchAndSwitchesGuideContract() = runTest {
        val service = FakeOfficialForumService()
        val viewModel = OfficialForumListViewModel(service)

        viewModel.ensureLoaded()
        advanceUntilIdle()
        assertEquals(listOf(8), viewModel.state.value.parts.map { it.id })
        viewModel.select(POST)
        viewModel.selectPostId(84)
        assertEquals(84, viewModel.state.value.selectedPostId)
        viewModel.togglePart(8)
        advanceUntilIdle()
        assertEquals(listOf(8), service.listQueries.last().partIds)

        viewModel.setSearchText("  guide  ")
        advanceUntilIdle()
        assertTrue(service.searchQueries.isEmpty())
        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals("guide", service.searchQueries.single().keywords)

        viewModel.setContentKind(OfficialForumContentKind.Guide)
        advanceUntilIdle()
        assertEquals(OfficialForumContentKind.Guide, service.listQueries.last().contentKind)
        assertTrue(service.listQueries.last().partIds.isEmpty())
        assertEquals(null, viewModel.state.value.selectedPostId)
    }

    @Test
    fun resubmittingCurrentSearchMatchesIosCommitSearch() = runTest {
        val service = FakeOfficialForumService()
        val viewModel = OfficialForumListViewModel(service)
        viewModel.ensureLoaded()
        advanceUntilIdle()

        viewModel.setSearchText("topic")
        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals(1, service.searchQueries.size)

        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals(2, service.searchQueries.size)
        assertEquals("topic", service.searchQueries.last().keywords)
    }

    @Test
    fun listPreservesConfirmedRowsOnFailureAndDeduplicatesPagination() = runTest {
        val service = FakeOfficialForumService()
        val viewModel = OfficialForumListViewModel(service)
        viewModel.ensureLoaded()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(42, 43), viewModel.state.value.posts.map { it.id })
        assertEquals(2, viewModel.state.value.page)

        service.failReads = true
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(OfficialForumLoadStatus.Failed, viewModel.state.value.status)
        assertEquals(listOf(42, 43), viewModel.state.value.posts.map { it.id })
    }

    @Test
    fun detailLoadsParallelCommentsPreviewsFiltersAndAllChildren() = runTest {
        val service = FakeOfficialForumService()
        val viewModel = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()

        assertEquals(OfficialForumLoadStatus.Loaded, viewModel.state.value.status)
        assertEquals(listOf(9), viewModel.state.value.comments.map { it.id })
        assertEquals(listOf(91, 92), viewModel.state.value.subCommentsByRootId[9]?.map { it.id })

        viewModel.openSubComments(COMMENT)
        advanceUntilIdle()
        assertEquals(listOf(91, 92, 93, 94), viewModel.state.value.subCommentsByRootId[9]?.map { it.id })
        assertEquals(9, viewModel.state.value.selectedSubCommentRootId)

        viewModel.toggleOnlyPostAuthor()
        advanceUntilIdle()
        assertTrue(service.commentQueries.last().onlyPostAuthor)
        assertFalse(viewModel.state.value.comments.isEmpty())
    }

    @Test
    fun detailMarksNestedReplyFailureForRetry() = runTest {
        val service = FakeOfficialForumService().apply { failSubComments = true }
        val viewModel = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()

        viewModel.openSubComments(COMMENT)
        advanceUntilIdle()

        assertTrue(9 in viewModel.state.value.subCommentErrorIds)
        assertFalse(9 in viewModel.state.value.loadingSubCommentIds)
    }

    @Test
    fun authenticatedActionsUpdateDetailAndPreserveExactReplyMarkup() = runTest {
        val service = FakeOfficialForumService()
        val viewModel = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()

        viewModel.likePost()
        viewModel.starPost()
        viewModel.submitComment(OfficialForumReplyTarget(9, 9, "Hero"), "Hello & <all>\n[emo2]")
        viewModel.submitVote(VOTE, setOf(2))
        advanceUntilIdle()

        val detail = requireNotNull(viewModel.state.value.detail)
        assertEquals(true, detail.isLiked)
        assertEquals(3, detail.likeCount)
        assertEquals(false, detail.isStarred)
        assertEquals(2, detail.starCount)
        assertEquals(2, detail.commentCount)
        assertEquals(9, service.lastCommentDraft?.parentId)
        assertEquals(9, service.lastCommentDraft?.rootParentId)
        assertEquals(
            "<p>Hello &amp; &lt;all&gt;</p><p><span class=\"at-emo\">[emo2]</span></p>",
            service.lastCommentDraft?.contentHtml,
        )
        assertEquals(listOf(2), service.lastVoteDraft?.options?.map { it.optionId })
        val vote = detail.votes.single()
        assertEquals(5, vote.totalUserCount)
        assertTrue(vote.options.single { it.optionId == 2 }.isParticipant)
        assertEquals(4, vote.options.single { it.optionId == 2 }.totalVoteCount)
    }
}

private class FakeOfficialForumService : OfficialForumService {
    override val canPerformAuthenticatedWrites = false
    var failReads = false
    var failSubComments = false
    val listQueries = mutableListOf<OfficialForumListQuery>()
    val searchQueries = mutableListOf<OfficialForumSearchQuery>()
    val commentQueries = mutableListOf<OfficialForumCommentQuery>()
    var lastCommentDraft: OfficialForumCommentDraft? = null
    var lastVoteDraft: OfficialForumVoteDraft? = null

    override suspend fun fetchParts() = listOf(OfficialForumPartFilter(8, "News", 9))

    override suspend fun fetchPosts(
        query: OfficialForumListQuery,
    ): OfficialForumPage<OfficialForumPostSummary> {
        if (failReads) error("offline")
        listQueries += query
        return if (query.page == 1) OfficialForumPage(listOf(POST), 2, 1)
        else OfficialForumPage(listOf(POST, POST.copy(id = 43)), 2, 2)
    }

    override suspend fun searchPosts(
        query: OfficialForumSearchQuery,
    ): OfficialForumPage<OfficialForumPostSummary> {
        searchQueries += query
        return OfficialForumPage(listOf(POST), 1, query.page)
    }

    override suspend fun fetchPostDetail(id: Int) = DETAIL

    override suspend fun fetchComments(query: OfficialForumCommentQuery): OfficialForumPage<OfficialForumComment> {
        commentQueries += query
        return OfficialForumPage(listOf(COMMENT), 1, query.page)
    }

    override suspend fun fetchSubComments(
        query: OfficialForumSubCommentQuery,
    ): OfficialForumPage<OfficialForumComment> {
        if (failSubComments) error("nested replies unavailable")
        val values = if (query.limit == 3) CHILDREN.take(2) else CHILDREN
        return OfficialForumPage(values, CHILDREN.size, query.page)
    }

    override suspend fun likePost(id: Int) = 1
    override suspend fun starPost(id: Int) = -1
    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> {
        lastCommentDraft = draft
        return listOf(10)
    }
    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult {
        lastVoteDraft = draft
        return OfficialForumVoteResult(5, mapOf(1 to 1, 2 to 4))
    }
}

private val NOW = Instant.parse("2026-07-21T12:00:00Z")
private val AUTHOR = OfficialForumAuthor("u", "Hero", "World", "DC", null, 0)
private val PART = OfficialForumPart(8, "News", "Official")
private val POST = OfficialForumPostSummary(
    42, "Maintenance", "Details", AUTHOR, PART, emptyList(), NOW, NOW,
    1, 2, 3, 4, false, true,
)
private val DETAIL = OfficialForumPostDetail(
    42, POST.title, "<p>Details</p>", "Details", emptyList(), emptyList(), emptyList(), listOf(
        OfficialForumPostVote(
            "vote", "Choice", 1, 1, 1, 0, null, 4,
            listOf(
                OfficialForumPostVoteOption("1", 1, "One", null, 1, 1, false),
                OfficialForumPostVoteOption("2", 2, "Two", null, 1, 3, false),
            ),
        ),
    ),
    AUTHOR, PART, NOW, NOW, NOW, 1, 2, 3, null, null, 4, null, false, true,
)
private val VOTE = DETAIL.votes.single()
private val COMMENT = OfficialForumComment(
    9, AUTHOR, null, "Root", emptyList(), emptyList(), NOW, null, 2, 4,
    isPostAuthor = true,
)
private val CHILDREN = (1..4).map { index ->
    COMMENT.copy(id = 90 + index, bodyText = "Child $index", childCount = 0, isPostAuthor = false)
}
