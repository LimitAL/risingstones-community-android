package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsingService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowseQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsePage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCategory
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumFeedFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumAuthor
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumComment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumListQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPart
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPartFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostDetail
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostSummary
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSubCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteResult
import java.time.Instant

class RisingStonesForumScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactSelectionReplacesTheListWithDetail() {
        showAtWidth(599.dp)
        waitFor("Topic")

        composeRule.onNodeWithText("Topic").performClick()
        waitFor("Body text")

        composeRule.onNodeWithText("Body text").assertExists()
        composeRule.onNodeWithText("Excerpt").assertDoesNotExist()
    }

    @Test
    fun mediumSelectionKeepsTheListBesideDetail() {
        showAtWidth(600.dp)
        waitFor("Topic")

        composeRule.onNodeWithText("Topic").performClick()
        waitFor("Body text")

        composeRule.onNodeWithText("Excerpt").assertExists()
        composeRule.onNodeWithText("Body text").assertExists()
    }

    @Test
    fun expandedSelectionKeepsTheListBesideDetail() {
        showAtWidth(840.dp)
        waitFor("Topic")

        composeRule.onNodeWithText("Topic").performClick()
        waitFor("Body text")

        composeRule.onNodeWithText("Excerpt").assertExists()
        composeRule.onNodeWithText("Body text").assertExists()
    }

    @Test
    fun upperMediumBoundaryKeepsListAndDetail() {
        showAtWidth(839.dp)
        waitFor("Topic")
        composeRule.onNodeWithText("Topic").performClick()
        waitFor("Body text")
        composeRule.onNodeWithText("Excerpt").assertExists()
    }

    @Test
    fun guideChildCategoryAndFeedFiltersUseSeparateRequests() {
        showAtWidth(600.dp)
        waitFor("Topic")
        composeRule.onNodeWithText("Newest posts").performClick()
        composeRule.waitUntil { FakeOfficialForumService.lastQuery?.filter == OfficialForumFeedFilter.Latest }
        composeRule.onNodeWithText("Guides").performClick()
        waitFor("Battle")
        composeRule.onNodeWithText("Battle").performClick()
        waitFor("Job")
        composeRule.onNodeWithText("Job").performClick()
        composeRule.waitUntil { FakeOfficialForumService.lastQuery?.list?.partIds == listOf(8) }
        assertEquals(OfficialForumContentKind.Guide, FakeOfficialForumService.lastQuery?.list?.contentKind)
        assertEquals(OfficialForumFeedFilter.Default, FakeOfficialForumService.lastQuery?.filter)
    }

    @Test
    fun officialPostLinkOpensInsideTheForumWorkspace() {
        showAtWidth(400.dp)
        waitFor("Topic")

        composeRule.onNodeWithText("Topic").performClick()
        waitFor("Open linked topic")
        composeRule.onNodeWithText("Open linked topic").performClick()
        waitFor("Linked topic body")

        composeRule.onNodeWithText("Linked topic body").assertExists()
        composeRule.onNodeWithText("Excerpt").assertDoesNotExist()
    }

    private fun showAtWidth(width: Dp) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(
                        Modifier
                            .width(width)
                            .height(1_000.dp),
                    ) {
                        RisingStonesForumScreen(
                            service = FakeOfficialForumService,
                            onOpenAccount = {},
                        )
                    }
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

internal object FakeOfficialForumService : OfficialForumBrowsingService {
    var lastQuery: OfficialForumBrowseQuery? = null
    override suspend fun fetchCategories(kind: OfficialForumContentKind) =
        if (kind == OfficialForumContentKind.Guide) listOf(
            OfficialForumCategory(OfficialForumPartFilter(3, "Battle", 1),
                listOf(OfficialForumCategory(OfficialForumPartFilter(8, "Job", 0)))))
        else listOf(OfficialForumCategory(OfficialForumPartFilter(8, "News", 1)))

