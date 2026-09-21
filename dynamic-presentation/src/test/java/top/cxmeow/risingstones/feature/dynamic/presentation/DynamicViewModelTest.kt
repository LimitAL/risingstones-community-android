package top.cxmeow.risingstones.feature.dynamic.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun refreshRetainsContentAndPaginationRetriesWithSameCursor() = runTest {
        val service = Service()
        val model = DynamicViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        service.failFeed = true
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf(1), model.state.value.items.map { it.id })
        assertEquals(DynamicLoadStatus.Failed, model.state.value.status)
        service.failFeed = false
        model.refresh(); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle()
        assertEquals("cursor", service.queries.last().pageTime)
        assertEquals(listOf(1, 2), model.state.value.items.map { it.id })
    }

    @Test
    fun selectingNewDetailRejectsLateOldResponse() = runTest {
        val service = Service().apply { firstDetail = CompletableDeferred() }
        val model = DynamicViewModel(service)
        model.select(1); runCurrent()
        model.select(2); runCurrent()
        service.firstDetail!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.detail?.id)
        assertEquals(listOf(2), model.state.value.comments.map { it.id })
    }

    @Test
    fun revokedSessionClearsEveryProtectedSurface() = runTest {
        val service = Service()
        val model = DynamicViewModel(service)
        model.ensureLoaded(); model.select(1); advanceUntilIdle()
        model.loadReplies(1); advanceUntilIdle()
        service.authFailure = true
        model.refresh(); advanceUntilIdle()
        assertEquals(DynamicLoadStatus.AuthenticationRequired, model.state.value.status)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.detail)
        assertTrue(model.state.value.comments.isEmpty())
        assertTrue(model.state.value.replies.isEmpty())
    }

    @Test
    fun failedCommentRefreshRetainsContentAndCanRetryFirstPage() = runTest {
        val service = Service()
        val model = DynamicViewModel(service)
        model.select(1); advanceUntilIdle()
        service.failComments = true
        model.refreshDetail(); advanceUntilIdle()
        assertEquals(listOf(1), model.state.value.comments.map { it.id })
        assertEquals(DynamicLoadStatus.Failed, model.state.value.commentsStatus)
        service.failComments = false
        model.loadMoreComments(); advanceUntilIdle()
        assertEquals(1, service.commentPages.last())
        assertEquals(DynamicLoadStatus.Loaded, model.state.value.commentsStatus)
    }

    @Test
    fun clearingSessionRejectsNonCancellableLateFeed() = runTest {
        val service = Service().apply { delayedFeed = CompletableDeferred() }
        val model = DynamicViewModel(service)
        model.ensureLoaded(); runCurrent()
        model.clearProtectedContent()
        service.delayedFeed!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(DynamicLoadStatus.Idle, model.state.value.status)
    }

    @Test
    fun linkedDynamicReturnsToItsSourceAndNewListSelectionResetsHistory() = runTest {
        val model = DynamicViewModel(Service())
        model.select(1); advanceUntilIdle()
        model.openLinkedDynamic(2); advanceUntilIdle()
        assertTrue(model.state.value.canNavigateBackInDetail)
        assertEquals(2, model.state.value.detail?.id)
        model.clearSelection(); advanceUntilIdle()
        assertFalse(model.state.value.canNavigateBackInDetail)
        assertEquals(1, model.state.value.detail?.id)
        model.openLinkedDynamic(2); advanceUntilIdle()
        model.select(3); advanceUntilIdle()
        model.clearSelection()
        assertNull(model.state.value.selectedId)
        assertFalse(model.state.value.canNavigateBackInDetail)
    }

    @Test
    fun successfulCommentRefreshDiscardsStaleReplies() = runTest {
        val model = DynamicViewModel(Service())
        model.select(1); advanceUntilIdle()
        model.loadReplies(1); advanceUntilIdle()
        assertTrue(model.state.value.replies.isNotEmpty())
        model.refreshDetail(); advanceUntilIdle()
        assertTrue(model.state.value.replies.isEmpty())
    }
}

private class Service : DynamicService {
    override val canRead = true
    var failFeed = false
    var failComments = false
    var authFailure = false
    var firstDetail: CompletableDeferred<Unit>? = null
    var delayedFeed: CompletableDeferred<Unit>? = null
    val queries = mutableListOf<DynamicListQuery>()
    val commentPages = mutableListOf<Int>()
    override suspend fun fetchFeed(query: DynamicListQuery): DynamicPage<DynamicEntry> {
        queries += query
        withContext(NonCancellable) { delayedFeed?.await() }
        if (authFailure) throw DynamicException.AuthenticationRequired
        if (failFeed) throw DynamicException.Network
        return DynamicPage(listOf(entry(query.page)), query.page, query.page == 1, "cursor")
    }
    override suspend fun fetchDetail(id: Int): DynamicEntry {
        if (id == 1) withContext(NonCancellable) { firstDetail?.await() }
        return entry(id)
    }
    override suspend fun fetchComments(id: Int, query: DynamicListQuery): DynamicPage<DynamicComment> {
        commentPages += query.page
        if (failComments) throw DynamicException.Network
        return DynamicPage(listOf(comment(id)), query.page, false)
    }
    override suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery) =
        DynamicPage(listOf(comment(100)), query.page, false)
}
private val Author = DynamicAuthor("fixture", "Member", "Area", "World", null)
private fun entry(id: Int) = DynamicEntry(id, Author, "<p>Activity $id</p>", emptyList(), null, 1, 0, false, null)
private fun comment(id: Int) = DynamicComment(id, Author, "Reply", emptyList(), null, null, 0, 1)
