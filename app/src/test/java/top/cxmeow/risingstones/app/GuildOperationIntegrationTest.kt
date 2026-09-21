package top.cxmeow.risingstones.app

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.auth.webview.RisingStonesCookieCredential
import top.cxmeow.risingstones.auth.webview.RisingStonesCookieStore
import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.feature.guild.data.GuildApiService
import top.cxmeow.risingstones.feature.guild.data.GuildImageUploadApiService
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

/** Real production services and authorization; every HTTP response and credential is synthetic. */
class GuildOperationIntegrationTest {
    @Test
    fun albumUploadAndRegistrationUseOneScopeWithIndependentGrantsAndSeparatedHeaders() = runBlocking {
        val fixture = GuildOperationFixture()
        fixture.activate()
        assertTrue(fixture.guild.canAttemptAuthenticatedWrites)
        assertTrue(fixture.images.canAttemptImageUpload)
        assertFalse(fixture.guild.canPerformAuthenticatedWrites)
        assertFalse(fixture.images.canUploadImages)
        fixture.intercept = { request ->
            if (request.path == TokenPath || request.method == RisingStonesHttpMethod.Put) {
                assertEquals(ReadCapabilities, fixture.provider.capabilities)
            } else if (request.path == RegisterPath) {
                assertEquals(ReadCapabilities + RisingStonesCapability.GuildImageUpload, fixture.provider.capabilities)
            }
            null
        }

        fixture.guild.beginActionScope().use { scope ->
            assertTrue(fixture.requests.isEmpty())
            val uploaded = fixture.images.uploadImage(scope, GuildImagePurpose.Album, imageInput())
            assertTrue(scope.isCurrent())
            assertTrue(fixture.images.canUploadImages)
            assertFalse(fixture.guild.canPerformAuthenticatedWrites)
            assertEquals(GuildImagePurpose.Album, uploaded.purpose)
            assertEquals(fixture.requests.single { it.method == RisingStonesHttpMethod.Put }.url, uploaded.url)
            fixture.guild.registerAlbumPhotos(scope, FixtureGuild, listOf(uploaded))
            assertTrue(fixture.guild.canPerformAuthenticatedWrites)
            assertEquals(ReadCapabilities + GuildWrites, fixture.provider.capabilities)

            val registration = fixture.requests.single { it.path == RegisterPath }
            assertEquals(RisingStonesHttpMethod.Post, registration.method)
            assertEquals(mapOf("photo_url" to uploaded.url, "guild_id" to FixtureGuild.value), registration.formFields())
        }

        assertEquals(listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put,
            RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Post),
            fixture.requests.map { it.method })
        assertEquals(listOf(TokenPath, IdentityPath, GuildInfoPath, RegisterPath),
            fixture.requests.filter { it.method != RisingStonesHttpMethod.Put }.map { it.path })
        assertEquals("guild", fixture.requests.first().url.toHttpUrl().queryParameter("channel"))
        assertEquals("1", fixture.requests.single { it.path == IdentityPath }.url.toHttpUrl().queryParameter("platform"))
        assertEquals(FixtureGuild.value, fixture.requests.single { it.path == GuildInfoPath }.url.toHttpUrl().queryParameter("guild_id"))
        val put = fixture.requests.single { it.method == RisingStonesHttpMethod.Put }
        assertTrue(put.url.toHttpUrl().encodedPath.startsWith("/guild/fixture/"))
        assertArrayEquals(ImageBytes, put.body)
        assertEquals("image/png", put.header("Content-Type"))
        assertEquals(ImageBytes.size.toString(), put.header("Content-Length"))
        assertNotNull(put.header("Authorization"))
        assertEquals("fixture-cos-token", put.header("x-cos-security-token"))
        assertEquals(1, fixture.validations)
        fixture.assertHeaderIsolation()
    }

    @Test
    fun credentialChangeOrReadRevocationWhileTokenReturnsPreventsAnyCosPut() = runBlocking {
        for (mutation in GuildOperationMutation.entries) {
            val fixture = GuildOperationFixture()
            fixture.activate()
            fixture.intercept = { request ->
                if (request.path == TokenPath) fixture.mutate(mutation)
                null
            }
            fixture.guild.beginActionScope().use { scope ->
                assertSame(GuildException.AuthenticationRequired,
                    runCatching { fixture.images.uploadImage(scope, GuildImagePurpose.Album, imageInput()) }.exceptionOrNull())
                assertFalse(scope.isCurrent())
                assertSame(GuildException.AuthenticationRequired,
                    runCatching { fixture.images.uploadImage(scope, GuildImagePurpose.Album, imageInput()) }.exceptionOrNull())
            }
            assertEquals(listOf(TokenPath), fixture.requests.map { it.path })
            assertEquals(ReadCapabilities, fixture.provider.capabilities)
            fixture.assertHeaderIsolation()
        }
    }

    @Test
    fun credentialChangeOrReadRevocationWhilePutReturnsDiscardsTheUploadResult() = runBlocking {
        for (mutation in GuildOperationMutation.entries) {
            val fixture = GuildOperationFixture()
            fixture.activate()
            fixture.intercept = { request ->
                if (request.method == RisingStonesHttpMethod.Put) fixture.mutate(mutation)
                null
            }
            fixture.guild.beginActionScope().use { scope ->
                assertSame(GuildException.AuthenticationRequired,
                    runCatching { fixture.images.uploadImage(scope, GuildImagePurpose.Album, imageInput()) }.exceptionOrNull())
                assertFalse(scope.isCurrent())
            }
            assertEquals(listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put), fixture.requests.map { it.method })
            assertFalse(fixture.requests.any { it.path == RegisterPath })
            assertEquals(ReadCapabilities, fixture.provider.capabilities)
            fixture.assertHeaderIsolation()
        }
    }

    @Test
    fun returnedImageCannotRegisterThroughOldOrReplacementScopeAfterIdentityBoundary() = runBlocking {
        for (mutation in GuildOperationMutation.entries) {
            val fixture = GuildOperationFixture()
            fixture.activate()
            fixture.guild.beginActionScope().use { oldScope ->
                val uploaded = fixture.images.uploadImage(oldScope, GuildImagePurpose.Album, imageInput())
                assertTrue(fixture.images.canUploadImages)
                fixture.mutate(mutation)
                assertFalse(oldScope.isCurrent())
                assertSame(GuildException.AuthenticationRequired,
                    runCatching { fixture.guild.registerAlbumPhotos(oldScope, FixtureGuild, listOf(uploaded)) }.exceptionOrNull())
                assertEquals(2, fixture.requests.size)

                fixture.guild.beginActionScope().use { newScope ->
                    assertTrue(newScope.isCurrent())
                    assertSame(GuildException.ActionNotEligible,
                        runCatching { fixture.guild.registerAlbumPhotos(newScope, FixtureGuild, listOf(uploaded)) }.exceptionOrNull())
                }
                assertFalse(fixture.requests.any { it.path == RegisterPath })
                assertEquals(1, fixture.requests.count { it.method == RisingStonesHttpMethod.Put })
                assertEquals(1, fixture.requests.count { it.path == TokenPath })
                assertEquals(ReadCapabilities, fixture.provider.capabilities)
            }
            fixture.assertHeaderIsolation()
        }
    }

    @Test
    fun failedRegistrationRetriesExplicitlyWithoutUploadingAgainOrGrantingWriteEarly() = runBlocking {
        val fixture = GuildOperationFixture()
        fixture.activate()
        var registrations = 0
        fixture.intercept = { request ->
            if (request.path == RegisterPath && ++registrations == 1) response("", status = 503) else null
        }
        fixture.guild.beginActionScope().use { scope ->
            val uploaded = fixture.images.uploadImage(scope, GuildImagePurpose.Album, imageInput())
            assertSame(GuildException.Network,
                runCatching { fixture.guild.registerAlbumPhotos(scope, FixtureGuild, listOf(uploaded)) }.exceptionOrNull())
            assertTrue(scope.isCurrent())
            assertEquals(1, registrations)
            assertEquals(ReadCapabilities + RisingStonesCapability.GuildImageUpload, fixture.provider.capabilities)

            fixture.guild.registerAlbumPhotos(scope, FixtureGuild, listOf(uploaded))
            assertEquals(2, registrations)
            assertEquals(ReadCapabilities + GuildWrites, fixture.provider.capabilities)
        }
        assertEquals(1, fixture.requests.count { it.path == TokenPath })
        assertEquals(1, fixture.requests.count { it.method == RisingStonesHttpMethod.Put })
        val writes = fixture.requests.filter { it.path == RegisterPath }
        assertEquals(2, writes.size)
        assertArrayEquals(writes.first().body, writes.last().body)
        assertEquals(2, fixture.requests.count { it.path == IdentityPath })
        assertEquals(2, fixture.requests.count { it.path == GuildInfoPath })
        assertEquals(1, fixture.validations)
        fixture.assertHeaderIsolation()
    }
}

