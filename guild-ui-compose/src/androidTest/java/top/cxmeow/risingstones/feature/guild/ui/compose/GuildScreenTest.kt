package top.cxmeow.risingstones.feature.guild.ui.compose

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.guild.domain.GuildActivitySummary
import top.cxmeow.risingstones.feature.guild.domain.GuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildActionService
import top.cxmeow.risingstones.feature.guild.domain.GuildCommentActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildGuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildHousing
import top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildInfo
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.domain.GuildLabel
import top.cxmeow.risingstones.feature.guild.domain.GuildMember
import top.cxmeow.risingstones.feature.guild.domain.GuildMemberRegistration
import top.cxmeow.risingstones.feature.guild.domain.GuildMembers
import top.cxmeow.risingstones.feature.guild.domain.GuildPage
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentDraft
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoDetail
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoSummary
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild

class GuildScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoNavigationAtCompact599() = photoNavigation(599, listRemains = false)
    @Test fun photoNavigationAtMedium600() = photoNavigation(600, listRemains = true)
    @Test fun photoNavigationAtUpperMedium839() = photoNavigation(839, listRemains = true)
    @Test fun photoNavigationAtExpanded840() = photoNavigation(840, listRemains = true)

    private fun photoNavigation(width: Int, listRemains: Boolean) {
        val service = GuildUiFakeService()
        val actions = GuildUiFakeActions()
        val authors = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width.dp).height(1_000.dp)) {
                    RisingStonesGuildActionProvider(actions) {
                        RisingStonesGuildAuthorNavigation(onOpenAuthor = authors::add) {
                            RisingStonesGuildScreen(service, onNavigateBack = {})
                        }
                    }
                }
            }
        }
        waitForText("Fixture Guild")
        compose.onNodeWithTag("guild-section-photos").performClick()
        waitForTag("guild-photo-7")
        compose.onNodeWithTag("guild-photo-7").performClick()
        waitForTag("guild-photo-content")
        compose.waitUntil(5_000) {
            compose.onNode(hasContentDescription("Photographer") and
                hasAnyAncestor(hasTestTag("guild-photo-content"))).isDisplayed()
        }
        if (listRemains) compose.onNodeWithTag("guild-photo-list").assertExists()
        else compose.onNodeWithTag("guild-photo-list").assertDoesNotExist()
        compose.onNodeWithTag("guild-author-photo-author").performClick()
        compose.runOnIdle { assertEquals(listOf("photo-author"), authors) }
        compose.onNodeWithText("Photo comment", substring = true).assertExists()
        compose.onNodeWithText("<b>", substring = true).assertDoesNotExist()
        waitForTag("guild-like-photo")
        compose.onNodeWithTag("guild-write-comment").assertExists()
        compose.runOnIdle { assertEquals(0, actions.mutations) }
        captureGuildScreenshotIfRequested(width)
    }

    @Test fun standalonePhotoUsesNoGuildLookupAndReturnsToCaller() {
        val service = GuildUiFakeService()
        var returned = false
        compose.setContent {
            MaterialTheme {
                RisingStonesGuildAuthorNavigation(onOpenAuthor = {}) {
                    RisingStonesGuildPhotoScreen(service, 7, { returned = true })
                }
            }
        }
        waitForTag("guild-photo-content")
        compose.onNodeWithTag("guild-author-photo-author").assertExists()
        compose.runOnIdle { assertEquals(0, service.ownReads) }
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertTrue(returned) }
    }

    @Test fun compactPhotoListKeepsItsScrollAfterReturningFromDetail() {
        val service = GuildUiFakeService()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(599.dp).height(1_000.dp)) {
                    RisingStonesGuildScreen(service, onNavigateBack = {})
                }
            }
        }
        waitForText("Fixture Guild")
        compose.onNodeWithTag("guild-section-photos").performClick()
        waitForTag("guild-photo-list")
        compose.onNodeWithTag("guild-photo-list").performScrollToNode(hasTestTag("guild-photo-26"))
        compose.onNodeWithTag("guild-photo-26").performScrollTo().performClick()
        waitForTag("guild-photo-content")
        compose.onNodeWithTag("guild-photo-back").performClick()
        compose.onNodeWithTag("guild-photo-26").assertIsDisplayed()
        compose.onNodeWithTag("guild-photo-7").assertDoesNotExist()
    }

    @Test fun activityOnlyNavigatesWhenTheHostAcceptsItsId() {
        val service = GuildUiFakeService()
        val opened = mutableListOf<Int>()
        var allowed by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                RisingStonesGuildScreen(
                    service = service,
                    onNavigateBack = {},
                    onOpenActivity = opened::add,
                    canOpenActivity = { allowed && it == 11 },
                )
            }
        }
        waitForText("Fixture Guild")
        compose.onNodeWithTag("guild-section-activities").performClick()
        waitForTag("guild-activity-11")
        compose.onNodeWithTag("guild-activity-11").assertHasNoClickAction()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.runOnIdle { allowed = true }
        compose.onNodeWithTag("guild-activity-11").performClick()
        compose.runOnIdle { assertEquals(listOf(11), opened) }
    }

    @Test fun capabilityRevocationRemovesConfirmedGuildContent() {
        val service = GuildUiFakeService()
        compose.setContent { MaterialTheme { RisingStonesGuildScreen(service, {}) } }
        waitForText("Fixture Guild")
        compose.runOnIdle { service.canReadState.value = false }
        waitForText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.guild_auth_required))
        compose.onNodeWithText("Fixture Guild").assertDoesNotExist()
    }

    @Test fun onlyRegisteredMembersWithAUuidOpenAnAuthor() {
        val service = GuildUiFakeService()
        val authors = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                RisingStonesGuildAuthorNavigation(onOpenAuthor = authors::add) {
                    RisingStonesGuildScreen(service, onNavigateBack = {})
                }
            }
        }
        waitForText("Fixture Guild")
        compose.onNodeWithTag("guild-section-members").performClick()
        waitForText("Registered member")
        compose.onNodeWithTag("guild-author-member-author").performClick()
        compose.onNodeWithText("Unregistered member").assertHasNoClickAction()
        compose.runOnIdle { assertEquals(listOf("member-author"), authors) }
    }

    @Test fun firstUseActionsAppearButWriteOnlyAfterExplicitControls() {
        val reading = GuildUiFakeService()
        val actions = GuildUiFakeActions()
        compose.setContent {
            MaterialTheme {
                RisingStonesGuildActionProvider(actions) {
                    RisingStonesGuildScreen(reading, onNavigateBack = {})
                }
            }
        }
        waitForText("Fixture Guild")
        waitForTag("guild-manage-profile")
        compose.runOnIdle { assertEquals(0, actions.mutations) }

        compose.onNodeWithTag("guild-manage-profile").performClick()
        compose.onNodeWithTag("guild-description-draft").performScrollTo().performTextClearance()
        compose.onNodeWithTag("guild-description-draft").performTextInput("Updated profile")
        compose.onNodeWithText("Save description").performScrollTo().performClick()
        compose.waitUntil(5_000) { actions.profileUpdates == 1 }

        compose.onNodeWithTag("guild-section-photos").performClick()
        waitForTag("guild-photo-7")
        compose.onNodeWithTag("guild-photo-7").performClick()
        waitForTag("guild-like-photo")
        compose.onNodeWithTag("guild-like-photo").performClick()
        compose.waitUntil(5_000) { actions.likeCalls == 1 }
        compose.onNodeWithText("Unlike").assertExists()

        compose.onNodeWithTag("guild-write-comment").performClick()
        compose.onNodeWithTag("guild-comment-draft").performTextInput("Explicit comment")
        compose.onNodeWithText("Close and keep draft").performClick()
        compose.onNodeWithTag("guild-write-comment").performClick()
        compose.onNodeWithTag("guild-comment-draft").assertTextContains("Explicit comment")
        compose.onNodeWithTag("guild-submit-comment").performClick()
        compose.waitUntil(5_000) { actions.commentCalls == 1 }
        compose.runOnIdle {
            assertEquals("Explicit comment", actions.lastComment?.contentHtml)
            assertEquals(3, actions.mutations)
        }
    }

    @Test fun replyAndDeletionRequireExplicitSubmitOrConfirmation() {
        val reading = GuildUiFakeService()
        val actions = GuildUiFakeActions()
        var returned = false
        compose.setContent {
            MaterialTheme {
                RisingStonesGuildActionProvider(actions) {
                    RisingStonesGuildPhotoScreen(reading, 7, { returned = true })
                }
            }
        }
        waitForTag("guild-photo-content")
        compose.onNodeWithTag("guild-photo-content").performScrollToNode(hasTestTag("guild-reply-comment-31"))
        compose.onNodeWithTag("guild-reply-comment-31").performScrollTo().performClick()
        compose.onNodeWithTag("guild-comment-draft").performTextInput("Explicit reply")
        compose.onNodeWithTag("guild-submit-comment").performClick()
        compose.waitUntil(5_000) { actions.commentCalls == 1 }
        compose.runOnIdle {
            assertEquals(31, actions.lastComment?.parentId)
            assertEquals(31, actions.lastComment?.rootParentId)
        }
        compose.onNodeWithTag("guild-photo-content").performScrollToNode(hasTestTag("guild-delete-comment-31"))
        compose.onNodeWithTag("guild-delete-comment-31").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, actions.deleteCommentCalls) }
        compose.onNodeWithTag("guild-delete-comment-31").performClick()
        compose.onNodeWithTag("guild-confirm-delete-comment").performClick()
        compose.waitUntil(5_000) { actions.deleteCommentCalls == 1 }
        compose.onNodeWithTag("guild-photo-content").performScrollToNode(hasTestTag("guild-delete-photo"))
        compose.onNodeWithTag("guild-delete-photo").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, actions.deletePhotoCalls); assertTrue(!returned) }
        compose.onNodeWithTag("guild-delete-photo").performClick()
        compose.onNodeWithTag("guild-confirm-delete-photo").performClick()
        compose.waitUntil(5_000) { returned }
        compose.runOnIdle { assertEquals(1, actions.deletePhotoCalls) }
    }

    @Test fun albumSelectionStaysLocalUntilExplicitUploadAndCanBeDiscarded() {
        val actions = GuildUiFakeActions()
        lateinit var model: top.cxmeow.risingstones.feature.guild.presentation.GuildActionViewModel
        lateinit var importer: GuildImageImportViewModel
        var reads = 0
        val source = top.cxmeow.risingstones.feature.guild.presentation.GuildAlbumImageSource {
            reads++
            top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput(byteArrayOf(1), "image/png")
        }
        compose.runOnIdle {
            model = top.cxmeow.risingstones.feature.guild.presentation.GuildActionViewModel(actions, actions)
            importer = GuildImageImportViewModel()
            model.bindResources(GuildId("123"), null, emptyList())
        }
        compose.setContent {
            val state by model.state.collectAsState()
            MaterialTheme { GuildActionOverlays(model, state, importer, null, null, GuildImageLaunchers({}, {}, {})) }
        }
        compose.waitUntil(5_000) { model.state.value.canUploadAlbum }
        compose.runOnIdle { model.setAlbumImageSources(listOf(source, source)) }
        waitForTag("guild-confirm-album-upload")
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, actions.mutations) }
        compose.onNodeWithText("Discard draft").performClick()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, actions.uploadCalls) }
        compose.runOnIdle { model.setAlbumImageSources(listOf(source, source)) }
        compose.onNodeWithTag("guild-confirm-album-upload").performClick()
        compose.waitUntil(5_000) { actions.albumCalls == 1 }
        compose.runOnIdle {
            assertEquals(2, reads)
            assertEquals(2, actions.uploadCalls)
            assertEquals(0, model.state.value.selectedAlbumImageCount)
            model.clearProtectedContent()
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun captureGuildScreenshotIfRequested(width: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("guildScreenshot") != "true") return
        val output = File(instrumentation.targetContext.cacheDir, "guild-$width.png")
        FileOutputStream(output).use { stream ->
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

class GuildConfigurationChangeTest {
    @get:Rule val compose = createAndroidComposeRule<GuildConfigurationTestActivity>()

    @Test fun recreationRetainsSectionPhotoSelectionAndReplyState() {
        lateinit var service: GuildUiFakeService
        compose.runOnIdle { service = GuildConfigurationFixture.reset(width = 599) }
        waitForText("Fixture Guild")
        compose.onNodeWithTag("guild-section-photos").performClick()
        waitForTag("guild-photo-7")
        compose.onNodeWithTag("guild-photo-7").performClick()
        waitForTag("guild-photo-content")
        compose.waitUntil(5_000) {
            compose.onNode(hasContentDescription("Photographer") and
                hasAnyAncestor(hasTestTag("guild-photo-content"))).isDisplayed()
        }
        compose.onNodeWithTag("guild-photo-content").performScrollToNode(hasTestTag("guild-comment-replies-31"))
        compose.onNodeWithTag("guild-comment-replies-31").performScrollTo().performClick()
        waitForTag("guild-replies-content")
        compose.activityRule.scenario.recreate()
        waitForTag("guild-replies-content")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("guild-photo-content").assertExists()
        compose.onNodeWithTag("guild-photo-list").assertDoesNotExist()
        assertEquals(1, service.ownReads)
    }

    @Test fun replySheetIsAbsentWhileTheGuildLayerIsBelowStarted() {
        compose.runOnIdle { GuildConfigurationFixture.reset(width = 840) }
        waitForText("Fixture Guild")
        compose.onNodeWithTag("guild-section-photos").performClick()
        waitForTag("guild-photo-7")
        compose.onNodeWithTag("guild-photo-7").performClick()
        waitForTag("guild-photo-content")
        compose.waitUntil(5_000) {
            compose.onNode(hasContentDescription("Photographer") and
                hasAnyAncestor(hasTestTag("guild-photo-content"))).isDisplayed()
        }
        compose.onNodeWithTag("guild-photo-content").performScrollToNode(hasTestTag("guild-comment-replies-31"))
        compose.onNodeWithTag("guild-comment-replies-31").performScrollTo().performClick()
        waitForTag("guild-replies-content")
        compose.onNodeWithTag("guild-replies-content").performTouchInput { swipeUp() }
        compose.onNodeWithTag("guild-replies-content").performScrollToNode(hasTestTag("guild-comment-51"))
        compose.onNodeWithTag("guild-comment-51").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
        }
        compose.onNodeWithTag("guild-replies-content").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("guild-replies-content")
        compose.waitUntil(5_000) { compose.onNodeWithTag("guild-comment-51").isDisplayed() }
        compose.onNodeWithTag("guild-comment-51").assertIsDisplayed()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

class GuildConfigurationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val service = GuildConfigurationFixture.service.value
            val width = GuildConfigurationFixture.width
            MaterialTheme {
                Box(Modifier.width(width.dp).height(1_000.dp)) {
                    RisingStonesGuildAuthorNavigation(onOpenAuthor = {}) {
                        RisingStonesGuildScreen(service, onNavigateBack = {})
                    }
                }
            }
        }
    }
}

