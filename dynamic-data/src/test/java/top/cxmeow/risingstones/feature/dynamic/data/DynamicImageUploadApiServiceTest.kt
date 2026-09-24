package top.cxmeow.risingstones.feature.dynamic.data

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
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.network.*

class DynamicImageUploadApiServiceTest {
    @Test fun commentUsesDefaultChannelAndOnlyCosCredentialsReachTheImageStore() = runTest {
        val fixture = UploadFixture()
        assertFalse(fixture.service.canUploadImages)
        assertTrue(fixture.service.canAttemptImageUpload)
        run {
            val result = fixture.service.uploadCommentImage(fixture.scope, image())
            val token = fixture.requests[fixture.requests.size - 2]
            val put = fixture.requests.last()
            assertEquals("/api/common/getCOSTokenI", token.url.toHttpUrl().encodedPath)
            assertEquals("default", token.url.toHttpUrl().queryParameter("channel"))
            assertEquals("synthetic-login-agent", token.headers["User-Agent"])
            assertTrue(token.headers.containsKey("Cookie"))
            assertEquals(RisingStonesHttpMethod.Put, put.method)
            assertEquals("ff14risingstones.gcloud.com.cn", put.url.toHttpUrl().host)
            assertFalse(put.headers.keys.any { it.equals("Cookie", true) || it.equals("User-Agent", true) })
            assertTrue(put.headers.containsKey("x-cos-security-token"))
            assertEquals(put.url, (result as BoundDynamicUploadedImage).url)
            assertSame(fixture.scope, (result as BoundDynamicUploadedImage).actionScope)
            assertTrue(fixture.scope.isCurrent())
        }
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.completions)
        assertEquals(1, fixture.closedAttempts)
        assertEquals(setOf(RisingStonesCapability.DynamicImageUpload), fixture.granted)
    }

    @Test fun publishingUsesDynamicChannelAndBindsTheResultToPublishing() = runTest {
        val fixture = UploadFixture()
        val result = fixture.service.uploadPublishingImage(fixture.scope, image())
        val token = fixture.requests[fixture.requests.size - 2]

        assertEquals("dynamic", token.url.toHttpUrl().queryParameter("channel"))
        assertSame(fixture.scope, (result as BoundDynamicUploadedImage).actionScope)
        assertEquals(DynamicImagePurpose.Publishing, result.purpose)
        assertEquals(1, fixture.completions)
        assertEquals(setOf(RisingStonesCapability.DynamicImageUpload), fixture.granted)
    }

    @Test fun malformedAndExpiredTokensNeverReachPutOrGrantCapability() = runTest {
        listOf(
            ValidToken.replace("guild/fixture", "../fixture"),
            ValidToken.replace("guild/fixture", "guild//fixture"),
            ValidToken.replace("\"expiredTime\":2000", "\"expiredTime\":999"),
            ValidToken.replace("\"startTime\":900", "\"startTime\":1100"),
            ValidToken.replace("\"tmpSecretKey\":\"synthetic-secret\"", "\"tmpSecretKey\":123"),
            "{\"code\":10000,\"data\":null}",
            ValidToken.replace("\"code\":10000", "\"code\":\"10000\""),
        ).forEach { body ->
            val fixture = UploadFixture().apply { respond = { response(body = body) } }
            val error = runCatching { fixture.service.uploadCommentImage(fixture.scope, image()) }.exceptionOrNull()
            assertTrue(error is DynamicException.ImageUploadFailed || error is DynamicException.InvalidResponse)
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.completions)
            assertEquals(1, fixture.closedAttempts)
        }
    }

    @Test fun cookieRejectionRefreshesOnceButCosRejectionDoesNotInvalidateTheWebSession() = runTest {
        val tokenFailure = UploadFixture().apply { respond = { response(status = 401) } }
        assertTrue(runCatching { tokenFailure.service.uploadCommentImage(tokenFailure.scope, image()) }
            .exceptionOrNull() is DynamicException.AuthenticationRequired)
        assertEquals(1, tokenFailure.requests.size)
        assertEquals(1, tokenFailure.refreshes)
        val cosFailure = UploadFixture().apply {
            respond = { if (it.method == RisingStonesHttpMethod.Get) response() else response(status = 403) }
        }
        assertTrue(runCatching { cosFailure.service.uploadCommentImage(cosFailure.scope, image()) }
            .exceptionOrNull() is DynamicException.ImageUploadFailed)
        assertEquals(2, cosFailure.requests.size)
        assertEquals(0, cosFailure.refreshes)
        assertEquals(0, cosFailure.completions)
    }

    @Test fun identityConflictNeverReclaimsOrReplaysTheTokenRequest() = runTest {
        val fixture = UploadFixture().apply { respond = { response(body = "{\"code\":10105}") } }
        assertTrue(runCatching { fixture.service.uploadCommentImage(fixture.scope, image()) }
            .exceptionOrNull() is DynamicException.IdentityConflict)
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
            assertTrue(runCatching { fixture.service.uploadCommentImage(fixture.scope, image()) }
                .exceptionOrNull() is DynamicException.AuthenticationRequired)
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
        val task = async { fixture.service.uploadCommentImage(fixture.scope, image()) }
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
            assertTrue(runCatching { fixture.service.uploadCommentImage(scope, image()) }
                .exceptionOrNull() is DynamicException.AuthenticationRequired)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun callersCannotChangeTheBytesOfAnAlreadySelectedImage() = runTest {
        val fixture = UploadFixture()
        val bytes = byteArrayOf(1, 2, 3)
        val input = DynamicImageUploadInput(bytes, "image/webp")
        bytes[0] = 99
        input.copyBytes()[1] = 99
        fixture.service.uploadCommentImage(fixture.scope, input)
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.requests.last().body)
        assertTrue(fixture.requests.last().url.endsWith(".webp"))
    }

    private fun image() = DynamicImageUploadInput(byteArrayOf(1, 2, 3), "image/png")

    @Test fun inputRejectsEmptyOversizedAndUnsupportedImagesBeforeNetwork() {
        assertTrue(runCatching { DynamicImageUploadInput(byteArrayOf(), "image/png") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DynamicImageUploadInput(byteArrayOf(1), "text/html") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DynamicImageUploadInput(ByteArray(DynamicImageUploadInput.MaximumDynamicImageBytes + 1), "image/png") }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun uncertainPutIsNeverRepeatedAndNeverGrantsCapability() = runTest {
        val fixture = UploadFixture().apply {
            respond = { request ->
                if (request.method == RisingStonesHttpMethod.Put) throw java.io.IOException("synthetic failure")
                response()
            }
        }
        assertTrue(runCatching { fixture.service.uploadCommentImage(fixture.scope, image()) }.exceptionOrNull() is DynamicException.ImageUploadFailed)
        assertEquals(2, fixture.requests.size)
        assertEquals(0, fixture.completions)
        assertEquals(0, fixture.refreshes)
        assertEquals(1, fixture.closedAttempts)
    }
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
            get() = if (current) setOf(RisingStonesCapability.DynamicRead) + granted else emptySet()
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
    val scope = OfficialDynamicActionScope(delegate, provider)
    val service = DynamicImageUploadApiService(RisingStonesPublicApiClient(object : RisingStonesHttpClient {
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
            requests += request
            return respond(request)
        }
    }), provider, nowEpochSeconds = { 1000 })
}

private fun response(status: Int = 200, body: String = ValidToken) =
    RisingStonesHttpResponse(status, emptyMap(), body.encodeToByteArray())

private const val ValidToken = """{"code":10000,"data":{"credentials":{"tmpSecretId":"SYNTHETICID","tmpSecretKey":"synthetic-secret","sessionToken":"synthetic-session"},"startTime":900,"expiredTime":2000,"keyDir":"guild/fixture"}}"""
