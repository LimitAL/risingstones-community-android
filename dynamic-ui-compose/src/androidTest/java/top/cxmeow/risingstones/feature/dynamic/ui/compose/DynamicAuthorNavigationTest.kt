package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel

class DynamicAuthorNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authorButtonsPassExactIdentityWithoutSelectingBodyOrOpeningReference() {
        val fixture = AuthorDynamicFixture()
        val model = DynamicViewModel(fixture)
        val authors = mutableListOf<String>()
        var references = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesDynamicAuthorNavigation({ authors += it }) {
                RisingStonesDynamicScreen(model, {}, onOpenReference = { references++ })
            }
        } } }
        waitFor("Feed 1")
        author("post-uuid").assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.runOnIdle { assertNull(model.state.value.selectedId); assertEquals(0, fixture.detailReads) }
        compose.onNodeWithText("Feed 1").performClick()
        waitFor("Detail body")
        author("post-uuid").performClick()
        author("comment-1").performScrollTo().performClick()
        compose.onNodeWithText("View 1 replies").performScrollTo().performClick()
        waitFor("Reply body")
        author("reply-uuid").performScrollTo().performClick()
        compose.onNodeWithText("Reply body").assertHasNoClickAction()
        compose.runOnIdle {
            assertEquals(listOf("post-uuid", "post-uuid", "comment-1", "reply-uuid"), authors)
            assertEquals(0, references)
            assertEquals(1, fixture.detailReads)
        }
    }

    @Test fun removingCallbackLeavesAuthorAsDisplayOnly() {
        val available = mutableStateOf(true)
        var calls = 0
        val model = DynamicViewModel(AuthorDynamicFixture())
        compose.setContent { MaterialTheme {
            RisingStonesDynamicAuthorNavigation(if (available.value) ({ calls++ }) else null) {
                RisingStonesDynamicScreen(model, {})
            }
        } }
        waitFor("Feed 1")
        author("post-uuid").performClick()
        compose.runOnIdle { available.value = false }
        author("post-uuid").assertHasNoClickAction()
        assertEquals(1, calls)
    }

    @Test fun blankCommunityIdentityDoesNotExposeButton() {
        val model = DynamicViewModel(AuthorDynamicFixture(authorId = ""))
        compose.setContent { MaterialTheme {
            RisingStonesDynamicAuthorNavigation({ error("blank identity") }) { RisingStonesDynamicScreen(model, {}) }
        } }
        waitFor("Feed 1")
        author("").assertHasNoClickAction()
    }

    @Test fun standaloneDetailReadsNoFeedAndBackAndSourceStayWithHost() {
        val fixture = AuthorDynamicFixture()
        val model = DynamicViewModel(fixture)
        var author: String? = null
        var reference: DynamicReference? = null
        var backs = 0
        compose.runOnIdle { model.select(73) }
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesDynamicAuthorNavigation({ author = it }) {
                RisingStonesDynamicDetailScreen(model, { backs++ }, onOpenReference = { reference = it })
            }
        } } }
        waitFor("Detail body")
        author("post-uuid").performClick()
        compose.onNodeWithText("View source").performScrollTo().performClick()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle {
            assertEquals("post-uuid", author)
            assertEquals(DynamicOrigin.Post, reference?.origin)
            assertEquals("42", reference?.id)
            assertEquals(1, backs)
            assertEquals(73, model.state.value.selectedId)
            assertEquals(0, fixture.feedReads)
            assertEquals(1, fixture.detailReads)
        }
    }

    @Test fun standaloneDetailShowsAccessFailureWithoutFallingBackToFeed() {
        val fixture = AuthorDynamicFixture()
        val service = object : DynamicService by fixture {
            override suspend fun fetchDetail(id: Int): DynamicEntry = throw DynamicException.AuthenticationRequired
        }
        val model = DynamicViewModel(service)
        compose.runOnIdle { model.select(73) }
        compose.setContent { MaterialTheme { RisingStonesDynamicDetailScreen(model, {}) } }
        waitFor("Your session has expired. Sign in again.")
        compose.onNodeWithText("Comments").assertDoesNotExist()
        assertEquals(0, fixture.feedReads)
    }

    @Test fun listScrollSurvivesAllWidthBoundaries() = preservesScroll(detail = false)
    @Test fun detailScrollSurvivesAllWidthBoundaries() = preservesScroll(detail = true)

    private fun preservesScroll(detail: Boolean) {
        val width = mutableIntStateOf(599)
        val fixture = AuthorDynamicFixture(count = 40)
        val model = DynamicViewModel(fixture)
        compose.setContent { MaterialTheme { Box(Modifier.width(width.intValue.dp).height(1000.dp)) {
            RisingStonesDynamicAuthorNavigation({}) { RisingStonesDynamicScreen(model, {}) }
        } } }
        waitFor("Feed 1")
        if (detail) { compose.onNodeWithText("Feed 1").performClick(); waitFor("Detail body") }
        val tag = if (detail) "dynamic-detail-content" else "dynamic-list-content"
        compose.onNodeWithTag(tag).performScrollToIndex(12)
        val first = scrollIndex(tag)
        assertTrue(first >= 12)
        listOf(600, 839, 840, 599).forEach { breakpoint ->
            compose.runOnIdle { width.intValue = breakpoint }
            assertEquals("width $breakpoint", first, scrollIndex(tag))
        }
        assertEquals(1, fixture.feedReads)
        assertEquals(if (detail) 1 else 0, fixture.detailReads)
    }

    private fun scrollIndex(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value().toInt()
    private fun author(id: String) = compose.onNodeWithTag("dynamic-author-$id", useUnmergedTree = true)
    private fun waitFor(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

private class AuthorDynamicFixture(private val authorId: String = "post-uuid", private val count: Int = 1) : DynamicService {
    override val canRead = true
    var feedReads = 0
    var detailReads = 0
    private val author = DynamicAuthor(authorId, "Dynamic author", "", "", null)
    override suspend fun fetchFeed(query: DynamicListQuery): DynamicPage<DynamicEntry> {
        feedReads++
        return DynamicPage((1..count).map { DynamicEntry(it, author, "Feed $it", emptyList(), null, 1, 0, false, null) }, 1, false)
    }
    override suspend fun fetchDetail(id: Int): DynamicEntry {
        detailReads++
        return DynamicEntry(id, author, "Detail body", emptyList(), null, count, 0, false,
            DynamicReference(DynamicOrigin.Post, "42", "Source title", "", emptyList()))
    }
    override suspend fun fetchComments(id: Int, query: DynamicListQuery) = DynamicPage((1..count).map {
        DynamicComment(it, author.copy(id = "comment-$it", name = "Comment author $it"), "Comment body $it",
            emptyList(), null, null, 0, if (it == 1) 1 else 0)
    }, 1, false)
    override suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery) = DynamicPage(listOf(
        DynamicComment(99, author.copy(id = "reply-uuid", name = "Reply author"), "Reply body", emptyList(), null, null, 0, 0)
    ), 1, false)
}
