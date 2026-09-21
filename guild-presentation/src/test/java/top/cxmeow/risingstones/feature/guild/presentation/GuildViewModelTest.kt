package top.cxmeow.risingstones.feature.guild.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.guild.domain.GuildActivitySummary
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildHousing
import top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildInfo
import top.cxmeow.risingstones.feature.guild.domain.GuildMember
import top.cxmeow.risingstones.feature.guild.domain.GuildMemberRegistration
import top.cxmeow.risingstones.feature.guild.domain.GuildMembers
import top.cxmeow.risingstones.feature.guild.domain.GuildPage
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoDetail
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoSummary
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild

@OptIn(ExperimentalCoroutinesApi::class)
class GuildViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun confirmedNoGuildHasItsOwnNonErrorState() = runTest {
        val service = FakeGuildService().apply { own = OwnGuild.None }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        assertTrue(model.state.value.hasNoGuild)
        assertNull(model.state.value.info)
        assertNull(model.state.value.error)
        assertEquals(0, service.infoReads)
    }

    @Test fun failedNoGuildRefreshKeepsTheConfirmedEmptyStateAndShowsAnError() = runTest {
        val service = FakeGuildService().apply { own = OwnGuild.None }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        service.ownFailure = GuildException.Network
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.hasNoGuild)
        assertEquals(GuildUiError.Failed, model.state.value.error)
        assertFalse(model.state.value.isRefreshing)
    }

    @Test fun joinedGuildLoadsProfileBeforeExposingContent() = runTest {
        val service = FakeGuildService()
        val model = GuildViewModel(service)
        advanceUntilIdle()
        assertEquals("Fixture Guild", model.state.value.info?.name)
        assertEquals(GuildSection.Profile, model.state.value.section)
        assertFalse(model.state.value.isLoading)
        assertEquals(1, service.infoReads)
    }

    @Test fun failedRefreshRetainsConfirmedGuildAndSectionContent() = runTest {
        val service = FakeGuildService()
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Members)
        advanceUntilIdle()
        service.failInfo = true
        model.refresh()
        advanceUntilIdle()
        assertEquals("Fixture Guild", model.state.value.info?.name)
        assertEquals(1, model.state.value.members?.registered?.size)
        assertEquals(GuildUiError.Failed, model.state.value.error)
        assertFalse(model.state.value.isRefreshing)
    }

    @Test fun activityAndPhotoEmptyPagesEndPaginationWithoutDroppingOldRows() = runTest {
        val service = FakeGuildService().apply {
            activityPages[1] = GuildPage(listOf(activity(1)), 1, true)
            activityPages[2] = GuildPage(emptyList(), 2, true)
            photoPages[1] = GuildPage(listOf(photoSummary(7)), 1, true)
            photoPages[2] = GuildPage(emptyList(), 2, true)
        }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Activities)
        advanceUntilIdle()
        model.loadMoreActivities()
        advanceUntilIdle()
        assertEquals(listOf(1), model.state.value.activities.map { it.id })
        assertFalse(model.state.value.hasMoreActivities)
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.loadMorePhotos()
        advanceUntilIdle()
        assertEquals(listOf(7), model.state.value.photos.map { it.id })
        assertFalse(model.state.value.hasMorePhotos)
    }

    @Test fun photoCommentsKeepServerCursorAndRepliesUseIndependentPages() = runTest {
        val service = FakeGuildService().apply {
            photoPages[1] = GuildPage(listOf(photoSummary(7)), 1, false)
            commentPages[1] = GuildPage(listOf(comment(1, childCount = 2)), 1, true, nextPageTime = "cursor-1")
            commentPages[2] = GuildPage(emptyList(), 2, true, nextPageTime = "cursor-2")
            replyPages[1] = GuildPage(listOf(comment(2)), 1, true)
            replyPages[2] = GuildPage(emptyList(), 2, true)
        }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        advanceUntilIdle()
        model.loadMoreComments()
        advanceUntilIdle()
        assertEquals(listOf(Triple(7, 1, null), Triple(7, 2, "cursor-1")), service.commentQueries)
        assertFalse(model.state.value.hasMoreComments)
        model.openReplies(model.state.value.comments.single())
        advanceUntilIdle()
        model.loadMoreReplies()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), service.replyQueries.map { it.second })
        assertFalse(model.state.value.hasMoreReplies)
    }

    @Test fun switchingSectionsCancelsLateResultAndClearPreventsCredentialBackfill() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeGuildService().apply { memberGate = gate }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Members)
        runCurrent()
        model.selectSection(GuildSection.Activities)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.members)
        assertFalse(model.state.value.isLoadingMembers)
        model.selectSection(GuildSection.Members)
        advanceUntilIdle()
        assertEquals(1, model.state.value.members?.registered?.size)

        val activityGate = CompletableDeferred<Unit>()
        service.activityGate = activityGate
        model.refreshSection()
        runCurrent()
        model.clearProtectedContent()
        activityGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(GuildUiError.AuthenticationRequired, model.state.value.error)
        assertTrue(model.state.value.activities.isEmpty())
        assertNull(model.state.value.info)
    }

    @Test fun unavailableFailureAndCapabilityLossClearConfirmedContent() = runTest {
        val service = FakeGuildService()
        val model = GuildViewModel(service)
        advanceUntilIdle()
        service.infoFailure = GuildException.Unavailable
        model.refresh()
        advanceUntilIdle()
        assertEquals(GuildUiError.Unavailable, model.state.value.error)
        assertNull(model.state.value.info)

        service.infoFailure = null
        model.retry()
        advanceUntilIdle()
        assertEquals("Fixture Guild", model.state.value.info?.name)
        service.canRead = false
        model.refresh()
        advanceUntilIdle()
        assertEquals(GuildUiError.AuthenticationRequired, model.state.value.error)
        assertNull(model.state.value.info)
    }

    @Test fun invalidPhotoIdentityBecomesAnExplicitFailure() = runTest {
        val service = FakeGuildService().apply {
            photoPages[1] = GuildPage(listOf(photoSummary(7)), 1, false)
            returnedPhoto = photoDetail(8)
        }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        advanceUntilIdle()
        assertEquals(GuildUiError.Failed, model.state.value.photoDetailError)
        assertFalse(model.state.value.isLoadingPhotoDetail)
        assertNull(model.state.value.photoDetail)

        val standalone = GuildPhotoViewModel(service, 7)
        advanceUntilIdle()
        assertEquals(GuildUiError.Failed, standalone.state.value.error)
        assertNull(standalone.state.value.detail)
    }

    @Test fun failedSelectedPhotoRefreshKeepsConfirmedDetailAndComments() = runTest {
        val service = FakeGuildService()
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        advanceUntilIdle()
        assertEquals(7, model.state.value.photoDetail?.id)
        assertEquals(listOf(1), model.state.value.comments.map { it.id })
        service.photoFailure = GuildException.Network
        model.refreshPhoto()
        advanceUntilIdle()
        assertEquals(GuildUiError.Failed, model.state.value.photoDetailError)
        assertEquals(7, model.state.value.photoDetail?.id)
        assertEquals(listOf(1), model.state.value.comments.map { it.id })
        assertFalse(model.state.value.isLoadingPhotoDetail)
    }

    @Test fun lateAuthenticationFromAnOldGuildCannotClearTheNewGuildOrConfirmedNone() = runTest {
        suspend fun changeOwner(destination: OwnGuild): GuildUiState {
            val gate = CompletableDeferred<Unit>()
            val service = FakeGuildService().apply {
                commentGate = gate
                commentFailure = GuildException.AuthenticationRequired
            }
            val model = GuildViewModel(service)
            advanceUntilIdle()
            model.selectSection(GuildSection.Photos)
            advanceUntilIdle()
            model.selectPhoto(7)
            runCurrent()
            service.own = destination
            model.refresh()
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            return model.state.value
        }

        val changed = changeOwner(OwnGuild.Joined(GuildId("456")))
        assertEquals(GuildId("456"), changed.info?.id)
        assertNull(changed.error)

        val none = changeOwner(OwnGuild.None)
        assertTrue(none.hasNoGuild)
        assertNull(none.error)
    }

    @Test fun returningToPhotosRestartsACancelledDetailRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeGuildService().apply { photoGate = gate }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        runCurrent()
        model.selectSection(GuildSection.Profile)
        assertFalse(model.state.value.isLoadingPhotoDetail)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.photoDetail)
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        assertEquals(7, model.state.value.photoDetail?.id)
        assertFalse(model.state.value.isLoadingPhotoDetail)
    }

    @Test fun returningToPhotosRestartsCancelledFirstCommentPage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeGuildService().apply { commentGate = gate }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        runCurrent()
        assertEquals(7, model.state.value.photoDetail?.id)
        assertTrue(model.state.value.isLoadingComments)
        model.selectSection(GuildSection.Profile)
        assertFalse(model.state.value.isLoadingComments)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.comments.isEmpty())
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        assertEquals(listOf(1), model.state.value.comments.map { it.id })
        assertEquals(2, service.commentQueries.size)
    }

    @Test fun returningToPhotosRestartsCancelledFirstReplyPage() = runTest {
        val service = FakeGuildService()
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        service.replyGate = gate
        model.openReplies(model.state.value.comments.single())
        runCurrent()
        model.selectSection(GuildSection.Profile)
        assertFalse(model.state.value.isLoadingReplies)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.replies.isEmpty())
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        assertEquals(listOf(2), model.state.value.replies.map { it.id })
        assertEquals(2, service.replyQueries.size)
    }

    @Test fun dismissingPendingRepliesDoesNotLockTheNextOpen() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeGuildService().apply { replyGate = gate }
        val model = GuildViewModel(service)
        advanceUntilIdle()
        model.selectSection(GuildSection.Photos)
        advanceUntilIdle()
        model.selectPhoto(7)
        advanceUntilIdle()
        val comment = model.state.value.comments.single()
        model.openReplies(comment)
        runCurrent()
        model.dismissReplies()
        assertFalse(model.state.value.isLoadingReplies)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.replies.isEmpty())
        model.openReplies(comment)
        advanceUntilIdle()
        assertEquals(listOf(2), model.state.value.replies.map { it.id })
        assertFalse(model.state.value.isLoadingReplies)
    }

    @Test fun authenticationFailureClearsConfirmedPrivateContent() = runTest {
        val service = FakeGuildService()
        val model = GuildViewModel(service)
        advanceUntilIdle()
        service.failInfoWithAuthentication = true
        model.refresh()
        advanceUntilIdle()
        assertEquals(GuildUiError.AuthenticationRequired, model.state.value.error)
        assertNull(model.state.value.info)
        assertTrue(model.state.value.photos.isEmpty())
    }

    @Test fun standalonePhotoDoesNotNeedOwnGuildAndPropagatesCancellation() = runTest {
        val service = FakeGuildService().apply {
            own = OwnGuild.None
            photoFailure = CancellationException("cancelled")
        }
        val model = GuildPhotoViewModel(service, 7)
        advanceUntilIdle()
        assertNull(model.state.value.detail)
        assertNull(model.state.value.error)
        assertEquals(0, service.ownReads)
        service.photoFailure = null
        model.retryPhoto()
        advanceUntilIdle()
        assertEquals(7, model.state.value.detail?.id)
        assertEquals(listOf(Triple(7, 1, null)), service.commentQueries)
    }
}

