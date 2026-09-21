package top.cxmeow.risingstones.feature.message.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.message.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessageViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun enteringOnlyReadsSummaryUntilUserChoosesCategory() = runTest {
        val service = Service()
        val model = MessageViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        assertEquals(1, service.summaryReads)
        assertTrue(service.reads.isEmpty())
        model.selectCategory(MessageCategory.System); advanceUntilIdle()
        assertEquals(1, service.reads.size)
        assertEquals(2, service.summaryReads)
        assertEquals(2, model.state.value.unread?.system)
    }

    @Test fun lateOldCategoryCannotPolluteNewMessages() = runTest {
        val service = Service().apply { pending = CompletableDeferred() }
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.System); runCurrent()
        model.selectCategory(MessageCategory.Comments); runCurrent()
        service.pending!!.complete(Unit); advanceUntilIdle()
        assertEquals(MessageCategory.Comments, model.state.value.query?.category)
        assertTrue(model.state.value.items.all { it.category == MessageCategory.Comments })
    }

    @Test fun refreshFailureRetainsMessagesAndSelectionThenRetriesFirstPage() = runTest {
        val service = Service()
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.Comments); advanceUntilIdle()
        model.selectMessage("Comments-1")
        service.failure = MessageException.Network
        model.refreshMessages(); advanceUntilIdle()
        assertEquals("Comments-1", model.state.value.selectedKey)
        assertEquals(1, model.state.value.items.size)
        assertEquals(MessageLoadStatus.Failed, model.state.value.status)
        service.failure = null
        model.refreshMessages(); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(1, 1, 1, 2), service.reads.map { it.page })
        assertEquals(2, model.state.value.items.size)
    }

    @Test fun unavailableGuildCategoryDoesNotDiscardSummaryOrSession() = runTest {
        val service = Service()
        val model = MessageViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        service.failure = MessageException.Business(10401)
        model.selectCategory(MessageCategory.Recruitment); advanceUntilIdle()
        assertEquals(10401, model.state.value.failureCode)
        assertNotNull(model.state.value.unread)
        assertEquals(MessageLoadStatus.Failed, model.state.value.status)
        service.failure = null
        model.selectRecruitmentChannel(MessageRecruitmentChannel.Beginner); advanceUntilIdle()
        assertEquals(MessageRecruitmentChannel.Beginner, service.reads.last().recruitmentChannel)
        assertEquals(MessageLoadStatus.Loaded, model.state.value.status)
    }

    @Test fun authenticationFailureClearsAllProtectedMessages() = runTest {
        val service = Service()
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.System); advanceUntilIdle()
        service.failure = MessageException.AuthenticationRequired
        model.refreshMessages(); advanceUntilIdle()
        assertNull(model.state.value.unread)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.query)
        assertEquals(MessageLoadStatus.AuthenticationRequired, model.state.value.status)
    }

    @Test fun leavingCategoryRejectsLateReadAndDoesNotAutomaticallyReadAnotherCategory() = runTest {
        val service = Service().apply { pending = CompletableDeferred() }
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.System); runCurrent()
        model.clearCategory()
        service.pending!!.complete(Unit); advanceUntilIdle()
        assertNull(model.state.value.query)
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(1, service.reads.size)
    }
}

private class Service : MessageService {
    override val canRead = true
    var summaryReads = 0
    val reads = mutableListOf<MessageQuery>()
    var pending: CompletableDeferred<Unit>? = null
    var failure: MessageException? = null
    override suspend fun fetchUnreadSummary(): MessageUnreadSummary {
        summaryReads++
        return MessageUnreadSummary(2, 0, 0, 0, 0, emptyMap())
    }
    override suspend fun readMessages(query: MessageQuery): MessagePage {
        reads += query
        if (query.category == MessageCategory.System) withContext(NonCancellable) { pending?.await() }
        failure?.let { throw it }
        return MessagePage(listOf(CommunityMessage("${query.category}-${query.page}", query.category,
            "Fixture member", "World", "Fixture title", "Message body", "", emptyList(), null, null, null, null)),
            query.page, query.page == 1)
    }
}
