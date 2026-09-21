package top.cxmeow.risingstones.feature.guild.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScope
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScopeProvider
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentDraft
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentMention
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GuildActionApiServiceTest {
    @Test
    fun labelsAndEligibilityUseVerifiedIdentityFieldsAndCanonicalGuild() = runTest {
        val transport = ActionTransport { request ->
            when (request.path) {
                "/api/home/guild/getGuildLabelList" -> envelope("""[{"id":7,"name":"Raid"},{"id":"8","name":"Casual"}]""")
                "/api/home/groupAndRole/getCharacterBindInfo" -> envelope(IDENTITY)
                "/api/home/guild/getGuildInfo" -> envelope(GUILD_INFO)
                else -> error("Unexpected ${request.path}")
            }
        }
        val provider = ActionProvider()
        val service = service(transport, provider)
        service.beginActionScope().use { scope ->
            assertEquals(listOf("7", "8"), service.labels(scope).map { it.id.value })
            val eligibility = service.guildEligibility(scope, GuildId("123"))
            assertEquals(GuildActionEligibility.Eligible, eligibility.manageGuild)
            assertEquals(GuildActionEligibility.Eligible, eligibility.uploadAlbum)
        }
        assertEquals("1", transport.requests[1].url.toHttpUrl().queryParameter("platform"))
        assertTrue(provider.completedCapabilities.isEmpty())
    }

    @Test
    fun updateUsesClosedKeyAndFormBodyThenCompletesExactlyOneWrite() = runTest {
        val transport = ActionTransport { request ->
            when (request.path) {
                "/api/home/groupAndRole/getCharacterBindInfo" -> envelope(IDENTITY)
                "/api/home/guild/getGuildInfo" -> envelope(GUILD_INFO)
                "/api/home/guild/setGuildInfo" -> envelope("null", 10002)
                else -> error("Unexpected ${request.path}")
            }
        }
        val provider = ActionProvider()
        val service = service(transport, provider)
        service.beginActionScope().use { scope ->
            service.updateGuildInfo(
                scope,
                GuildId("123"),
                GuildInfoUpdate.Description("A&B + text"),
            )
        }

        val write = transport.requests.single { it.method == RisingStonesHttpMethod.Post }
        assertEquals("/api/home/guild/setGuildInfo", write.path)
        assertEquals(
            "guild_id=123&key=guild_describe&guild_describe=A%26B+%2B+text",
            write.body!!.decodeToString(),
        )
        assertEquals(listOf(RisingStonesCapability.GuildWrite), provider.completedCapabilities)
        assertEquals(1, provider.attempts)
    }

    @Test
    fun photoEligibilityNeverCrossComparesUuidAndCharacterId() = runTest {
        val transport = ActionTransport { request ->
            when (request.path) {
                "/api/home/groupAndRole/getCharacterBindInfo" -> envelope(IDENTITY.replace("current-uuid", "789"))
                "/api/home/guild/getGuildPhotoDetail" -> envelope("""{
                  "id":"7","guild_id":"123","uuid":"456"
                }""")
                "/api/home/guild/getGuildInfo" -> envelope("""{
                  "guild_id":"123","master_id":"789"
                }""")
                else -> error("Unexpected ${request.path}")
            }
        }
        val service = service(transport, ActionProvider())
        service.beginActionScope().use { scope ->
            assertEquals(
                GuildActionEligibility.Ineligible,
                service.photoEligibility(scope, 7).deletePhoto,
            )
        }
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
    }

    @Test
    fun likeRequiresExactNumericResultBeforeCompleting() = runTest {
        val transport = ActionTransport { envelope("1") }
        val provider = ActionProvider()
        val service = service(transport, provider)
        assertFalse(service.canPerformAuthenticatedWrites)
        assertTrue(service.canAttemptAuthenticatedWrites)
        service.beginActionScope().use { scope ->
            assertEquals(GuildPhotoLikeResult.Liked, service.togglePhotoLike(scope, 7))
        }
        assertTrue(service.canPerformAuthenticatedWrites)
        assertEquals(1, provider.completions)

        transport.response = { envelope("\"1\"") }
        service.beginActionScope().use { scope ->
            try {
                service.togglePhotoLike(scope, 7)
                fail()
            } catch (_: GuildException.InvalidResponse) {
            }
        }
        assertEquals(1, provider.completions)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun commentUsesQsStyleFieldsAndOnlyCode10000Completes() = runTest {
        val transport = ActionTransport { envelope("null") }
        val provider = ActionProvider()
        val service = service(transport, provider)
        service.beginActionScope().use { scope ->
            service.commentPhoto(
                scope,
                GuildPhotoCommentDraft(
                    photoId = 7,
                    contentHtml = "<p>Hello</p>",
                    mentions = listOf(GuildPhotoCommentMention("uuid-1", "A B")),
                    parentId = 31,
                    rootParentId = 31,
                ),
            )
        }
        val body = transport.requests.single().body!!.decodeToString()
        assertTrue("atInfo%5B0%5D%5Buuid%5D=uuid-1" in body)
        assertTrue("atInfo%5B0%5D%5Bcharacter_name%5D=A+B" in body)
        assertTrue("parent_id=31&root_parent=31" in body)
        assertEquals(1, provider.completions)

        transport.response = { envelope("null", 10002) }
        service.beginActionScope().use { scope ->
            try {
                service.commentPhoto(scope, GuildPhotoCommentDraft(7, "second"))
                fail()
            } catch (_: GuildException.Business) {
            }
        }
        assertEquals(1, provider.completions)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun authenticationFailureRefreshesReadOnlyOnceAndNeverReplaysWrite() = runTest {
        val transport = ActionTransport { """{"code":10403,"data":null}""" }
        val provider = ActionProvider()
        val service = service(transport, provider)
        service.beginActionScope().use { scope ->
            try {
                service.togglePhotoLike(scope, 7)
                fail()
            } catch (_: GuildException.AuthenticationRequired) {
            }
        }
        assertEquals(1, provider.refreshes)
        assertEquals(1, transport.requests.size)
        assertEquals(0, provider.completions)
    }

    @Test
    fun commentDeletionRequiresAnObservedMatchingAuthorAndUsesDeleteBody() = runTest {
        val transport = ActionTransport { request ->
            when (request.path) {
                "/api/home/guild/GuildPhotoCommentDetail" -> envelope("""{
                  "rows":[{"id":31,"uuid":"current-uuid","character_name":"Owner",
                    "parent_id":0,"root_parent":0}],"pageTime":null
                }""")
                "/api/home/groupAndRole/getCharacterBindInfo" -> envelope(IDENTITY)
                "/api/home/guild/deleteComment" -> envelope("null")
                else -> error("Unexpected ${request.path}")
            }
        }
        val provider = ActionProvider()
        val service = service(transport, provider)
        service.comments(photoId = 7)
        service.beginActionScope().use { scope ->
            assertEquals(
                GuildActionEligibility.Eligible,
                service.commentEligibility(scope, 31).deleteOwnComment,
            )
            service.deleteOwnComment(scope, 31)
            val before = transport.requests.size
            try {
                service.deleteOwnComment(scope, 99)
                fail()
            } catch (_: GuildException.ActionNotEligible) {
            }
            assertEquals(before, transport.requests.size)
        }

        val request = transport.requests.single { it.path == "/api/home/guild/deleteComment" }
        assertEquals(RisingStonesHttpMethod.Delete, request.method)
        assertEquals("comment_id=31", request.body!!.decodeToString())
        assertEquals(1, provider.completions)
    }

    @Test
    fun lateSuccessAfterScopeRevocationIsDiscardedWithoutCompleting() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ActionTransport {
            entered.complete(Unit)
            release.await()
            envelope("-1")
        }
        val provider = ActionProvider()
        val service = service(transport, provider)
        val scope = service.beginActionScope()
        val result = async {
            runCatching { service.togglePhotoLike(scope, 7) }
        }
        entered.await()
        provider.current = false
        release.complete(Unit)
        val outcome = result.await()
        assertTrue(outcome.exceptionOrNull() is GuildException.AuthenticationRequired)
        assertEquals(1, transport.requests.size)
        assertEquals(0, provider.completions)
        scope.close()
    }

    @Test
    fun foreignScopeAndLegacyProviderFailClosedBeforeSending() = runTest {
        val transport = ActionTransport { envelope("1") }
        val first = service(transport, ActionProvider())
        val second = service(transport, ActionProvider())
        val scope = first.beginActionScope()
        try {
            second.togglePhotoLike(scope, 7)
            fail()
        } catch (_: GuildException.AuthenticationRequired) {
        } finally {
            scope.close()
        }
        assertTrue(transport.requests.isEmpty())

        val legacy = GuildApiService(
            RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
            object : RisingStonesSessionProvider {
                override val capabilities = setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.GuildWrite)
                override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, _ -> }
            },
        )
        assertFalse(legacy.canAttemptAuthenticatedWrites)
        try {
            legacy.beginActionScope()
            fail()
        } catch (_: GuildException.Unavailable) {
        }
        assertTrue(transport.requests.isEmpty())
    }

    private fun service(transport: ActionTransport, provider: ActionProvider) = GuildApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        provider,
    )
}

