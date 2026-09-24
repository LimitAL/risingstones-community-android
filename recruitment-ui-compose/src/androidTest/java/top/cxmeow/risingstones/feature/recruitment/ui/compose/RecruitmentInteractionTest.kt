package top.cxmeow.risingstones.feature.recruitment.ui.compose

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

/** Real screens with synthetic services; no session container or official request is used. */
class RecruitmentInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<RecruitmentInteractionTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle { RecruitmentInteractionFixture.configuration = null }
    }

    @Test fun dutyResponseAtCompact599() = respondAtWidth(599, RecruitmentBoardKind.Duty)
    @Test fun beginnerResponseAtMedium600() = respondAtWidth(600, RecruitmentBoardKind.Beginner)
    @Test fun dutyResponseAtUpperMedium839() = respondAtWidth(839, RecruitmentBoardKind.Duty)
    @Test fun beginnerResponseAtExpanded840() = respondAtWidth(840, RecruitmentBoardKind.Beginner)

    private fun respondAtWidth(width: Int, board: RecruitmentBoardKind) {
        val service = show(width = width, board = board, workspace = true)
        if (board == RecruitmentBoardKind.Beginner) selectBoard(R.string.recruitment_board_beginner)
        compose.onNodeWithText(if (board == RecruitmentBoardKind.Duty) "Synthetic duty" else "Synthetic beginner")
            .performClick()
        waitForTag("recruitment-detail-content")
        if (width < 600) compose.onNodeWithText("List fixture excerpt", substring = true).assertDoesNotExist()
        else compose.onNodeWithText("List fixture excerpt", substring = true).assertExists()
        openResponse()
        compose.onNodeWithTag("recruitment-submit-response").assertIsNotEnabled()
        enterContact("My synthetic contact")
        submitResponse()
        compose.waitUntil { service.responses.size == 1 }
        detailNode(hasText("Returned recruiter contact")).assertIsDisplayed()
        compose.onNodeWithText("My synthetic contact").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
        assertEquals(ResponseCall(board, 42, "My synthetic contact"), service.responses.single())
        saveFirstActionScreenshot(width)
    }

    private fun saveFirstActionScreenshot(width: Int) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        compose.activity.filesDir.resolve("recruitment-first-action-$width.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun dutyFiltersCancelWithoutRequestAndAppliedConditionsSurviveBoardSwitches() {
        val service = show(workspace = true)
        val initialReads = service.dutyQueries.size
        compose.onNodeWithTag("recruitment-filters").performClick()
        choose("duty-type", "零式")
        choose("duty-name", "Fixture trial")
        compose.onNodeWithText(text(R.string.recruitment_cancel)).performClick()
        compose.runOnIdle { assertEquals(initialReads, service.dutyQueries.size) }

        compose.onNodeWithTag("recruitment-filters").performClick()
        compose.onNodeWithTag("recruitment-filter-duty-type").assertTextContains(text(R.string.recruitment_all))
        choose("duty-type", "零式")
        choose("duty-name", "Fixture trial")
        compose.onNodeWithTag("recruitment-filter-option-MainTank").performScrollTo().performClick()
        compose.onNodeWithTag("recruitment-filter-option-Healer1").performScrollTo().performClick()
        choose("duty-area", "Fixture region")
        compose.onNodeWithTag("recruitment-filter-option-label-1").performScrollTo().performClick()
        compose.onNodeWithTag("recruitment-apply-filters").performClick()
        compose.waitUntil { service.dutyQueries.size == initialReads + 1 }
        val selected = service.dutyQueries.last()
        assertEquals("零式", selected.list.dutyType)
        assertEquals("Fixture trial", selected.list.dutyName)
        assertEquals(listOf(DutyRecruitmentPosition.MainTank, DutyRecruitmentPosition.Healer1), selected.positions)
        assertEquals("满编小队", selected.teamComposition)
        assertEquals("area-1", selected.targetAreaId)
        assertEquals(listOf("label-1"), selected.labelIds)
        assertEquals(1, selected.list.page)

        selectBoard(R.string.recruitment_board_beginner)
        compose.onNodeWithTag("recruitment-filters").performClick()
        choose("area", "Fixture region")
        choose("server", "Fixture world")
        compose.onNodeWithTag("recruitment-apply-filters").performClick()
        compose.waitUntil { service.communityQueries.lastOrNull()?.groupId == "world-1" }
        val beginner = service.communityQueries.last()
        assertEquals("area-1", beginner.areaId)
        assertEquals(CommunityRecruitmentKind.Beginner, beginner.kind)
        selectBoard(R.string.recruitment_board_duty)
        compose.waitUntil { service.dutyQueries.size == initialReads + 2 }
        assertEquals(selected, service.dutyQueries.last())
        selectBoard(R.string.recruitment_board_beginner)
        compose.waitUntil { service.communityQueries.lastOrNull() == beginner }
        compose.onNodeWithTag("recruitment-filters").performClick()
        compose.onNodeWithTag("recruitment-filter-area").assertTextContains("Fixture region")
        compose.onNodeWithTag("recruitment-filter-server").assertTextContains("Fixture world")
    }

    @Test fun dutyDraftKeepDiscardAndThirtyCharacterBoundary() {
        val service = show()
        openResponse()
        enterContact("x".repeat(31))
        submitResponse()
        compose.onNode(hasText(text(R.string.recruitment_invalid_input)) and hasAnyAncestor(isDialog())).assertExists()
        assertTrue(service.responses.isEmpty())
        compose.onNodeWithTag("recruitment-response-input").performTextReplacement("x".repeat(30))
        compose.onNodeWithText(text(R.string.recruitment_keep_draft)).performClick()
        assertTrue(service.responses.isEmpty())
        openResponse()
        compose.onNodeWithTag("recruitment-response-input").assertTextContains("x".repeat(30))
        compose.onNodeWithTag("recruitment-discard-response").performScrollTo().performClick()
        openResponse()
        compose.onNodeWithTag("recruitment-submit-response").assertIsNotEnabled()
        assertTrue(service.responses.isEmpty())
        enterContact("x".repeat(30))
        submitResponse()
        compose.waitUntil { service.responses.size == 1 }
        assertEquals(30, service.responses.single().contact.length)
    }

    @Test fun rolePlayAreaChangeClearsMultipleServersAndAllServersOmitsGroup() =
        multipleServersAndAreaReset(RecruitmentBoardKind.RolePlay, "")

    @Test fun otherAreaChangeClearsMultipleServersAndAllServersUsesZeroGroup() =
        multipleServersAndAreaReset(RecruitmentBoardKind.Other, "0")

    private fun multipleServersAndAreaReset(board: RecruitmentBoardKind, allServers: String) {
        val service = show(workspace = true)
        selectBoard(if (board == RecruitmentBoardKind.RolePlay) R.string.recruitment_board_roleplay
            else R.string.recruitment_board_other)
        compose.onNodeWithTag("recruitment-filters").performClick()
        choose("area", "Fixture region")
        compose.onNodeWithTag("recruitment-filter-server-world-1").performScrollTo().performClick()
        compose.onNodeWithTag("recruitment-filter-server-world-2").performScrollTo().performClick()
        compose.onNodeWithTag("recruitment-filter-server-world-1").assertIsSelected()
        compose.onNodeWithTag("recruitment-filter-server-world-2").assertIsSelected()
        val selected = applyCommunityFilters(service)
        assertEquals("area-1", selected.areaId)
        assertEquals("world-1,world-2", selected.groupId)

        compose.onNodeWithTag("recruitment-filters").performClick()
        choose("area", "Second fixture region")
        compose.onNodeWithTag("recruitment-filter-server-world-1").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-filter-server-world-2").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-filter-server-world-3").assertIsNotSelected()
        compose.onNodeWithTag("recruitment-filter-server-all").assertIsSelected()
        val changed = applyCommunityFilters(service)
        assertEquals("area-2", changed.areaId)
        assertEquals(allServers, changed.groupId)

        compose.onNodeWithTag("recruitment-filters").performClick()
        choose("area", "Fixture region")
        compose.onNodeWithTag("recruitment-filter-server-world-1").assertIsNotSelected()
        compose.onNodeWithTag("recruitment-filter-server-world-2").assertIsNotSelected()
        compose.onNodeWithTag("recruitment-filter-server-world-1").performScrollTo().performClick()
        compose.onNodeWithTag("recruitment-filter-server-world-2").performScrollTo().performClick()
        compose.onNodeWithTag("recruitment-filter-server-all").performScrollTo().performClick()
        val all = applyCommunityFilters(service)
        assertEquals("area-1", all.areaId)
        assertEquals(allServers, all.groupId)

        compose.onNodeWithTag("recruitment-filters").performClick()
        choose("area", text(R.string.recruitment_all))
        compose.onNodeWithTag("recruitment-filter-servers").assertDoesNotExist()
        val unrestricted = applyCommunityFilters(service)
        assertEquals("", unrestricted.areaId)
        assertEquals("", unrestricted.groupId)
        assertTrue(service.responses.isEmpty())
    }

    private fun applyCommunityFilters(service: RecruitmentInteractionServiceFixture): CommunityRecruitmentQuery {
        val reads = service.communityQueries.size
        compose.onNodeWithTag("recruitment-apply-filters").performClick()
        compose.waitUntil { service.communityQueries.size > reads }
        return service.communityQueries.last()
    }

    @Test fun beginnerErrorRetainsDraftAndNullSuccessDoesNotInventRecruiterContact() {
        val service = show(RecruitmentInteractionServiceFixture().apply {
            failNextResponse = true
            returnedContact = null
        }, board = RecruitmentBoardKind.Beginner)
        openResponse()
        enterContact("My private fixture contact")
        compose.onNodeWithText(text(R.string.recruitment_keep_draft)).performClick()
        openResponse()
        compose.onNodeWithTag("recruitment-response-input").assertTextContains("My private fixture contact")
        submitResponse()
        compose.waitUntil { service.responses.size == 1 }
        compose.onNodeWithTag("recruitment-response-input").assertTextContains("My private fixture contact")
        compose.onNode(hasText(text(R.string.recruitment_action_failed)) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithTag("recruitment-submit-response").assertIsEnabled()
        submitResponse()
        compose.waitUntil { service.responses.size == 2 }
        detailNode(hasText(text(R.string.recruitment_contact_unavailable))).assertIsDisplayed()
        compose.onNodeWithText("My private fixture contact").assertDoesNotExist()
        compose.onNodeWithText("Old detail contact").assertDoesNotExist()
        compose.onNodeWithText("Returned recruiter contact").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
        assertEquals(service.responses[0], service.responses[1])
    }

    @Test fun dutyNullResponseContactCanBeReplacedByFreshDetailContact() =
        refreshContactAfterNullResponse(RecruitmentBoardKind.Duty)

    @Test fun beginnerNullResponseContactCanBeReplacedByFreshDetailContact() =
        refreshContactAfterNullResponse(RecruitmentBoardKind.Beginner)

    private fun refreshContactAfterNullResponse(board: RecruitmentBoardKind) {
        val service = show(RecruitmentInteractionServiceFixture().apply {
            returnedContact = null
            detailContact = ""
        }, board = board)
        openResponse()
        enterContact("My pending fixture contact")
        submitResponse()
        compose.waitUntil { service.responses.size == 1 }
        detailNode(hasText(text(R.string.recruitment_contact_unavailable))).assertExists()
        compose.runOnIdle {
            service.detailContact = "Fresh recruiter contact"
            service.detailIsResponded = true
        }
        detailNode(hasText(text(R.string.recruitment_refresh_detail))).performClick()
        compose.waitUntil { service.detailReads == 2 }
        detailNode(hasText("Fresh recruiter contact")).assertIsDisplayed()
        compose.onNodeWithText("My pending fixture contact").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.recruitment_contact_unavailable)).assertDoesNotExist()
        compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
        assertEquals(1, service.responses.size)
    }

    @Test fun dutyDraftCanBeResumedAndDiscardedButNotSubmittedWhenAuthorBecomesUnknown() =
        unknownAuthorRetainsAccessibleDraft(RecruitmentBoardKind.Duty)

    @Test fun beginnerDraftCanBeResumedAndDiscardedButNotSubmittedWhenAuthorBecomesUnknown() =
        unknownAuthorRetainsAccessibleDraft(RecruitmentBoardKind.Beginner)

    private fun unknownAuthorRetainsAccessibleDraft(board: RecruitmentBoardKind) {
        val service = show(board = board)
        openResponse()
        enterContact("Retained contact draft")
        compose.onNodeWithText(text(R.string.recruitment_keep_draft)).performClick()
        awaitClosedResponse()
        compose.runOnIdle { service.isCurrentUserAuthor = null }
        detailNode(hasText(text(R.string.recruitment_refresh_detail))).performClick()
        compose.waitUntil(5000) { service.detailReads == 2 }
        compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
        detailNode(hasTestTag("recruitment-resume-response")).performClick()
        compose.onNodeWithTag("recruitment-response-input").assertTextContains("Retained contact draft")
        compose.onNodeWithTag("recruitment-submit-response").assertIsNotEnabled()
            .performTouchInput { click() }
        assertTrue(service.responses.isEmpty())
        compose.onNodeWithTag("recruitment-discard-response").performScrollTo().performClick()
        waitForTagAbsent("recruitment-response-input")
        compose.onNodeWithTag("recruitment-resume-response").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
        assertTrue(service.responses.isEmpty())
    }

    @Test fun pendingResponseSurvivesActivityRecreationAndRouteExitClearsInteractionState() {
        val service = show(RecruitmentInteractionServiceFixture().apply { responseGate = CompletableDeferred() })
        openResponse()
        enterContact("Rotation contact")
        compose.onNodeWithTag("recruitment-submit-response").performTouchInput { doubleClick() }
        compose.waitUntil { service.responses.size == 1 }
        compose.onNodeWithTag("recruitment-submit-response").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        waitForTag("recruitment-response-input")
        compose.onNodeWithTag("recruitment-response-input").assertTextContains("Rotation contact")
        compose.onNodeWithTag("recruitment-submit-response").assertIsNotEnabled()
        compose.onNodeWithTag("recruitment-discard-response").assertIsNotEnabled()
        service.responseGate!!.complete(Unit)
        waitForTagAbsent("recruitment-response-input")
        detailNode(hasText("Returned recruiter contact")).assertIsDisplayed()
        assertEquals(1, service.responses.size)
        assertEquals(1, service.detailReads)
        compose.onNodeWithText(text(R.string.recruitment_back)).performClick()
        waitForText("Fixture route closed")
        compose.runOnIdle { checkNotNull(RecruitmentInteractionFixture.configuration).showRoute = true }
        waitForTag("recruitment-detail-content")
        openResponse()
        compose.onNodeWithTag("recruitment-submit-response").assertIsNotEnabled()
        assertEquals(2, service.detailReads)
        assertEquals(1, service.responses.size)
    }

    @Test fun selfAuthoredAndUnknownAuthorDetailsNeverExposeResponseAction() {
        for (author in listOf(true, null)) {
            val service = show(RecruitmentInteractionServiceFixture(isCurrentUserAuthor = author))
            detailNode(hasText("Synthetic team details")).assertExists()
            compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
            if (author == null) detailNode(hasText(text(R.string.recruitment_response_eligibility_unknown))).assertExists()
            assertTrue(service.responses.isEmpty())
        }
    }

    @Test fun revokedFirstWriteEligibilityClearsTheOpenComposerWithoutSending() {
        val service = show()
        openResponse()
        enterContact("Credential-bound draft")
        compose.runOnIdle { service.canAttemptAuthenticatedWrites = false }
        waitForTagAbsent("recruitment-response-input")
        compose.onNodeWithTag("recruitment-respond").assertDoesNotExist()
        assertTrue(service.responses.isEmpty())
    }

    @Test fun rolePlayReviewPagesPreserveContentAndLikesWhileChangingOrder() {
        val service = show(board = RecruitmentBoardKind.RolePlay)
        detailNode(hasTestTag("recruitment-like-review-review-1")).performClick()
        detailNode(hasTestTag("recruitment-like-review-review-1"))
            .assertTextContains(text(R.string.recruitment_unlike_review, 3))
        detailNode(hasTestTag("recruitment-more-reviews")).performClick()
        compose.waitUntil { service.reviewQueries.any { it.page == 2 } }
        detailNode(hasText("Second review")).assertExists()
        detailNode(hasText("First review")).assertExists()
        assertEquals(listOf("review-1"), service.reviewLikes)
        detailNode(hasTestTag("recruitment-filter-review-order")).performClick()
        compose.onNodeWithText(text(R.string.recruitment_hottest)).performClick()
        compose.waitUntil { service.reviewQueries.lastOrNull()?.order == RolePlayRecruitmentReviewOrder.Hottest }
        assertEquals(1, service.reviewQueries.last().page)
        detailNode(hasText("First review")).assertExists()
        compose.onNodeWithText("Second review").assertDoesNotExist()
        detailNode(hasTestTag("recruitment-like-review-review-1"))
            .assertTextContains(text(R.string.recruitment_unlike_review, 3))
    }

    @Test fun rolePlayReplyPaginationFailureRetainsPageAndCanRetry() {
        val service = show(RecruitmentInteractionServiceFixture().apply { failNextReplyPageTwo = true },
            board = RecruitmentBoardKind.RolePlay)
        detailNode(hasTestTag("recruitment-review-replies-review-1")).performClick()
        waitForTag("recruitment-replies")
        repliesNode(hasText("First reply")).assertExists()
        repliesNode(hasTestTag("recruitment-more-replies")).performClick()
        compose.waitUntil { service.subcommentQueries.count { it.second == 2 } == 1 }
        repliesNode(hasText(text(R.string.recruitment_action_failed))).assertExists()
        repliesNode(hasText("First reply")).assertExists()
        repliesNode(hasText(text(R.string.recruitment_retry))).performClick()
        compose.waitUntil { service.subcommentQueries.count { it.second == 2 } == 2 }
        repliesNode(hasText("Last reply")).assertExists()
        repliesNode(hasText("First reply")).assertExists()
        compose.onNodeWithTag("recruitment-more-replies").assertDoesNotExist()
    }

    @Test fun coveredRepliesReturnToTheSameRootAndScrollWithoutAnotherRead() {
        lateinit var lifecycleOwner: RecruitmentReadingLifecycleOwner
        compose.runOnIdle { lifecycleOwner = RecruitmentReadingLifecycleOwner() }
        val service = show(RecruitmentInteractionServiceFixture().apply { bulkReplyCount = 20 },
            board = RecruitmentBoardKind.RolePlay, lifecycleOwner = lifecycleOwner)
        detailNode(hasTestTag("recruitment-review-replies-review-1")).performClick()
        waitForTag("recruitment-replies")
        repliesNode(hasTestTag("recruitment-more-replies")).performClick()
        compose.waitUntil { service.subcommentQueries.any { it.second == 2 } }
        repliesNode(hasText("Long reply 20")).assertExists()
        compose.onNodeWithTag("recruitment-replies").performScrollToIndex(8)
        val originalScroll = replyScrollPosition()
        assertTrue(originalScroll > 0)
        compose.onNode(hasText("Long reply 9") and hasAnyAncestor(hasTestTag("recruitment-replies")))
            .assertIsDisplayed()
        val detailReads = service.detailReads
        val reviewQueries = service.reviewQueries.toList()
        val replyQueries = service.subcommentQueries.toList()
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.CREATED }
        waitForTagAbsent("recruitment-replies")
        compose.runOnIdle {
            assertEquals(replyQueries, service.subcommentQueries.toList())
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        waitForTag("recruitment-replies")
        assertEquals(originalScroll, replyScrollPosition())
        compose.onNode(hasText("First review") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Long reply 9") and hasAnyAncestor(hasTestTag("recruitment-replies")))
            .assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(detailReads, service.detailReads)
            assertEquals(reviewQueries, service.reviewQueries.toList())
            assertEquals(replyQueries, service.subcommentQueries.toList())
            assertTrue(service.responses.isEmpty())
            assertTrue(service.reviewLikes.isEmpty())
        }
    }

    // Valid for this unchanged list; Compose exposes an estimate, not a raw item index.
    private fun replyScrollPosition() = compose.onNodeWithTag("recruitment-replies").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value().toInt()

    @Test fun rolePlayReviewPaginationFailureRetainsFirstPageAndRetriesPageTwo() {
        val service = show(RecruitmentInteractionServiceFixture().apply { failNextReviewPageTwo = true },
            board = RecruitmentBoardKind.RolePlay)
        detailNode(hasTestTag("recruitment-more-reviews")).performClick()
        compose.waitUntil { service.reviewQueries.count { it.page == 2 } == 1 }
        detailNode(hasText(text(R.string.recruitment_load_failed))).assertExists()
        detailNode(hasText("First review")).assertExists()
        detailNode(hasText(text(R.string.recruitment_retry))).performClick()
        compose.waitUntil { service.reviewQueries.count { it.page == 2 } == 2 }
        detailNode(hasText("Second review")).assertExists()
        detailNode(hasText("First review")).assertExists()
        compose.onNodeWithTag("recruitment-more-reviews").assertDoesNotExist()
    }

    @Test fun dutyAuthorAt599() = authorAtWidth(599, RecruitmentBoardKind.Duty)
    @Test fun beginnerAuthorAt600() = authorAtWidth(600, RecruitmentBoardKind.Beginner)
    @Test fun guildAuthorAt839() = authorAtWidth(839, RecruitmentBoardKind.Guild)
    @Test fun otherAuthorAt840() = authorAtWidth(840, RecruitmentBoardKind.Other)

    private fun authorAtWidth(width: Int, board: RecruitmentBoardKind) {
        val opened = mutableListOf<String>()
        val service = show(width = width, board = board, workspace = true, onOpenAuthor = { opened += it })
        if (board != RecruitmentBoardKind.Duty) selectBoard(when (board) {
            RecruitmentBoardKind.Beginner -> R.string.recruitment_board_beginner
            RecruitmentBoardKind.Guild -> R.string.recruitment_board_guild
            else -> R.string.recruitment_board_other
        })
        waitForTag("recruitment-author-link")
        compose.onNodeWithTag("recruitment-author-link").performClick()
        val expected = if (board == RecruitmentBoardKind.Duty) "fixture-publisher" else "community-author"
        assertEquals(listOf(expected), opened)
        assertEquals(0, service.detailReads)
        compose.onNodeWithText(when (board) {
            RecruitmentBoardKind.Duty -> "Synthetic duty"
            RecruitmentBoardKind.Beginner -> "Synthetic beginner"
            else -> "Synthetic roleplay"
        }).performClick()
        waitForTag("recruitment-detail-content")
        compose.onNode(hasTestTag("recruitment-author-link") and
            hasAnyAncestor(hasTestTag("recruitment-detail-content"))).performScrollTo().performClick()
        assertEquals(listOf(expected, expected), opened)
        assertEquals(1, service.detailReads)
        assertTrue(service.responses.isEmpty())
    }

    @Test fun rolePlayReviewAndReplyAuthorsPreserveTheirOwnIdentities() {
        val opened = mutableListOf<String>()
        val service = show(board = RecruitmentBoardKind.RolePlay, onOpenAuthor = { opened += it })
        detailNode(hasText("Fixture recruiter")).performClick()
        detailNode(hasText("Fixture reviewer")).performClick()
        detailNode(hasText("Fixture reply author")).performClick()
        detailNode(hasTestTag("recruitment-review-replies-review-1")).performClick()
        repliesNode(hasText("Fixture reply author")).performClick()
        assertEquals(listOf("community-author", "review-author", "reply-author", "reply-author"), opened)
        assertTrue(service.responses.isEmpty())
        assertTrue(service.reviewLikes.isEmpty())
    }

    @Test fun absentCommunityIdentityDoesNotInventAnAuthorFromTheRecordId() {
        show(RecruitmentInteractionServiceFixture().apply { includeAuthors = false },
            board = RecruitmentBoardKind.RolePlay, onOpenAuthor = { error("No author identity") })
        detailNode(hasText("Fixture reviewer")).assertExists()
        compose.onAllNodesWithTag("recruitment-author-link").assertCountEquals(0)
    }

    @Test fun relayEntryUsesLoadedDetailsForAllBoardsWithoutRecruitmentWriteEligibility() {
        RecruitmentBoardKind.entries.forEach { board ->
            val calls = mutableListOf<RecruitmentRelayCall>()
            val service = RecruitmentInteractionServiceFixture(isCurrentUserAuthor = true).apply {
                canAttemptAuthenticatedWrites = false
            }
            show(service, board = board, onRelay = { id, sourceBoard, title ->
                calls += RecruitmentRelayCall(id, sourceBoard, title)
            })
            detailNode(hasTestTag("recruitment-relay-dynamic")).performClick()
            val expectedTitle = if (board == RecruitmentBoardKind.Duty) {
                "Synthetic duty"
            } else if (board == RecruitmentBoardKind.Beginner) {
                "Synthetic beginner"
            } else {
                "Synthetic roleplay"
            }
            assertEquals(listOf(RecruitmentRelayCall(42, board, expectedTitle)), calls)
            assertTrue(service.responses.isEmpty())
        }
    }

    @Test fun absentRelayCallbackKeepsLoadedDetailReadOnly() {
        show(board = RecruitmentBoardKind.Other)
        compose.onNodeWithTag("recruitment-relay-dynamic").assertDoesNotExist()
    }

    private fun show(service: RecruitmentInteractionServiceFixture = RecruitmentInteractionServiceFixture(),
        width: Int = 599, board: RecruitmentBoardKind = RecruitmentBoardKind.Duty,
        workspace: Boolean = false, onOpenAuthor: ((String) -> Unit)? = null,
        lifecycleOwner: LifecycleOwner? = null,
        onRelay: ((Int, RecruitmentBoardKind, String) -> Unit)? = null,
    ): RecruitmentInteractionServiceFixture {
        val configuration = RecruitmentInteractionConfiguration(
            service, width, board, workspace, onOpenAuthor, lifecycleOwner, onRelay,
        )
        compose.runOnIdle { RecruitmentInteractionFixture.configuration = configuration }
        if (workspace) waitForText("Synthetic duty") else {
            compose.waitUntil { service.detailReads > 0 }
            waitForTag("recruitment-detail-content")
        }
        compose.runOnIdle { assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.1f) }
        return service
    }

    private fun selectBoard(label: Int) {
        compose.onNodeWithText(text(label)).performScrollTo().performClick()
    }

    private fun choose(tag: String, option: String) {
        compose.onNodeWithTag("recruitment-filter-$tag").performScrollTo().performClick()
        compose.onNode(hasText(option) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    private fun openResponse() {
        awaitClosedResponse()
        detailNode(hasTestTag("recruitment-respond")).performClick()
        waitForTag("recruitment-response-input")
    }
    private fun awaitClosedResponse() {
        waitForTagAbsent("recruitment-response-input")
        // Semantics disappear before the platform dialog finishes releasing its touch window.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
    }
    private fun enterContact(value: String) = compose.onNodeWithTag("recruitment-response-input")
        .performScrollTo().performTextInput(value)
    private fun submitResponse() = compose.onNodeWithTag("recruitment-submit-response").performClick()
    private fun detailNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("recruitment-detail-content").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun repliesNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("recruitment-replies").performScrollToNode(matcher)
        return compose.onNode(matcher and hasAnyAncestor(hasTestTag("recruitment-replies"))).performScrollTo()
    }
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForTagAbsent(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
    private fun waitForText(value: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Configuration is shared, while the production Activity/route owns all interaction state. */
class RecruitmentInteractionTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecruitmentInteractionFixture.configuration?.let { configuration ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = Density(constraints.maxWidth.toFloat() / configuration.width, 1f)
                    CompositionLocalProvider(LocalDensity provides density,
                        LocalLifecycleOwner provides (configuration.lifecycleOwner ?: LocalLifecycleOwner.current)) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().onSizeChanged {
                                configuration.measuredWidthDp = with(density) { it.width.toDp().value }
                            }) {
                                RisingStonesRecruitmentAuthorNavigation(configuration.onOpenAuthor) {
                                    RisingStonesRecruitmentRelayNavigation(configuration.onRelay) {
                                        when {
                                            !configuration.showRoute -> Text("Fixture route closed")
                                            configuration.workspace -> RisingStonesRecruitmentScreen(configuration.service,
                                                { configuration.showRoute = false })
                                            else -> RisingStonesRecruitmentDetailScreen(configuration.service, 42,
                                                configuration.board, { configuration.showRoute = false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private object RecruitmentInteractionFixture {
    var configuration by mutableStateOf<RecruitmentInteractionConfiguration?>(null)
}

private class RecruitmentInteractionConfiguration(val service: RecruitmentInteractionServiceFixture,
    val width: Int, val board: RecruitmentBoardKind, val workspace: Boolean, val onOpenAuthor: ((String) -> Unit)? = null,
    val lifecycleOwner: LifecycleOwner? = null,
    val onRelay: ((Int, RecruitmentBoardKind, String) -> Unit)? = null,
) {
    var measuredWidthDp = 0f
    var showRoute by mutableStateOf(true)
}

private data class ResponseCall(val board: RecruitmentBoardKind, val id: Int, val contact: String)
private data class RecruitmentRelayCall(val id: Int, val board: RecruitmentBoardKind, val title: String)

private class RecruitmentInteractionServiceFixture(var isCurrentUserAuthor: Boolean? = false) :
    RecruitmentBrowsingService, RecruitmentResponseEligibilityService, RecruitmentActionEligibilityService,
    RecruitmentAuthorService {
    override val hasCommunityIdentity = true
    override val canPerformAuthenticatedWrites = false
    override var canAttemptAuthenticatedWrites by mutableStateOf(true)
    val dutyQueries = CopyOnWriteArrayList<DutyRecruitmentBrowseQuery>()
    val communityQueries = CopyOnWriteArrayList<CommunityRecruitmentQuery>()
    val responses = CopyOnWriteArrayList<ResponseCall>()
    val reviewQueries = CopyOnWriteArrayList<RolePlayRecruitmentReviewQuery>()
    val subcommentQueries = CopyOnWriteArrayList<Pair<String, Int>>()
    val reviewLikes = CopyOnWriteArrayList<String>()
    var includeAuthors = true
    var bulkReplyCount = 0
    @Volatile var detailReads = 0
    var returnedContact: String? = "Returned recruiter contact"
    var detailContact = "Old detail contact"
    var detailIsResponded = false
    var failNextResponse = false
    var failNextReplyPageTwo = false
    var failNextReviewPageTwo = false
    var responseGate: CompletableDeferred<Unit>? = null
    private val areas = listOf(
        CommunityRecruitmentArea("area-1", "Fixture region", listOf(
            CommunityRecruitmentServer("world-1", "Fixture world"),
            CommunityRecruitmentServer("world-2", "Second fixture world"))),
        CommunityRecruitmentArea("area-2", "Second fixture region", listOf(
            CommunityRecruitmentServer("world-3", "Third fixture world"))),
    )
    private val catalogs = DutyRecruitmentCatalogs(duties = listOf(
        DutyRecruitmentDutyConfig("duty-1", "零式", "Fixture trial", "满编小队", 1)))
    private val duty = DutyRecruitmentSummary(42, "fixture-publisher", null, "Fixture recruiter", "", "", "",
        "", "Synthetic duty", "满编小队", "", "", "List fixture excerpt", null, null, 0, 1,
        emptyList(), emptyList(), emptyList(), DutyRecruitmentRoleCounts(), null)

    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery) =
        fetchDutyRecruitments(DutyRecruitmentBrowseQuery(query))
    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentBrowseQuery): DutyRecruitmentListPage {
        dutyQueries += query
        return DutyRecruitmentListPage(listOf(duty), 1, query.list.page)
    }
    override suspend fun fetchCatalogs() = catalogs
    override suspend fun fetchDutyFilterCatalog() = DutyRecruitmentFilterCatalog(catalogs,
        listOf(DutyRecruitmentLabel("label-1", "Fixture label", 1)), areas)
    override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail {
        detailReads++
        return DutyRecruitmentDetail(duty, null, "Synthetic team details", "", "Synthetic strategy", detailContact,
            7, null, null, detailIsResponded, true, null, "fixture-publisher")
    }
    override suspend fun fetchDutyInteractionDetail(id: Int) =
        DutyRecruitmentInteractionDetail(fetchDutyRecruitmentDetail(id), isCurrentUserAuthor)
    override suspend fun fetchCommunityRecruitments(query: CommunityRecruitmentQuery): CommunityRecruitmentPage {
        communityQueries += query
        return CommunityRecruitmentPage(listOf(summary(query.kind)), 1, query.page)
    }
    override suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentDetail {
        detailReads++
        return CommunityRecruitmentDetail(summary(kind).copy(summary = null), emptyList(), listOf(
            CommunityRecruitmentContent(CommunityRecruitmentContentKind.Description, "Synthetic community detail", "")))
    }
    override suspend fun fetchCommunityInteractionDetail(id: Int, kind: CommunityRecruitmentKind) =
        CommunityRecruitmentInteractionDetail(fetchCommunityRecruitmentDetail(id, kind), isCurrentUserAuthor)
    override suspend fun fetchCommunityFilterCatalog(kind: CommunityRecruitmentKind) =
        CommunityRecruitmentFilterCatalog(areas = areas,
            styles = listOf(CommunityRecruitmentFilterOption("style-1", "Fixture style")))
    override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String) =
        respond(ResponseCall(RecruitmentBoardKind.Duty, id, contactInfo))
    override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String) =
        respond(ResponseCall(RecruitmentBoardKind.Beginner, id, contactInfo))
    private suspend fun respond(call: ResponseCall): String? {
        responses += call
        responseGate?.await()
        if (failNextResponse) {
            failNextResponse = false
            throw DutyRecruitmentException.Business(12345, "Synthetic response failure")
        }
        return returnedContact
    }
    override suspend fun fetchRolePlayMembers(id: Int) = emptyList<RolePlayRecruitmentMember>()
    override suspend fun fetchRolePlayRating(id: Int) = RolePlayRecruitmentRating(listOf(0, 0, 0, 1, 0))
    override suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int) =
        fetchRolePlayReviews(RolePlayRecruitmentReviewQuery(id, page, limit))
    override suspend fun fetchRolePlayReviews(query: RolePlayRecruitmentReviewQuery): RolePlayRecruitmentReviewPage {
        reviewQueries += query
        if (query.page == 2 && failNextReviewPageTwo) {
            failNextReviewPageTwo = false
            throw DutyRecruitmentException.Business(12345, "Synthetic review page failure")
        }
        val first = RolePlayRecruitmentReview("review-1", "Fixture reviewer", null, null, "First review",
            "4", 2, false, null, emptyList(), bulkReplyCount.takeIf { it > 0 } ?: 3)
        return RolePlayRecruitmentReviewPage(if (query.page == 1) listOf(first)
            else listOf(first.copy(id = "review-2", content = "Second review", childCount = 0)), query.page, query.page == 1)
    }
    override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage {
        subcommentQueries += rootParentId to page
        if (page == 2 && failNextReplyPageTwo) {
            failNextReplyPageTwo = false
            throw DutyRecruitmentException.Business(12345, "Synthetic page failure")
        }
        fun reply(id: String, content: String) = RolePlayRecruitmentSubcomment(id, "Fixture reply author",
            null, null, content, emptyList())
        if (bulkReplyCount > 0) return RolePlayRecruitmentSubcommentPage((1..bulkReplyCount).map {
            reply("long-reply-$it", "Long reply $it")
        }.drop((page - 1) * limit).take(limit), page, page * limit < bulkReplyCount)
        return RolePlayRecruitmentSubcommentPage(if (page == 1) listOf(reply("reply-1", "First reply"))
            else listOf(reply("reply-2", "Second reply"), reply("reply-3", "Last reply")), page, page == 1)
    }
    override suspend fun likeRolePlayReview(id: String): Int { reviewLikes += id; return 1 }

    override suspend fun fetchCommunityRecruitmentsWithAuthors(query: CommunityRecruitmentQuery) =
        CommunityRecruitmentAuthorPage(fetchCommunityRecruitments(query),
            if (includeAuthors) mapOf(42 to "community-author") else emptyMap())
    override suspend fun fetchCommunityDetailWithAuthor(id: Int, kind: CommunityRecruitmentKind) =
        CommunityRecruitmentAuthorDetail(fetchCommunityInteractionDetail(id, kind),
            "community-author".takeIf { includeAuthors })
    override suspend fun fetchRolePlayReviewsWithAuthors(query: RolePlayRecruitmentReviewQuery): RolePlayReviewAuthorPage {
        val page = fetchRolePlayReviews(query)
        return RolePlayReviewAuthorPage(page, if (includeAuthors) page.items.associate { it.id to "review-author" } else emptyMap())
    }
    override suspend fun fetchRolePlaySubcommentsWithAuthors(rootParentId: String, page: Int, limit: Int): RolePlaySubcommentAuthorPage {
        val result = fetchRolePlaySubcomments(rootParentId, page, limit)
        return RolePlaySubcommentAuthorPage(result, if (includeAuthors) result.items.associate { it.id to "reply-author" } else emptyMap())
    }

    private fun summary(kind: CommunityRecruitmentKind) = CommunityRecruitmentSummary(42, kind,
        if (kind == CommunityRecruitmentKind.Beginner) "Synthetic beginner" else "Synthetic roleplay",
        "Fixture recruiter", null, null, null, "List fixture excerpt", null,
        beginner = if (kind == CommunityRecruitmentKind.Beginner) BeginnerRecruitmentCard("Fixture recruiter", null,
            BeginnerRecruitmentIdentity.Mentor, "Synthetic beginner", null, emptyList(), null, null, null,
            detailIsResponded, detailContact) else null)
}

private class RecruitmentReadingLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}
