package top.cxmeow.risingstones.feature.glamour.presentation

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessorySearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
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
    override val hasCommunityIdentity = true
    val requests = mutableListOf<GlamourListRequest>()
    val favoritedIds = mutableListOf<Int>()
    val likedIds = mutableListOf<Int>()
    var folderFetchCount = 0
    var failLists = false
    var failDetails = false
    var failFolderCreate = false

    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        if (failLists) error("list failed")
        requests += request
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

    override suspend fun fetchProfileStatistics(authorId: String?) = GlamourProfileStatistics(4, 5, 6)
    override suspend fun fetchRaces() = listOf(GlamourRace(1, "Hyur"))
    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> = emptyList()
    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> = emptyList()
    override suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult> = emptyList()

    override suspend fun fetchDetail(id: Int): GlamourDetail {
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
