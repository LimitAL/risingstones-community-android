package top.cxmeow.risingstones.feature.forum.ui.compose

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumInteractionError
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumLoadStatus

/** Every service and image in this suite is synthetic; it never uses the app's session container. */
class ForumInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ForumInteractionTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle { ForumInteractionFixture.configuration = null }
    }

    @Test fun commentFromCompact599Workspace() = commentFromWorkspace(599)
    @Test fun commentFromMedium600Workspace() = commentFromWorkspace(600)
    @Test fun commentFromMedium839Workspace() = commentFromWorkspace(839)
    @Test fun commentFromExpanded840Workspace() = commentFromWorkspace(840)

    private fun commentFromWorkspace(width: Int) {
        val service = show(width = width, workspace = true)
        compose.onNodeWithText("Fixture topic").performClick()
        waitForTag("forum-new-comment")
        if (width < 600) compose.onNodeWithText("Fixture excerpt").assertDoesNotExist()
        else compose.onNodeWithText("Fixture excerpt").assertExists()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        compose.onNodeWithTag("forum-comment-input").performTextInput("Comment at $width")
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 1 }
        compose.runOnIdle {
            val draft = service.comments.single()
            assertEquals(42, draft.postId)
            assertEquals(0, draft.parentId)
            assertEquals(0, draft.rootParentId)
            assertTrue(draft.contentHtml.contains("Comment at $width"))
            assertEquals("", draft.commentPictureText)
            assertEquals(2, model().state.value.detail?.commentCount)
            assertEquals(1L, model().interactionState.value.commentSuccessRevision)
            assertFalse(model().interactionState.value.isComposerOpen)
            assertEquals("", model().interactionState.value.commentText)
        }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
    }

    @Test fun rootAndNestedRepliesUseTheSelectedParentAndSameRoot() {
        val service = show()
        detailNode(hasTestTag("forum-reply-10")).performClick()
        sendText("Reply to root")
        compose.waitUntil { service.comments.size == 1 }
        detailNode(hasText(text(R.string.forum_view_replies, 1))).performClick()
        waitForTag("forum-reply-11")
        compose.onNodeWithTag("forum-reply-11").performScrollTo().performClick()
        sendText("Reply to child")
        compose.waitUntil { service.comments.size == 2 }
        assertEquals(listOf(10 to 10, 11 to 10), service.comments.map { it.parentId to it.rootParentId })
    }

    @Test fun likeAndSaveReflectBothToggleDirectionsWithoutRefetch() {
        val service = show()
        compose.onNodeWithTag("forum-like-post").performClick()
        compose.onNodeWithTag("forum-like-post").assertTextContains(text(R.string.forum_unlike_action, 5))
        compose.onNodeWithTag("forum-star-post").performClick()
        compose.onNodeWithTag("forum-star-post").assertTextContains(text(R.string.forum_unstar_action, 3))
        compose.onNodeWithTag("forum-like-post").performClick()
        compose.onNodeWithTag("forum-star-post").performClick()
        compose.onNodeWithTag("forum-like-post").assertTextContains(text(R.string.forum_like_action, 4))
        compose.onNodeWithTag("forum-star-post").assertTextContains(text(R.string.forum_star_action, 2))
        assertEquals(2, service.likeCalls)
        assertEquals(2, service.starCalls)
        assertEquals(1, service.detailReads)
    }

    @Test fun keepDraftDiscardDraftAndCancelDeletionDoNotWrite() {
        val service = show()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").performTextInput("Unsent draft")
        compose.onNodeWithText(text(R.string.forum_keep_draft)).performClick()
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").assertTextContains("Unsent draft")
        compose.onNodeWithTag("forum-discard-comment").performScrollTo().performClick()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").assertTextContains("")
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.forum_keep_draft)).performClick()
        detailNode(hasText(text(R.string.forum_delete))).performClick()
        compose.onNodeWithText(text(R.string.forum_delete_confirm_title)).assertExists()
        compose.onNodeWithText(text(R.string.forum_cancel)).performClick()
        compose.onNodeWithText("Root comment").assertExists()
        assertTrue(service.comments.isEmpty())
        assertTrue(service.deletedComments.isEmpty())
        assertEquals(0, service.imageUploads)
        compose.runOnIdle { assertEquals("", model().interactionState.value.commentText) }
    }

    @Test fun ordinaryFailureKeepsTextAndImageAndRetryReusesUploadedImage() {
        val service = show(ForumInteractionService().apply { failNextComment = true })
        val image = syntheticForumImage()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").performTextInput("Retry draft")
        compose.runOnIdle { model().setCommentImage(image) }
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 1 }
        compose.onNodeWithTag("forum-comment-input").assertTextContains("Retry draft")
        compose.onNodeWithContentDescription(text(R.string.forum_comment_image)).assertExists()
        compose.onNodeWithTag("forum-submit-comment").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(OfficialForumInteractionError.Failed, model().interactionState.value.commentError)
            assertEquals(1, model().state.value.detail?.commentCount)
            assertArrayEquals(image.bytes, model().interactionState.value.commentImage?.bytes)
        }
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 2 }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        assertEquals(1, service.imageUploads)
        assertEquals(service.comments[0], service.comments[1])
        compose.runOnIdle { assertEquals(1L, model().interactionState.value.commentSuccessRevision) }
    }

    @Test fun recreationRetainsImageAndDraftAndCompletesPendingSubmissionExactlyOnce() {
        val service = show(ForumInteractionService().apply { commentGate = CompletableDeferred() })
        val image = syntheticForumImage()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").performTextInput("Rotation draft")
        lateinit var original: OfficialForumDetailViewModel
        compose.runOnIdle { original = model(); original.setCommentImage(image) }
        compose.activityRule.scenario.recreate()
        waitForTag("forum-comment-input")
        compose.onNodeWithTag("forum-comment-input").assertTextContains("Rotation draft")
        compose.onNodeWithContentDescription(text(R.string.forum_comment_image)).assertExists()
        compose.runOnIdle {
            assertSame(original, model())
            assertArrayEquals(image.bytes, model().interactionState.value.commentImage?.bytes)
        }
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 1 }
        compose.activityRule.scenario.recreate()
        waitForTag("forum-comment-input")
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        compose.onNodeWithTag("forum-discard-comment").assertIsNotEnabled()
        compose.runOnIdle {
            assertSame(original, model())
            // An extra event while the real request is pending must also be ignored.
            model().submitDraftComment()
        }
        service.commentGate!!.complete(Unit)
        compose.waitUntil { !original.state.value.isSubmittingComment }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1L, model().interactionState.value.commentSuccessRevision)
            assertEquals(2, model().state.value.detail?.commentCount)
            assertNull(model().interactionState.value.commentImage)
        }
        assertEquals(1, service.comments.size)
        assertEquals(1, service.imageUploads)
        assertEquals(1, service.detailReads)
        assertEquals("fixture-uploaded-image", service.comments.single().commentPictureText)
    }

    @Test fun imageReadSurvivesRecreationAndFinishesWithoutUploadingOrSubmitting() {
        val service = show()
        val image = syntheticForumImage()
        val readGate = CompletableDeferred<OfficialForumCommentImageUpload>()
        var readCalls = 0
        lateinit var importer: ForumCommentImageImportViewModel
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.runOnIdle {
            importer = compose.activity.imageImportModel()
            importer.readImage(model()) { readCalls++; readGate.await() }
            assertTrue(importer.state.value.isReading)
        }
        compose.onNodeWithTag("forum-discard-comment").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        waitForTag("forum-comment-input")
        compose.onNodeWithTag("forum-discard-comment").assertIsNotEnabled()
        compose.runOnIdle {
            assertSame(importer, compose.activity.imageImportModel())
            assertTrue(importer.state.value.isReading)
            assertNull(model().interactionState.value.commentImage)
        }
        readGate.complete(image)
        compose.waitUntil { !importer.state.value.isReading }
        compose.onNodeWithContentDescription(text(R.string.forum_comment_image))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("forum-submit-comment").assertIsEnabled()
        compose.runOnIdle {
            assertArrayEquals(image.bytes, model().interactionState.value.commentImage?.bytes)
            assertNull(importer.state.value.failure)
            assertEquals(1, readCalls)
        }
        assertEquals(0, service.imageUploads)
        assertTrue(service.comments.isEmpty())
    }

    @Test fun resumingReplyDraftFromTopEntryKeepsItsOriginalParent() {
        val service = show()
        detailNode(hasTestTag("forum-reply-10")).performClick()
        compose.onNodeWithTag("forum-comment-input").performTextInput("Preserved reply draft")
        compose.onNodeWithText(text(R.string.forum_keep_draft)).performClick()
        detailNode(hasTestTag("forum-new-comment")).performClick()
        compose.onNodeWithTag("forum-comment-input").assertTextContains("Preserved reply draft")
        compose.runOnIdle {
            assertEquals(10, model().interactionState.value.replyTarget?.parentId)
            assertEquals(10, model().interactionState.value.replyTarget?.rootParentId)
        }
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 1 }
        assertEquals(10, service.comments.single().parentId)
        assertEquals(10, service.comments.single().rootParentId)
        assertTrue(service.comments.single().contentHtml.contains("Preserved reply draft"))
    }

    @Test fun capabilityLossDuringInitialDetailLoadShowsRetryAndCanRecoverReadOnly() {
        val gate = CompletableDeferred<Unit>()
        val service = show(ForumInteractionService().apply { firstDetailGate = gate }, waitForContent = false)
        compose.runOnIdle {
            assertNull(model().state.value.detail)
            assertEquals(OfficialForumLoadStatus.Loading, model().state.value.status)
            service.canPerformAuthenticatedWrites = false
        }
        waitForText(text(R.string.forum_retry))
        compose.runOnIdle {
            assertNull(model().state.value.detail)
            assertEquals(OfficialForumLoadStatus.Idle, model().state.value.status)
            assertEquals(1, service.cancelledDetailReads)
        }
        // Completing the abandoned read cannot resurrect its old authenticated detail.
        gate.complete(Unit)
        compose.runOnIdle { assertNull(model().state.value.detail) }
        compose.onNodeWithText(text(R.string.forum_retry)).performClick()
        waitForText("Fixture body")
        compose.onNodeWithTag("forum-new-comment").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(OfficialForumLoadStatus.Loaded, model().state.value.status)
            assertFalse(model().canWrite)
        }
        assertEquals(2, service.detailReads)
        assertTrue(service.comments.isEmpty())
        assertEquals(0, service.imageUploads)
    }

    @Test fun pickerResultReachesTheSameImporterAfterCrossingCompactToMedium() {
        val service = show(width = 599, workspace = true)
        compose.onNodeWithText("Fixture topic").performClick()
        waitForTag("forum-new-comment")
        detailNode(hasTestTag("forum-new-comment")).performClick()
        compose.onNodeWithText(text(R.string.forum_choose_image)).performScrollTo().performClick()
        lateinit var importer: ForumCommentImageImportViewModel
        var requestCode = 0
        val medium = ForumInteractionConfiguration(service, 600, workspace = true)
        compose.runOnIdle {
            importer = compose.activity.imageImportModel()
            requestCode = compose.activity.imagePickerRegistry.launches.single()
            ForumInteractionFixture.configuration = medium
        }
        waitForTag("forum-comment-input")
        compose.runOnIdle {
            assertEquals(600f, medium.measuredWidthDp, 0.1f)
            assertSame(importer, compose.activity.imageImportModel())
            assertNull(importer.state.value.failure)
            // A typed synthetic result exercises the registered callback without opening a picker.
            compose.activity.imagePickerRegistry.dispatchResult(requestCode, Uri.EMPTY)
        }
        compose.waitUntil { importer.state.value.failure != null }
        compose.onNodeWithText(text(R.string.forum_image_read_failed)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(ForumImageReadFailure.Unreadable, importer.state.value.failure)
            assertNull(model().interactionState.value.commentImage)
            assertEquals(1, compose.activity.imagePickerRegistry.launches.size)
        }
        assertEquals(0, service.imageUploads)
        assertTrue(service.comments.isEmpty())
    }

    @Test fun imageOnlyCommentCanBeSubmitted() {
        val service = show()
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.runOnIdle { model().setCommentImage(syntheticForumImage()) }
        compose.onNodeWithTag("forum-submit-comment").assertIsEnabled().performClick()
        compose.waitUntil { service.comments.size == 1 }
        assertEquals("", service.comments.single().contentHtml)
        assertEquals("fixture-uploaded-image", service.comments.single().commentPictureText)
    }

    @Test fun singleChoiceReplacesPreviousSelectionAndSuccessPreventsAnotherVote() {
        val service = show(ForumInteractionService(vote = fixtureVote()))
        detailNode(hasTestTag("forum-submit-vote-poll")).assertIsNotEnabled()
        detailNode(hasTestTag("forum-vote-option-poll-1")).performClick()
        detailNode(hasTestTag("forum-vote-option-poll-2")).performClick()
        detailNode(hasTestTag("forum-vote-option-poll-1")).assertIsNotSelected()
        detailNode(hasTestTag("forum-vote-option-poll-2")).assertIsSelected()
        detailNode(hasTestTag("forum-submit-vote-poll")).performClick()
        compose.waitUntil { service.votes.size == 1 }
        assertEquals(listOf(2), service.votes.single().options.map { it.optionId })
        detailNode(hasText(text(R.string.forum_vote_participated))).assertExists()
        compose.onNodeWithTag("forum-submit-vote-poll").assertDoesNotExist()
        detailNode(hasTestTag("forum-vote-option-poll-2")).assertIsNotEnabled()
        compose.runOnIdle { model().submitSelectedVote("poll") }
        assertEquals(1, service.votes.size)
    }

    @Test fun multipleChoiceEnforcesMinimumAndMaximumAndAllowsDeselecting() {
        val service = show(ForumInteractionService(vote = fixtureVote(multiple = true)))
        detailNode(hasTestTag("forum-submit-vote-poll")).assertIsNotEnabled()
        detailNode(hasTestTag("forum-vote-option-poll-1")).performClick()
        detailNode(hasTestTag("forum-submit-vote-poll")).assertIsNotEnabled()
        detailNode(hasTestTag("forum-vote-option-poll-2")).performClick()
        detailNode(hasTestTag("forum-submit-vote-poll")).assertIsEnabled()
        detailNode(hasTestTag("forum-vote-option-poll-3")).assertIsNotEnabled()
        detailNode(hasTestTag("forum-vote-option-poll-1")).performClick()
        detailNode(hasTestTag("forum-submit-vote-poll")).assertIsNotEnabled()
        detailNode(hasTestTag("forum-vote-option-poll-3")).assertIsEnabled().performClick()
        detailNode(hasTestTag("forum-submit-vote-poll")).performClick()
        compose.waitUntil { service.votes.size == 1 }
        assertEquals(listOf(2, 3), service.votes.single().options.map { it.optionId })
    }

    @Test fun previouslyParticipatedPollCannotBeSubmittedAgain() {
        assertReadOnlyPoll(ForumInteractionService(vote = fixtureVote(participated = true)),
            R.string.forum_vote_participated)
    }

    @Test fun explicitlyVisibleZeroVoteResultsCannotBeSubmitted() {
        assertReadOnlyPoll(ForumInteractionService(vote = fixtureVote(), resultsAvailable = true),
            R.string.forum_vote_results)
    }

    @Test fun expiredPollCannotBeSubmitted() {
        assertReadOnlyPoll(ForumInteractionService(vote = fixtureVote().copy(endDateText = "946684800000")),
            R.string.forum_vote_closed)
    }

    private fun assertReadOnlyPoll(service: ForumInteractionService, status: Int) {
        show(service)
        detailNode(hasText(text(status))).assertExists()
        detailNode(hasTestTag("forum-vote-option-poll-1")).assertIsNotEnabled()
        compose.onNodeWithTag("forum-submit-vote-poll").assertDoesNotExist()
        compose.runOnIdle {
            model().setVoteSelection("poll", setOf(1))
            model().submitSelectedVote("poll")
        }
        assertTrue(service.votes.isEmpty())
    }


    @Test fun firstEligibleCommentAt599KeepsDraftAndReturns() = firstEligibleComment(599)
    @Test fun firstEligibleCommentAt600KeepsDraftAndReturns() = firstEligibleComment(600)
    @Test fun firstEligibleCommentAt839KeepsDraftAndReturns() = firstEligibleComment(839)
    @Test fun firstEligibleCommentAt840KeepsDraftAndReturns() = firstEligibleComment(840)

    private fun firstEligibleComment(width: Int) {
        val service = show(ForumInteractionService(firstUseEligible = true), width, workspace = true)
        compose.onNodeWithText("Fixture topic").performClick()
        waitForTag("forum-new-comment")
        compose.runOnIdle {
            assertFalse(model().canWrite)
            assertFalse(model().canUploadCommentImages)
            assertTrue(model().canInteract)
        }
        detailNode(hasTestTag("forum-new-comment")).performClick()
        compose.onNodeWithTag("forum-comment-input").performTextInput("First draft $width")
        compose.onNodeWithText(text(R.string.forum_keep_draft)).performClick()
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        detailNode(hasTestTag("forum-new-comment")).performClick()
        compose.onNodeWithTag("forum-comment-input").assertTextContains("First draft $width")
        assertTrue(service.comments.isEmpty())
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 1 }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(model().canWrite)
            assertFalse(model().canUploadCommentImages)
            assertEquals(1L, model().interactionState.value.commentSuccessRevision)
        }
        if (width < 600) {
            compose.onNodeWithText(text(R.string.forum_back)).performClick()
            waitForText("Fixture excerpt")
            compose.onNodeWithText("Fixture topic").performClick()
            waitForTag("forum-new-comment")
        }
        detailNode(hasTestTag("forum-like-post")).performClick()
        detailNode(hasTestTag("forum-star-post")).performClick()
        assertEquals(1, service.comments.size)
        assertEquals(1, service.likeCalls)
        assertEquals(1, service.starCalls)
        assertEquals(0, service.imageUploads)
        saveFirstActionScreenshot(width)
        val authorLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("forum-comment-author-10", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(authorLayout) }
        val nameLayout = authorLayout.single()
        // Check visible characters, not fractional font metrics rounded to an integer layout size.
        assertTrue(nameLayout.lineCount <= 2)
        assertFalse((0 until nameLayout.lineCount).any(nameLayout::isLineEllipsized))
        assertEquals(nameLayout.layoutInput.text.length,
            nameLayout.getLineEnd(nameLayout.lineCount - 1, visibleEnd = true))
    }

    private fun saveFirstActionScreenshot(width: Int) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        compose.activity.filesDir.resolve("forum-first-action-$width.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun firstImageIsOnlyReadLocallyAndRetryDoesNotUploadTwice() {
        val service = show(ForumInteractionService(firstUseEligible = true).apply { failNextComment = true })
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-choose-comment-image").performScrollTo().assertIsEnabled()
        compose.runOnIdle { compose.activity.imageImportModel().readImage(model()) { syntheticForumImage() } }
        compose.waitUntil { model().interactionState.value.commentImage != null }
        compose.onNodeWithContentDescription(text(R.string.forum_comment_image)).performScrollTo().assertIsDisplayed()
        assertEquals(0, service.imageUploads)
        assertTrue(service.comments.isEmpty())
        compose.onNodeWithTag("forum-submit-comment").performClick()
        compose.waitUntil { service.comments.size == 1 }
        compose.runOnIdle {
            assertTrue(model().canUploadCommentImages)
            assertFalse(model().canWrite)
            assertEquals(0L, model().interactionState.value.commentSuccessRevision)
        }
        compose.onNodeWithContentDescription(text(R.string.forum_comment_image)).assertExists()
        compose.onNodeWithTag("forum-submit-comment").assertIsEnabled().performClick()
        compose.waitUntil { service.comments.size == 2 }
        assertEquals(1, service.imageUploads)
        compose.runOnIdle { assertTrue(model().canWrite) }
    }

    @Test fun firstCommentWithoutImageEligibilityHasNoPickerAndStillSends() {
        val service = show(ForumInteractionService(firstUseEligible = true).apply {
            canAttemptCommentImageUpload = false
        })
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-choose-comment-image").assertDoesNotExist()
        sendText("Text first")
        compose.waitUntil { service.comments.size == 1 }
        assertEquals(0, service.imageUploads)
        assertTrue(service.canPerformAuthenticatedWrites)
    }

    @Test fun firstEligiblePollIsAnExplicitSingleSubmission() {
        val service = show(ForumInteractionService(vote = fixtureVote(), firstUseEligible = true))
        detailNode(hasTestTag("forum-vote-option-poll-2")).performClick()
        assertFalse(service.canPerformAuthenticatedWrites)
        assertTrue(service.votes.isEmpty())
        detailNode(hasTestTag("forum-submit-vote-poll")).performClick()
        compose.waitUntil { service.votes.size == 1 }
        detailNode(hasText(text(R.string.forum_vote_participated))).assertIsDisplayed()
        assertTrue(service.canPerformAuthenticatedWrites)
        assertEquals(listOf(2), service.votes.single().options.map { it.optionId })
    }

    @Test fun revokedFirstEligibilityClosesDraftAndCancelsLocalReadBeforeAnyUpload() {
        val service = show(ForumInteractionService(firstUseEligible = true))
        val gate = CompletableDeferred<OfficialForumCommentImageUpload>()
        lateinit var importer: ForumCommentImageImportViewModel
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").performTextInput("Private draft")
        compose.runOnIdle {
            importer = compose.activity.imageImportModel()
            importer.readImage(model()) { gate.await() }
            service.canAttemptAuthenticatedWrites = false
        }
        compose.waitUntil { !importer.state.value.isReading }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        compose.onNodeWithTag("forum-new-comment").assertDoesNotExist()
        gate.complete(syntheticForumImage())
        compose.runOnIdle {
            assertEquals("", model().interactionState.value.commentText)
            assertNull(model().interactionState.value.commentImage)
            assertNull(importer.state.value.failure)
            service.canAttemptAuthenticatedWrites = true
        }
        waitForTag("forum-new-comment")
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-comment-input").assertTextContains("")
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        assertEquals(0, service.imageUploads)
        assertTrue(service.comments.isEmpty())
    }

    @Test fun revokedImageEligibilityCancelsPendingLocalReadButKeepsTextActionAvailable() {
        val service = show(ForumInteractionService(firstUseEligible = true))
        val gate = CompletableDeferred<OfficialForumCommentImageUpload>()
        lateinit var importer: ForumCommentImageImportViewModel
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.runOnIdle {
            importer = compose.activity.imageImportModel()
            importer.readImage(model()) { gate.await() }
            service.canAttemptCommentImageUpload = false
        }
        compose.waitUntil { !importer.state.value.isReading }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        gate.complete(syntheticForumImage())
        detailNode(hasTestTag("forum-new-comment")).performClick()
        compose.onNodeWithTag("forum-choose-comment-image").assertDoesNotExist()
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        sendText("New text draft")
        compose.waitUntil { service.comments.size == 1 }
        assertEquals("", service.comments.single().commentPictureText)
        assertEquals(0, service.imageUploads)
    }

    @Test fun firstPendingCommentSurvivesRecreationAndOnlyExplicitSuccessVerifiesIt() {
        val gate = CompletableDeferred<Unit>()
        val service = show(ForumInteractionService(firstUseEligible = true).apply { commentGate = gate })
        compose.onNodeWithTag("forum-new-comment").performClick()
        sendText("First pending")
        compose.waitUntil { service.comments.size == 1 }
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        waitForTag("forum-comment-input")
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        assertFalse(service.canPerformAuthenticatedWrites)
        compose.runOnIdle { model().submitDraftComment() }
        gate.complete(Unit)
        compose.waitUntil { service.canPerformAuthenticatedWrites }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        assertEquals(1, service.comments.size)
        assertEquals(1, service.detailReads)
    }

    @Test fun pickerResultFromRevokedDraftCannotAttachToANewlyEligibleDraft() {
        val service = show(ForumInteractionService(firstUseEligible = true))
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.onNodeWithTag("forum-choose-comment-image").performScrollTo().performClick()
        var requestCode = 0
        lateinit var importer: ForumCommentImageImportViewModel
        compose.runOnIdle {
            requestCode = compose.activity.imagePickerRegistry.launches.single()
            importer = compose.activity.imageImportModel()
            service.canAttemptAuthenticatedWrites = false
        }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        compose.runOnIdle { service.canAttemptAuthenticatedWrites = true }
        waitForTag("forum-new-comment")
        compose.onNodeWithTag("forum-new-comment").performClick()
        compose.runOnIdle {
            // An unreadable URI would produce an error if the old callback started a local read.
            compose.activity.imagePickerRegistry.dispatchResult(requestCode, Uri.EMPTY)
        }
        compose.runOnIdle {
            assertNull(importer.state.value.failure)
            assertFalse(importer.state.value.isReading)
            assertNull(model().interactionState.value.commentImage)
        }
        compose.onNodeWithTag("forum-submit-comment").assertIsNotEnabled()
        assertEquals(0, service.imageUploads)
        assertTrue(service.comments.isEmpty())
    }

    @Test fun anonymousSessionHasNoFirstUseActionsOrImageEntry() {
        val service = show(ForumInteractionService().apply {
            canPerformAuthenticatedWrites = false
            canUploadCommentImages = false
        }, waitForContent = false)
        waitForText("Fixture body")
        compose.onNodeWithTag("forum-new-comment").assertDoesNotExist()
        compose.onNodeWithTag("forum-like-post").assertDoesNotExist()
        compose.onNodeWithTag("forum-star-post").assertDoesNotExist()
        compose.runOnIdle {
            model().openCommentComposer()
            model().submitComment(top.cxmeow.risingstones.feature.forum.presentation.OfficialForumReplyTarget(0, 0), "Blocked")
        }
        compose.onNodeWithTag("forum-comment-input").assertDoesNotExist()
        assertTrue(service.comments.isEmpty())
        assertEquals(0, service.imageUploads)
    }

    private fun show(service: ForumInteractionService = ForumInteractionService(), width: Int = 599,
        workspace: Boolean = false, waitForContent: Boolean = true): ForumInteractionService {
        val configuration = ForumInteractionConfiguration(service, width, workspace)
        compose.runOnIdle { ForumInteractionFixture.configuration = configuration }
        if (!waitForContent) compose.waitUntil { service.detailReads == 1 }
        else if (workspace) waitForText("Fixture topic") else waitForTag("forum-new-comment")
        compose.runOnIdle { assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.1f) }
        return service
    }

    private fun sendText(value: String) {
        compose.onNodeWithTag("forum-comment-input").performTextInput(value)
        compose.onNodeWithTag("forum-submit-comment").performClick()
    }

    private fun detailNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // Lazy items outside the viewport do not have semantics until the list composes them.
        compose.onNodeWithTag("forum-detail-content").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun model() = compose.activity.detailModel()
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForText(value: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }
}