    override suspend fun fetchBrowsePage(query: OfficialForumBrowseQuery): OfficialForumBrowsePage {
        lastQuery = query
        return OfficialForumBrowsePage(fetchPosts(query.list), null)
    }

    override suspend fun searchBrowsePage(query: OfficialForumSearchQuery, pageTime: String?) =
        OfficialForumBrowsePage(searchPosts(query), null)

    override val canPerformAuthenticatedWrites = false

    override suspend fun fetchParts() = listOf(OfficialForumPartFilter(8, "News", 1))

    override suspend fun fetchPosts(query: OfficialForumListQuery) =
        OfficialForumPage(listOf(Post), 1, query.page)

    override suspend fun searchPosts(query: OfficialForumSearchQuery) =
        OfficialForumPage(listOf(Post), 1, query.page)

    override suspend fun fetchPostDetail(id: Int) =
        if (id == 84) LinkedDetail else Detail

    override suspend fun fetchComments(query: OfficialForumCommentQuery) =
        OfficialForumPage<OfficialForumComment>(emptyList(), 0, query.page)

    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery) =
        OfficialForumPage<OfficialForumComment>(emptyList(), 0, query.page)

    override suspend fun likePost(id: Int) = 0

    override suspend fun likeComment(id: Int) = error("unused")

    override suspend fun starPost(id: Int) = 0

    override suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload) = error("unused")

    override suspend fun submitComment(draft: OfficialForumCommentDraft) = error("unused")

    override suspend fun deleteComment(id: Int) = error("unused")

    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult =
        error("unused")
}

private val TestAuthor = OfficialForumAuthor(
    uuid = "helper",
    characterName = "Forum helper",
    areaName = "World",
    groupName = "DC",
    avatarUrl = null,
    adminTag = 1,
)

private val TestPart = OfficialForumPart(8, "News", "Official")
private val TestTime = Instant.parse("2026-07-27T12:00:00Z")

private val Post = OfficialForumPostSummary(
    id = 42,
    title = "Topic",
    excerpt = "Excerpt",
    author = TestAuthor,
    part = TestPart,
    coverImageUrls = emptyList(),
    createdAt = TestTime,
    lastCommentAt = TestTime,
    commentCount = 0,
    likeCount = 0,
    starCount = 0,
    readCount = 1,
    isTop = false,
    isRefined = false,
)

private val Detail = OfficialForumPostDetail(
    id = 42,
    title = "Topic",
    bodyHtml = "<p>Body text</p>",
    bodyText = "Body text",
    bodySegments = listOf(OfficialForumRichTextSegment.Text("Body text")),
    bodyBlocks = listOf(
        OfficialForumPostBodyBlock.Paragraph(
            listOf(
                OfficialForumRichTextSegment.Text("Body text"),
                OfficialForumRichTextSegment.Link(
                    text = "Open linked topic",
                    url = "https://ff14risingstones.web.sdo.com/detail/84",
                ),
            ),
        ),
    ),
    contentImageUrls = emptyList(),
    votes = emptyList(),
    author = TestAuthor,
    part = TestPart,
    createdAt = TestTime,
    updatedAt = TestTime,
    lastCommentAt = TestTime,
    commentCount = 0,
    likeCount = 0,
    starCount = 0,
    isLiked = null,
    isStarred = null,
    readCount = 1,
    ipLocation = null,
    isTop = false,
    isRefined = false,
)

private val LinkedDetail = Detail.copy(
    id = 84,
    title = "Linked topic",
    bodyHtml = "<p>Linked topic body</p>",
    bodyText = "Linked topic body",
    bodySegments = listOf(OfficialForumRichTextSegment.Text("Linked topic body")),
    bodyBlocks = listOf(
        OfficialForumPostBodyBlock.Paragraph(
            listOf(OfficialForumRichTextSegment.Text("Linked topic body")),
        ),
    ),
)