private object GuildConfigurationFixture {
    val service = mutableStateOf(GuildUiFakeService())
    var width by mutableIntStateOf(599)

    fun reset(width: Int): GuildUiFakeService = GuildUiFakeService().also {
        this.width = width
        service.value = it
    }
}

private val GuildPhotoFixture: String get() {
    val context = InstrumentationRegistry.getInstrumentation().context
    val id = context.resources.getIdentifier("guild_photo_fixture", "drawable", context.packageName)
    check(id != 0) { "Synthetic photo resource is missing" }
    return "android.resource://${context.packageName}/$id"
}

private class GuildUiFakeService : GuildService {
    val canReadState = mutableStateOf(true)
    override val canRead: Boolean get() = canReadState.value
    var ownReads = 0

    override suspend fun ownGuild(): OwnGuild {
        ownReads++
        return OwnGuild.Joined(GuildId("123"))
    }

    override suspend fun info(guildId: GuildId) = GuildInfo(
        guildId, "Fixture Guild", "FIX", "Area", "World", null,
        "<p>Guild <b>profile</b></p>", "2026-09-20", 30, 2, 1, "Maelstrom",
        "Evening", "All day", listOf("Casual"),
        GuildHousing(GuildHousingVisibility.Visible, "Ward 1", "10 days"),
    )

