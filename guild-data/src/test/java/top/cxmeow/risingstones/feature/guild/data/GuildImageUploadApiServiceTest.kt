package top.cxmeow.risingstones.feature.guild.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.guild.domain.*
import top.cxmeow.risingstones.network.*

class GuildImageUploadApiServiceTest {
    @Test fun allPurposesUseTheirOfficialChannelAndOnlyCosCredentialsReachTheImageStore() = runTest {
        val fixture = UploadFixture()
        assertFalse(fixture.service.canUploadImages)
        assertTrue(fixture.service.canAttemptImageUpload)
        for (purpose in GuildImagePurpose.entries) {
            val result = fixture.service.uploadImage(fixture.scope, purpose, image())
            val token = fixture.requests[fixture.requests.size - 2]
            val put = fixture.requests.last()
            assertEquals("/api/common/getCOSTokenI", token.url.toHttpUrl().encodedPath)
            assertEquals(purpose.officialChannel, token.url.toHttpUrl().queryParameter("channel"))
            assertEquals("synthetic-login-agent", token.headers["User-Agent"])
            assertTrue(token.headers.containsKey("Cookie"))
            assertEquals(RisingStonesHttpMethod.Put, put.method)
            assertEquals("ff14risingstones.gcloud.com.cn", put.url.toHttpUrl().host)
            assertFalse(put.headers.keys.any { it.equals("Cookie", true) || it.equals("User-Agent", true) })
            assertTrue(put.headers.containsKey("x-cos-security-token"))
            assertEquals(put.url, result.url)
            assertEquals(purpose, result.purpose)
            assertSame(fixture.scope, (result as BoundGuildUploadedImage).actionScope)
            assertTrue(fixture.scope.isCurrent())
        }
        assertEquals(6, fixture.requests.size)
        assertEquals(3, fixture.completions)
        assertEquals(3, fixture.closedAttempts)
        assertEquals(setOf(RisingStonesCapability.GuildImageUpload), fixture.granted)
    }

    @Test fun malformedAndExpiredTokensNeverReachPutOrGrantCapability() = runTest {
        listOf(
            ValidToken.replace("guild/fixture", "../fixture"),
            ValidToken.replace("guild/fixture", "guild//fixture"),
            ValidToken.replace("\"expiredTime\":2000", "\"expiredTime\":999"),
            ValidToken.replace("\"startTime\":900", "\"startTime\":1100"),
            ValidToken.replace("\"tmpSecretKey\":\"synthetic-secret\"", "\"tmpSecretKey\":123"),
            "{\"code\":10000,\"data\":null}",
        ).forEach { body ->
            val fixture = UploadFixture().apply { respond = { response(body = body) } }
            val error = runCatching { fixture.service.uploadImage(fixture.scope, GuildImagePurpose.Album, image()) }.exceptionOrNull()
            assertTrue(error is GuildException.ImageUploadFailed || error is GuildException.InvalidResponse)
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.completions)
            assertEquals(1, fixture.closedAttempts)
        }
    }

    @Test fun cookieRejectionRefreshesOnceButCosRejectionDoesNotInvalidateTheWebSession() = runTest {
        val tokenFailure = UploadFixture().apply { respond = { response(status = 401) } }
        assertTrue(runCatching { tokenFailure.service.uploadImage(tokenFailure.scope, GuildImagePurpose.Comment, image()) }
            .exceptionOrNull() is GuildException.AuthenticationRequired)
        assertEquals(1, tokenFailure.requests.size)
        assertEquals(1, tokenFailure.refreshes)
        val cosFailure = UploadFixture().apply {
            respond = { if (it.method == RisingStonesHttpMethod.Get) response() else response(status = 403) }
        }
        assertTrue(runCatching { cosFailure.service.uploadImage(cosFailure.scope, GuildImagePurpose.Comment, image()) }
            .exceptionOrNull() is GuildException.ImageUploadFailed)
        assertEquals(2, cosFailure.requests.size)
        assertEquals(0, cosFailure.refreshes)
        assertEquals(0, cosFailure.completions)
    }

    @Test fun identityConflictNeverReclaimsOrReplaysTheTokenRequest() = runTest {
        val fixture = UploadFixture().apply { respond = { response(body = "{\"code\":10105}") } }
        assertTrue(runCatching { fixture.service.uploadImage(fixture.scope, GuildImagePurpose.Comment, image()) }
            .exceptionOrNull() is GuildException.IdentityConflict)
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.refreshes)
        assertEquals(0, fixture.completions)
    }

    @Test fun revocationAfterTokenPreventsPutAndRevocationAfterPutDiscardsItsSuccess() = runTest {
        for (revokeAfter in listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put)) {
            val fixture = UploadFixture()
            fixture.respond = { request ->
                if (request.method == revokeAfter) fixture.current = false
                response()
            }
            assertTrue(runCatching { fixture.service.uploadImage(fixture.scope, GuildImagePurpose.Album, image()) }
                .exceptionOrNull() is GuildException.AuthenticationRequired)
            assertEquals(if (revokeAfter == RisingStonesHttpMethod.Get) 1 else 2, fixture.requests.size)
            assertEquals(0, fixture.completions)
            assertEquals(1, fixture.closedAttempts)
        }
    }

    @Test fun aLatePutCannotGrantCapabilityAfterCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = UploadFixture().apply {
            respond = { request ->
                if (request.method == RisingStonesHttpMethod.Put) withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                response()
            }
        }
        val task = async { fixture.service.uploadImage(fixture.scope, GuildImagePurpose.Album, image()) }
        entered.await()
        task.cancel()
        release.complete(Unit)
        try { task.await(); fail("Cancelled upload returned content") }
        catch (_: CancellationException) { }
        assertEquals(2, fixture.requests.size)
        assertEquals(0, fixture.completions)
        assertEquals(1, fixture.closedAttempts)
    }

    @Test fun foreignAndClosedScopesAreRejectedBeforeAnyNetworkRequest() = runTest {
        val fixture = UploadFixture()
        val foreign = UploadFixture()
        for (scope in listOf(foreign.scope, fixture.scope.also { it.close() })) {
            assertTrue(runCatching { fixture.service.uploadImage(scope, GuildImagePurpose.Avatar, image()) }
                .exceptionOrNull() is GuildException.AuthenticationRequired)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun callersCannotChangeTheBytesOfAnAlreadySelectedImage() = runTest {
        val fixture = UploadFixture()
        val bytes = byteArrayOf(1, 2, 3)
        val input = GuildImageUploadInput(bytes, "image/webp")
        bytes[0] = 99
        input.copyBytes()[1] = 99
        fixture.service.uploadImage(fixture.scope, GuildImagePurpose.Album, input)
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.requests.last().body)
        assertTrue(fixture.requests.last().url.endsWith(".webp"))
    }

    private fun image() = GuildImageUploadInput(byteArrayOf(1, 2, 3), "image/png")
}

