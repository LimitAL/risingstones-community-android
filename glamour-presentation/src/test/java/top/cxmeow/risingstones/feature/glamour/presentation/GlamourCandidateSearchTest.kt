package top.cxmeow.risingstones.feature.glamour.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessorySearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthorProfile
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourGlassesSearchGroup
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService

@OptIn(ExperimentalCoroutinesApi::class)
class GlamourCandidateSearchTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun searchIsExplicitTrimsInputAndPreservesEquipmentDescriptionAndJobs() = runTest {
        val service = CandidateServiceFake()
        val viewModel = GlamourCandidateSearchViewModel(service)
        advanceUntilIdle()
        assertTrue(service.equipmentCalls.isEmpty())
        assertFalse(viewModel.state.value.hasLoaded)

        viewModel.search(GlamourCandidateKind.Equipment, "  coat \n")
        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()
        assertEquals(listOf("coat" to 1), service.equipmentCalls)
        assertEquals("coat", viewModel.state.value.query)
        assertEquals(
            GlamourCandidate(1, "Equipment 1", "Description 1", jobNames = listOf("Job A", "Job B")),
            viewModel.state.value.items.single(),
        )
        assertTrue(viewModel.state.value.hasLoaded)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun eachCandidateKindBuildsTheCorrectTypedSelectionAndGlassesKeepTheirGroups() = runTest {
        val service = CandidateServiceFake()
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        assertEquals(
            GlamourSearchSelection("1", searchByEquipment = true, displayTitle = "Equipment 1"),
            viewModel.selection(viewModel.state.value.items.single()),
        )

        viewModel.search(GlamourCandidateKind.Glasses, "glasses")
        advanceUntilIdle()
        assertEquals(listOf("glasses"), service.glassesCalls)
        assertEquals(listOf("Round", "Square"), viewModel.state.value.items.map { it.groupName })
        assertEquals(
            GlamourSearchSelection("7", searchByGlasses = true, displayTitle = "Glasses 7"),
            viewModel.selection(viewModel.state.value.items.first()),
        )
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(1, service.glassesCalls.size)

        viewModel.search(GlamourCandidateKind.Ornament, "umbrella")
        advanceUntilIdle()
        assertEquals(listOf("umbrella"), service.ornamentCalls)
        assertEquals(
            GlamourSearchSelection("9", searchByOrnament = true, displayTitle = "Ornament 9"),
            viewModel.selection(viewModel.state.value.items.single()),
        )
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(1, service.ornamentCalls.size)
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun blankQueryCancelsPreviousSearchWithoutSendingAnEmptyRequest() = runTest {
        val pending = CompletableDeferred<List<GlamourEquipmentSearchResult>>()
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, _ -> withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        runCurrent()
        viewModel.search(GlamourCandidateKind.Glasses, " \n\t ")
        pending.complete(listOf(equipment(1)))
        advanceUntilIdle()
        assertEquals(GlamourCandidateSearchUiState(kind = GlamourCandidateKind.Glasses), viewModel.state.value)
        assertTrue(service.glassesCalls.isEmpty())
        assertEquals(1, service.equipmentCalls.size)
    }

    @Test
    fun sameQueryRefreshFailurePreservesAllPagesAndPaginationResumesAtTheConfirmedPage() = runTest {
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, page -> ((page - 1) * 20 + 1..page * 20).map(::equipment) }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        service.failure = IllegalStateException("refresh failed")
        viewModel.search(GlamourCandidateKind.Equipment, "  coat  ")
        assertTrue(viewModel.state.value.isRefreshing)
        advanceUntilIdle()
        assertEquals(40, viewModel.state.value.items.size)
        assertEquals(2, viewModel.state.value.page)
        assertTrue(viewModel.state.value.hasLoaded)
        assertTrue(viewModel.state.value.hasNextPage)
        assertEquals("refresh failed", viewModel.state.value.error)
        service.failure = null
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals("coat" to 3, service.equipmentCalls.last())
        assertEquals(60, viewModel.state.value.items.size)
    }

    @Test
    fun changingQueryClearsPriorContentAndDoesNotReportFailedSearchAsConfirmedEmpty() = runTest {
        val service = CandidateServiceFake()
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        service.failure = IllegalStateException("new query failed")
        viewModel.search(GlamourCandidateKind.Equipment, "hat")
        assertTrue(viewModel.state.value.items.isEmpty())
        advanceUntilIdle()
        assertEquals("hat", viewModel.state.value.query)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertFalse(viewModel.state.value.hasLoaded)
        assertFalse(viewModel.state.value.hasNextPage)
        assertEquals(1, viewModel.state.value.page)
        assertEquals("new query failed", viewModel.state.value.error)
    }

