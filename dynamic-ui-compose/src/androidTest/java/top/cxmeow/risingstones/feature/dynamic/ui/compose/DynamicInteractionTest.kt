package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel

class DynamicInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cancelReplySubmitAndDeleteRequireExplicitUserActions() {
        val actions = UiActions()
        show(actions)
        openDetail()
        rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("discard")
        rule.onNodeWithTag("dynamic-cancel-comment").performClick()
        rule.onNodeWithText("discard").assertDoesNotExist()

        rule.onNodeWithText("Reply").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("answer")
        rule.onNodeWithTag("dynamic-submit-comment").performClick()
        rule.waitUntil(5_000) { actions.comments.size == 1 }
        assertEquals(10, actions.comments.single().parentId)
        assertEquals(10, actions.comments.single().rootParentId)

        rule.waitUntil(5_000) { rule.onAllNodesWithText("Delete comment").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Delete comment").performScrollTo().performClick()
        assertTrue(actions.deletedComments.isEmpty())
        rule.onNodeWithTag("dynamic-confirm-delete-comment").performClick()
        rule.waitUntil(5_000) { actions.deletedComments == listOf(10) }
    }

    @Test fun widthChangeKeepsOneDraftAndDoesNotSendUntilSubmit() {
        val width = mutableStateOf(599)
        val actions = UiActions()
        val model = DynamicViewModel(UiReadService)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme { RisingStonesDynamicActionProvider(actions) {
            Box(Modifier.width(width.value.dp).height(1000.dp)) { RisingStonesDynamicScreen(model, {}) }
        } } } }
        openDetail()
        rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("kept")
        rule.runOnIdle { width.value = 840 }
        rule.onNodeWithTag("dynamic-comment-input").assertTextContains("kept")
        assertTrue(actions.comments.isEmpty())
        rule.onNodeWithTag("dynamic-submit-comment").performClick()
        rule.waitUntil(5_000) { actions.comments.size == 1 }
    }

    @Test fun failedCommentShowsErrorInsideEditorAndPreservesTextForRetry() {
        val actions = UiActions().apply { failComment = true }
        show(actions)
        openDetail()
        rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("retry me")
        rule.onNodeWithTag("dynamic-submit-comment").performClick()
        rule.onNodeWithTag("dynamic-comment-error").assertIsDisplayed()
        rule.onNodeWithTag("dynamic-comment-input").assertTextContains("retry me")
        rule.onNodeWithTag("dynamic-submit-comment").assertIsEnabled()
        rule.runOnIdle { actions.failComment = false }
        rule.onNodeWithTag("dynamic-submit-comment").performClick()
        rule.waitUntil(5_000) { actions.comments.size == 1 }
    }

    @Test fun returningToListClearsDraftAndClosesCredentialScope() {
        val actions = UiActions()
        show(actions)
        openDetail()
        rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("private")
        rule.onNodeWithTag("dynamic-cancel-comment").performClick()
        rule.onAllNodesWithText("Back")[1].performClick()
        rule.waitUntil(5_000) { actions.lastScope?.closed == true }
        openDetail()
        rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").assertTextEquals("")
    }

    @Test fun interactionIsAvailableAt599() = interactionAt(599)
    @Test fun interactionIsAvailableAt600() = interactionAt(600)
    @Test fun interactionIsAvailableAt839() = interactionAt(839)
    @Test fun interactionIsAvailableAt840() = interactionAt(840)

    private fun interactionAt(width: Int) {
        val actions = UiActions()
        val model = DynamicViewModel(UiReadService)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme {
            RisingStonesDynamicActionProvider(actions) {
                Box(Modifier.width(width.dp).height(1000.dp)) { RisingStonesDynamicScreen(model, {}) }
            }
        } } }
        openDetail()
        rule.onNodeWithTag("dynamic-comment").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("width $width")
        captureWidth(width, "editor")
        rule.onNodeWithTag("dynamic-cancel-comment").performClick()
        rule.onNodeWithTag("dynamic-comment-input").assertDoesNotExist()
        captureWidth(width, "reading")
        assertTrue(actions.comments.isEmpty())
    }

    private fun captureWidth(width: Int, stage: String) {
        if (InstrumentationRegistry.getArguments().getString("dynamicScreenshots") != "true") return
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.targetContext.cacheDir.resolve("dynamic-$width-$stage.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(actions: UiActions) {
        val model = DynamicViewModel(UiReadService)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme { RisingStonesDynamicActionProvider(actions) {
            Box(Modifier.width(599.dp).height(1000.dp)) { RisingStonesDynamicScreen(model, {}) }
        } } } }
    }
    private fun openDetail() {
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Feed item").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Feed item").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Detail body").fetchSemanticsNodes().isNotEmpty() }
    }
}

internal class UiScope : DynamicActionScope {
    var closed = false
    override suspend fun isCurrent() = !closed
    override fun close() { closed = true }
}
internal class UiActions : DynamicActionService {
    override val canPerformAuthenticatedWrites = false
    override val canAttemptAuthenticatedWrites = true
    var lastScope: UiScope? = null
    val comments = mutableListOf<DynamicCommentDraft>()
    val deletedComments = mutableListOf<Int>()
    var failComment = false
    var commentGate: CompletableDeferred<Unit>? = null
    override suspend fun beginActionScope() = UiScope().also { lastScope = it }
    override suspend fun entryEligibility(scope: DynamicActionScope, dynamicId: Int) = DynamicEntryActionEligibility(dynamicId, DynamicActionEligibility.Eligible)
    override suspend fun commentEligibility(scope: DynamicActionScope, commentId: Int) = DynamicCommentActionEligibility(commentId, DynamicActionEligibility.Eligible)
    override suspend fun fetchMentionCandidates(scope: DynamicActionScope, query: DynamicListQuery) = DynamicPage<DynamicCommentMention>(emptyList(), query.page, false)
    override suspend fun fetchComments(scope: DynamicActionScope, dynamicId: Int, query: DynamicListQuery) = DynamicPage(listOf(uiComment(10)), query.page, false)
    override suspend fun fetchReplies(scope: DynamicActionScope, rootParentId: Int, query: DynamicListQuery) = DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int) = DynamicLikeResult.Liked
    override suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft) {
        if (failComment) throw DynamicException.Network
        comments += draft
        commentGate?.await()
    }
    override suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int) { deletedComments += commentId }
    override suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int) = Unit
}
private val UiAuthor = DynamicAuthor("fixture", "Member", "Area", "World", null)
private fun uiEntry(body: String) = DynamicEntry(1, UiAuthor, body, emptyList(), null, 1, 0, false, null)
private fun uiComment(id: Int) = DynamicComment(id, UiAuthor, "Comment body", emptyList(), null, null, 0, 0)
internal object UiReadService : DynamicService {
    override val canRead = true
    override suspend fun fetchFeed(query: DynamicListQuery) = DynamicPage(listOf(uiEntry("Feed item")), 1, false)
    override suspend fun fetchDetail(id: Int) = uiEntry("Detail body")
    override suspend fun fetchComments(id: Int, query: DynamicListQuery) = DynamicPage(listOf(uiComment(10)), 1, false)
    override suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery) = DynamicPage<DynamicComment>(emptyList(), 1, false)
}
