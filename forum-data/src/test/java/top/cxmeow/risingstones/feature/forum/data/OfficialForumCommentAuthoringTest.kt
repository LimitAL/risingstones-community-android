package top.cxmeow.risingstones.feature.forum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentAuthoringService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentMention
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictHandler
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictState
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumMentionCandidate
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import java.net.URLDecoder

class OfficialForumCommentAuthoringTest {
    @Test
    fun candidatesUseOneRequiredAccountReadAndRetainOnlyExplicitIdentities() = runBlocking {
        for (code in listOf(10000, 10002)) {
            val transport = RecordingTransport {
                response("""{"code":$code,"msg":"未登录","data":{"rows":[
                    {"uuid":"fixture-one","character_name":"示例 A","avatar":"https://static.web.sdo.com/fixture.png","area_name":"区 A","group_name":"服 B"},
                    {"uuid":"fixture-two","character_name":"第二位","avatar":"javascript:invalid","area_name":null,"group_name":4},
                    {"uuid":"","character_name":"Invalid"},
                    {"uuid":"   ","character_name":"Invalid"},
                    {"uuid":42,"character_name":"Invalid"},
                    {"uuid":"fixture-three","character_name":" "},
                    {"uuid":"fixture-four","character_name":8},
                    {"uuid":"fixture#invalid","character_name":"Invalid"},
                    {"uuid":"fixture-five","character_name":"Invalid#name"},
                    {"uuid":"fixture-six","character_name":"Invalid\nname"},
                    {"uuid":"fixture-seven","character_name":"Invalid\u0000name"},
                    {"character_name":"Missing UUID"},
                    null, []
                ]}}""")
            }
            val session = AuthoringSession(setOf(RisingStonesCapability.AccountRead))
            val service: OfficialForumCommentAuthoringService = service(transport, session)
            assertTrue(service.canReadMentionCandidates)
            assertFalse(service.canPerformAuthenticatedWrites)
            assertEquals(
                listOf(
                    OfficialForumMentionCandidate("fixture-one", "示例 A", "https://static.web.sdo.com/fixture.png", "区 A", "服 B"),
                    OfficialForumMentionCandidate("fixture-two", "第二位"),
                ),
                service.fetchMentionCandidates(),
            )
            val request = transport.requests.single()
            assertEquals(RisingStonesHttpMethod.Get, request.method)
            assertEquals("/api/home/userRelation/followList", request.url.toHttpUrl().encodedPath)
            assertEquals("page=1&limit=5000", request.url.toHttpUrl().encodedQuery)
            assertNull(request.body)
            assertEquals(listOf(RisingStonesRequestContext(
                "api/home/userRelation/followList", RisingStonesAuthenticationRequirement.Required,
                RisingStonesCapability.AccountRead,
            )), session.contexts)
            assertEquals(0, session.refreshes)
            assertEquals(0, session.conflicts)
            assertEquals(setOf(RisingStonesCapability.AccountRead), session.capabilities)
        }
    }

    @Test
    fun confirmedEmptyRowsDifferFromMissingOrMalformedPayloads() {
        for (body in listOf(
            """{"code":10000}""",
            """{"code":10002,"data":null}""",
            """{"code":10000,"data":[]}""",
            """{"code":10000,"data":{}}""",
            """{"code":10000,"data":{"rows":null}}""",
            """{"code":10000,"data":{"rows":{}}}""",
            "<html>fixture challenge</html>",
        )) {
            val transport = RecordingTransport { response(body) }
            val session = AuthoringSession()
            assertThrows(OfficialForumException.MissingPayload::class.java) {
                runBlocking { service(transport, session).fetchMentionCandidates() }
            }
            assertEquals(1, transport.requests.size)
            assertEquals(0, session.refreshes)
        }
        runBlocking {
            val transport = RecordingTransport { response(EmptyCandidates) }
            assertEquals(emptyList<OfficialForumMentionCandidate>(), service(transport, AuthoringSession()).fetchMentionCandidates())
        }
    }