private class FakeGuildService : GuildService {
    override var canRead = true
    var own: OwnGuild = OwnGuild.Joined(GuildId("123"))
    var ownFailure: Throwable? = null
    var infoReads = 0
    var ownReads = 0
    var failInfo = false
    var failInfoWithAuthentication = false
    var infoFailure: Throwable? = null
    var photoFailure: Throwable? = null
    var returnedPhoto: GuildPhotoDetail? = null
    var memberGate: CompletableDeferred<Unit>? = null
    var activityGate: CompletableDeferred<Unit>? = null
    var photoGate: CompletableDeferred<Unit>? = null
    var commentGate: CompletableDeferred<Unit>? = null
    var commentFailure: Throwable? = null
    var replyGate: CompletableDeferred<Unit>? = null
    val activityPages = mutableMapOf(1 to GuildPage(listOf(activity(1)), 1, false))
    val photoPages = mutableMapOf(1 to GuildPage(listOf(photoSummary(7)), 1, false))
    val commentPages = mutableMapOf(1 to GuildPage(listOf(comment(1, childCount = 1)), 1, false))
    val replyPages = mutableMapOf(1 to GuildPage(listOf(comment(2)), 1, false))
    val commentQueries = mutableListOf<Triple<Int, Int, String?>>()
    val replyQueries = mutableListOf<Pair<Int, Int>>()