/** The Activity owns the real ViewModelStore. The fixture never retains a ViewModel or draft. */
class ForumInteractionTestActivity : ComponentActivity() {
    internal val imagePickerRegistry = ForumFixtureActivityResultRegistry()
    private val imagePickerOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = imagePickerRegistry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForumInteractionFixture.configuration?.let { configuration ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Fit each exact dp breakpoint into the emulator's physical viewport.
                    val density = Density(constraints.maxWidth.toFloat() / configuration.width, 1f)
                    CompositionLocalProvider(LocalDensity provides density,
                        LocalActivityResultRegistryOwner provides imagePickerOwner) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().onSizeChanged {
                                configuration.measuredWidthDp = with(density) { it.width.toDp().value }
                            }) {
                                if (configuration.workspace) RisingStonesForumScreen(configuration.service, {})
                                else RisingStonesForumPostScreen(configuration.service, 42, {})
                            }
                        }
                    }
                }
            }
        }
    }

    internal fun detailModel(): OfficialForumDetailViewModel {
        val service = checkNotNull(ForumInteractionFixture.configuration).service
        return existingModel("rising-stones-forum-detail-${System.identityHashCode(service)}-42",
            OfficialForumDetailViewModel::class.java)
    }

    internal fun imageImportModel(): ForumCommentImageImportViewModel =
        existingModel("forum-comment-image-${System.identityHashCode(detailModel())}",
            ForumCommentImageImportViewModel::class.java)

    private fun <T : ViewModel> existingModel(key: String, modelClass: Class<T>): T {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                error("The production screen must already own this ViewModel")
        }
        return ViewModelProvider(this, factory)[key, modelClass]
    }
}

