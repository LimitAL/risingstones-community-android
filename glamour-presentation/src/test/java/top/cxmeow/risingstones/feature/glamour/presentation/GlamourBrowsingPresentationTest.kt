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
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowsePage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowseRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowsingService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourGlassesSearchGroup
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListOrder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTag
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTagCategory
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTribe

@OptIn(ExperimentalCoroutinesApi::class)
class GlamourBrowsingPresentationTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun successfulPagesAdvanceTheCursorAndFailedRefreshPreservesTheConfirmedCursor() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(null, "cursor-1"), service.requests.map { it.pageTime })
        service.listFailure = IllegalStateException("refresh failed")
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(service.requests.last().pageTime)
        assertEquals(2, viewModel.state.value.page)
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        service.listFailure = null
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(3, service.requests.last().listing.page)
        assertEquals("cursor-2", service.requests.last().pageTime)
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(service.requests.last().pageTime)
        assertEquals(1, viewModel.state.value.page)
        assertEquals(0, service.legacyCalls)
    }

    @Test
    fun followingClearsFiltersAndCanReturnToCommunityOrSearch() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11))
        advanceUntilIdle()
        viewModel.selectFollowing()
        advanceUntilIdle()
        val following = service.requests.last()
        assertTrue(following.following)
        assertEquals(GlamourListSource.Community, following.listing.source)
        assertEquals(GlamourFilter(), following.listing.filter)
        assertNull(following.listing.search)
        assertNull(following.tribeId)
        assertTrue(following.tagIds.isEmpty())
        assertNull(following.pageTime)

        viewModel.selectSource(GlamourListSource.Community)
        advanceUntilIdle()
        assertFalse(service.requests.last().following)
        viewModel.selectFollowing()
        advanceUntilIdle()
        viewModel.search(GlamourSearchSelection("hat"))
        advanceUntilIdle()
        assertFalse(viewModel.browsingState.value.following)
        assertFalse(service.requests.last().following)
        assertEquals("hat", service.requests.last().listing.search?.keywords)
        assertNull(service.requests.last().pageTime)
    }

    @Test
    fun catalogEnforcesOneTagPerCategoryAndTribeMatchesTheSelectedRace() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11, 21, 999))
        advanceUntilIdle()
        assertEquals(setOf(11, 21), viewModel.browsingState.value.tagIds)
        assertEquals(101, viewModel.browsingState.value.tribeId)
        assertEquals(GlamourListOrder.Latest, viewModel.state.value.filter.order)

        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11, 12, 21))
        advanceUntilIdle()
        assertEquals(setOf(12, 21), viewModel.browsingState.value.tagIds)
        assertEquals(setOf(12, 21), service.requests.last().tagIds)
        viewModel.applyFilter(viewModel.state.value.filter.copy(raceId = 2))
        advanceUntilIdle()
        assertNull(viewModel.browsingState.value.tribeId)
        assertNull(service.requests.last().tribeId)
        assertEquals(setOf(12, 21), service.requests.last().tagIds)
    }

    @Test
    fun unknownCatalogValuesAreNotAcceptedBeforeTheCatalogSucceeds() = runTest {
        val pendingTags = CompletableDeferred<List<GlamourTagCategory>>()
        val service = BrowsingServiceFake().apply { tagHandler = { pendingTags.await() } }
        val viewModel = GlamourViewModel(service)
        runCurrent()
        assertTrue(viewModel.browsingState.value.isLoadingCatalog)
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11))
        runCurrent()
        assertNull(service.requests.last().tribeId)
        assertTrue(service.requests.last().tagIds.isEmpty())
        pendingTags.complete(service.categories)
        advanceUntilIdle()
        assertTrue(viewModel.browsingState.value.tagIds.isEmpty())
        assertNull(viewModel.browsingState.value.tribeId)
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11))
        advanceUntilIdle()
        assertEquals(setOf(11), service.requests.last().tagIds)
        assertEquals(101, service.requests.last().tribeId)
    }

    @Test
    fun changingOnlyOrderPreservesCommunityBrowsingFiltersAndResetsTheCursor() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11))
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.applyFilter(viewModel.state.value.filter.copy(order = GlamourListOrder.Hottest))
        advanceUntilIdle()
        val request = service.requests.last()
        assertEquals(setOf(11), request.tagIds)
        assertEquals(101, request.tribeId)
        assertEquals(GlamourListOrder.Hottest, request.listing.filter.order)
        assertEquals(1, request.listing.page)
        assertNull(request.pageTime)

        viewModel.selectFollowing()
        advanceUntilIdle()
        viewModel.applyFilter(GlamourFilter(order = GlamourListOrder.Hottest))
        advanceUntilIdle()
        assertFalse(service.requests.last().following)
        assertTrue(service.requests.last().tagIds.isEmpty())
    }

    @Test
    fun profileUsesLatestByDefaultAndSourceChangesClearBrowsingSelections() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11))
        advanceUntilIdle()
        viewModel.selectSource(GlamourListSource.Profile)
        advanceUntilIdle()
        assertEquals(GlamourListOrder.Latest, viewModel.state.value.filter.order)
        assertEquals(GlamourListOrder.Latest, service.requests.last().listing.filter.order)
        assertTrue(service.requests.last().tagIds.isEmpty())
        assertNull(service.requests.last().tribeId)
        viewModel.applyFilter(GlamourFilter())
        advanceUntilIdle()
        assertEquals(GlamourListOrder.Latest, service.requests.last().listing.filter.order)

        val authorService = BrowsingServiceFake()
        GlamourViewModel(authorService, GlamourAuthor("author", "Hero", "World", "DC", null))
        advanceUntilIdle()
        assertEquals(GlamourListOrder.Latest, authorService.requests.single().listing.filter.order)
        assertEquals("author", authorService.requests.single().listing.authorId)
    }

    @Test
    fun authorViewModelDoesNotIssueAnAuthorScopedFollowingRequest() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service, GlamourAuthor("author", "Hero", "World", "DC", null))
        advanceUntilIdle()
        viewModel.selectFollowing()
        advanceUntilIdle()
        assertEquals(1, service.requests.size)
        assertFalse(viewModel.browsingState.value.following)
        assertEquals(GlamourListSource.Profile, viewModel.state.value.source)
    }

    @Test
    fun profileRefreshRetriesMissingStatisticsAndAuthorWithoutDuplicatingInitialLoads() = runTest {
        val service = BrowsingServiceFake().apply {
            statisticsFailure = IllegalStateException("statistics failed")
            authorFailure = IllegalStateException("author failed")
        }
        val viewModel = GlamourViewModel(service, GlamourAuthor("author", "Hero", "World", "DC", null))
        advanceUntilIdle()
        assertEquals(1, service.statisticsCalls)
        assertEquals(1, service.authorCalls)
        assertNull(viewModel.state.value.profileStatistics)
        assertNull(viewModel.state.value.authorProfile)
        assertTrue(viewModel.state.value.profileStatisticsResolved)
        assertTrue(viewModel.state.value.authorProfileResolved)

        service.statisticsFailure = null
        service.authorFailure = null
        viewModel.refresh()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, service.statisticsCalls)
        assertEquals(2, service.authorCalls)
        assertEquals(GlamourProfileStatistics(1, 2, 3), viewModel.state.value.profileStatistics)
        assertEquals("author", viewModel.state.value.authorProfile?.author?.id)
        assertNull(viewModel.state.value.followError)
    }

    @Test
    fun refreshingTheListKeepsConfirmedAuthorAndStatisticsAndFailedAuthorReloadKeepsItsProfile() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service, GlamourAuthor("author", "Hero", "World", "DC", null))
        advanceUntilIdle()
        val profile = viewModel.state.value.authorProfile
        val statistics = viewModel.state.value.profileStatistics
        service.listFailure = IllegalStateException("list failed")
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(profile, viewModel.state.value.authorProfile)
        assertEquals(statistics, viewModel.state.value.profileStatistics)
        assertEquals(1, service.statisticsCalls)
        assertEquals(1, service.authorCalls)

        service.authorFailure = IllegalStateException("author failed")
        viewModel.toggleFollow()
        advanceUntilIdle()
        assertEquals(profile, viewModel.state.value.authorProfile)
        assertEquals("author failed", viewModel.state.value.followError)
        assertFalse(viewModel.state.value.isUpdatingFollow)
    }

    @Test
    fun catalogRefreshFailurePreservesConfirmedChoicesAndRemovedChoicesRefreshTheList() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.applyBrowsingFilter(GlamourFilter(raceId = 1), 101, setOf(11))
        advanceUntilIdle()
        val confirmed = viewModel.browsingState.value
        service.tagHandler = { error("catalog failed") }
        viewModel.loadBrowsingCatalog()
        advanceUntilIdle()
        assertEquals(confirmed.tagCategories, viewModel.browsingState.value.tagCategories)
        assertEquals(confirmed.tagIds, viewModel.browsingState.value.tagIds)
        assertEquals("catalog failed", viewModel.browsingState.value.catalogError)
        assertFalse(viewModel.browsingState.value.isLoadingCatalog)

        service.tagHandler = { emptyList() }
        service.tribes = emptyList()
        viewModel.loadBrowsingCatalog()
        advanceUntilIdle()
        assertTrue(viewModel.browsingState.value.tagIds.isEmpty())
        assertNull(viewModel.browsingState.value.tribeId)
        assertTrue(service.requests.last().tagIds.isEmpty())
        assertNull(service.requests.last().tribeId)
        assertNull(service.requests.last().pageTime)
        assertNull(viewModel.browsingState.value.catalogError)
    }

    @Test
    fun authenticationFailureClearsBrowsingSelectionsCatalogAndCursor() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.selectFollowing()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        service.listFailure = GlamourException.AuthenticationRequired
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(GlamourBrowsingUiState(), viewModel.browsingState.value)
        assertTrue(viewModel.state.value.items.isEmpty())
        service.listFailure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(service.requests.last().following)
        assertNull(service.requests.last().pageTime)
        assertEquals(1, service.requests.last().listing.page)
    }

    @Test
    fun stalePaginationCannotReplaceFollowingContentOrItsCursor() = runTest {
        val stalePage = CompletableDeferred<GlamourBrowsePage>()
        val service = BrowsingServiceFake().apply {
            pageHandler = { request ->
                when {
                    request.following -> browsePage(request.listing.page, "following-${request.listing.page}")
                    request.listing.page == 2 -> withContext(NonCancellable) { stalePage.await() }
                    else -> browsePage(1, "community-1")
                }
            }
        }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()
        viewModel.selectFollowing()
        advanceUntilIdle()
        stalePage.complete(browsePage(2, "stale-community-2"))
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.state.value.items.map { it.id })
        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(service.requests.last().following)
        assertEquals("following-1", service.requests.last().pageTime)
        assertEquals(2, service.requests.last().listing.page)
    }

    @Test
    fun cancellationPreservesConfirmedCursorAndDoesNotBecomeAContentError() = runTest {
        val service = BrowsingServiceFake()
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        service.listFailure = CancellationException("cancelled")
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.state.value.listError)
        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals(2, viewModel.state.value.page)
        service.listFailure = null
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals("cursor-2", service.requests.last().pageTime)
        assertEquals(3, service.requests.last().listing.page)
    }

    @Test
    fun cancelledCatalogDoesNotCommitPartialChoicesAndCanBeRetried() = runTest {
        val service = BrowsingServiceFake().apply { tribeFailure = CancellationException("cancelled") }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        assertTrue(viewModel.browsingState.value.tagCategories.isEmpty())
        assertTrue(viewModel.browsingState.value.tribes.isEmpty())
        assertFalse(viewModel.browsingState.value.isLoadingCatalog)
        assertNull(viewModel.browsingState.value.catalogError)
        service.tribeFailure = null
        viewModel.loadBrowsingCatalog()
        advanceUntilIdle()
        assertEquals(service.categories, viewModel.browsingState.value.tagCategories)
        assertEquals(service.tribes, viewModel.browsingState.value.tribes)
    }

    @Test
    fun retryingTheCatalogRetriesFailedRacesWithoutDuplicatingInitialOrPendingRequests() = runTest {
        val service = BrowsingServiceFake().apply { raceFailure = IllegalStateException("races failed") }
        val viewModel = GlamourViewModel(service)
        advanceUntilIdle()
        assertEquals(1, service.raceCalls)
        assertEquals("races failed", viewModel.state.value.raceError)
        service.raceFailure = null
        viewModel.loadBrowsingCatalog()
        viewModel.loadBrowsingCatalog()
        advanceUntilIdle()
        assertEquals(2, service.raceCalls)
        assertNull(viewModel.state.value.raceError)
        assertEquals(listOf(1, 2), viewModel.state.value.races.map { it.id })
    }

    @Test
    fun authenticationFailurePreventsAnOlderCatalogFromRestoringCachedChoices() = runTest {
        val staleTags = CompletableDeferred<List<GlamourTagCategory>>()
        val service = BrowsingServiceFake().apply {
            tagHandler = { withContext(NonCancellable) { staleTags.await() } }
        }
        val viewModel = GlamourViewModel(service)
        runCurrent()
        service.listFailure = GlamourException.AuthenticationRequired
        viewModel.refresh()
        runCurrent()
        staleTags.complete(service.categories)
        advanceUntilIdle()
        assertEquals(GlamourBrowsingUiState(), viewModel.browsingState.value)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    @Test
    fun legacyServiceKeepsItsOriginalListContractAndDoesNotOfferFollowing() = runTest {
        val service = BrowsingServiceFake()
        val legacy = object : GlamourService by service {}
        val viewModel = GlamourViewModel(legacy)
        advanceUntilIdle()
        assertFalse(viewModel.supportsBrowsing)
        assertEquals(1, service.legacyCalls)
        assertTrue(service.requests.isEmpty())
        viewModel.selectFollowing()
        viewModel.loadBrowsingCatalog()
        advanceUntilIdle()
        assertEquals(1, service.legacyCalls)
        assertEquals(GlamourBrowsingUiState(), viewModel.browsingState.value)
    }
}