    override suspend fun ownGuild(): OwnGuild {
        ownReads++
        ownFailure?.let { throw it }
        return own
    }

    override suspend fun info(guildId: GuildId): GuildInfo {
        infoReads++
        infoFailure?.let { throw it }
        if (failInfoWithAuthentication) throw GuildException.AuthenticationRequired
        if (failInfo) throw GuildException.Network
        return guildInfo(guildId)
    }

    override suspend fun members(guildId: GuildId): GuildMembers {
        memberGate?.let { withContext(NonCancellable) { it.await() } }
        return GuildMembers(listOf(member()), listOf(member().copy(
            registration = GuildMemberRegistration.Unregistered,
            authorUuid = null,
            characterName = "Unregistered",
        )))
    }

    override suspend fun activities(guildId: GuildId, page: Int): GuildPage<GuildActivitySummary> {
        activityGate?.let { withContext(NonCancellable) { it.await() } }
        return activityPages[page] ?: GuildPage(emptyList(), page, false)
    }

    override suspend fun photos(guildId: GuildId, page: Int): GuildPage<GuildPhotoSummary> =
        photoPages[page] ?: GuildPage(emptyList(), page, false)

    override suspend fun photo(id: Int): GuildPhotoDetail {
        photoFailure?.let { throw it }
        photoGate?.let { withContext(NonCancellable) { it.await() } }
        return returnedPhoto ?: photoDetail(id)
    }

