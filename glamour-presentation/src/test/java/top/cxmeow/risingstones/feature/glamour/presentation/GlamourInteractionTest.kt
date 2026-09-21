package top.cxmeow.risingstones.feature.glamour.presentation

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
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessorySearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthorProfile
import top.cxmeow.risingstones.feature.glamour.domain.GlamourCollectionService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourGlassesSearchGroup
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService

@OptIn(ExperimentalCoroutinesApi::class)
class GlamourInteractionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun authorPickerReadsOwnFoldersAndDoesNotReplaceTheAuthorFolders() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service, interactionAuthor)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        assertEquals(listOf(40), viewModel.state.value.folders.map { it.id })
        viewModel.openFavoritePicker(1)
        advanceUntilIdle()
        assertEquals(listOf("author", null), service.folderAuthors)
        assertEquals(listOf(7, 8), viewModel.interactionState.value.favoriteFolders.map { it.id })
        assertEquals(listOf(40), viewModel.state.value.folders.map { it.id })
        assertNull(viewModel.interactionState.value.selectedFavoriteFolderId)
        viewModel.selectFavoriteFolder(40)
        assertNull(viewModel.interactionState.value.selectedFavoriteFolderId)
        viewModel.selectFavoriteFolder(8)
        assertEquals(8, viewModel.interactionState.value.selectedFavoriteFolderId)
        assertFalse(viewModel.canManageFolders)
        assertTrue(viewModel.supportsCollectionManagement)
    }

    @Test
    fun closingThePickerCancelsItsReadAndLateResultsDoNotReopenIt() = runTest {
        val pending = CompletableDeferred<List<GlamourFavoriteFolder>>()
        val service = InteractionServiceFake().apply {
            folderHandler = { withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.openFavoritePicker(1)
        runCurrent()
        viewModel.closeFavoritePicker()
        pending.complete(service.ownFolders)
        advanceUntilIdle()
        assertNull(viewModel.interactionState.value.favoriteTargetId)
        assertTrue(viewModel.interactionState.value.favoriteFolders.isEmpty())
        assertFalse(viewModel.interactionState.value.isLoadingFavoriteFolders)
    }

    @Test
    fun submittingUsesOnlyCurrentOwnFoldersKeepsSelectionOnFailureAndUpdatesCountsOnce() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectDetail(1)
        viewModel.openFavoritePicker(1)
        advanceUntilIdle()
        viewModel.submitFavorite(40)
        assertTrue(service.favorites.isEmpty())
        service.favoriteFailure = IllegalStateException("favorite failed")
        viewModel.selectFavoriteFolder(8)
        viewModel.submitFavorite(8)
        viewModel.submitFavorite(8)
        viewModel.closeFavoritePicker()
        assertEquals(1, viewModel.interactionState.value.favoriteTargetId)
        advanceUntilIdle()
        assertEquals(listOf(1 to 8), service.favorites)
        assertEquals(8, viewModel.interactionState.value.selectedFavoriteFolderId)
        assertEquals("favorite failed", viewModel.interactionState.value.favoriteFolderError)
        assertFalse(viewModel.interactionState.value.isSubmittingFavorite)
        assertEquals(1, viewModel.state.value.selectedDetail?.favorites)

        service.favoriteFailure = null
        viewModel.submitFavorite(8)
        advanceUntilIdle()
        assertEquals(listOf(1 to 8, 1 to 8), service.favorites)
        assertNull(viewModel.interactionState.value.favoriteTargetId)
        assertNull(viewModel.interactionState.value.selectedFavoriteFolderId)
        assertTrue(viewModel.state.value.selectedDetail?.isFavorite == true)
        assertEquals(2, viewModel.state.value.selectedDetail?.favorites)
        assertEquals(2, viewModel.state.value.items.first { it.id == 1 }.favorites)
        assertTrue(viewModel.state.value.mutatingIds.isEmpty())
    }

    @Test
    fun createSuccessIsReportedBeforeRefreshFailureAndRetryDoesNotCreateAgain() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        viewModel.openFavoritePicker(1)
        advanceUntilIdle()
        viewModel.selectFavoriteFolder(8)
        service.folderFailureAfterWrite = IllegalStateException("refresh failed")
        var completed = 0
        viewModel.createFolder("  New looks  ", true) { completed++ }
        viewModel.createFolder("duplicate", false) { completed++ }
        advanceUntilIdle()
        assertEquals(listOf("New looks" to true), service.created)
        assertEquals(1, completed)
        assertNull(viewModel.state.value.folderMutationError)
        assertFalse(viewModel.state.value.isCreatingFolder)
        assertEquals("refresh failed", viewModel.interactionState.value.folderRefreshError)
        assertNull(viewModel.interactionState.value.selectedFavoriteFolderId)
        assertEquals(listOf(7, 8), viewModel.state.value.folders.map { it.id })
        service.folderFailureAfterWrite = null
        viewModel.retryFolderRefresh()
        advanceUntilIdle()
        assertEquals(1, service.created.size)
        assertEquals(listOf(7, 8, 99), viewModel.state.value.folders.map { it.id })
        assertEquals(listOf(7, 8, 99), viewModel.interactionState.value.favoriteFolders.map { it.id })
        assertNull(viewModel.interactionState.value.folderRefreshError)
    }

    @Test
    fun readsStartedBeforeFavoriteSubmissionCannotUndoTheConfirmedFavorite() = runTest {
        val pendingList = CompletableDeferred<GlamourListPage>()
        val pendingDetail = CompletableDeferred<GlamourDetail>()
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectDetail(1)
        viewModel.openFavoritePicker(1)
        advanceUntilIdle()
        service.listHandler = { withContext(NonCancellable) { pendingList.await() } }
        service.detailHandler = { withContext(NonCancellable) { pendingDetail.await() } }
        viewModel.refresh()
        viewModel.refreshDetail()
        runCurrent()
        viewModel.submitFavorite(8)
        runCurrent()
        pendingList.complete(GlamourListPage(listOf(interactionSummary(1)), 1, false))
        pendingDetail.complete(interactionDetail(1))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.single().isFavorite)
        assertEquals(2, viewModel.state.value.items.single().favorites)
        assertTrue(viewModel.state.value.selectedDetail?.isFavorite == true)
        assertEquals(2, viewModel.state.value.selectedDetail?.favorites)
    }

    @Test
    fun authorPickerCanCreateAnOwnFolderWhileAuthorManagementRemainsForbidden() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service, interactionAuthor)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        viewModel.openFavoritePicker(1)
        advanceUntilIdle()
        viewModel.selectFavoriteFolder(8)
        var completed = 0
        viewModel.createFolder("Mine", false) { completed++ }
        advanceUntilIdle()
        assertEquals(1, completed)
        assertNull(service.folderAuthors.last())
        assertEquals(listOf(7, 8, 99), viewModel.interactionState.value.favoriteFolders.map { it.id })
        assertEquals(listOf(40), viewModel.state.value.folders.map { it.id })
        assertNull(viewModel.interactionState.value.selectedFavoriteFolderId)
        viewModel.updateFolder(40, "Forbidden", false)
        viewModel.deleteFolder(40)
        advanceUntilIdle()
        assertTrue(service.updated.isEmpty())
        assertTrue(service.deleted.isEmpty())
    }

    @Test
    fun folderValidationRejectsInvalidNamesUnknownFoldersAndTheDefaultFolderDeletion() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        viewModel.createFolder("   ", false)
        viewModel.createFolder("x".repeat(21), false)
        viewModel.updateFolder(8, " ", false)
        viewModel.updateFolder(100, "Unknown", false)
        viewModel.deleteFolder(7)
        viewModel.deleteFolder(100)
        advanceUntilIdle()
        assertTrue(service.created.isEmpty())
        assertTrue(service.updated.isEmpty())
        assertTrue(service.deleted.isEmpty())
        assertTrue(viewModel.state.value.folderMutationError != null)
        viewModel.createFolder("  ${"x".repeat(20)}  ", false)
        advanceUntilIdle()
        assertEquals("x".repeat(20), service.created.single().first)
    }

    @Test
    fun successfulUpdateKeepsConfirmedLocalValuesWhenTheReadAfterWritingFails() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        service.folderFailureAfterWrite = IllegalStateException("refresh failed")
        var completed = 0
        viewModel.updateFolder(8, "  Updated  ", true) { completed++ }
        assertEquals(8, viewModel.interactionState.value.updatingFolderId)
        viewModel.updateFolder(8, "Duplicate", false)
        advanceUntilIdle()
        assertEquals(listOf(Triple(8, "Updated", true)), service.updated)
        assertEquals(1, completed)
        assertEquals("Updated", viewModel.state.value.folders.first { it.id == 8 }.name)
        assertTrue(viewModel.state.value.folders.first { it.id == 8 }.isPublic)
        assertNull(viewModel.state.value.folderMutationError)
        assertNull(viewModel.interactionState.value.updatingFolderId)
        assertEquals("refresh failed", viewModel.interactionState.value.folderRefreshError)
    }

    @Test
    fun successfulDeletionRemovesTheFolderSelectsTheNextFolderAndRefreshesItsList() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        viewModel.selectFolder(8)
        advanceUntilIdle()
        service.folderFailureAfterWrite = IllegalStateException("refresh failed")
        var completed = 0
        viewModel.deleteFolder(8) { completed++ }
        advanceUntilIdle()
        assertEquals(listOf(8), service.deleted)
        assertEquals(1, completed)
        assertEquals(listOf(7), viewModel.state.value.folders.map { it.id })
        assertEquals(7, viewModel.state.value.selectedFolderId)
        assertEquals(7, service.listRequests.last().favoriteFolderId)
        assertNull(viewModel.state.value.deletingFolderId)
        assertNull(viewModel.state.value.folderMutationError)
        assertEquals("refresh failed", viewModel.interactionState.value.folderRefreshError)
    }

    @Test
    fun staleFolderReadCannotUndoAConfirmedUpdate() = runTest {
        val pending = CompletableDeferred<List<GlamourFavoriteFolder>>()
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        val oldFolders = service.ownFolders
        service.folderHandler = { withContext(NonCancellable) { pending.await() } }
        viewModel.retryFolderRefresh()
        runCurrent()
        service.folderHandler = null
        service.folderFailureAfterWrite = IllegalStateException("refresh failed")
        viewModel.updateFolder(8, "Confirmed", true)
        advanceUntilIdle()
        pending.complete(oldFolders)
        advanceUntilIdle()
        assertEquals("Confirmed", viewModel.state.value.folders.first { it.id == 8 }.name)
        assertEquals("refresh failed", viewModel.interactionState.value.folderRefreshError)
    }

    @Test
    fun folderMutationRevisionAdvancesBeforeCallbacksOnlyForConfirmedWritesAndResetsOnAuthenticationFailure() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        val callbackRevisions = mutableListOf<Long>()
        val completed = { callbackRevisions += viewModel.interactionState.value.folderMutationRevision; Unit }
        service.createHandler = { error("write failed") }
        viewModel.createFolder("Failed", false, completed)
        advanceUntilIdle()
        assertEquals(0L, viewModel.interactionState.value.folderMutationRevision)
        service.createHandler = { throw CancellationException("cancelled") }
        viewModel.createFolder("Cancelled", false, completed)
        advanceUntilIdle()
        assertEquals(0L, viewModel.interactionState.value.folderMutationRevision)
        assertTrue(callbackRevisions.isEmpty())

        service.createHandler = null
        service.folderFailureAfterWrite = IllegalStateException("refresh failed")
        viewModel.createFolder("Created", false, completed)
        advanceUntilIdle()
        viewModel.updateFolder(8, "Updated", true, completed)
        advanceUntilIdle()
        viewModel.deleteFolder(8, completed)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), callbackRevisions)
        assertEquals(3L, viewModel.interactionState.value.folderMutationRevision)
        assertEquals("refresh failed", viewModel.interactionState.value.folderRefreshError)
        service.listFailure = GlamourException.AuthenticationRequired
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(0L, viewModel.interactionState.value.folderMutationRevision)
    }

    @Test
    fun couponGuardsEligibilityAndSuccessfulClaimDoesNotSendAnExtraFollowRequest() = runTest {
        val service = InteractionServiceFake().apply {
            details[1] = interactionDetail(1).copy(isCouponEligible = false)
            details[2] = interactionDetail(2).copy(couponInviteCode = " ")
            details[3] = interactionDetail(3).copy(isCouponClaimed = true)
        }
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.claimSelectedCoupon()
        for (id in 1..3) {
            viewModel.selectDetail(id)
            advanceUntilIdle()
            viewModel.claimSelectedCoupon()
            advanceUntilIdle()
        }
        assertTrue(service.claims.isEmpty())
        viewModel.selectDetail(4)
        advanceUntilIdle()
        viewModel.claimSelectedCoupon()
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertEquals(listOf("coupon-4" to 4), service.claims)
        assertEquals(0, service.followCalls)
        assertTrue(viewModel.state.value.selectedDetail?.isCouponClaimed == true)
        assertTrue(viewModel.state.value.selectedDetail?.isFollowingAuthor == true)
        assertTrue(viewModel.interactionState.value.couponClaimedNotice)
        assertFalse(viewModel.interactionState.value.isClaimingCoupon)
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertEquals(1, service.claims.size)
        viewModel.clearCouponNotice()
        assertFalse(viewModel.interactionState.value.couponClaimedNotice)
    }

    @Test
    fun failedCouponClaimKeepsTheDetailAndCanBeExplicitlyRetried() = runTest {
        val service = InteractionServiceFake().apply { claimFailure = IllegalStateException("claim failed") }
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.selectedDetail?.isCouponClaimed == true)
        assertFalse(viewModel.state.value.selectedDetail?.isFollowingAuthor == true)
        assertEquals("claim failed", viewModel.interactionState.value.couponError)
        service.claimFailure = null
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertEquals(2, service.claims.size)
        assertTrue(viewModel.state.value.selectedDetail?.isCouponClaimed == true)
        assertNull(viewModel.interactionState.value.couponError)
        viewModel.clearSelection()
        assertFalse(viewModel.interactionState.value.couponClaimedNotice)
    }

    @Test
    fun switchingDetailCannotClaimThePreviouslyLoadedItemBeforeTheNewLoadStarts() = runTest {
        val service = InteractionServiceFake()
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.selectDetail(2)
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertTrue(service.claims.isEmpty())
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertEquals(listOf("coupon-2" to 2), service.claims)
    }

    @Test
    fun claimingAFormerDetailDoesNotChangeTheNewDetailOrItsNotice() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = InteractionServiceFake().apply {
            claimHandler = { _, _ -> withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.claimSelectedCoupon()
        runCurrent()
        viewModel.selectDetail(2)
        advanceUntilIdle()
        assertFalse(viewModel.interactionState.value.isClaimingCoupon)
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.selectedDetail?.id)
        assertFalse(viewModel.state.value.selectedDetail?.isCouponClaimed == true)
        assertFalse(viewModel.state.value.selectedDetail?.isFollowingAuthor == true)
        assertFalse(viewModel.interactionState.value.couponClaimedNotice)
        assertNull(viewModel.interactionState.value.couponError)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.selectedDetail?.isCouponClaimed == true)
        assertTrue(viewModel.state.value.selectedDetail?.isFollowingAuthor == true)
    }

    @Test
    fun authenticationFailureClearsInteractionStateAndLatePickerResponses() = runTest {
        val pending = CompletableDeferred<List<GlamourFavoriteFolder>>()
        val service = InteractionServiceFake().apply {
            folderHandler = { withContext(NonCancellable) { pending.await() } }
            claimFailure = GlamourException.AuthenticationRequired
        }
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.openFavoritePicker(1)
        runCurrent()
        viewModel.claimSelectedCoupon()
        runCurrent()
        pending.complete(service.ownFolders)
        advanceUntilIdle()
        assertEquals(GlamourInteractionUiState(), viewModel.interactionState.value)
        assertNull(viewModel.state.value.selectedDetail)
        assertTrue(viewModel.state.value.folders.isEmpty())
    }

    @Test
    fun aWriteReturningAfterAuthenticationWasRevokedDoesNotRestoreCacheOrInvokeSuccess() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = InteractionServiceFake().apply {
            createHandler = { withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        var completed = 0
        viewModel.createFolder("Pending", true) { completed++ }
        runCurrent()
        service.listFailure = GlamourException.AuthenticationRequired
        viewModel.refresh()
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, completed)
        assertEquals(GlamourInteractionUiState(), viewModel.interactionState.value)
        assertTrue(viewModel.state.value.folders.isEmpty())
        assertFalse(viewModel.state.value.isCreatingFolder)
    }

    @Test
    fun cancelledPickerAndCouponRequestsDoNotBecomeOrdinaryErrorsOrKeepLoadingFlags() = runTest {
        val service = InteractionServiceFake().apply {
            folderHandler = { throw CancellationException("cancelled") }
            claimFailure = CancellationException("cancelled")
        }
        val viewModel = GlamourViewModel(service, autoLoadList = false)
        viewModel.openFavoritePicker(1)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        assertNull(viewModel.interactionState.value.favoriteFolderError)
        assertFalse(viewModel.interactionState.value.isLoadingFavoriteFolders)
        viewModel.claimSelectedCoupon()
        advanceUntilIdle()
        assertNull(viewModel.interactionState.value.couponError)
        assertFalse(viewModel.interactionState.value.isClaimingCoupon)
    }

    @Test
    fun legacyServicesKeepTheOriginalFavoriteToggleWithoutAnUnsupportedPicker() = runTest {
        val service = InteractionServiceFake()
        val legacy = object : GlamourService by service {}
        val viewModel = GlamourViewModel(legacy)
        advanceUntilIdle()
        assertFalse(viewModel.supportsCollectionManagement)
        viewModel.openFavoritePicker(1)
        assertNull(viewModel.interactionState.value.favoriteTargetId)
        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.toggleFavorite(1)
        advanceUntilIdle()
        assertEquals(listOf(1), service.legacyFavorites)
        assertTrue(service.favorites.isEmpty())
        assertTrue(viewModel.state.value.selectedDetail?.isFavorite == true)
    }
}

