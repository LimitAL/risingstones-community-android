package top.cxmeow.risingstones.feature.forum.ui.compose

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Build
import android.text.style.BackgroundColorSpan
import android.text.style.ImageSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every bitmap and service response is synthetic; no official asset, candidate or write is requested. */
class ForumCommentEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ForumCommentEditorTestActivity>()
    @After fun clearFixture() { compose.runOnIdle { ForumEditorFixture.configuration = null } }

    @Test fun nativeEmojiMentionAndSingleSubmitAt599() = nativeEmojiMentionAndSingleSubmit(599)
    @Test fun nativeEmojiMentionAndSingleSubmitAt600() = nativeEmojiMentionAndSingleSubmit(600)
    @Test fun nativeEmojiMentionAndSingleSubmitAt839() = nativeEmojiMentionAndSingleSubmit(839)
    @Test fun nativeEmojiMentionAndSingleSubmitAt840() = nativeEmojiMentionAndSingleSubmit(840)

    private fun nativeEmojiMentionAndSingleSubmit(width: Int) {
        val fixture = show(width)
        fixture.submitGate = CompletableDeferred()
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("A🙂Z")
        input().performTextInputSelection(TextRange(1, 3))
        compose.onNodeWithTag("forum-open-emoji").performClick()
        if (width == 599) saveScreenshot("forum-comment-emoji-picker-599.png", "forum-emoji-picker")
        compose.onNodeWithTag("forum-emoji-1").performClick()
        input().assertTextContains("A[emo1]Z")
        compose.waitUntil { bitmapSpanCount() == 1 }
        nativeEditor { editor ->
            assertEquals("A[emo1]Z", editor.text.toString())
            assertEquals(7, editor.selectionStart)
            assertEquals(7, editor.selectionEnd)
            assertTrue(editor.isFocused)
            assertEquals(1, editor.text!!.getSpans(0, editor.length(), ImageSpan::class.java).size)
        }
        compose.onNodeWithTag("forum-open-mention").performClick()
        waitForTag("forum-mention-alice-id")
        if (width == 599) saveScreenshot("forum-comment-mention-picker-599.png", "forum-mention-picker")
        compose.onNodeWithTag("forum-mention-query").performTextInput("Ali")
        compose.onNodeWithTag("forum-mention-bob-id").assertDoesNotExist()
        assertEquals(1, fixture.candidateReads)
        compose.onNodeWithTag("forum-mention-alice-id").performClick()
        input().assertTextContains("@Alice", substring = true)
        nativeEditor { editor ->
            assertEquals("A[emo1]@Alice Z", editor.text.toString())
            assertFalse(editor.text.toString().contains("alice-id"))
            assertFalse(editor.text.toString().contains("<span"))
            assertEquals(1, editor.text!!.getSpans(0, editor.length(), BackgroundColorSpan::class.java).size)
        }
        saveScreenshot("forum-comment-editor-$width.png", "forum-comment-input")
        compose.onNodeWithTag("forum-submit-comment").performTouchInput { doubleClick() }
        compose.waitUntil { fixture.submissions.size == 1 }
        nativeEditor { assertFalse(it.isEnabled) }
        fixture.submitGate!!.complete(Unit)
        waitForTagAbsent("forum-comment-input")
        val sent = fixture.submissions.single()
        assertEquals(listOf(OfficialForumCommentMention("alice-id", "Alice")), sent.mentions)
        assertTrue(sent.draft.contentHtml.contains("class=\"at-emo\""))
        assertTrue(sent.draft.contentHtml.contains("class=\"at-text\""))
        assertEquals(0, sent.draft.parentId)
        assertEquals(1, fixture.candidateReads)
    }

    @Test fun replyDraftSelectionAndPickerSurviveConfigurationAndBackKeepsComposer() {
        val fixture = show(599)
        compose.onNodeWithTag("forum-detail-content").performScrollToNode(hasTestTag("forum-reply-10"))
        compose.onNodeWithTag("forum-reply-10").performClick()
        input().performTextInput("Reply draft")
        input().performTextInputSelection(TextRange(1, 5))
        compose.onNodeWithTag("forum-open-emoji").performClick()
        compose.activityRule.scenario.recreate()
        waitForTag("forum-emoji-picker")
        // After recreation the activity root can exist while the dialog owns window focus.
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        waitForTag("forum-comment-input")
        nativeEditor { assertEquals("Reply draft", it.text.toString()); assertEquals(1, it.selectionStart); assertEquals(5, it.selectionEnd); assertTrue(it.isFocused) }
        compose.runOnIdle { assertEquals(10, model().interactionState.value.replyTarget?.parentId) }
        compose.onNodeWithText(text(R.string.forum_keep_draft)).performClick()
        waitForTagAbsent("forum-comment-input")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        compose.onNodeWithTag("forum-new-comment").performClick()
        waitForTag("forum-comment-input")
        nativeEditor { assertEquals(1, it.selectionStart); assertEquals(5, it.selectionEnd) }
        assertTrue(fixture.submissions.isEmpty())
        assertEquals(1, fixture.detailReads)
        assertEquals(0, fixture.candidateReads)
    }

    @Test fun nativeImeChangesRemoveEditedMentionIdentityAndSelectionAloneRetainsIt() {
        val fixture = show(600)
        compose.onNodeWithTag("forum-new-comment").performClick()
        mentionAlice()
        input().performTextInputSelection(TextRange(1, 3))
        compose.runOnIdle { assertEquals(1, model().commentEditorState.value.mentions.size) }
        nativeEditor { editor ->
            editor.requestFocus()
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            connection.commitText("XX", 1)
        }
        compose.runOnIdle {
            assertEquals("@XXice ", model().commentEditorState.value.text)
            assertTrue(model().commentEditorState.value.mentions.isEmpty())
        }
        input().performTextReplacement("")
        mentionAlice()
        input().performTextReplacement("@Alice ")
        compose.runOnIdle { assertTrue(model().commentEditorState.value.mentions.isEmpty()) }
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { fixture.submissions.size == 1 }
        assertTrue(fixture.submissions.single().mentions.isEmpty())
        assertFalse(fixture.submissions.single().draft.contentHtml.contains("data-uuid"))
    }

    @Test fun mentionFailureRetriesAndFilterNeverReadsTheServer() {
        val fixture = show(599, ForumEditorService().apply { failCandidates = true })
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-open-mention").performClick()
        waitForTag("forum-refresh-mentions")
        compose.waitUntil { fixture.candidateReads == 1 }
        compose.onNodeWithText(text(R.string.forum_mention_load_failed)).assertExists()
        compose.onNodeWithTag("forum-refresh-mentions").performClick()
        waitForTag("forum-mention-alice-id")
        compose.onNodeWithTag("forum-mention-query").performTextInput("not followed")
        compose.onNodeWithText(text(R.string.forum_mention_no_match)).assertExists()
        assertEquals(2, fixture.candidateReads)
        // Explicit picker back, unlike submitting the composer, only preserves the draft.
        compose.onNodeWithTag("forum-close-comment-picker").performClick()
        waitForTag("forum-comment-input")
        assertTrue(fixture.submissions.isEmpty())
    }

    @Test fun emojiImageFailureStillUsesAccessibleImageSpanAndSupportsLastOfficialNumber() {
        show(599, missingImages = true)
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-open-emoji").performClick()
        compose.onNodeWithTag("forum-emoji-picker").performScrollToIndex(45)
        compose.onNodeWithTag("forum-emoji-46").assertContentDescriptionEquals(text(R.string.forum_comment_emoji_number, 46))
            .performClick()
        nativeEditor { editor ->
            val span = editor.text!!.getSpans(0, editor.length(), ImageSpan::class.java).single()
            assertFalse(span.drawable is BitmapDrawable)
            assertEquals(text(R.string.forum_comment_emoji_number, 46), span.source)
            assertTrue(span.drawable.bounds.width() > 0)
        }
    }

    @Test fun loadedMentionPickerAndIdentitySurviveRecreationWithoutDuplicateReads() {
        val fixture = show(840)
        compose.onNodeWithTag("forum-new-comment").performClick()
        mentionAlice()
        input().performTextInputSelection(TextRange(7))
        compose.onNodeWithTag("forum-open-mention").performClick()
        waitForTag("forum-mention-alice-id")
        compose.activityRule.scenario.recreate()
        waitForTag("forum-mention-alice-id")
        assertEquals(1, fixture.candidateReads)
        compose.onNodeWithTag("forum-close-comment-picker").performClick()
        nativeEditor { assertEquals("@Alice ", it.text.toString()); assertEquals(7, it.selectionStart); assertTrue(it.isFocused) }
        compose.runOnIdle { assertEquals(1, model().commentEditorState.value.mentions.size) }
        assertTrue(fixture.submissions.isEmpty())
    }

    @Test fun selectedNameContainingEmojiSyntaxRemainsReadableAndNeverBecomesAnImage() {
        val fixture = show(600, ForumEditorService().apply {
            candidates = listOf(OfficialForumMentionCandidate("literal-id", "Name[emo1]"))
        })
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-open-mention").performClick()
        waitForTag("forum-mention-literal-id")
        compose.onNodeWithTag("forum-mention-literal-id").performClick()
        nativeEditor { editor ->
            assertEquals("@Name[emo1] ", editor.text.toString())
            assertTrue(editor.text!!.getSpans(0, editor.length(), ImageSpan::class.java).isEmpty())
            assertEquals(1, editor.text!!.getSpans(0, editor.length(), BackgroundColorSpan::class.java).size)
        }
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { fixture.submissions.size == 1 }
        assertEquals("Name[emo1]", fixture.submissions.single().mentions.single().name)
        assertFalse(fixture.submissions.single().draft.contentHtml.contains("at-emo"))
    }

    @Test fun nativeImeAndHardwareBackspaceRemoveAnEntireEmojiWithoutTokenFragments() {
        show(599)
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("AZ")
        repeat(3) { deletion ->
            input().performTextInputSelection(TextRange(1))
            compose.onNodeWithTag("forum-open-emoji").performClick()
            compose.onNodeWithTag("forum-emoji-1").performClick()
            nativeEditor { editor ->
                assertEquals("A[emo1]Z", editor.text.toString())
                assertTrue(editor.isFocused)
                val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                when (deletion) {
                    0 -> assertTrue(connection.deleteSurroundingText(1, 0))
                    1 -> assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
                    else -> assertTrue(editor.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
                }
                assertEquals("AZ", editor.text.toString())
                assertEquals(1, editor.selectionStart)
            }
            compose.runOnIdle { assertEquals("AZ", model().commentEditorState.value.text) }
        }
    }

    @Test fun accountReadRevocationClearsCachedIdentityAndClosesPrivatePickerWithoutAWrite() {
        val fixture = show(840)
        compose.onNodeWithTag("forum-new-comment").performClick()
        mentionAlice()
        compose.onNodeWithTag("forum-open-mention").performClick()
        waitForTag("forum-mention-alice-id")
        compose.runOnIdle { fixture.canReadMentionCandidates = false }
        waitForTagAbsent("forum-mention-picker")
        compose.runOnIdle {
            val state = model().commentEditorState.value
            assertTrue(state.text.isEmpty())
            assertTrue(state.mentions.isEmpty())
            assertTrue(state.candidates.isEmpty())
            assertFalse(model().interactionState.value.isComposerOpen)
            assertTrue(model().canWrite)
        }
        assertTrue(fixture.submissions.isEmpty())
        assertEquals(1, fixture.candidateReads)
    }

    @Test fun selectedEmojiHardwareDeletionKeepsAdjacentTextAndImeDeletesOnlyAroundSelection() {
        show(600)
        compose.onNodeWithTag("forum-new-comment").performClick()
        listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL).forEach { key ->
            input().performTextReplacement("ABZ")
            input().performTextInputSelection(TextRange(2))
            compose.onNodeWithTag("forum-open-emoji").performClick()
            compose.onNodeWithTag("forum-emoji-1").performClick()
            input().performTextInputSelection(TextRange(2, 8))
            nativeEditor { editor ->
                assertTrue(editor.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key)))
                assertEquals("ABZ", editor.text.toString())
                assertEquals(2, editor.selectionStart)
            }
        }
        input().performTextReplacement("A[emo1]B[emo1]Z")
        input().performTextInputSelection(TextRange(7, 8))
        nativeEditor { editor ->
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.deleteSurroundingText(1, 1))
            assertEquals("ABZ", editor.text.toString())
            assertEquals(1, editor.selectionStart)
            assertEquals(2, editor.selectionEnd)
        }
        compose.runOnIdle { assertEquals("ABZ", model().commentEditorState.value.text) }
    }

    @Test fun imeSurroundingDeletionPreservesComposingTextWhileRemovingAdjacentEmoji() {
        show(600)
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("A[emo1]中文[emo1]Z")
        input().performTextInputSelection(TextRange(8))
        nativeEditor { editor ->
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingRegion(7, 9))
            assertTrue(connection.deleteSurroundingTextInCodePoints(1, 1))
            assertEquals("A中文Z", editor.text.toString())
            assertEquals(2, editor.selectionStart)
            assertEquals(2, editor.selectionEnd)
            assertEquals(1, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(3, BaseInputConnection.getComposingSpanEnd(editor.text))
        }
        compose.runOnIdle { assertEquals("A中文Z", model().commentEditorState.value.text) }
        input().performTextReplacement("[emo1]中")
        input().performTextInputSelection(TextRange(7))
        nativeEditor { editor ->
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingRegion(6, 7))
            assertTrue(editor.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
            assertEquals("[emo1]", editor.text.toString())
            assertEquals(6, editor.selectionStart)
        }
    }

    @Test fun unavailableMentionCapabilityDoesNotEraseAnOrdinaryCommentDraft() {
        val fixture = show(599, ForumEditorService().apply { canReadMentionCandidates = false })
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("Ordinary comment")
        compose.onNodeWithTag("forum-open-mention").assertDoesNotExist()
        compose.onNodeWithTag("forum-open-emoji").performClick()
        compose.onNodeWithTag("forum-close-comment-picker").performClick()
        nativeEditor { assertEquals("Ordinary comment", it.text.toString()) }
        assertEquals(0, fixture.candidateReads)
        assertTrue(fixture.submissions.isEmpty())
    }

    @Test fun closingLoadingMentionPickerCancelsReadAndKeepsDraftWithoutAnError() {
        val fixture = show(599, ForumEditorService().apply { candidateGate = CompletableDeferred() })
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("Still drafting")
        compose.onNodeWithTag("forum-open-mention").performClick()
        compose.waitUntil { fixture.candidateReads == 1 }
        compose.onNodeWithTag("forum-close-comment-picker").performClick()
        compose.waitUntil { fixture.cancelledCandidateReads == 1 }
        nativeEditor { assertEquals("Still drafting", it.text.toString()); assertTrue(it.isFocused) }
        compose.runOnIdle { assertNull(model().commentEditorState.value.candidateError) }
        assertTrue(fixture.submissions.isEmpty())
    }

    @Test fun accessibilityHasOneRealEditableNodeAndSetTextUpdatesTheSameNativeDraft() {
        show(600)
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("Synthetic accessibility draft")
        nativeEditor { editor ->
            (editor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(editor.windowToken, 0)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var editableNodes: List<AccessibilityNodeInfo> = emptyList()
        compose.waitUntil(5000) {
            editableNodes = editableNodes(automation.rootInActiveWindow)
            editableNodes.any { it.text?.toString() == "Synthetic accessibility draft" }
        }
        assertEquals(1, editableNodes.size)
        val nativeNode = editableNodes.single()
        assertEquals("android.widget.EditText", nativeNode.className.toString())
        assertTrue(nativeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Edited through accessibility")
        }))
        input().assertTextContains("Edited through accessibility")
        nativeEditor { assertEquals("Edited through accessibility", it.text.toString()) }
        compose.runOnIdle { assertEquals("Edited through accessibility", model().commentEditorState.value.text) }
    }

    @Test fun cancelledInitialCandidateReadIsNotPresentedAsAConfirmedEmptyList() {
        val fixture = show(599, ForumEditorService().apply { cancelCandidates = true })
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-open-mention").performClick()
        compose.waitUntil(5000) { fixture.candidateReads == 1 }
        compose.onNodeWithText(text(R.string.forum_mention_empty)).assertDoesNotExist()
        compose.onNodeWithTag("forum-refresh-mentions").performClick()
        waitForTag("forum-mention-alice-id")
        assertEquals(2, fixture.candidateReads)
        assertTrue(fixture.submissions.isEmpty())
    }

    @Test fun realSoftKeyboardHidesForPickerAndReturnsToFocusedNativeSelection() {
        show(599)
        compose.onNodeWithTag("forum-new-comment").performClick()
        input().performTextInput("Keyboard draft")
        input().performTextInputSelection(TextRange(3, 7))
        nativeEditor { editor ->
            editor.requestFocus()
            val manager = editor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            assertTrue(manager.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT))
        }
        if (Build.VERSION.SDK_INT >= 30) compose.waitUntil(5000) {
            var visible = false
            nativeEditor { visible = it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            visible
        }
        compose.onNodeWithTag("forum-open-emoji").performScrollTo().performClick()
        waitForTag("forum-emoji-picker")
        saveScreenshot("forum-comment-emoji-picker-after-ime-599.png", "forum-emoji-picker")
        compose.onNodeWithTag("forum-close-comment-picker").performClick()
        waitForTag("forum-comment-input")
        nativeEditor { editor ->
            assertTrue(editor.isFocused)
            assertEquals("Keyboard draft", editor.text.toString())
            assertEquals(3, editor.selectionStart)
            assertEquals(7, editor.selectionEnd)
            if (Build.VERSION.SDK_INT >= 30) assertFalse(editor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
        }
    }

    private fun editableNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return (if (node.isEditable) listOf(node) else emptyList()) +
            (0 until node.childCount).flatMap { editableNodes(node.getChild(it)) }
    }

    private fun mentionAlice() {
        compose.onNodeWithTag("forum-open-mention").performClick()
        waitForTag("forum-mention-alice-id")
        compose.onNodeWithTag("forum-mention-alice-id").performClick()
        waitForTag("forum-comment-input")
    }
    private fun show(width: Int, service: ForumEditorService = ForumEditorService(), missingImages: Boolean = false): ForumEditorService {
        compose.runOnIdle { ForumEditorFixture.configuration = ForumEditorConfiguration(service, width, missingImages) }
        waitForTag("forum-new-comment")
        return service
    }
    private fun input() = compose.onNodeWithTag("forum-comment-input")
    private fun model() = compose.activity.detailModel()
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitForTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForTagAbsent(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun nativeEditor(check: (ForumNativeCommentEditText) -> Unit) {
        onView(withTagValue(equalTo("forum-comment-native-input"))).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            check(view as ForumNativeCommentEditText)
        }
    }
    private fun bitmapSpanCount(): Int {
        var count = 0
        nativeEditor { editor -> count = editor.text!!.getSpans(0, editor.length(), ImageSpan::class.java).count { it.drawable is BitmapDrawable } }
        return count
    }
    private fun saveScreenshot(name: String, visibleTag: String) {
        waitForTag(visibleTag)
        compose.onNodeWithTag(visibleTag).assertIsDisplayed()
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        onView(isRoot()).inRoot(isDialog()).check { root, error ->
            if (error != null) throw error
            root.postOnAnimation { root.postOnAnimation { rendered.countDown() } }
        }
        check(rendered.await(3, TimeUnit.SECONDS)) { "Synthetic editor frame did not render" }
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        compose.activity.cacheDir.resolve(name).outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        screenshot.recycle()
    }
}

class ForumCommentEditorTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForumEditorFixture.configuration?.let { config ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = Density(constraints.maxWidth.toFloat() / config.width, 1f)
                    CompositionLocalProvider(LocalDensity provides density, LocalForumCommentEmojiLoader provides config.imageLoader) {
                        MaterialTheme { RisingStonesForumPostScreen(config.service, 42, {}) }
                    }
                }
            }
        }
    }
    internal fun detailModel(): OfficialForumDetailViewModel {
        val service = checkNotNull(ForumEditorFixture.configuration).service
        return ViewModelProvider(this)["rising-stones-forum-detail-${System.identityHashCode(service)}-42", OfficialForumDetailViewModel::class.java]
    }
}

private object ForumEditorFixture { var configuration by mutableStateOf<ForumEditorConfiguration?>(null) }
private class ForumEditorConfiguration(val service: ForumEditorService, val width: Int, missingImages: Boolean) {
    private val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
    val imageLoader: suspend (Int) -> Bitmap? = { if (missingImages) null else image }
}
private data class ForumEditorSubmission(val draft: OfficialForumCommentDraft, val mentions: List<OfficialForumCommentMention>)
private class ForumEditorService : OfficialForumCommentAuthoringService, OfficialForumService by FakeOfficialForumService {
    override val canPerformAuthenticatedWrites = true
    override var canReadMentionCandidates by mutableStateOf(true)
    var candidateReads = 0
    var detailReads = 0
    var failCandidates = false
    var cancelCandidates = false
    var candidateGate: CompletableDeferred<Unit>? = null
    var cancelledCandidateReads = 0
    var candidates = listOf(OfficialForumMentionCandidate("alice-id", "Alice"), OfficialForumMentionCandidate("bob-id", "Bob"))
    var submitGate: CompletableDeferred<Unit>? = null
    val submissions = CopyOnWriteArrayList<ForumEditorSubmission>()
    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail {
        detailReads++
        return FakeOfficialForumService.fetchPostDetail(id)
    }
    override suspend fun fetchComments(query: OfficialForumCommentQuery) = OfficialForumPage(listOf(
        OfficialForumComment(10, OfficialForumAuthor("reply-target", "Reply target", "", "", null, 0), null,
            "Root comment", emptyList(), emptyList(), null, null, 0, false, 0, isPostAuthor = false, isMine = false)
    ), 1, query.page)
    override suspend fun fetchMentionCandidates(): List<OfficialForumMentionCandidate> {
        candidateReads++
        if (cancelCandidates) { cancelCandidates = false; throw CancellationException("Synthetic cancellation") }
        try { candidateGate?.await() } catch (cancelled: CancellationException) { cancelledCandidateReads++; throw cancelled }
        if (failCandidates) { failCandidates = false; throw IllegalStateException("Synthetic candidate failure") }
        return candidates
    }
    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> = submit(draft, emptyList())
    override suspend fun submitCommentWithMentions(draft: OfficialForumCommentDraft, mentions: List<OfficialForumCommentMention>) = submit(draft, mentions)
    private suspend fun submit(draft: OfficialForumCommentDraft, mentions: List<OfficialForumCommentMention>): List<Int> {
        submissions += ForumEditorSubmission(draft, mentions)
        submitGate?.await()
        return listOf(100)
    }
}