    override suspend fun comments(photoId: Int, page: Int, pageTime: String?): GuildPage<GuildPhotoComment> {
        commentQueries += Triple(photoId, page, pageTime)
        commentGate?.let { withContext(NonCancellable) { it.await() } }
        commentFailure?.let { throw it }
        return commentPages[page] ?: GuildPage(emptyList(), page, false)
    }

    override suspend fun replies(rootParentId: Int, page: Int): GuildPage<GuildPhotoComment> {
        replyQueries += rootParentId to page
        replyGate?.let { withContext(NonCancellable) { it.await() } }
        return replyPages[page] ?: GuildPage(emptyList(), page, false)
    }
}

private fun guildInfo(id: GuildId = GuildId("123")) = GuildInfo(
    id, "Fixture Guild", "FIX", "Area", "World", null, "<p>Guild profile</p>", "2026-09-20",
    30, 2, 1, "Maelstrom", "Evening", "All day", listOf("Casual"),
    GuildHousing(GuildHousingVisibility.Visible, "Ward 1", "10 days"),
)

private fun member() = GuildMember(
    GuildMemberRegistration.Registered, "member-uuid", "Registered", "Area", "World", null,
    "<b>Profile</b>", null, null,
)

private fun activity(id: Int) = GuildActivitySummary(
    id, "<p>Activity $id</p>", emptyList(), null, "activity-author", "Author", "Area", "World", null,
)

private fun photoSummary(id: Int) = GuildPhotoSummary(id, "https://fixture/photo.jpg", "Photographer", null, 1, 2, false)

private fun photoDetail(id: Int) = GuildPhotoDetail(
    id, GuildId("123"), "https://fixture/photo.jpg", "photo-author", "Photographer", "Area", "World",
    null, 0, 1, 2, false,
)

private fun comment(id: Int, childCount: Int = 0) = GuildPhotoComment(
    id, 7, 0, id, "comment-author-$id", "Commenter $id", "Area", "World", null,
    "<p>Comment $id</p>", null, null, null, null, childCount,
)
