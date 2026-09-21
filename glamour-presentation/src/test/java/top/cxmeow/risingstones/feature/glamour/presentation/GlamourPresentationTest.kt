package top.cxmeow.risingstones.feature.glamour.presentation

import java.time.Instant
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
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourGlassesSearchGroup
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService

@OptIn(ExperimentalCoroutinesApi::class)
class GlamourViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialLoadAndPaginationKeepUniqueItems() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        assertTrue(viewModel.state.value.hasNextPage)
        assertEquals(listOf(1), viewModel.state.value.races.map { it.id })

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.hasNextPage)
        assertEquals(listOf(1, 2), service.requests.map { it.page })
    }

    @Test
    fun favoriteSourceLoadsDefaultFolderAndRefreshesAfterMutation() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()

        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()

        val request = service.requests.last()
        assertEquals(GlamourListSource.Favorites, request.source)
        assertEquals(7, request.favoriteFolderId)
        assertEquals(16, request.limit)

        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.toggleFavorite(1)
        advanceUntilIdle()

        assertEquals(listOf(1), service.favoritedIds)
        assertTrue(viewModel.state.value.selectedDetail?.isFavorite == true)
        assertEquals(2, viewModel.state.value.selectedDetail?.favorites)
        assertTrue(service.folderFetchCount >= 2)
    }

    @Test
    fun profileAndDetailInteractionsUseAuthorAndUpdateCounters() = runTest {
        val service = FakeGlamourService()
        val author = GlamourAuthor("author-9", "Hero", "World", "DC", null)
        val viewModel = GlamourViewModel(service, author)
        advanceUntilIdle()

        assertEquals(GlamourListSource.Profile, viewModel.state.value.source)
        assertEquals("author-9", service.requests.single().authorId)
        assertEquals(20, service.requests.single().limit)
        assertEquals(GlamourProfileStatistics(4, 5, 6), viewModel.state.value.profileStatistics)
        assertEquals("A profile", viewModel.state.value.authorProfile?.profile)
        assertEquals(12, viewModel.state.value.authorProfile?.followingCount)
        assertTrue(viewModel.state.value.authorProfile?.isFollowing == false)

        viewModel.toggleFollow()
        advanceUntilIdle()

        assertEquals(listOf("author-9"), service.followedAuthors)
        assertTrue(viewModel.state.value.authorProfile?.isFollowing == true)

        viewModel.selectDetail(1)
        advanceUntilIdle()
        viewModel.toggleLike(1)
        advanceUntilIdle()

        assertEquals(listOf(1), service.likedIds)
        assertTrue(viewModel.state.value.selectedDetail?.isLiked == true)
        assertEquals(3, viewModel.state.value.selectedDetail?.likes)
        assertTrue(viewModel.state.value.mutatingIds.isEmpty())
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun refreshFailurePreservesConfirmedContent() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        service.failLists = true

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        assertEquals("list failed", viewModel.state.value.listError)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun refreshFailurePreservesTheLastConfirmedPageForPagination() = runTest {
        val service = FakeGlamourService().apply {
            listHandler = { GlamourListPage(listOf(summary(it.page)), it.page, true) }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        service.failLists = true

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        assertEquals(2, viewModel.state.value.page)
        assertTrue(viewModel.state.value.hasNextPage)
        service.failLists = false
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(3, service.requests.last().page)
        assertEquals(listOf(1, 2, 3), viewModel.state.value.items.map { it.id })
    }

    @Test
    fun changingSourceDiscardsAnOlderPaginationResultEvenWhenItIgnoresCancellation() = runTest {
        val oldPage = CompletableDeferred<GlamourListPage>()
        val service = FakeGlamourService().apply {
            listHandler = { request ->
                when {
                    request.source == GlamourListSource.Profile -> GlamourListPage(listOf(summary(8)), 1, false)
                    request.page == 2 -> withContext(NonCancellable) { oldPage.await() }
                    else -> GlamourListPage(listOf(summary(1)), 1, true)
                }
            }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()

        viewModel.selectSource(GlamourListSource.Profile)
        advanceUntilIdle()
        oldPage.complete(GlamourListPage(listOf(summary(2)), 2, true))
        advanceUntilIdle()

        assertEquals(GlamourListSource.Profile, viewModel.state.value.source)
        assertEquals(listOf(8), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.page)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun refreshCancelsPaginationAndPreventsAnotherPageWhileRefreshing() = runTest {
        val oldPage = CompletableDeferred<GlamourListPage>()
        val refreshedPage = CompletableDeferred<GlamourListPage>()
        var firstPageCalls = 0
        val service = FakeGlamourService().apply {
            listHandler = { request ->
                if (request.page == 2) withContext(NonCancellable) { oldPage.await() }
                else if (++firstPageCalls == 1) GlamourListPage(listOf(summary(1)), 1, true)
                else refreshedPage.await()
            }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()
        viewModel.refresh()
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2, 1), service.requests.map { it.page })
        assertFalse(viewModel.state.value.isLoadingMore)
        assertTrue(viewModel.state.value.isRefreshing)

        oldPage.complete(GlamourListPage(listOf(summary(2)), 2, true))
        refreshedPage.complete(GlamourListPage(listOf(summary(10)), 1, false))
        advanceUntilIdle()
        assertEquals(listOf(10), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.page)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun changingFilterDiscardsAnOlderRefreshResultEvenWhenItIgnoresCancellation() = runTest {
        val oldPage = CompletableDeferred<GlamourListPage>()
        val service = FakeGlamourService().apply {
            listHandler = { request ->
                if (request.filter.raceId == null) withContext(NonCancellable) { oldPage.await() }
                else GlamourListPage(listOf(summary(7)), 1, false)
            }
        }
        val viewModel = GlamourViewModel(service)
        runCurrent()
        viewModel.applyFilter(GlamourFilter(raceId = 7))
        advanceUntilIdle()
        oldPage.complete(GlamourListPage(listOf(summary(1)), 1, true))
        advanceUntilIdle()

        assertEquals(7, viewModel.state.value.filter.raceId)
        assertEquals(listOf(7), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun filterAndSearchFailuresDoNotKeepItemsOrSelectionFromThePreviousQuery() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectDetail(1)
        advanceUntilIdle()
        service.failLists = true

        viewModel.applyFilter(GlamourFilter(raceId = 3))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.selectedId)
        assertNull(viewModel.state.value.selectedDetail)
        assertFalse(viewModel.state.value.hasNextPage)

        service.failLists = false
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectDetail(1)
        advanceUntilIdle()
        service.failLists = true
        viewModel.search(GlamourSearchSelection("new query"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.selectedId)
        assertNull(viewModel.state.value.selectedDetail)
        assertFalse(viewModel.state.value.hasNextPage)
        assertEquals("list failed", viewModel.state.value.listError)
    }

    @Test
    fun reapplyingAnUnchangedQueryPreservesContentWhenRefreshingFails() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        service.failLists = true
        viewModel.applyFilter(GlamourFilter())
        advanceUntilIdle()
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        service.failLists = false
        val search = GlamourSearchSelection("same query")
        viewModel.search(search)
        advanceUntilIdle()
        service.failLists = true
        viewModel.search(search)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
    }

    @Test
    fun failedPaginationPreservesTheConfirmedPageAndCanBeRetried() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        service.failLists = true
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.page)
        assertTrue(viewModel.state.value.hasNextPage)
        assertFalse(viewModel.state.value.isLoadingMore)
        service.failLists = false
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2), service.requests.map { it.page })
        assertEquals(listOf(1, 2, 3), viewModel.state.value.items.map { it.id })
    }

    @Test
    fun duplicatesWithinPagesAreRemovedWithoutStoppingBeforeLaterUniqueResults() = runTest {
        val service = FakeGlamourService().apply {
            listHandler = { request ->
                val ids = when (request.page) {
                    1 -> listOf(1, 1)
                    2 -> listOf(1, 2, 2)
                    3 -> listOf(1, 2)
                    else -> listOf(3)
                }
                GlamourListPage(ids.map(::summary), request.page, request.page < 4)
            }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.state.value.items.map { it.id })
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), service.requests.map { it.page })
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.hasNextPage)
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun authenticationFailureClearsAllCachedAccountContent() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service, GlamourAuthor("author-9", "Hero", "World", "DC", null))
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Favorites)
        advanceUntilIdle()
        viewModel.selectDetail(1)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.folders.isNotEmpty())
        assertTrue(viewModel.state.value.profileStatistics != null)
        assertTrue(viewModel.state.value.authorProfile != null)
        service.listHandler = { throw GlamourException.AuthenticationRequired }

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.items.isEmpty())
        assertTrue(state.folders.isEmpty())
        assertTrue(state.races.isEmpty())
        assertNull(state.selectedFolderId)
        assertNull(state.selectedId)
        assertNull(state.selectedDetail)
        assertNull(state.profileStatistics)
        assertNull(state.authorProfile)
        assertFalse(state.hasNextPage)
        assertFalse(state.isRefreshing)
        assertEquals(GlamourException.AuthenticationRequired.message, state.listError)
    }

    @Test
    fun detailAuthenticationFailurePreventsAPendingListFromRestoringCachedContent() = runTest {
        val delayedPage = CompletableDeferred<GlamourListPage>()
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        service.listHandler = { withContext(NonCancellable) { delayedPage.await() } }
        viewModel.refresh()
        runCurrent()
        service.detailFailure = GlamourException.AuthenticationRequired
        viewModel.selectDetail(1)
        runCurrent()
        delayedPage.complete(GlamourListPage(listOf(summary(3)), 1, true))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.selectedDetail)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(GlamourException.AuthenticationRequired.message, viewModel.state.value.listError)
    }

    @Test
    fun revokedCapabilityClearsCachedContentWithoutRequestingAnotherPage() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        service.hasCommunityIdentity = false
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(1, service.requests.size)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun cancelledListRequestDoesNotBecomeAContentErrorOrLeaveLoadingFlagsSet() = runTest {
        val service = FakeGlamourService().apply {
            listHandler = { throw CancellationException("cancelled") }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        assertNull(viewModel.state.value.listError)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isRefreshing)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun selectingOwnProfileLoadsItsStatisticsWithoutAnAuthorId() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Profile)
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), service.statisticsAuthors)
        assertEquals(GlamourProfileStatistics(4, 5, 6), viewModel.state.value.profileStatistics)
        assertNull(service.requests.last().authorId)
    }

    @Test
    fun detailFailureIsScopedToDetailPane() = runTest {
        val service = FakeGlamourService()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        service.failDetails = true

        viewModel.selectDetail(1)
        advanceUntilIdle()

        assertEquals("detail failed", viewModel.state.value.detailError)
        assertNull(viewModel.state.value.error)
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
    }

    @Test
    fun clearSelectionRemovesCompactDetailState() = runTest {
        val viewModel = GlamourViewModel(FakeGlamourService())
        advanceUntilIdle()
        viewModel.selectDetail(1)
        advanceUntilIdle()

        viewModel.clearSelection()

        assertNull(viewModel.state.value.selectedId)
        assertNull(viewModel.state.value.selectedDetail)
        assertNull(viewModel.state.value.detailError)
        assertFalse(viewModel.state.value.isLoadingDetail)
    }

    @Test
    fun folderCreateFailureKeepsMutationStateForInlineRecovery() = runTest {
        val service = FakeGlamourService().apply { failFolderCreate = true }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()

        viewModel.createFolder("Looks", true)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isCreatingFolder)
        assertEquals("folder failed", viewModel.state.value.folderMutationError)
        assertNull(viewModel.state.value.error)
    }
}

