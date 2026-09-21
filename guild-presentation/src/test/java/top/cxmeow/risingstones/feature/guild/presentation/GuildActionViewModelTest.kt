package top.cxmeow.risingstones.feature.guild.presentation

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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.guild.domain.GuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildActionService
import top.cxmeow.risingstones.feature.guild.domain.GuildCommentActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildGuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.domain.GuildLabel
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentDraft
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult
import top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage

@OptIn(ExperimentalCoroutinesApi::class)
class GuildActionViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun firstUseAttemptLoadsEligibilityWithoutPerformingAWrite() = runTest {
        val service = FakeGuildActions(canWrite = false, canAttempt = true)
        val model = GuildActionViewModel(service, service)

        model.bindResources(GuildId("123"), 7, listOf(comment(31)))
        advanceUntilIdle()

        assertTrue(model.canAttemptWrite)
        assertFalse(model.canWrite)
        assertTrue(model.canInteract)
        assertTrue(model.state.value.canManageGuild)
        assertTrue(model.state.value.canUploadAlbum)
        assertTrue(model.state.value.canDeletePhoto(7))
        assertTrue(model.state.value.canDeleteComment(31))
        assertEquals(0, service.mutations)
        assertEquals(0, service.scopes.single().closedCount)
        model.clearProtectedContent()
        assertEquals(1, service.scopes.single().closedCount)
    }

    @Test fun explicitCommentUploadAndWriteShareOneScopeAndSuccessClearsDraft() = runTest {
        val service = FakeGuildActions()
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, listOf(comment(31)))
        advanceUntilIdle()
        val localImage = image()
        model.openComment(GuildCommentTarget(31, 31, "author", "Writer"))
        model.updateCommentText("<hello>\nworld")
        model.setCommentImage(localImage)

        model.submitComment(7)
        advanceUntilIdle()

        assertEquals(1, service.commentCalls)
        assertSame(service.uploadScopes.single(), service.commentScopes.single())
        assertEquals("&lt;hello&gt;<br>world", service.lastDraft?.contentHtml)
        assertEquals("author", service.lastDraft?.mentions?.single()?.uuid)
        assertEquals("memory://comment", service.lastDraft?.commentImageUrl)
        assertNull(model.state.value.commentTarget)
        assertTrue(model.state.value.commentText.isEmpty())
        assertNull(model.state.value.commentImage)
        assertEquals(1, model.state.value.commentSuccessRevision)
        assertEquals(0, service.commentScopes.single().closedCount)
    }

    @Test fun failedWriteIsSentOnceAndKeepsTheSameCredentialDraft() = runTest {
        val service = FakeGuildActions().apply { mutationFailure = GuildException.Network }
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, emptyList())
        advanceUntilIdle()
        model.openComment()
        model.updateCommentText("keep me")

        model.submitComment(7)
        advanceUntilIdle()

        assertEquals(1, service.commentCalls)
        assertEquals(GuildActionError.Failed, model.state.value.error)
        assertEquals("keep me", model.state.value.commentText)
        assertEquals(GuildCommentTarget.TopLevel, model.state.value.commentTarget)
        assertFalse(model.state.value.isSubmittingComment)
        assertEquals(0, service.commentScopes.single().closedCount)
    }

    @Test fun uploadFailureKeepsImagesAndDoesNotRegisterPartialAlbum() = runTest {
        val service = FakeGuildActions().apply { uploadFailure = GuildException.ImageUploadFailed }
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), null, emptyList())
        advanceUntilIdle()
        model.setAlbumImages(listOf(image(), image()))

        model.uploadAlbum()
        advanceUntilIdle()

        assertEquals(1, service.uploadCalls)
        assertEquals(0, service.albumCalls)
        assertEquals(2, model.state.value.selectedAlbumImages.size)
        assertEquals(GuildActionError.UploadFailed, model.state.value.error)
        assertFalse(model.state.value.isUploadingAlbum)
        assertEquals(0, service.scopes.last().closedCount)
    }

    @Test fun albumReadsSequentiallyAndRetryKeepsSuccessfulUploadsInTheSameScope() = runTest {
        val service = FakeGuildActions().apply { uploadFailureAtCall = 2 }
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), null, emptyList())
        advanceUntilIdle()
        val reads = IntArray(3)
        model.setAlbumImageSources((0..2).map { index ->
            GuildAlbumImageSource {
                reads[index]++
                image()
            }
        })

        model.uploadAlbum()
        advanceUntilIdle()
        assertEquals(listOf(1, 1, 0), reads.toList())
        assertEquals(1, model.state.value.uploadedAlbumCount)
        assertEquals(0, service.albumCalls)
        val albumScope = service.uploadScopes.first()
        assertTrue(service.uploadScopes.all { it === albumScope })

        service.uploadFailureAtCall = null
        model.uploadAlbum()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1), reads.toList())
        assertEquals(4, service.uploadCalls)
        assertEquals(1, service.albumCalls)
        assertTrue(service.uploadScopes.all { it === albumScope })
        assertSame(albumScope, service.albumScopes.single())
        assertEquals(0, albumScope.closedCount)
        assertEquals(0, model.state.value.selectedAlbumImageCount)
        assertEquals(1, model.state.value.albumSuccessRevision)
    }

    @Test fun replacedCredentialRejectsExistingDraftEvenWhenCapabilityFlagsStayTrue() = runTest {
        val service = FakeGuildActions()
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, emptyList())
        advanceUntilIdle()
        model.openComment()
        model.updateCommentText("old credential")
        model.setCommentImage(image())
        service.scopes.single().current = false

        model.submitComment(7)
        advanceUntilIdle()

        assertEquals(0, service.uploadCalls)
        assertEquals(0, service.commentCalls)
        assertEquals(1, service.scopes.size)
        assertEquals(1, service.scopes.single().closedCount)
        assertTrue(model.state.value.commentText.isEmpty())
        assertEquals(GuildActionError.AuthenticationRequired, model.state.value.error)
    }

    @Test fun changingPhotoClearsReplyAndDeleteTargetsBeforeOpeningNewScope() = runTest {
        val service = FakeGuildActions()
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, listOf(comment(31)))
        advanceUntilIdle()
        model.openComment(GuildCommentTarget(31, 31, "author", "Writer"))
        model.updateCommentText("photo seven")
        model.requestDeletePhoto(7)
        val oldScope = service.scopes.single()

        model.bindResources(GuildId("123"), 8, emptyList())
        advanceUntilIdle()
        model.submitComment(7)
        model.togglePhotoLike(7)
        advanceUntilIdle()

        assertEquals(1, oldScope.closedCount)
        assertNull(model.state.value.commentTarget)
        assertNull(model.state.value.confirmDeletePhotoId)
        assertTrue(model.state.value.commentText.isEmpty())
        assertEquals(0, service.mutations)
    }

    @Test fun explicitRetryOfCommentRegistrationReusesItsUploadedImage() = runTest {
        val service = FakeGuildActions().apply { mutationFailure = GuildException.Network }
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, emptyList())
        advanceUntilIdle()
        model.openComment()
        model.setCommentImage(image())
        model.submitComment(7)
        advanceUntilIdle()
        service.mutationFailure = null
        model.submitComment(7)
        advanceUntilIdle()

        assertEquals(1, service.uploadCalls)
        assertEquals(2, service.commentCalls)
        assertTrue(service.commentScopes.all { it === service.uploadScopes.single() })
        assertEquals(1, model.state.value.commentSuccessRevision)
    }

    @Test fun albumRejectsMoreThanNineLazySourcesWithoutReadingAny() = runTest {
        val service = FakeGuildActions()
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), null, emptyList())
        advanceUntilIdle()
        var reads = 0

        model.setAlbumImageSources(List(10) { GuildAlbumImageSource { reads++; image() } })

        assertEquals(GuildActionError.InvalidInput, model.state.value.error)
        assertEquals(0, model.state.value.selectedAlbumImageCount)
        assertEquals(0, reads)
    }

    @Test fun ineligibleFailureKeepsDraftButIdentityConflictRevokesIt() = runTest {
        val service = FakeGuildActions().apply { mutationFailure = GuildException.ActionNotEligible }
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, emptyList())
        advanceUntilIdle()
        model.openComment()
        model.updateCommentText("same account draft")
        model.submitComment(7)
        advanceUntilIdle()
        assertEquals(GuildActionError.Unavailable, model.state.value.error)
        assertEquals("same account draft", model.state.value.commentText)

        service.mutationFailure = GuildException.IdentityConflict
        model.submitComment(7)
        advanceUntilIdle()
        assertEquals(GuildActionError.AuthenticationRequired, model.state.value.error)
        assertTrue(model.state.value.commentText.isEmpty())
        assertNull(model.state.value.guildId)
    }

    @Test fun revocationCancelsPendingMutationAndRejectsItsLateSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = FakeGuildActions().apply { mutationGate = gate }
        val model = GuildActionViewModel(service, service)
        model.bindResources(GuildId("123"), 7, emptyList())
        advanceUntilIdle()
        model.openComment()
        model.updateCommentText("pending")
        model.submitComment(7)
        runCurrent()

        service.canAttempt = false
        service.canWrite = false
        model.synchronizeEligibility()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, service.commentCalls)
        assertNull(model.state.value.guildId)
        assertTrue(model.state.value.commentText.isEmpty())
        assertEquals(0, model.state.value.commentSuccessRevision)
        assertFalse(model.state.value.isSubmittingComment)
    }

    @Test fun likeAndDeleteRequireLoadedEligibilityAndPublishConsumableResults() = runTest {
        val service = FakeGuildActions()
        val model = GuildActionViewModel(service, service)
        model.requestDeleteComment(31)
        model.requestDeletePhoto(7)
        assertNull(model.state.value.confirmDeleteCommentId)
        assertNull(model.state.value.confirmDeletePhotoId)

        model.bindResources(GuildId("123"), 7, listOf(comment(31)))
        advanceUntilIdle()
        model.togglePhotoLike(7)
        model.requestDeleteComment(31)
        model.confirmDeleteComment()
        model.requestDeletePhoto(7)
        model.confirmDeletePhoto()
        advanceUntilIdle()

        assertEquals(GuildPhotoLikeResult.Liked, model.state.value.likeResults[7])
        assertEquals(31, model.state.value.deletedCommentId)
        assertEquals(7, model.state.value.deletedPhotoId)
        model.consumeDeletedCommentEvent(31)
        model.consumeDeletedPhotoEvent(7)
        assertNull(model.state.value.deletedCommentId)
        assertNull(model.state.value.deletedPhotoId)
        assertEquals(1, service.likeCalls)
        assertEquals(1, service.deleteCommentCalls)
        assertEquals(1, service.deletePhotoCalls)
    }
}

