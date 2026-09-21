package top.cxmeow.risingstones.feature.forum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteSelection
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class OfficialForumAuthenticationFailureTest {
    @Test
    fun finalJsonExpiryIsAnAuthenticationErrorAfterAtMostOneRefreshAcrossReadAndWritePaths() {
        for (canRefresh in listOf(false, true)) {
            for (code in listOf(401, 403)) {
                for (operation in operations) {
                    val session = ExpiringSession(canRefresh)
                    val transport = FailureTransport { response("""{"code":$code,"msg":"登录失效"}""") }
                    assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                        runBlocking { operation.run(service(transport, session)) }
                    }
                    assertEquals(1, session.refreshes)
                    assertEquals(if (operation.read && canRefresh) 2 else 1, transport.requests)
                }
            }
        }
    }

    @Test
    fun finalHttpExpiryWithUnavailableOrStillInvalidRefreshUsesTheSameDomainError() {
        for (canRefresh in listOf(false, true)) {
            for (status in listOf(401, 403)) {
                for (operation in operations) {
                    val session = ExpiringSession(canRefresh)
                    val transport = FailureTransport {
                        throw RisingStonesHttpException.ServerResponse(status, byteArrayOf())
                    }
                    assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                        runBlocking { operation.run(service(transport, session)) }
                    }
                    assertEquals(1, session.refreshes)
                    assertEquals(if (operation.read && canRefresh) 2 else 1, transport.requests)
                }
            }
        }
    }

    @Test
    fun httpFailureAfterJsonRefreshDoesNotTriggerASecondRefreshOrReplay() {
        for (operation in operations) {
            val session = ExpiringSession(true)
            val transport = FailureTransport { count ->
                if (count == 1) response("""{"code":401,"msg":"登录失效"}""")
                else throw RisingStonesHttpException.ServerResponse(403, byteArrayOf())
            }
            assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                runBlocking { operation.run(service(transport, session)) }
            }
            assertEquals(1, session.refreshes)
            assertEquals(if (operation.read) 2 else 1, transport.requests)
        }
    }

    @Test
    fun anonymousExpiredReadDoesNotTryToRefreshOrBecomeEmptyContent() {
        val transport = FailureTransport { response("""{"code":401,"msg":"登录失效"}""") }
        assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
            runBlocking { service(transport, null).fetchPostInteraction(42) }
        }
        assertEquals(1, transport.requests)
    }

    @Test
    fun refreshCancellationPropagatesWithoutRetrying() {
        val cancellation = CancellationException("fixture-cancelled")
        val session = ExpiringSession(true).apply { refreshFailure = cancellation }
        val transport = FailureTransport { response("""{"code":401,"msg":"登录失效"}""") }
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { service(transport, session).fetchPostInteraction(42) }
        }
        assertSame(cancellation, error)
        assertEquals(1, transport.requests)
        assertEquals(1, session.refreshes)
    }

    private data class TestOperation(
        val read: Boolean,
        val run: suspend (OfficialForumApiService) -> Unit,
    )

    private val operations = listOf(
        TestOperation(true) { it.fetchPostInteraction(42) },
        TestOperation(false) { it.likePost(42) },
        TestOperation(false) { it.likeComment(9) },
        TestOperation(false) { it.starPost(42) },
        TestOperation(false) { it.submitComment(OfficialForumCommentDraft(42, 0, 0, "Fixture", "")) },
        TestOperation(false) { it.submitVote(OfficialForumVoteDraft(42, listOf(OfficialForumVoteSelection(1, "Option")))) },
        TestOperation(false) { it.deleteComment(9) },
        TestOperation(false) { it.uploadCommentImage(OfficialForumCommentImageUpload(byteArrayOf(1), "image/png")) },
    )

    private fun service(transport: RisingStonesHttpClient, session: RisingStonesSessionProvider?) =
        OfficialForumApiService(RisingStonesPublicApiClient(transport), session)

    private fun response(body: String) = RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())

    private class ExpiringSession(private val canRefresh: Boolean) : RisingStonesSessionProvider {
        override val capabilities = setOf(RisingStonesCapability.ForumWrite, RisingStonesCapability.ForumImageUpload)
        var refreshes = 0
        var refreshFailure: Exception? = null
        override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
            sink.set("User-Agent", "fixture-paired-agent")
        }
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? {
            refreshes++
            refreshFailure?.let { throw it }
            return if (canRefresh) currentAuthorizer() else null
        }
    }

    private class FailureTransport(private val response: (Int) -> RisingStonesHttpResponse) : RisingStonesHttpClient {
        var requests = 0
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse = response(++requests)
    }
}