private class GuildOperationFixture {
    private var stored: RisingStonesCookieCredential? = null
    private var currentSuffix = "current"
    private var readCapabilities = ReadCapabilities
    var validations = 0
        private set
    val requests = mutableListOf<RisingStonesHttpRequest>()
    private val expectedHeaders = mutableListOf<Pair<String, String>>()
    var intercept: suspend (RisingStonesHttpRequest) -> RisingStonesHttpResponse? = { null }
    val provider = RisingStonesWebCookieSessionProvider(
        store = object : RisingStonesCookieStore {
            override suspend fun read(): RisingStonesCookieCredential? = stored
            override suspend fun write(credential: RisingStonesCookieCredential) { stored = credential }
            override suspend fun clear() { stored = null }
        },
        sessionValidator = object : RisingStonesSessionValidator {
            override suspend fun validateSession(authorizer: RisingStonesRequestAuthorizer): RisingStonesSessionValidation {
                validations++
                return RisingStonesSessionValidation("Fixture", 10000, readCapabilities)
            }
        },
    )
    private val client = RisingStonesPublicApiClient(object : RisingStonesHttpClient {
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
            requests += request
            expectedHeaders += "ff14risingstones=fixture-guild-$currentSuffix" to "GuildOperationFixture/$currentSuffix"
            intercept(request)?.let { return it }
            return when {
                request.method == RisingStonesHttpMethod.Put -> response("")
                request.path == TokenPath -> response(TokenResponse)
                request.path == IdentityPath -> response(
                    """{"code":10000,"data":{"character_id":"456","uuid":"fixture-community","characterDetail":{"fc_id":"123"}}}""",
                )
                request.path == GuildInfoPath -> response(
                    """{"code":10000,"data":{"guild_id":"123","master_id":"456","isGuildMember":1}}""",
                )
                request.path == RegisterPath -> response("""{"code":10002,"data":null}""")
                else -> throw AssertionError("Unexpected synthetic request ${request.method} ${request.path}")
            }
        }
    })
    val guild = GuildApiService(client, provider)
    val images = GuildImageUploadApiService(client, provider, nowEpochSeconds = { FixtureNow })

    suspend fun activate(suffix: String = "current") {
        // Same synthetic-only reflection boundary as FirstWriteSessionIntegrationTest.
        val credential = RisingStonesCookieCredential::class.java
            .getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance("fixture-guild-$suffix", "GuildOperationFixture/$suffix")
        assertTrue(provider.accept(credential).isSuccess)
        currentSuffix = suffix
    }

    suspend fun mutate(mutation: GuildOperationMutation) {
        when (mutation) {
            GuildOperationMutation.ReplaceCredential -> activate("replacement")
            GuildOperationMutation.RevokeAndRegainGuildRead -> {
                val revision = provider.credentialRevision.value
                readCapabilities = setOf(RisingStonesCapability.AccountRead)
                assertNotNull(provider.refreshAuthorizer())
                assertFalse(provider.canAttemptCapability(RisingStonesCapability.GuildWrite))
                readCapabilities = ReadCapabilities
                assertNotNull(provider.refreshAuthorizer())
                assertEquals(revision, provider.credentialRevision.value)
                assertTrue(provider.canAttemptCapability(RisingStonesCapability.GuildWrite))
            }
        }
    }

    fun assertHeaderIsolation() {
        requests.forEachIndexed { index, request ->
            val host = request.url.toHttpUrl().host
            if (request.method == RisingStonesHttpMethod.Put) {
                assertEquals("ff14risingstones.gcloud.com.cn", host)
                assertNull(request.header("Cookie"))
                assertNull(request.header("User-Agent"))
                assertNull(request.header("Origin"))
                assertNull(request.header("Referer"))
                assertNotNull(request.header("Authorization"))
                assertNotNull(request.header("x-cos-security-token"))
            } else {
                assertEquals("apiff14risingstones.web.sdo.com", host)
                assertEquals(expectedHeaders[index].first, request.header("Cookie"))
                assertEquals(expectedHeaders[index].second, request.header("User-Agent"))
                assertNull(request.header("x-cos-security-token"))
                assertNull(request.header("Authorization"))
            }
        }
    }
}