private class UploadFixture {
    var current = true
    var completions = 0
    var closedAttempts = 0
    var refreshes = 0
    val granted = mutableSetOf<RisingStonesCapability>()
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var respond: suspend (RisingStonesHttpRequest) -> RisingStonesHttpResponse = { response() }
    private val authorizer = RisingStonesRequestAuthorizer { _, sink ->
        check(current)
        sink.set("Cookie", "ff14risingstones=fixture")
        sink.set("User-Agent", "synthetic-login-agent")
    }
    val provider: RisingStonesSessionProvider = object : RisingStonesSessionProvider, RisingStonesExplicitCapabilityProvider,
        RisingStonesCapabilityScopeProvider {
        override val capabilities: Set<RisingStonesCapability>
            get() = if (current) setOf(RisingStonesCapability.GuildRead) + granted else emptySet()
        override suspend fun currentAuthorizer() = authorizer.takeIf { current }
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? { refreshes++; return currentAuthorizer() }
        override fun canAttemptCapability(capability: RisingStonesCapability) = current
        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? = null
        override suspend fun captureCapabilityScope(capabilities: Set<RisingStonesCapability>): RisingStonesCapabilityScope? =
            delegate.takeIf { current }
    }
    private val delegate = object : RisingStonesCapabilityScope {
        private var open = true
        override val authorizer = this@UploadFixture.authorizer
        override suspend fun isCurrent() = current && open
        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? {
            if (!isCurrent()) return null
            return object : RisingStonesCapabilityAttempt, RisingStonesCapabilityAttemptGuard {
                private var active = true
                override val authorizer = this@UploadFixture.authorizer
                override suspend fun isCurrent() = active && current && open
                override suspend fun complete(): Boolean {
                    if (!isCurrent()) return false
                    completions++
                    granted += context.capability!!
                    return true
                }
                override fun close() { if (active) closedAttempts++; active = false }
            }
        }
        override fun close() { open = false }
    }
    val scope = OfficialGuildActionScope(delegate, provider)
    val service = GuildImageUploadApiService(RisingStonesPublicApiClient(object : RisingStonesHttpClient {
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
            requests += request
            return respond(request)
        }
    }), provider, nowEpochSeconds = { 1000 })
}

private fun response(status: Int = 200, body: String = ValidToken) =
    RisingStonesHttpResponse(status, emptyMap(), body.encodeToByteArray())

private const val ValidToken = """{"code":10000,"data":{"credentials":{"tmpSecretId":"SYNTHETICID","tmpSecretKey":"synthetic-secret","sessionToken":"synthetic-session"},"startTime":900,"expiredTime":2000,"keyDir":"guild/fixture"}}"""