    override suspend fun members(guildId: GuildId) = GuildMembers(
        registered = listOf(GuildMember(
            GuildMemberRegistration.Registered, "member-author", "Registered member", "Area", "World",
            null, "<b>Member profile</b>", null, null,
        )),
        unregistered = listOf(GuildMember(
            GuildMemberRegistration.Unregistered, null, "Unregistered member", "Area", "World",
            null, "Unregistered profile", null, null,
        )),
    )

    override suspend fun activities(guildId: GuildId, page: Int) = GuildPage(
        listOf(GuildActivitySummary(
            11, "<p>Activity summary</p>", emptyList(), null, "activity-author", "Activity author",
            "Area", "World", null,
        )), page, false,
    )

    override suspend fun photos(guildId: GuildId, page: Int) = GuildPage(
        (7..26).map { id -> GuildPhotoSummary(id, GuildPhotoFixture, "Photographer $id", null, 1, 2, false) },
        page,
        false,
    )

    override suspend fun photo(id: Int) = GuildPhotoDetail(
        id, GuildId("123"), GuildPhotoFixture, "photo-author", "Photographer",
        "Area", "World", null, 0, 1, 2, false,
    )

    override suspend fun comments(photoId: Int, page: Int, pageTime: String?) = GuildPage(
        listOf(GuildPhotoComment(
            31, photoId, 0, 31, "comment-author", "Comment author", "Area", "World", null,
            "<p>Photo <b>comment</b></p>", null, null, null, null, 1,
        )), page, false,
    )

