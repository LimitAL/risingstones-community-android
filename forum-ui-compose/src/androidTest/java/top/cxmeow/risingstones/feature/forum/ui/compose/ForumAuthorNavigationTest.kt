package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.*

class ForumAuthorNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listAndDetailAuthorDoNotSelectPostOrTriggerWriteActions() {
        val fixture = AuthorForumFixture()
        val authors = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesForumAuthorNavigation({ authors += it }) { RisingStonesForumScreen(fixture, {}) }
        } } }
        waitFor("Topic 1")
        author("post-uuid").assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.runOnIdle { assertEquals(0, fixture.detailReads); assertEquals(0, fixture.writes) }
        compose.onNodeWithText("Topic 1").performClick()
        waitFor("Body text")
        author("post-uuid").performClick()
        compose.onNodeWithTag("forum-like-post").performClick()
        compose.runOnIdle { assertEquals(listOf("post-uuid", "post-uuid"), authors); assertEquals(1, fixture.writes) }
    }

    @Test fun standaloneCommentPreviewAndExpandedRepliesUseTheirOwnAuthorIdentity() {
        val fixture = AuthorForumFixture()
        val authors = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesForumAuthorNavigation({ authors += it }) { RisingStonesForumPostScreen(fixture, 42, {}) }
        } } }
        waitFor("Body text")
        author("post-uuid").performClick()
        compose.onNodeWithTag("forum-detail-content").performScrollToNode(hasTestTag("forum-author-comment-1"))
        author("comment-1").performClick()
        author("reply-uuid").performScrollTo().performClick()
        compose.onNodeWithText("Reply body").assertHasNoClickAction()
        compose.onNodeWithText("View 1 replies").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Replies").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("forum-author-comment-1", useUnmergedTree = true).onLast().performClick()
        compose.onAllNodesWithTag("forum-author-reply-uuid", useUnmergedTree = true).onLast().performClick()
        compose.runOnIdle {
            assertEquals(listOf("post-uuid", "comment-1", "reply-uuid", "comment-1", "reply-uuid"), authors)
            assertEquals(0, fixture.feedReads)
            assertEquals(0, fixture.writes)
        }
    }

    @Test fun coveredReplyWindowStopsAndReturnsWithoutLosingSelectionOrReadingAgain() {
        val fixture = AuthorForumFixture(replyCount = 20)
        lateinit var owner: ForumReadingTestOwner
        var opened: String? = null
        compose.runOnIdle { owner = ForumReadingTestOwner() }
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                RisingStonesForumAuthorNavigation({
                    opened = it
                    owner.registry.currentState = Lifecycle.State.CREATED
                }) { RisingStonesForumPostScreen(fixture, 42, {}) }
            }
        } } }
        waitFor("Body text")
        compose.onNodeWithTag("forum-detail-content").performScrollToNode(hasText("View 20 replies"))
        compose.onNodeWithText("View 20 replies").performClick()
        waitFor("Replies")
        compose.onNodeWithTag("forum-replies-content").performScrollToIndex(8)
        val offset = scrollIndex("forum-replies-content")
        val reads = fixture.replyReads
        lateinit var model: OfficialForumDetailViewModel
        compose.runOnIdle {
            model = ViewModelProvider(owner)[
                "rising-stones-forum-detail-${System.identityHashCode(fixture)}-42", OfficialForumDetailViewModel::class.java]
            assertEquals(1, model.state.value.selectedSubCommentRootId)
        }
        compose.onAllNodesWithTag("forum-author-reply-uuid-9", useUnmergedTree = true).onLast().performClick()
        compose.onNodeWithText("Replies").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("reply-uuid-9", opened)
            assertEquals(1, model.state.value.selectedSubCommentRootId)
            assertEquals(reads, fixture.replyReads)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        waitFor("Replies")
        compose.onAllNodesWithTag("forum-author-reply-uuid-9", useUnmergedTree = true).onLast().assertIsDisplayed()
        assertEquals(offset, scrollIndex("forum-replies-content"))
        compose.runOnIdle {
            assertEquals(1, model.state.value.selectedSubCommentRootId)
            assertEquals(reads, fixture.replyReads)
            assertEquals(1, fixture.detailReads)
            owner.viewModelStore.clear()
        }
    }

    @Test fun absentCallbackShowsIdentityWithoutAuthorAction() {
        compose.setContent { MaterialTheme {
            RisingStonesForumAuthorNavigation(null) { RisingStonesForumPostScreen(AuthorForumFixture(), 42, {}) }
        } }
        waitFor("Body text")
        author("post-uuid").assertHasNoClickAction()
        compose.onNodeWithTag("forum-relay-post").assertDoesNotExist()
    }

    @Test fun relayUsesReadDetailIdentityWithoutForumWriteCapability() {
        val fixture = AuthorForumFixture(canWrite = false)
        var source: Pair<Int, String>? = null
        compose.setContent { MaterialTheme {
            RisingStonesForumRelayNavigation({ id, title -> source = id to title }) {
                RisingStonesForumPostScreen(fixture, 42, {})
            }
        } }
        waitFor("Body text")
        compose.onNodeWithTag("forum-like-post").assertDoesNotExist()
        compose.onNodeWithTag("forum-relay-post").performClick()
        compose.runOnIdle {
            assertEquals(42 to "Topic", source)
            assertEquals(0, fixture.writes)
        }
    }

    @Test fun blankUuidCannotNavigateEvenWhenCallbackIsAvailable() {
        compose.setContent { MaterialTheme {
            RisingStonesForumAuthorNavigation({ error("blank identity") }) {
                RisingStonesForumPostScreen(AuthorForumFixture(uuid = ""), 42, {})
            }
        } }
        waitFor("Body text")
        author("").assertHasNoClickAction()
    }

    @Test fun listScrollSurvivesAllWidthBoundaries() = preservesScroll(detail = false)
    @Test fun detailScrollSurvivesAllWidthBoundaries() = preservesScroll(detail = true)

    private fun preservesScroll(detail: Boolean) {
        val width = mutableIntStateOf(599)
        val fixture = AuthorForumFixture(count = 40)
        compose.setContent { MaterialTheme { Box(Modifier.width(width.intValue.dp).height(1000.dp)) {
            RisingStonesForumAuthorNavigation({}) { RisingStonesForumScreen(fixture, {}) }
        } } }
        waitFor("Topic 1")
        if (detail) { compose.onNodeWithText("Topic 1").performClick(); waitFor("Body text") }
        val tag = if (detail) "forum-detail-content" else "forum-list-content"
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
    private fun author(id: String) = compose.onNodeWithTag("forum-author-$id", useUnmergedTree = true)
    private fun waitFor(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

private class AuthorForumFixture(private val uuid: String = "post-uuid", private val count: Int = 1,
    private val replyCount: Int = 1, private val canWrite: Boolean = true) :
    OfficialForumService by FakeOfficialForumService {
    override val canPerformAuthenticatedWrites = canWrite
    var feedReads = 0
    var detailReads = 0
    var replyReads = 0
    var writes = 0
    private val author = OfficialForumAuthor(uuid, "Forum author", "", "", null, 0)
    override suspend fun fetchPosts(query: OfficialForumListQuery): OfficialForumPage<OfficialForumPostSummary> {
        feedReads++
        val source = FakeOfficialForumService.fetchPosts(query).items.single()
        return OfficialForumPage((1..count).map { source.copy(id = it, title = "Topic $it", author = author) }, count, 1)
    }
    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail {
        detailReads++
        return FakeOfficialForumService.fetchPostDetail(42).copy(id = id, author = author)
    }
    override suspend fun fetchComments(query: OfficialForumCommentQuery) = OfficialForumPage((1..count).map {
        OfficialForumComment(it, author.copy(uuid = "comment-$it", characterName = "Comment author $it"),
            null, "Comment body $it", emptyList(), emptyList(), null, null, 0, false,
            if (it == 1) replyCount else 0, isPostAuthor = false, isMine = false)
    }, count, 1)
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery): OfficialForumPage<OfficialForumComment> {
        replyReads++
        return OfficialForumPage((1..replyCount).map {
            OfficialForumComment(98 + it, author.copy(uuid = if (it == 1) "reply-uuid" else "reply-uuid-$it",
                characterName = "Reply author $it"), null, "Reply body", emptyList(), emptyList(), null, null,
                0, false, 0, isPostAuthor = false, isMine = false)
        }.drop((query.page - 1) * query.limit).take(query.limit), replyCount, query.page)
    }
    override suspend fun likePost(id: Int): Int { writes++; return 1 }
}

private class ForumReadingTestOwner : LifecycleOwner, ViewModelStoreOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
}
