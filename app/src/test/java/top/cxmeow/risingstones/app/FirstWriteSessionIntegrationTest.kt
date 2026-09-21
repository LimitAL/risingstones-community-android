package top.cxmeow.risingstones.app

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.auth.webview.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.forum.data.OfficialForumApiService
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.network.*

/** Real session and feature implementations, in-memory credentials and synthetic HTTP only. */
class FirstWriteSessionIntegrationTest {
    @Test fun successfulFirstRecruitmentResponseDoesNotGrantGuildReadOrOtherWrites() = runBlocking {
        val provider = activeProvider()
        var calls = 0
        val service = top.cxmeow.risingstones.feature.recruitment.data.DutyRecruitmentApiService(
            RisingStonesPublicApiClient(object : RisingStonesHttpClient {
                override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                    calls++
                    assertEquals(RisingStonesHttpMethod.Post, request.method)
                    return RisingStonesHttpResponse(200, emptyMap(), """{"code":10000,"data":{}}""".encodeToByteArray())
                }
            }), provider,
        )
        assertFalse(service.canPerformAuthenticatedWrites)
        assertTrue(service.canAttemptAuthenticatedWrites)
        assertNull(service.respondToDutyRecruitment(42, "Synthetic contact"))
        assertEquals(1, calls)
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.RecruitmentWrite), provider.capabilities)
        assertTrue(service.canPerformAuthenticatedWrites)
        assertFalse(RisingStonesCapability.RecruitmentAuthenticated in provider.capabilities)
    }

    @Test fun successfulFirstLikeGrantsOnlyForumWriteAndKeepsTheLoginUserAgent() = runBlocking {
        val provider = activeProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = service(provider, requests) { """{"code":10000,"data":1}""" }
        assertFalse(service.canPerformAuthenticatedWrites)
        assertTrue(service.canAttemptAuthenticatedWrites)
        assertEquals(1, service.likePost(42))
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.ForumWrite), provider.capabilities)
        assertTrue(service.canPerformAuthenticatedWrites)
        assertFalse(service.canUploadCommentImages)
        assertEquals(1, requests.size)
        assertEquals("SyntheticFirstWrite/1", requests.single().headers.entries
            .single { it.key.equals("User-Agent", true) }.value)
        assertTrue(requests.single().headers.entries
            .single { it.key.equals("Cookie", true) }.value.isNotBlank())
    }

    @Test fun lateLikeResponseCannotGrantTheReplacementSession() = runBlocking {
        val provider = activeProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = service(provider, requests) {
            assertTrue(provider.accept(credential("replacement")).isSuccess)
            """{"code":10000,"data":1}"""
        }
        val error = runCatching { service.likePost(42) }.exceptionOrNull()
        assertSame(OfficialForumException.AuthenticationRequired, error)
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
        assertEquals(1, requests.size)
    }

    @Test fun tokenFromAnOldCredentialNeverReachesTheUploadStage() = runBlocking {
        val provider = activeProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = service(provider, requests) {
            assertTrue(provider.accept(credential("replacement")).isSuccess)
            tokenBody()
        }
        assertSame(OfficialForumException.AuthenticationRequired,
            runCatching { service.uploadCommentImage(image()) }.exceptionOrNull())
        assertEquals(listOf(RisingStonesHttpMethod.Get), requests.map { it.method })
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
    }

    @Test fun successfulFirstUploadGrantsOnlyUploadAndDoesNotForwardWebCredentials() = runBlocking {
        val provider = activeProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = service(provider, requests) { request ->
            assertFalse(RisingStonesCapability.ForumImageUpload in provider.capabilities)
            if (request.method == RisingStonesHttpMethod.Get) tokenBody() else ""
        }
        assertTrue(service.uploadCommentImage(image()).startsWith("https://ff14risingstones.gcloud.com.cn/default/fixture/"))
        assertEquals(listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put), requests.map { it.method })
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.ForumImageUpload), provider.capabilities)
        assertFalse(service.canPerformAuthenticatedWrites)
        assertTrue(service.canUploadCommentImages)
        assertTrue(requests.last().headers.keys.none { it.equals("Cookie", true) || it.equals("User-Agent", true) })
    }

    private suspend fun activeProvider(): RisingStonesWebCookieSessionProvider {
        val store = object : RisingStonesCookieStore {
            private var stored: RisingStonesCookieCredential? = null
            override suspend fun read() = stored
            override suspend fun write(credential: RisingStonesCookieCredential) { stored = credential }
            override suspend fun clear() { stored = null }
        }
        return RisingStonesWebCookieSessionProvider(store, object : RisingStonesSessionValidator {
            override suspend fun validateSession(authorizer: RisingStonesRequestAuthorizer) =
                RisingStonesSessionValidation("Fixture", 10000, setOf(RisingStonesCapability.AccountRead))
        }).also { assertTrue(it.accept(credential("current")).isSuccess) }
    }

    private fun service(provider: RisingStonesWebCookieSessionProvider,
        requests: MutableList<RisingStonesHttpRequest>, body: suspend (RisingStonesHttpRequest) -> String,
    ) = OfficialForumApiService(RisingStonesPublicApiClient(object : RisingStonesHttpClient {
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
            requests += request
            return RisingStonesHttpResponse(200, emptyMap(), body(request).encodeToByteArray())
        }
    }), provider)

    // Production credentials remain constructible only inside auth-webview; do not add a public
    // raw-cookie factory just to let this cross-module synthetic integration fixture create one.
    private fun credential(suffix: String): RisingStonesCookieCredential =
        RisingStonesCookieCredential::class.java.getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance("synthetic-$suffix", "SyntheticFirstWrite/1")

    private fun image() = OfficialForumCommentImageUpload(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")

    private fun tokenBody(): String {
        val now = Instant.now().epochSecond
        return """{"code":10000,"data":{"startTime":${now - 60},"expiredTime":${now + 600},"keyDir":"default/fixture","credentials":{"tmpSecretId":"fixture-id","tmpSecretKey":"fixture-key","sessionToken":"fixture-token"}}}"""
    }
}