    override suspend fun replies(rootParentId: Int, page: Int) = GuildPage(
        (32..51).map { id -> GuildPhotoComment(
            id, 7, rootParentId, rootParentId, "reply-author-$id", "Reply author $id", "Area", "World", null,
            "<p>Reply body $id</p>", null, null, null, null, 0,
        ) }, page, false,
    )
}

private class GuildUiFakeActions : GuildActionService, top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService {
    override val canUploadImages = true
    override val canAttemptImageUpload = false
    var uploadCalls = 0
    override val canPerformAuthenticatedWrites = false
    override val canAttemptAuthenticatedWrites = true
    var profileUpdates = 0
    var likeCalls = 0
    var commentCalls = 0
    var deleteCommentCalls = 0
    var deletePhotoCalls = 0
    var albumCalls = 0
    var lastComment: GuildPhotoCommentDraft? = null
    val mutations: Int
        get() = profileUpdates + likeCalls + commentCalls + deleteCommentCalls + deletePhotoCalls + albumCalls

    override suspend fun beginActionScope(): GuildActionScope = object : GuildActionScope {
        override suspend fun isCurrent() = true
        override fun close() = Unit
    }

    override suspend fun labels(scope: GuildActionScope): List<GuildLabel> = emptyList()

    override suspend fun guildEligibility(scope: GuildActionScope, guildId: GuildId) =
        GuildGuildActionEligibility(guildId, GuildActionEligibility.Eligible, GuildActionEligibility.Eligible)