/** Launches remain local to the test; results are explicitly dispatched by each picker test. */
internal class ForumFixtureActivityResultRegistry : ActivityResultRegistry() {
    val launches = mutableListOf<Int>()

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
        input: I, options: ActivityOptionsCompat?) {
        launches += requestCode
    }
}

private object ForumInteractionFixture {
    var configuration by mutableStateOf<ForumInteractionConfiguration?>(null)
}

private class ForumInteractionConfiguration(val service: ForumInteractionService, val width: Int,
    val workspace: Boolean) {
    var measuredWidthDp = 0f
}

private class ForumInteractionService(
    vote: OfficialForumPostVote? = null,
    private val resultsAvailable: Boolean = false,
    firstUseEligible: Boolean = false,
) : OfficialForumInteractionService, OfficialForumActionEligibilityService {
    override var canPerformAuthenticatedWrites by mutableStateOf(!firstUseEligible)
    override var canUploadCommentImages by mutableStateOf(!firstUseEligible)
    override var canAttemptAuthenticatedWrites by mutableStateOf(firstUseEligible)
    override var canAttemptCommentImageUpload by mutableStateOf(firstUseEligible)
    private fun recordWriteSuccess() {
        if (canAttemptAuthenticatedWrites) canPerformAuthenticatedWrites = true
    }
    val comments = CopyOnWriteArrayList<OfficialForumCommentDraft>()
    val votes = CopyOnWriteArrayList<OfficialForumVoteDraft>()
    val deletedComments = CopyOnWriteArrayList<Int>()
    @Volatile var detailReads = 0
    @Volatile var cancelledDetailReads = 0
    @Volatile var imageUploads = 0
    @Volatile var likeCalls = 0
    @Volatile var starCalls = 0
    var failNextComment = false
    var commentGate: CompletableDeferred<Unit>? = null
    var firstDetailGate: CompletableDeferred<Unit>? = null
    private val author = OfficialForumAuthor("fixture-author", "Fixture author", "", "", null, 0)
    private val part = OfficialForumPart(8, "Fixture category", "")
    private val detail = OfficialForumPostDetail(42, "Fixture topic", "", "Fixture body",
        listOf(OfficialForumRichTextSegment.Text("Fixture body")),
        listOf(OfficialForumPostBodyBlock.Paragraph(listOf(OfficialForumRichTextSegment.Text("Fixture body")))),
        emptyList(), listOfNotNull(vote), author, part, null, null, null, 1, 4, 2,
        false, false, 10, null, false, false)
    private val root = OfficialForumComment(10, author, null, "Root comment", emptyList(), emptyList(),
        null, null, 0, false, 1, isPostAuthor = false, isMine = true)
    private val child = root.copy(id = 11, bodyText = "Child comment", childCount = 0, isMine = false)

    override suspend fun fetchParts() = listOf(OfficialForumPartFilter(8, "Fixture category", 1))
    override suspend fun fetchPosts(query: OfficialForumListQuery) = OfficialForumPage(listOf(
        OfficialForumPostSummary(42, "Fixture topic", "Fixture excerpt", author, part, emptyList(),
            null, null, 1, 4, 2, 10, false, false)), 1, query.page)
    override suspend fun searchPosts(query: OfficialForumSearchQuery) = fetchPosts(OfficialForumListQuery(page = query.page))
    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail {
        detailReads++
        try {
            if (detailReads == 1) firstDetailGate?.await()
        } catch (error: CancellationException) {
            cancelledDetailReads++
            throw error
        }
        return detail
    }
    override suspend fun fetchPostInteraction(id: Int) = OfficialForumPostInteraction(fetchPostDetail(id),
        if (resultsAvailable) setOf("poll") else emptySet())
    override suspend fun fetchComments(query: OfficialForumCommentQuery) = OfficialForumPage(listOf(root), 1, query.page)
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery) = OfficialForumPage(listOf(child), 1, query.page)
    override suspend fun likePost(id: Int): Int { likeCalls++; recordWriteSuccess(); return if (likeCalls % 2 == 1) 1 else -1 }
    override suspend fun starPost(id: Int): Int { starCalls++; recordWriteSuccess(); return if (starCalls % 2 == 1) 1 else -1 }
    override suspend fun likeComment(id: Int) = 1
    override suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload): String {
        imageUploads++
        if (canAttemptCommentImageUpload) canUploadCommentImages = true
        return "fixture-uploaded-image"
    }
    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> {
        comments += draft
        commentGate?.await()
        if (failNextComment) { failNextComment = false; throw OfficialForumException.Business(12345, "fixture failure") }
        recordWriteSuccess()
        return listOf(100 + comments.size)
    }
    override suspend fun deleteComment(id: Int) { deletedComments += id }
    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult {
        votes += draft
        recordWriteSuccess()
        return OfficialForumVoteResult(1, draft.options.associate { it.optionId to 1 })
    }
}

private fun fixtureVote(multiple: Boolean = false, participated: Boolean = false) = OfficialForumPostVote(
    "poll", "Fixture poll", 1, if (multiple) 2 else 1, if (multiple) 2 else 1, 0, null, 0,
    (1..3).map { OfficialForumPostVoteOption("option-$it", it, "Option $it", null,
        if (multiple) 2 else 1, 0, participated && it == 1) })

internal fun syntheticForumImage(width: Int = 16, height: Int = 16): OfficialForumCommentImageUpload {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        OfficialForumCommentImageUpload(output.toByteArray(), "image/png")
    } finally { bitmap.recycle() }
}
