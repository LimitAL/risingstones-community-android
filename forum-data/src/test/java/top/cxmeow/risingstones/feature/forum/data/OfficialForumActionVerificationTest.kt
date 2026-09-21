package top.cxmeow.risingstones.feature.forum.data

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.network.*

class OfficialForumActionVerificationTest {
    @Test fun eligibilityIsSeparateFromVerifiedCapabilities() {
        val fixture = Fixture()
        val eligibility: OfficialForumActionEligibilityService = fixture.service
        assertTrue(eligibility.canAttemptAuthenticatedWrites)
        assertTrue(eligibility.canAttemptCommentImageUpload)
        assertFalse(fixture.service.canPerformAuthenticatedWrites)
        assertFalse(fixture.service.canUploadCommentImages)
        fixture.provider.eligible = emptySet()
        assertFalse(eligibility.canAttemptAuthenticatedWrites)
        assertFalse(eligibility.canAttemptCommentImageUpload)
    }

    @Test fun everyExplicitForumWriteUsesOneBoundAttemptAndCompletesAfterValidatedPayload() = runBlocking {
        val cases: List<Pair<String, suspend (OfficialForumApiService) -> Unit>> = listOf(
            "api/home/posts/like" to { assertEquals(1, it.likePost(42)) },
            "api/home/posts/like" to { assertEquals(1, it.likeComment(9)) },
            "api/home/posts/star" to { assertEquals(-1, it.starPost(42)) },
            "api/home/posts/comment" to { assertEquals(listOf(31), it.submitComment(OfficialForumCommentDraft(42, 0, 0, "Fixture", ""))) },
            "api/home/posts/vote" to { assertEquals(5, it.submitVote(OfficialForumVoteDraft(42, listOf(OfficialForumVoteSelection(2, "B")))).voteDetails[2]) },
            "api/home/posts/deleteComment" to { it.deleteComment(31) },
        )
        for ((path, operation) in cases) {
            val fixture = Fixture()
            operation(fixture.service)
            assertEquals(1, fixture.requests.size)
            assertEquals(path, fixture.provider.context?.path)
            assertEquals(RisingStonesCapability.ForumWrite, fixture.provider.context?.capability)
            assertEquals(1, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
            assertEquals(0, fixture.provider.refreshes)
        }
    }

    @Test fun missingOrInvalidEndpointPayloadNeverCompletesAndNeverReplays() {
        val cases: List<Pair<String, suspend (OfficialForumApiService) -> Unit>> = listOf(
            "api/home/posts/like" to { it.likePost(42) },
            "api/home/posts/star" to { it.starPost(42) },
            "api/home/posts/comment" to { it.submitComment(OfficialForumCommentDraft(42, 0, 0, "Fixture", "")) },
            "api/home/posts/vote" to { it.submitVote(OfficialForumVoteDraft(42, listOf(OfficialForumVoteSelection(2, "B")))) },
        )
        for ((path, operation) in cases) {
            val fixture = Fixture().apply { bodies[path] = """{"code":10000,"data":null}""" }
            assertThrows(OfficialForumException.MissingPayload::class.java) { runBlocking { operation(fixture.service) } }
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
        }
        for ((path, body, operation) in listOf<Triple<String, String, suspend (OfficialForumApiService) -> Unit>>(
            Triple("api/home/posts/like", """{"code":10000,"data":0}""") { it.likePost(42) },
            Triple("api/home/posts/star", """{"code":10000,"data":2}""") { it.starPost(42) },
            Triple("api/home/posts/comment", """{"code":10000,"data":[]}""") {
                it.submitComment(OfficialForumCommentDraft(42, 0, 0, "Fixture", ""))
            },
            Triple("api/home/posts/comment", """{"code":10000,"data":[0]}""") {
                it.submitComment(OfficialForumCommentDraft(42, 0, 0, "Fixture", ""))
            },
            Triple("api/home/posts/vote", """{"code":10000,"data":{"voteTotalUser":7}}""") {
                it.submitVote(OfficialForumVoteDraft(42, listOf(OfficialForumVoteSelection(2, "B"))))
            },
        )) {
            val fixture = Fixture().apply { bodies[path] = body }
            assertThrows(OfficialForumException.MissingPayload::class.java) { runBlocking { operation(fixture.service) } }
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
        }
        for ((body, expectedRefreshes) in listOf(
            """{"code":401}""" to 1,
            """{"code":10105}""" to 0,
        )) {
            val fixture = Fixture().apply { bodies["api/home/posts/like"] = body }
            assertThrows(OfficialForumException::class.java) { runBlocking { fixture.service.likePost(42) } }
            assertEquals(1, fixture.requests.size)
            assertEquals(expectedRefreshes, fixture.provider.refreshes)
            assertEquals(0, fixture.provider.completed)
        }
    }

    @Test fun voteAcceptsOfficialCamelCaseAndTreatsMissingCountsOrOptionsAsZero() = runBlocking {
        val fixture = Fixture().apply {
            bodies["api/home/posts/vote"] =
                """{"code":10000,"data":{"voteTotalUser":"7","voteDetails":[{"option_id":"1"}]}}"""
        }
        val result = fixture.service.submitVote(
            OfficialForumVoteDraft(42, listOf(OfficialForumVoteSelection(2, "B"))),
        )
        assertEquals(7, result.voteTotalUser)
        assertEquals(mapOf(1 to 0), result.voteDetails)
        assertEquals(1, fixture.provider.completed)
    }

    @Test fun cancelledOrLateCompletionCannotGrantAnotherCredential() {
        val late = Fixture().apply { provider.current = false }
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { runBlocking { late.service.likePost(42) } }.exceptionOrNull())
        assertEquals(1, late.requests.size)
        assertEquals(0, late.provider.completed)
        assertEquals(1, late.provider.closed)

        val cancellation = CancellationException("fixture cancellation")
        val cancelled = Fixture().apply { failure = cancellation }
        assertSame(cancellation, runCatching { runBlocking { cancelled.service.starPost(42) } }.exceptionOrNull())
        assertEquals(1, cancelled.requests.size)
        assertEquals(0, cancelled.provider.completed)
        assertEquals(1, cancelled.provider.closed)
    }