private fun RisingStonesHttpRequest.header(name: String): String? =
    headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value

private val RisingStonesHttpRequest.path: String
    get() = url.toHttpUrl().encodedPath

private fun RisingStonesHttpRequest.formFields(): Map<String, String> = requireNotNull(body).decodeToString()
    .split('&').associate { field ->
        val parts = field.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
    }

private fun response(body: String, status: Int = 200) = RisingStonesHttpResponse(status, emptyMap(), body.encodeToByteArray())
private fun imageInput() = GuildImageUploadInput(ImageBytes, "image/png")
private val ImageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
private val FixtureGuild = GuildId("123")
private val ReadCapabilities = setOf(RisingStonesCapability.GuildRead)
private val GuildWrites = setOf(RisingStonesCapability.GuildWrite, RisingStonesCapability.GuildImageUpload)
private enum class GuildOperationMutation { ReplaceCredential, RevokeAndRegainGuildRead }
private const val FixtureNow = 1_800_000_000L
private const val TokenPath = "/api/common/getCOSTokenI"
private const val IdentityPath = "/api/home/groupAndRole/getCharacterBindInfo"
private const val GuildInfoPath = "/api/home/guild/getGuildInfo"
private const val RegisterPath = "/api/home/guild/uploadGuildPhoto"
private const val TokenResponse = """{"code":10000,"data":{"startTime":1799999940,"expiredTime":1800000600,"keyDir":"guild/fixture","credentials":{"tmpSecretId":"fixture-cos-id","tmpSecretKey":"fixture-cos-key","sessionToken":"fixture-cos-token"}}}"""