private class FakeGuildActions(
    canWrite: Boolean = true,
    canAttempt: Boolean = false,
) : GuildActionService, GuildImageUploadService {
    override var canPerformAuthenticatedWrites: Boolean = canWrite
    override var canAttemptAuthenticatedWrites: Boolean = canAttempt
    override var canUploadImages: Boolean = true
    override var canAttemptImageUpload: Boolean = false
    var canWrite: Boolean
        get() = canPerformAuthenticatedWrites
        set(value) { canPerformAuthenticatedWrites = value }
    var canAttempt: Boolean
        get() = canAttemptAuthenticatedWrites
        set(value) { canAttemptAuthenticatedWrites = value }
    val scopes = mutableListOf<FakeGuildScope>()
    val uploadScopes = mutableListOf<FakeGuildScope>()
    val commentScopes = mutableListOf<FakeGuildScope>()
    val albumScopes = mutableListOf<FakeGuildScope>()
    var mutationFailure: Exception? = null
    var uploadFailure: Exception? = null
    var uploadFailureAtCall: Int? = null
    var mutationGate: CompletableDeferred<Unit>? = null
    var uploadCalls = 0
    var commentCalls = 0
    var albumCalls = 0
    var likeCalls = 0
    var deleteCommentCalls = 0
    var deletePhotoCalls = 0
    var lastDraft: GuildPhotoCommentDraft? = null
    val mutations: Int get() = commentCalls + albumCalls + likeCalls + deleteCommentCalls + deletePhotoCalls

    override suspend fun beginActionScope(): GuildActionScope = FakeGuildScope().also(scopes::add)

    override suspend fun labels(scope: GuildActionScope): List<GuildLabel> = emptyList()

    override suspend fun guildEligibility(scope: GuildActionScope, guildId: GuildId) =
        GuildGuildActionEligibility(guildId, GuildActionEligibility.Eligible, GuildActionEligibility.Eligible)

    override suspend fun photoEligibility(scope: GuildActionScope, photoId: Int) =
        GuildPhotoActionEligibility(photoId, GuildId("123"), GuildActionEligibility.Eligible)

    override suspend fun commentEligibility(scope: GuildActionScope, commentId: Int) =
        GuildCommentActionEligibility(commentId, GuildActionEligibility.Eligible)

    override suspend fun updateGuildInfo(scope: GuildActionScope, guildId: GuildId, update: GuildInfoUpdate) {
        awaitMutation()
    }

    override suspend fun togglePhotoLike(scope: GuildActionScope, photoId: Int): GuildPhotoLikeResult {
        likeCalls++
        awaitMutation()
        return GuildPhotoLikeResult.Liked
    }

    override suspend fun commentPhoto(scope: GuildActionScope, draft: GuildPhotoCommentDraft) {
        commentCalls++
        commentScopes += scope as FakeGuildScope
        lastDraft = draft
        awaitMutation()
    }

    override suspend fun deleteOwnComment(scope: GuildActionScope, commentId: Int) {
        deleteCommentCalls++
        awaitMutation()
    }

    override suspend fun registerAlbumPhotos(
        scope: GuildActionScope,
        guildId: GuildId,
        images: List<GuildUploadedImage>,
    ) {
        albumCalls++
        albumScopes += scope as FakeGuildScope
        awaitMutation()
    }

    override suspend fun deletePhoto(scope: GuildActionScope, photoId: Int) {
        deletePhotoCalls++
        awaitMutation()
    }

    override suspend fun uploadImage(
        scope: GuildActionScope,
        purpose: GuildImagePurpose,
        input: GuildImageUploadInput,
    ): GuildUploadedImage {
        uploadCalls++
        uploadScopes += scope as FakeGuildScope
        if (uploadFailureAtCall == uploadCalls) throw GuildException.ImageUploadFailed
        uploadFailure?.let { throw it }
        return FakeUploadedImage("memory://${purpose.name.lowercase()}", purpose)
    }

    private suspend fun awaitMutation() {
        mutationGate?.let { withContext(NonCancellable) { it.await() } }
        mutationFailure?.let { throw it }
    }
}

private class FakeGuildScope : GuildActionScope {
    var current = true
    var closedCount = 0
    override suspend fun isCurrent(): Boolean = current && closedCount == 0
    override fun close() { closedCount++ }
}

private data class FakeUploadedImage(
    override val url: String,
    override val purpose: GuildImagePurpose,
) : GuildUploadedImage

private fun image() = GuildImageUploadInput(byteArrayOf(1, 2, 3), "image/png")

private fun comment(id: Int) = GuildPhotoComment(
    id, 7, 0, id, "author-$id", "Author $id", "Area", "World", null,
    "comment", null, null, null, null, 0,
)