private class ActionProvider : RisingStonesSessionProvider,
    RisingStonesExplicitCapabilityProvider,
    RisingStonesCapabilityScopeProvider {
    override val capabilities: Set<RisingStonesCapability>
        get() = if (current) buildSet {
            add(RisingStonesCapability.GuildRead)
            addAll(completedCapabilities)
        } else emptySet()
    var current = true
    var attempts = 0
    var completions = 0
    var refreshes = 0
    val completedCapabilities = mutableListOf<RisingStonesCapability>()
    private val authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("X-Test", "fixture") }

    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = authorizer.takeIf { current }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? {
        refreshes++
        return authorizer.takeIf { current }
    }

    override fun canAttemptCapability(capability: RisingStonesCapability): Boolean =
        current && capability in setOf(RisingStonesCapability.GuildWrite, RisingStonesCapability.GuildImageUpload)

    override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? =
        null

    override suspend fun captureCapabilityScope(
        capabilities: Set<RisingStonesCapability>,
    ): RisingStonesCapabilityScope? {
        if (!current || capabilities != setOf(
                RisingStonesCapability.GuildWrite,
                RisingStonesCapability.GuildImageUpload,
            )
        ) return null
        return object : RisingStonesCapabilityScope {
            private var open = true
            override val authorizer: RisingStonesRequestAuthorizer = this@ActionProvider.authorizer
            override suspend fun isCurrent(): Boolean = open && current
            override suspend fun beginCapabilityAttempt(
                context: RisingStonesRequestContext,
            ): RisingStonesCapabilityAttempt? {
                if (!isCurrent() || context.capability !in capabilities) return null
                attempts++
                val capability = context.capability!!
                return object : RisingStonesCapabilityAttempt, RisingStonesCapabilityAttemptGuard {
                    private var attemptOpen = true
                    override val authorizer: RisingStonesRequestAuthorizer = this@ActionProvider.authorizer
                    override suspend fun isCurrent(): Boolean = attemptOpen && open && current
                    override suspend fun complete(): Boolean {
                        if (!isCurrent()) return false
                        completions++
                        completedCapabilities += capability
                        return true
                    }
                    override fun close() { attemptOpen = false }
                }
            }
            override fun close() { open = false }
        }
    }
}

private class ActionTransport(
    var response: suspend (RisingStonesHttpRequest) -> String,
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), response(request).encodeToByteArray())
    }
}

private val RisingStonesHttpRequest.path: String
    get() = url.toHttpUrl().encodedPath

private fun envelope(data: String, code: Int = 10000): String =
    """{"code":$code,"msg":"fixture","data":$data}"""

private const val IDENTITY = """{
  "uuid":"current-uuid","character_id":"456",
  "characterDetail":{"fc_id":"123","character_id":"456"}
}"""

private const val GUILD_INFO = """{
  "guild_id":"123","master_id":"456","isGuildMember":1
}"""
