package top.cxmeow.risingstones.feature.message.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.message.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessageAuthorsTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun metadataUsesOneExplicitReadAndSelectionOrReentryDoesNotReadAgain() = runTest {
        val service = AuthorMessageService()
        val model = MessageViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        assertEquals(0, service.reads)
        model.selectCategory(MessageCategory.Comments); advanceUntilIdle()
        assertEquals(MessageAuthorTarget.Community("author-a"), model.authorState.value.authorsByKey["a"])
        model.selectMessage("a"); model.selectMessage("a"); model.ensureLoaded(); advanceUntilIdle()
        assertEquals(1, service.reads)
    }

    @Test fun paginationKeepsFirstDisplayedAuthorAndRefreshReplacesOnlyOnSuccess() = runTest {
        val service = AuthorMessageService()
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.Comments); advanceUntilIdle()
        service.result = { query -> authorPage(query, listOf("a", "b"), mapOf("a" to "wrong", "b" to "author-b")) }
        model.loadMore(); advanceUntilIdle()
        assertEquals(mapOf("a" to MessageAuthorTarget.Community("author-a"), "b" to MessageAuthorTarget.Community("author-b")), model.authorState.value.authorsByKey)
        service.failure = MessageException.Network
        model.refreshMessages(); advanceUntilIdle()
        assertEquals(2, model.authorState.value.authorsByKey.size)
        service.failure = null
        service.result = { query -> authorPage(query, listOf("b"), emptyMap()) }
        model.refreshMessages(); advanceUntilIdle()
        assertEquals(listOf("b"), model.state.value.items.map { it.key })
        assertTrue(model.authorState.value.authorsByKey.isEmpty())
    }

    @Test fun channelSwitchRejectsLateAuthorsAndUsesSelfWithoutResolvingIdentity() = runTest {
        val delayed = CompletableDeferred<Unit>()
        val service = AuthorMessageService().apply {
            result = { query ->
                if (query.commentChannel == MessageCommentChannel.Received) {
                    withContext(NonCancellable) { delayed.await() }
                    authorPage(query, listOf("a"), mapOf("a" to "late"))
                } else MessageAuthorPage(authorPage(query, listOf("a"), emptyMap()).page, mapOf("a" to MessageAuthorTarget.Self))
            }
        }
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.Comments); runCurrent()
        model.selectCommentChannel(MessageCommentChannel.Sent); runCurrent()
        delayed.complete(Unit); advanceUntilIdle()
        assertEquals(mapOf("a" to MessageAuthorTarget.Self), model.authorState.value.authorsByKey)
        assertEquals(2, service.reads)
        model.clearCategory()
        assertTrue(model.authorState.value.authorsByKey.isEmpty())
    }

    @Test fun revokedMessagesClearAuthorsAndLateReadCannotRestoreThem() = runTest {
        val service = AuthorMessageService()
        val model = MessageViewModel(service)
        model.selectCategory(MessageCategory.Comments); advanceUntilIdle()
        val delayed = CompletableDeferred<Unit>()
        service.result = { query -> withContext(NonCancellable) { delayed.await() }; authorPage(query, listOf("a"), mapOf("a" to "late")) }
        model.refreshMessages(); runCurrent()
        model.clearProtectedContent()
        delayed.complete(Unit); advanceUntilIdle()
        assertTrue(model.authorState.value.authorsByKey.isEmpty())
        assertTrue(model.state.value.items.isEmpty())
        service.result = { query -> authorPage(query, listOf("a"), mapOf("a" to "author-a")) }
        model.selectCategory(MessageCategory.Comments); advanceUntilIdle()
        service.failure = MessageException.AuthenticationRequired
        model.refreshMessages(); advanceUntilIdle()
        assertTrue(model.authorState.value.authorsByKey.isEmpty())
    }
}

private class AuthorMessageService : MessageAuthorService {
    override val canRead = true
    var reads = 0
    var failure: MessageException? = null
    var result: suspend (MessageQuery) -> MessageAuthorPage = { authorPage(it, listOf("a"), mapOf("a" to "author-a")) }
    override suspend fun fetchUnreadSummary() = MessageUnreadSummary(0, 0, 0, 0, 0, emptyMap())
    override suspend fun readMessages(query: MessageQuery): MessagePage = error("Rich reads must not duplicate the acknowledging request")
    override suspend fun readMessagesWithAuthors(query: MessageQuery): MessageAuthorPage {
        reads++
        failure?.let { throw it }
        return result(query)
    }
}

private fun authorPage(query: MessageQuery, ids: List<String>, authors: Map<String, String>) = MessageAuthorPage(
    MessagePage(ids.map { CommunityMessage(it, query.category, "Same name", "", "Title", "Content", "", emptyList(), null, null, null, null) }, query.page, true),
    authors.mapValues { MessageAuthorTarget.Community(it.value) },
)