    @Test fun explicitAuthenticationFailureRefreshesReadStateOnceWithoutReplayingWrite() {
        for (failure in listOf("json", "status", "http")) {
            val fixture = Fixture()
            when (failure) {
                "json" -> fixture.bodies["api/home/posts/like"] = """{"code":401}"""
                "status" -> fixture.status = { 401 }
                else -> fixture.failure = RisingStonesHttpException.ServerResponse(403, byteArrayOf())
            }
            assertSame(OfficialForumException.AuthenticationRequired,
                runCatching { runBlocking { fixture.service.likePost(42) } }.exceptionOrNull())
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.provider.refreshes)
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
        }
    }

    @Test fun refreshFailureIsBestEffortButCancellationStillPropagates() {
        val ioFailure = Fixture().apply {
            bodies["api/home/posts/like"] = """{"code":401}"""
            provider.refreshFailure = IOException("synthetic refresh failure")
        }
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { runBlocking { ioFailure.service.likePost(42) } }.exceptionOrNull())
        assertEquals(1, ioFailure.requests.size)
        assertEquals(1, ioFailure.provider.refreshes)

        val cancellation = CancellationException("synthetic refresh cancellation")
        val cancelled = Fixture().apply {
            bodies["api/home/posts/like"] = """{"code":401}"""
            provider.refreshFailure = cancellation
        }
        assertSame(cancellation,
            runCatching { runBlocking { cancelled.service.likePost(42) } }.exceptionOrNull())
        assertEquals(1, cancelled.requests.size)
        assertEquals(1, cancelled.provider.refreshes)
        assertEquals(1, cancelled.provider.closed)
    }

    @Test fun imageAttemptMustStillBeCurrentBeforePutAndCompletesOnlyAfterPut() = runBlocking {
        val revoked = Fixture().apply { revokeAfterToken = true }
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { runBlocking { revoked.service.uploadCommentImage(image()) } }.exceptionOrNull())
        assertEquals(listOf(RisingStonesHttpMethod.Get), revoked.requests.map { it.method })
        assertEquals(0, revoked.provider.completed)
        assertEquals(1, revoked.provider.closed)

        val success = Fixture()
        val url = success.service.uploadCommentImage(image())
        assertTrue(url.startsWith("https://ff14risingstones.gcloud.com.cn/default/fixture/"))
        assertEquals(listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put), success.requests.map { it.method })
        assertEquals("api/common/getCOSTokenI", success.provider.context?.path)
        assertEquals(RisingStonesCapability.ForumImageUpload, success.provider.context?.capability)
        assertEquals(1, success.provider.guardChecks)
        assertEquals(1, success.provider.completed)
        assertEquals(1, success.provider.closed)
        assertFalse(success.requests.last().headers.containsKey("Cookie"))
        assertFalse(success.requests.last().headers.containsKey("User-Agent"))
    }

    @Test fun imageAttemptWithoutGuardFailsClosedBeforePut() {
        val provider = UnguardedProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = OfficialForumApiService(
            RisingStonesPublicApiClient(
                recordingClient(requests, body = { tokenBody() }),
                listOf("https://fixture.invalid"),
            ),
            provider,
        )
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { runBlocking { service.uploadCommentImage(image()) } }.exceptionOrNull())
        assertEquals(listOf(RisingStonesHttpMethod.Get), requests.map { it.method })
        assertEquals(1, provider.closed)
    }

    @Test fun explicitTokenAndCosAuthenticationFailuresRefreshOnceWithoutReplayOrGrant() {
        val tokenFailure = Fixture().apply { status = { request -> if (request.method == RisingStonesHttpMethod.Get) 401 else 200 } }
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { runBlocking { tokenFailure.service.uploadCommentImage(image()) } }.exceptionOrNull())
        assertEquals(listOf(RisingStonesHttpMethod.Get), tokenFailure.requests.map { it.method })
        assertEquals(1, tokenFailure.provider.refreshes)
        assertEquals(0, tokenFailure.provider.completed)

        val cosFailure = Fixture().apply { status = { request -> if (request.method == RisingStonesHttpMethod.Put) 403 else 200 } }
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { runBlocking { cosFailure.service.uploadCommentImage(image()) } }.exceptionOrNull())
        assertEquals(listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put), cosFailure.requests.map { it.method })
        assertEquals(1, cosFailure.provider.refreshes)
        assertEquals(0, cosFailure.provider.completed)
        assertEquals(1, cosFailure.provider.closed)
    }

    @Test fun unknownWriteFailureDoesNotRefreshOrReplay() {
        val fixture = Fixture().apply { failure = IOException("synthetic unknown outcome") }
        val error = runCatching { runBlocking { fixture.service.likePost(42) } }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error is OfficialForumException.AuthenticationRequired)
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.provider.refreshes)
        assertEquals(0, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
    }

    private class Fixture {
        val provider = AttemptProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val bodies = mutableMapOf(
            "api/home/posts/like" to """{"code":10000,"data":1}""",
            "api/home/posts/star" to """{"code":10000,"data":-1}""",
            "api/home/posts/comment" to """{"code":10000,"data":[31]}""",
            "api/home/posts/vote" to """{"code":10000,"data":{"vote_total_user":7,"vote_details":[{"option_id":2,"total_vote_num":5}]}}""",
            "api/home/posts/deleteComment" to """{"code":10000,"msg":"Synthetic success","data":null}""",
        )
        var failure: Exception? = null
        var revokeAfterToken = false
        var status: (RisingStonesHttpRequest) -> Int = { 200 }
        val service = OfficialForumApiService(
            RisingStonesPublicApiClient(
                recordingClient(
                    requests = requests,
                    body = { request ->
                        failure?.let { throw it }
                        if (request.method == RisingStonesHttpMethod.Put) {
                            ""
                        } else if (request.url.contains("getCOSTokenI")) {
                            tokenBody().also { if (revokeAfterToken) provider.current = false }
                        } else {
                            bodies.entries.first { request.url.contains(it.key) }.value
                        }
                    },
                    status = { request -> status(request) },
                ),
                listOf("https://fixture.invalid"),
            ),
            provider,
        )
    }

    private class AttemptProvider : RisingStonesSessionProvider, RisingStonesExplicitCapabilityProvider {
        override val capabilities = emptySet<RisingStonesCapability>()
        var eligible = setOf(RisingStonesCapability.ForumWrite, RisingStonesCapability.ForumImageUpload)
        var current = true
        var completed = 0
        var closed = 0
        var refreshes = 0
        var refreshFailure: Exception? = null
        var guardChecks = 0
        var context: RisingStonesRequestContext? = null
        override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = null
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? {
            refreshes++
            refreshFailure?.let { throw it }
            return null
        }
        override fun canAttemptCapability(capability: RisingStonesCapability) = capability in eligible
        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? {
            if (!canAttemptCapability(context.capability ?: return null)) return null
            this.context = context
            return object : RisingStonesCapabilityAttempt, RisingStonesCapabilityAttemptGuard {
                private var closedAttempt = false
                override val authorizer = RisingStonesRequestAuthorizer { actual, sink ->
                    assertEquals(context, actual)
                    sink.set("User-Agent", "SyntheticForum/1")
                }
                override suspend fun isCurrent(): Boolean { guardChecks++; return current && !closedAttempt }
                override suspend fun complete(): Boolean {
                    if (!current || closedAttempt) return false
                    completed++
                    return true
                }
                override fun close() { if (!closedAttempt) { closedAttempt = true; closed++ } }
            }
        }
    }

    private class UnguardedProvider : RisingStonesSessionProvider, RisingStonesExplicitCapabilityProvider {
        override val capabilities = emptySet<RisingStonesCapability>()
        var closed = 0
        override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = null
        override fun canAttemptCapability(capability: RisingStonesCapability) = true
        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext) = object : RisingStonesCapabilityAttempt {
            override val authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("User-Agent", "SyntheticForum/1") }
            override suspend fun complete() = true
            override fun close() { closed++ }
        }
    }

    private companion object {
        fun image() = OfficialForumCommentImageUpload(byteArrayOf(1, 2), "image/png")
        fun tokenBody(): String {
            val now = Instant.now().epochSecond
            return """{"code":10000,"data":{"credentials":{"sessionToken":"fixture-token","tmpSecretId":"fixture-id","tmpSecretKey":"fixture-key"},"startTime":${now - 5},"expiredTime":${now + 60},"keyDir":"default/fixture"}}"""
        }
        fun recordingClient(
            requests: MutableList<RisingStonesHttpRequest>,
            body: (RisingStonesHttpRequest) -> String,
            status: (RisingStonesHttpRequest) -> Int = { 200 },
        ) = object : RisingStonesHttpClient {
            override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                requests += request
                return RisingStonesHttpResponse(status(request), emptyMap(), body(request).encodeToByteArray())
            }
        }
    }
}