private class BrowsingServiceFake : GlamourBrowsingService {
    override val hasCommunityIdentity = true
    val requests = mutableListOf<GlamourBrowseRequest>()
    var legacyCalls = 0
    var listFailure: Throwable? = null
    var tribeFailure: Throwable? = null
    var raceFailure: Throwable? = null
    var raceCalls = 0
    var statisticsFailure: Throwable? = null
    var authorFailure: Throwable? = null
    var statisticsCalls = 0
    var authorCalls = 0
    var pageHandler: (suspend (GlamourBrowseRequest) -> GlamourBrowsePage)? = null
    var tagHandler: (suspend () -> List<GlamourTagCategory>)? = null
    val categories = listOf(
        GlamourTagCategory(1, "Style", listOf(GlamourTag(11, "Casual"), GlamourTag(12, "Formal"))),
        GlamourTagCategory(2, "Scene", listOf(GlamourTag(21, "City"), GlamourTag(22, "Forest"))),
    )
    var tribes = listOf(GlamourTribe(101, 1, "Tribe A"), GlamourTribe(201, 2, "Tribe B"))

    override suspend fun fetchBrowsePage(request: GlamourBrowseRequest): GlamourBrowsePage {
        requests += request
        listFailure?.let { throw it }
        return pageHandler?.invoke(request) ?: browsePage(request.listing.page, "cursor-${request.listing.page}")
    }

    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        legacyCalls++
        return browsePage(request.page, null).page
    }

    override suspend fun fetchTagCategories() = tagHandler?.invoke() ?: categories
    override suspend fun fetchTribes(): List<GlamourTribe> {
        tribeFailure?.let { throw it }
        return tribes
    }
    override suspend fun fetchFavoriteFolders(authorId: String?) =
        listOf(GlamourFavoriteFolder(7, "Default", true, false, 1))
    override suspend fun fetchRaces(): List<GlamourRace> {
        raceCalls++
        raceFailure?.let { throw it }
        return listOf(GlamourRace(1, "Race A"), GlamourRace(2, "Race B"))
    }
    override suspend fun fetchProfileStatistics(authorId: String?): GlamourProfileStatistics {
        statisticsCalls++
        statisticsFailure?.let { throw it }
        return GlamourProfileStatistics(1, 2, 3)
    }
    override suspend fun fetchAuthorProfile(authorId: String): GlamourAuthorProfile {
        authorCalls++
        authorFailure?.let { throw it }
        return GlamourAuthorProfile(GlamourAuthor(authorId, "Hero", "World", "DC", null), null, 0, 0, 0)
    }
    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) = error("unused")
    override suspend fun deleteFavoriteFolder(id: Int) = error("unused")
    override suspend fun followAuthor(authorId: String) = Unit
    override suspend fun cancelFollowAuthor(authorId: String) = Unit
    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> = emptyList()
    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> = emptyList()
    override suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult> = emptyList()
    override suspend fun fetchDetail(id: Int): GlamourDetail = error("unused")
    override suspend fun favorite(id: Int) = error("unused")
    override suspend fun cancelFavorite(id: Int) = error("unused")
    override suspend fun toggleLike(id: Int): Boolean = error("unused")
}

private fun browsePage(page: Int, cursor: String?) = GlamourBrowsePage(
    page = GlamourListPage(
        items = listOf(
            GlamourListingSummary(
                id = page,
                title = "Look $page",
                description = "Description",
                imageUrls = emptyList(),
                author = GlamourAuthor("author", "Hero", "World", "DC", null),
                likes = 0,
                favorites = 0,
                isLiked = false,
                isFavorite = false,
                createdAt = null,
                jobIds = emptyList(),
                raceIds = emptyList(),
                genderIds = emptyList(),
            ),
        ),
        currentPage = page,
        hasNextPage = page < 3,
    ),
    nextPageTime = cursor,
)