private val interactionAuthor = GlamourAuthor("author", "Hero", "World", "DC", null)

private class InteractionServiceFake : GlamourCollectionService {
    override var hasCommunityIdentity = true
    var ownFolders = listOf(
        GlamourFavoriteFolder(7, "Default", true, false, 1),
        GlamourFavoriteFolder(8, "Looks", false, false, 1),
    )
    val authorFolders = listOf(GlamourFavoriteFolder(40, "Author looks", false, true, 1))
    val folderAuthors = mutableListOf<String?>()
    val listRequests = mutableListOf<GlamourListRequest>()
    val created = mutableListOf<Pair<String, Boolean>>()
    val updated = mutableListOf<Triple<Int, String, Boolean>>()
    val deleted = mutableListOf<Int>()
    val favorites = mutableListOf<Pair<Int, Int>>()
    val legacyFavorites = mutableListOf<Int>()
    val claims = mutableListOf<Pair<String, Int>>()
    val details = mutableMapOf<Int, GlamourDetail>()
    var followCalls = 0
    var folderFailureAfterWrite: Throwable? = null
    var listFailure: Throwable? = null
    var listHandler: (suspend () -> GlamourListPage)? = null
    var detailHandler: (suspend () -> GlamourDetail)? = null
    var favoriteFailure: Throwable? = null
    var claimFailure: Throwable? = null
    var folderHandler: (suspend (String?) -> List<GlamourFavoriteFolder>)? = null
    var createHandler: (suspend () -> Unit)? = null
    var claimHandler: (suspend (String, Int) -> Unit)? = null
    private var hasWrittenFolder = false

    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        listRequests += request
        listFailure?.let { throw it }
        listHandler?.let { return it() }
        return GlamourListPage(listOf(interactionSummary(1), interactionSummary(2)), request.page, false)
    }
    override suspend fun fetchFavoriteFolders(authorId: String?): List<GlamourFavoriteFolder> {
        folderAuthors += authorId
        folderHandler?.let { return it(authorId) }
        if (hasWrittenFolder) folderFailureAfterWrite?.let { throw it }
        return if (authorId == null) ownFolders else authorFolders
    }
    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) {
        created += name to isPublic
        createHandler?.invoke()
        hasWrittenFolder = true
        ownFolders = ownFolders + GlamourFavoriteFolder(99, name, false, isPublic, 0)
    }
    override suspend fun updateFavoriteFolder(id: Int, name: String, isPublic: Boolean) {
        updated += Triple(id, name, isPublic)
        hasWrittenFolder = true
        ownFolders = ownFolders.map { if (it.id == id) it.copy(name = name, isPublic = isPublic) else it }
    }
    override suspend fun deleteFavoriteFolder(id: Int) {
        deleted += id
        hasWrittenFolder = true
        ownFolders = ownFolders.filterNot { it.id == id }
    }
    override suspend fun favoriteInFolder(id: Int, folderId: Int) {
        favorites += id to folderId
        favoriteFailure?.let { throw it }
    }
    override suspend fun claimCoupon(inviteCode: String, glamourId: Int) {
        claims += inviteCode to glamourId
        claimFailure?.let { throw it }
        claimHandler?.invoke(inviteCode, glamourId)
    }
    override suspend fun fetchDetail(id: Int) = detailHandler?.invoke() ?: details[id] ?: interactionDetail(id)
    override suspend fun fetchProfileStatistics(authorId: String?) = GlamourProfileStatistics(1, 2, 3)
    override suspend fun fetchAuthorProfile(authorId: String) = GlamourAuthorProfile(interactionAuthor, null, 0, 0, 0)
    override suspend fun followAuthor(authorId: String) { followCalls++ }
    override suspend fun cancelFollowAuthor(authorId: String) { followCalls++ }
    override suspend fun fetchRaces(): List<GlamourRace> = emptyList()
    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> = emptyList()
    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> = emptyList()
    override suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult> = emptyList()
    override suspend fun favorite(id: Int) { legacyFavorites += id }
    override suspend fun cancelFavorite(id: Int) = Unit
    override suspend fun toggleLike(id: Int) = true
}

private fun interactionDetail(id: Int) = GlamourDetail(
    id, "Look $id", "Description", emptyList(), interactionAuthor,
    1, 1, false, false, null, emptyList(), emptyList(), null, null,
    isCouponEligible = true, couponInviteCode = "coupon-$id",
)

private fun interactionSummary(id: Int) = GlamourListingSummary(
    id, "Look $id", "Description", emptyList(), interactionAuthor,
    1, 1, false, false, null, emptyList(), emptyList(), emptyList(),
)