    @Test
    fun missingReadCapabilityOrAuthorizerNeverSendsAnAnonymousCandidateRequest() {
        val noCapability = AuthoringSession(setOf(RisingStonesCapability.ForumWrite))
        val noAuthorizer = AuthoringSession().apply { hasAuthorizer = false }
        val emptyAuthorizer = AuthoringSession().apply { supplyHeaders = false }
        for (session in listOf(null, noCapability, noAuthorizer, emptyAuthorizer)) {
            val transport = RecordingTransport { response(EmptyCandidates) }
            assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                runBlocking { service(transport, session).fetchMentionCandidates() }
            }
            assertTrue(transport.requests.isEmpty())
            assertEquals(0, session?.refreshes ?: 0)
        }
        assertEquals(0, noCapability.currentReads)
    }

    @Test
    fun readCapabilityIsRecheckedAfterObtainingAndApplyingAuthorizationAndAfterAResponse() {
        for (stage in listOf("current", "authorize", "response")) {
            val session = AuthoringSession()
            val revoke: suspend () -> Unit = { session.capabilities = emptySet() }
            when (stage) {
                "current" -> session.onCurrent = revoke
                "authorize" -> session.onAuthorize = revoke
            }
            val transport = RecordingTransport {
                if (stage == "response") revoke()
                response(EmptyCandidates)
            }
            val service = service(transport, session)
            assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                runBlocking { service.fetchMentionCandidates() }
            }
            assertEquals(if (stage == "response") 1 else 0, transport.requests.size)
            assertEquals(0, session.refreshes)
            assertFalse(service.canReadMentionCandidates)
        }
    }

    @Test
    fun refreshedOrReclaimedAuthorizerCannotRestoreARevokedReadCapability() {
        for (identityConflict in listOf(false, true)) {
            val session = AuthoringSession()
            val revoke: suspend () -> Unit = { session.capabilities = emptySet() }
            if (identityConflict) session.onConflict = revoke else session.onRefresh = revoke
            val transport = RecordingTransport {
                response(if (identityConflict) """{"code":10105}""" else """{"code":401}""")
            }
            assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                runBlocking { service(transport, session).fetchMentionCandidates() }
            }
            assertEquals(1, transport.requests.size)
            assertEquals(if (identityConflict) 0 else 1, session.refreshes)
            assertEquals(if (identityConflict) 1 else 0, session.conflicts)
        }
    }

    @Test
    fun candidateAuthenticationRecoveryOccursOnlyOnceAndTerminalFailuresUseDomainErrors() {
        for (failure in listOf("json", "http", "status")) {
            for (canRefresh in listOf(false, true)) {
                val session = AuthoringSession().apply { hasRefresh = canRefresh }
                val transport = RecordingTransport {
                    when (failure) {
                        "http" -> throw RisingStonesHttpException.ServerResponse(401, byteArrayOf())
                        "status" -> response("{}", 403)
                        else -> response("""{"code":401,"msg":"登录失效"}""")
                    }
                }
                assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                    runBlocking { service(transport, session).fetchMentionCandidates() }
                }
                assertEquals(1, session.refreshes)
                assertEquals(if (canRefresh) 2 else 1, transport.requests.size)
            }
        }
    }

    @Test
    fun candidateRecoveryAcceptsValidPayloadAndKeepsIdentityConflictSeparateFromExpiry() = runBlocking {
        for (identityConflict in listOf(false, true)) {
            val session = AuthoringSession()
            val transport = RecordingTransport { count ->
                response(if (count == 1) {
                    if (identityConflict) """{"code":10105,"msg":"未登录"}""" else """{"code":401}"""
                } else EmptyCandidates)
            }
            assertTrue(service(transport, session).fetchMentionCandidates().isEmpty())
            assertEquals(2, transport.requests.size)
            assertEquals(if (identityConflict) 0 else 1, session.refreshes)
            assertEquals(if (identityConflict) 1 else 0, session.conflicts)
            assertTrue(session.contexts.all { it.capability == RisingStonesCapability.AccountRead })
        }
        for (canResolve in listOf(false, true)) {
            val session = AuthoringSession().apply { hasConflictResolution = canResolve }
            val transport = RecordingTransport { response("""{"code":10105,"msg":"未登录"}""") }
            assertThrows(OfficialForumException.IdentityConflict::class.java) {
                runBlocking { service(transport, session).fetchMentionCandidates() }
            }
            assertEquals(0, session.refreshes)
            assertEquals(1, session.conflicts)
            assertEquals(if (canResolve) 2 else 1, transport.requests.size)
        }
    }

    @Test
    fun candidateCancellationDuringTransportOrRecoveryIsPropagatedWithoutReplay() {
        for (stage in listOf("transport", "refresh", "conflict", "response")) {
            val cancellation = CancellationException("fixture cancellation")
            val session = AuthoringSession()
            if (stage == "refresh") session.onRefresh = { throw cancellation }
            if (stage == "conflict") session.onConflict = { throw cancellation }
            val transport = RecordingTransport {
                when (stage) {
                    "transport" -> throw cancellation
                    "response" -> {
                        currentCoroutineContext().cancel(cancellation)
                        response(EmptyCandidates)
                    }
                    "conflict" -> response("""{"code":10105}""")
                    else -> response("""{"code":401}""")
                }
            }
            val caught = assertThrows(CancellationException::class.java) {
                runBlocking { service(transport, session).fetchMentionCandidates() }
            }
            assertSame(cancellation, caught)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun mentionsUseUtf8IndexedFormFieldsAndDeduplicateOnlyIdenticalUuidAndNamePairs() = runBlocking {
        val session = AuthoringSession(setOf(RisingStonesCapability.ForumWrite))
        val transport = RecordingTransport { response("""{"code":10002,"msg":"未登录","data":[31]}""") }
        val result = service(transport, session).submitCommentWithMentions(
            Draft,
            listOf(
                OfficialForumCommentMention("fixture+one&", "测试 A&B"),
                OfficialForumCommentMention("fixture+one&", "测试 A&B"),
                OfficialForumCommentMention("fixture+one&", "另一个名字"),
            ),
        )
        assertEquals(listOf(31), result)
        val request = transport.requests.single()
        assertEquals(RisingStonesHttpMethod.Post, request.method)
        assertEquals("/api/home/posts/comment", request.url.toHttpUrl().encodedPath)
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
        val body = request.body!!.decodeToString()
        assertTrue(body.endsWith(
            "&atInfo%5B0%5D%5Buuid%5D=fixture%2Bone%26" +
                "&atInfo%5B0%5D%5Bcharacter_name%5D=%E6%B5%8B%E8%AF%95+A%26B" +
                "&atInfo%5B1%5D%5Buuid%5D=fixture%2Bone%26" +
                "&atInfo%5B1%5D%5Bcharacter_name%5D=%E5%8F%A6%E4%B8%80%E4%B8%AA%E5%90%8D%E5%AD%97",
        ))
        val fields = body.formFields()
        assertEquals(Draft.contentHtml, fields["content"])
        assertEquals("42", fields["posts_id"])
        assertEquals("7", fields["parent_id"])
        assertEquals("3", fields["root_parent"])
        assertEquals(Draft.commentPictureText, fields["comment_pic"])
        assertEquals(request.url.toHttpUrl().queryParameter("tempsuid"), fields["tempsuid"])
        assertEquals(10, fields.size)
        assertEquals(0, session.refreshes)
        assertEquals(0, session.conflicts)
    }

    @Test
    fun emptyMentionExtensionPreservesLegacyBodyAndResultWithoutAnyAtInfoKey() = runBlocking {
        val transport = RecordingTransport { response("""{"code":10000,"data":[31,32]}""") }
        val service = service(transport, AuthoringSession(setOf(RisingStonesCapability.ForumWrite)))
        val legacy: OfficialForumService = service
        assertEquals(listOf(31, 32), legacy.submitComment(Draft))
        assertEquals(listOf(31, 32), service.submitCommentWithMentions(Draft, emptyList()))
        assertEquals(transport.requests[0].body!!.decodeToString(), transport.requests[1].body!!.decodeToString())
        assertFalse(transport.requests.last().body!!.decodeToString().contains("atInfo"))
        assertEquals(6, transport.requests.last().body!!.decodeToString().formFields().size)
    }

    @Test
    fun mentionSubmissionRequiresForumWriteAndDoesNotReplayAmbiguousOrBusinessFailures() {
        val mentions = listOf(OfficialForumCommentMention("fixture-uuid", "Fixture"))
        val readOnly = AuthoringSession(setOf(RisingStonesCapability.AccountRead))
        val denied = RecordingTransport { response("""{"code":10000,"data":[31]}""") }
        assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
            runBlocking { service(denied, readOnly).submitCommentWithMentions(Draft, mentions) }
        }
        assertTrue(denied.requests.isEmpty())
        for (failure in listOf("transport", "business", "cancellation")) {
            val session = AuthoringSession(setOf(RisingStonesCapability.ForumWrite))
            val cancellation = CancellationException("fixture cancellation")
            val transport = RecordingTransport {
                when (failure) {
                    "transport" -> throw RisingStonesHttpException.Transport(IllegalStateException("fixture failure"))
                    "cancellation" -> throw cancellation
                    else -> response("""{"code":500,"msg":"fixture rejected"}""")
                }
            }
            val caught = assertThrows(Exception::class.java) {
                runBlocking { service(transport, session).submitCommentWithMentions(Draft, mentions) }
            }
            if (failure == "cancellation") assertSame(cancellation, caught)
            if (failure == "business") assertTrue(caught is OfficialForumException.Business)
            assertEquals(1, transport.requests.size)
            assertEquals(0, session.refreshes)
            assertEquals(0, session.conflicts)
        }
    }

    @Test
    fun mentionSubmissionDoesNotReplayAuthenticationFailure() = runBlocking {
        val session = AuthoringSession(setOf(RisingStonesCapability.ForumWrite))
        val transport = RecordingTransport { count ->
            response(if (count == 1) """{"code":401}""" else """{"code":10000,"data":[31]}""")
        }
        assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
            runBlocking {
                service(transport, session).submitCommentWithMentions(
                    Draft, listOf(OfficialForumCommentMention("fixture-uuid", "Fixture")),
                )
            }
        }
        assertEquals(1, transport.requests.size)
        assertEquals(1, session.refreshes)
        assertTrue(session.contexts.all { it.capability == RisingStonesCapability.ForumWrite })
    }

    @Test
    fun ambiguousMentionIdentitiesAreRejectedBeforeSubmissionWhileHtmlCharactersArePreserved() {
        val session = AuthoringSession(setOf(RisingStonesCapability.ForumWrite))
        val transport = RecordingTransport { response("""{"code":10000,"data":[31]}""") }
        val service = service(transport, session)
        for (invalid in listOf("", " ", "fixture#split", "fixture\nline", "fixture\rline", "fixture\tcontrol", "fixture\u0000control", "fixture\u0085line", "fixture\u2028line", "fixture\u2029line")) {
            for (mention in listOf(
                OfficialForumCommentMention(invalid, "Fixture"),
                OfficialForumCommentMention("fixture", invalid),
            )) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { service.submitCommentWithMentions(Draft, listOf(mention)) }
                }
                assertEquals("Invalid official comment mention identity", error.message)
            }
        }
        assertTrue(transport.requests.isEmpty())
        val name = "测试 <&>\"' 角色"
        runBlocking { service.submitCommentWithMentions(Draft, listOf(OfficialForumCommentMention("fixture-uuid", name))) }
        assertEquals(name, transport.requests.single().body!!.decodeToString().formFields()["atInfo[0][character_name]"])
    }

    private fun service(transport: RecordingTransport, session: AuthoringSession?) =
        OfficialForumApiService(RisingStonesPublicApiClient(transport), session)

    private fun response(body: String, statusCode: Int = 200) =
        RisingStonesHttpResponse(statusCode, emptyMap(), body.encodeToByteArray())

    private fun String.formFields(): Map<String, String> = split('&').associate { field ->
        URLDecoder.decode(field.substringBefore('='), "UTF-8") to
            URLDecoder.decode(field.substringAfter('='), "UTF-8")
    }

    private class RecordingTransport(
        private val handler: suspend (Int) -> RisingStonesHttpResponse,
    ) : RisingStonesHttpClient {
        val requests = mutableListOf<RisingStonesHttpRequest>()
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
            requests += request
            return handler(requests.size)
        }
    }

    private class AuthoringSession(
        override var capabilities: Set<RisingStonesCapability> = setOf(RisingStonesCapability.AccountRead),
    ) : RisingStonesSessionProvider, OfficialForumIdentityConflictHandler {
        override val identityConflictState = MutableStateFlow(OfficialForumIdentityConflictState())
        var hasAuthorizer = true
        var hasRefresh = true
        var hasConflictResolution = true
        var supplyHeaders = true
        var currentReads = 0
        var refreshes = 0
        var conflicts = 0
        val contexts = mutableListOf<RisingStonesRequestContext>()
        var onCurrent: suspend () -> Unit = {}
        var onAuthorize: suspend () -> Unit = {}
        var onRefresh: suspend () -> Unit = {}
        var onConflict: suspend () -> Unit = {}
        private val authorizer = RisingStonesRequestAuthorizer { context, sink ->
            contexts += context
            onAuthorize()
            if (supplyHeaders) sink.set("User-Agent", "fixture-agent")
        }
        override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? {
            currentReads++
            onCurrent()
            return authorizer.takeIf { hasAuthorizer }
        }
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? {
            refreshes++
            onRefresh()
            return authorizer.takeIf { hasRefresh }
        }
        override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer? {
            conflicts++
            onConflict()
            return authorizer.takeIf { hasConflictResolution }
        }
        override suspend fun reclaimIdentityConflict() = Unit
        override suspend fun cancelIdentityConflict() = Unit
    }

    private companion object {
        const val EmptyCandidates = """{"code":10000,"data":{"rows":[]}}"""
        val Draft = OfficialForumCommentDraft(42, 7, 3, "<p>示例 [emo1] @测试 A&amp;B</p>", "https://static.web.sdo.com/image.png")
    }
}