    @Test
    fun delayedSearchCannotOverwriteANewerKindEvenIfTheServiceIgnoresCancellation() = runTest {
        val pending = CompletableDeferred<List<GlamourEquipmentSearchResult>>()
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, _ -> withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "old query")
        runCurrent()
        viewModel.search(GlamourCandidateKind.Glasses, "new query")
        advanceUntilIdle()
        pending.complete(listOf(equipment(1)))
        advanceUntilIdle()
        assertEquals(GlamourCandidateKind.Glasses, viewModel.state.value.kind)
        assertEquals("new query", viewModel.state.value.query)
        assertEquals(listOf(7, 8), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun fullEquipmentPagesAllowPaginationAndDuplicatesDoNotHideLaterUniqueResults() = runTest {
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, page ->
                if (page < 3) List(20) { equipment(1) } else listOf(equipment(2), equipment(2))
            }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.state.value.items.map { it.id })
        assertTrue(viewModel.state.value.hasNextPage)
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), service.equipmentCalls.map { it.second })
        assertEquals(listOf(1), viewModel.state.value.items.map { it.id })
        assertTrue(viewModel.state.value.hasNextPage)
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), viewModel.state.value.items.map { it.id })
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun refreshCancelsOldPaginationAndDisablesMoreUntilTheRefreshCompletes() = runTest {
        val pendingPage = CompletableDeferred<List<GlamourEquipmentSearchResult>>()
        val pendingRefresh = CompletableDeferred<List<GlamourEquipmentSearchResult>>()
        var firstPageCalls = 0
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, page ->
                if (page == 2) withContext(NonCancellable) { pendingPage.await() }
                else if (++firstPageCalls == 1) (1..20).map(::equipment)
                else pendingRefresh.await()
            }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        viewModel.loadMore()
        runCurrent()
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2, 1), service.equipmentCalls.map { it.second })
        assertFalse(viewModel.state.value.isLoadingMore)
        pendingPage.complete(listOf(equipment(21)))
        pendingRefresh.complete(listOf(equipment(100)))
        advanceUntilIdle()
        assertEquals(listOf(100), viewModel.state.value.items.map { it.id })
        assertEquals(1, viewModel.state.value.page)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun failedPaginationKeepsContentAndCanRetryTheSamePage() = runTest {
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, page -> if (page == 1) (1..20).map(::equipment) else listOf(equipment(21)) }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        service.failure = IllegalStateException("page failed")
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(20, viewModel.state.value.items.size)
        assertEquals(1, viewModel.state.value.page)
        assertTrue(viewModel.state.value.hasNextPage)
        assertFalse(viewModel.state.value.isLoadingMore)
        service.failure = null
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2), service.equipmentCalls.map { it.second })
        assertEquals(21, viewModel.state.value.items.size)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun emptySuccessIsDistinguishedFromAnUnsearchedOrFailedQuery() = runTest {
        val service = CandidateServiceFake().apply { equipmentHandler = { _, _ -> emptyList() } }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "nothing")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.isEmpty())
        assertTrue(viewModel.state.value.hasLoaded)
        assertFalse(viewModel.state.value.hasNextPage)
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun selectionRejectsForgedOrStaleCandidates() = runTest {
        val service = CandidateServiceFake()
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        val oldCandidate = viewModel.state.value.items.single()
        assertThrows(IllegalArgumentException::class.java) {
            viewModel.selection(oldCandidate.copy(name = "Different name"))
        }
        viewModel.search(GlamourCandidateKind.Glasses, "glasses")
        advanceUntilIdle()
        assertThrows(IllegalArgumentException::class.java) { viewModel.selection(oldCandidate) }
    }

    @Test
    fun authenticationFailureClearsAllCachedCandidatesAndInput() = runTest {
        val service = CandidateServiceFake()
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Ornament, "umbrella")
        advanceUntilIdle()
        service.failure = GlamourException.AuthenticationRequired
        viewModel.search(GlamourCandidateKind.Ornament, "umbrella")
        advanceUntilIdle()
        assertEquals(
            GlamourCandidateSearchUiState(error = GlamourException.AuthenticationRequired.message),
            viewModel.state.value,
        )
    }

    @Test
    fun revokedIdentityPreventsSelectingCachedCandidatesOrMakingAnotherRequest() = runTest {
        val service = CandidateServiceFake()
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        val candidate = viewModel.state.value.items.single()
        service.hasCommunityIdentity = false
        assertThrows(GlamourException.AuthenticationRequired::class.java) { viewModel.selection(candidate) }
        viewModel.search(GlamourCandidateKind.Equipment, "hat")
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(1, service.equipmentCalls.size)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertEquals(GlamourException.AuthenticationRequired.message, viewModel.state.value.error)
    }

    @Test
    fun resettingOrClearingProtectedContentDiscardsEvenUncooperativePendingSearches() = runTest {
        for (protected in listOf(false, true)) {
            val pending = CompletableDeferred<List<GlamourEquipmentSearchResult>>()
            val service = CandidateServiceFake().apply {
                equipmentHandler = { _, _ -> withContext(NonCancellable) { pending.await() } }
            }
            val viewModel = GlamourCandidateSearchViewModel(service)
            viewModel.search(GlamourCandidateKind.Equipment, "coat")
            runCurrent()
            if (protected) viewModel.clearProtectedContent() else viewModel.resetCancel()
            pending.complete(listOf(equipment(1)))
            advanceUntilIdle()
            assertEquals(
                GlamourCandidateSearchUiState(error = if (protected) GlamourException.AuthenticationRequired.message else null),
                viewModel.state.value,
            )
        }
    }

    @Test
    fun cancellationPropagatesWithoutBecomingAContentErrorOrLeavingLoadingSet() = runTest {
        var requestJob: Job? = null
        val service = CandidateServiceFake().apply {
            equipmentHandler = { _, _ ->
                requestJob = currentCoroutineContext()[Job]
                throw CancellationException("cancelled")
            }
        }
        val viewModel = GlamourCandidateSearchViewModel(service)
        viewModel.search(GlamourCandidateKind.Equipment, "coat")
        advanceUntilIdle()
        assertTrue(requestJob?.isCancelled == true)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isRefreshing)
        assertFalse(viewModel.state.value.isLoadingMore)
        assertFalse(viewModel.state.value.hasLoaded)
    }
}