private class FakeGlamourService : GlamourService {
    override var hasCommunityIdentity = true
    val requests = mutableListOf<GlamourListRequest>()
    val favoritedIds = mutableListOf<Int>()
    val likedIds = mutableListOf<Int>()
    val followedAuthors = mutableListOf<String>()
    var isFollowing = false
    var folderFetchCount = 0
    var failLists = false
    var failDetails = false
    var failFolderCreate = false
    var listHandler: (suspend (GlamourListRequest) -> GlamourListPage)? = null
    var detailFailure: Throwable? = null
    val statisticsAuthors = mutableListOf<String?>()

    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        requests += request
        if (failLists) error("list failed")
        listHandler?.let { return it(request) }
        return if (request.page == 1) {
            GlamourListPage(listOf(summary(1), summary(2)), 1, true)
        } else {
            GlamourListPage(listOf(summary(2), summary(3)), 2, false)
        }
    }

    override suspend fun fetchFavoriteFolders(authorId: String?): List<GlamourFavoriteFolder> {
        folderFetchCount += 1
        return listOf(GlamourFavoriteFolder(7, "Default", true, false, 2))
    }

    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) {
        if (failFolderCreate) error("folder failed")
    }
    override suspend fun deleteFavoriteFolder(id: Int) = Unit

    override suspend fun fetchProfileStatistics(authorId: String?): GlamourProfileStatistics {
        statisticsAuthors += authorId
        return GlamourProfileStatistics(4, 5, 6)
    }
    override suspend fun fetchAuthorProfile(authorId: String) = GlamourAuthorProfile(
        author = GlamourAuthor(authorId, "Hero", "World", "DC", null),
        profile = "A profile",
        followingCount = 12,
        followerCount = 34,
        relation = if (isFollowing) 2 else 0,
    )
    override suspend fun followAuthor(authorId: String) {
        followedAuthors += authorId
        isFollowing = true
    }
    override suspend fun cancelFollowAuthor(authorId: String) {
        isFollowing = false
    }
    override suspend fun fetchRaces() = listOf(GlamourRace(1, "Hyur"))
    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> = emptyList()
    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> = emptyList()
    override suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult> = emptyList()

    override suspend fun fetchDetail(id: Int): GlamourDetail {
        detailFailure?.let { throw it }
        if (failDetails) error("detail failed")
        return GlamourDetail(
            id = id,
            title = "Look $id",
            description = "Description",
            imageUrls = emptyList(),
            author = GlamourAuthor("author", "Hero", "World", "DC", null),
            likes = 2,
            favorites = 1,
            isLiked = false,
            isFavorite = false,
            createdAt = Instant.parse("2026-07-21T00:00:00Z"),
            raceNames = emptyList(),
            equipments = emptyList(),
            faceAccessory = null,
            fashionAccessory = null,
        )
    }

    override suspend fun favorite(id: Int) {
        favoritedIds += id
    }

    override suspend fun cancelFavorite(id: Int) = Unit

    override suspend fun toggleLike(id: Int): Boolean {
        likedIds += id
        return true
    }
}

private fun summary(id: Int) = GlamourListingSummary(
    id = id,
    title = "Look $id",
    description = "Description",
    imageUrls = emptyList(),
    author = GlamourAuthor("author", "Hero", "World", "DC", null),
    likes = 2,
    favorites = 1,
    isLiked = false,
    isFavorite = false,
    createdAt = Instant.parse("2026-07-21T00:00:00Z"),
    jobIds = emptyList(),
    raceIds = emptyList(),
    genderIds = emptyList(),
)