    override suspend fun photoEligibility(scope: GuildActionScope, photoId: Int) =
        GuildPhotoActionEligibility(photoId, GuildId("123"), GuildActionEligibility.Eligible)

    override suspend fun commentEligibility(scope: GuildActionScope, commentId: Int) =
        GuildCommentActionEligibility(commentId, GuildActionEligibility.Eligible)

    override suspend fun updateGuildInfo(scope: GuildActionScope, guildId: GuildId, update: GuildInfoUpdate) {
        profileUpdates++
    }

    override suspend fun togglePhotoLike(scope: GuildActionScope, photoId: Int): GuildPhotoLikeResult {
        likeCalls++
        return GuildPhotoLikeResult.Liked
    }

    override suspend fun commentPhoto(scope: GuildActionScope, draft: GuildPhotoCommentDraft) {
        commentCalls++
        lastComment = draft
    }

    override suspend fun deleteOwnComment(scope: GuildActionScope, commentId: Int) { deleteCommentCalls++ }

    override suspend fun registerAlbumPhotos(
        scope: GuildActionScope,
        guildId: GuildId,
        images: List<top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage>,
    ) { albumCalls++ }

    override suspend fun deletePhoto(scope: GuildActionScope, photoId: Int) { deletePhotoCalls++ }

    override suspend fun uploadImage(
        scope: GuildActionScope,
        purpose: top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose,
        input: top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput,
    ): top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage {
        uploadCalls++
        return object : top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage {
            override val url = "memory://fixture/$uploadCalls"
            override val purpose = purpose
        }
    }

}