private class CandidateServiceFake : GlamourService {
    override var hasCommunityIdentity = true
    val equipmentCalls = mutableListOf<Pair<String, Int>>()
    val glassesCalls = mutableListOf<String>()
    val ornamentCalls = mutableListOf<String>()
    var failure: Throwable? = null
    var equipmentHandler: (suspend (String, Int) -> List<GlamourEquipmentSearchResult>)? = null

    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> {
        equipmentCalls += name to page
        failure?.let { throw it }
        return equipmentHandler?.invoke(name, page) ?: listOf(equipment(1))
    }

    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> {
        glassesCalls += name
        failure?.let { throw it }
        return listOf(
            GlamourGlassesSearchGroup(1, "Round", listOf(GlamourAccessorySearchResult(7, "Glasses 7", "Description 7", null))),
            GlamourGlassesSearchGroup(2, "Square", listOf(GlamourAccessorySearchResult(8, "Glasses 8", "Description 8", null))),
        )
    }

    override suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult> {
        ornamentCalls += name
        failure?.let { throw it }
        return listOf(GlamourAccessorySearchResult(9, "Ornament 9", "Description 9", null))
    }

    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage = error("unused")
    override suspend fun fetchFavoriteFolders(authorId: String?): List<GlamourFavoriteFolder> = error("unused")
    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) = error("unused")
    override suspend fun deleteFavoriteFolder(id: Int) = error("unused")
    override suspend fun fetchProfileStatistics(authorId: String?): GlamourProfileStatistics = error("unused")
    override suspend fun fetchAuthorProfile(authorId: String): GlamourAuthorProfile = error("unused")
    override suspend fun followAuthor(authorId: String) = error("unused")
    override suspend fun cancelFollowAuthor(authorId: String) = error("unused")
    override suspend fun fetchRaces(): List<GlamourRace> = error("unused")
    override suspend fun fetchDetail(id: Int): GlamourDetail = error("unused")
    override suspend fun favorite(id: Int) = error("unused")
    override suspend fun cancelFavorite(id: Int) = error("unused")
    override suspend fun toggleLike(id: Int): Boolean = error("unused")
}

private fun equipment(id: Int) = GlamourEquipmentSearchResult(
    id = id,
    name = "Equipment $id",
    description = "Description $id",
    iconId = null,
    jobNames = listOf("Job A", "Job B"),
)
