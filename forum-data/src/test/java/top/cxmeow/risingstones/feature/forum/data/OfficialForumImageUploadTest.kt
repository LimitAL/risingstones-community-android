package top.cxmeow.risingstones.feature.forum.data

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumImageUploadService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class OfficialForumImageUploadTest {
    @Test
    fun optionalUploadCapabilityIsIndependentOfForumWriteAndTracksRevocation() {
        val session = UploadSession().apply { capabilities = setOf(RisingStonesCapability.ForumWrite) }
        val upload: OfficialForumImageUploadService = service(UploadTransport(), session)
        val legacy: OfficialForumService = upload
        assertFalse(upload.canUploadCommentImages)
        assertTrue(legacy.canPerformAuthenticatedWrites)
        session.capabilities = setOf(RisingStonesCapability.ForumImageUpload)
        assertTrue(upload.canUploadCommentImages)
        assertFalse(legacy.canPerformAuthenticatedWrites)
        session.capabilities = emptySet()
        assertFalse(upload.canUploadCommentImages)
    }

    @Test
    fun defaultChannelAcceptsSafeReturnedDirectoriesAndAlwaysUsesTheOfficialObjectHost() = runBlocking {
        for (directory in listOf("default/20260918/fixture", "posts/fixture", "media-v2/draft_1.assets")) {
            val transport = UploadTransport().apply { keyDir = directory }
            val bytes = byteArrayOf(1, 2, 3)
            val result = service(transport).uploadCommentImage(OfficialForumCommentImageUpload(bytes, "image/png"))
            assertEquals(2, transport.requests.size)
            val token = transport.requests.first()
            assertEquals("default", token.url.toHttpUrl().queryParameter("channel"))
            assertEquals("fixture-paired-agent", token.headers["User-Agent"])
            val put = transport.requests.last()
            val url = put.url.toHttpUrl()
            assertEquals(RisingStonesHttpMethod.Put, put.method)
            assertEquals("https", url.scheme)
            assertEquals("ff14risingstones.gcloud.com.cn", url.host)
            assertEquals(443, url.port)
            assertNull(url.query)
            assertNull(url.fragment)
            assertEquals(directory, url.pathSegments.dropLast(1).joinToString("/"))
            assertTrue(url.pathSegments.last().endsWith(".png"))
            assertArrayEquals(bytes, put.body)
            assertEquals("image/png", put.contentType)
            assertEquals("3", put.headers["Content-Length"])
            assertFalse(put.headers.containsKey("Cookie"))
            assertEquals(result, put.url)
        }
    }

    @Test
    fun unsafeObjectDirectoriesNeverReachTheUploadHost() {
        val directories = listOf("", "/default/a", "//other.test/a", "https://other.test/a", "default/../a",
            "default/./a", "default//a", "default/a/", "default\\a", "default/%2e%2e/a",
            "default/a?query=1", "default/a#fragment", "default/a\u0000", "default/中文")
        for (directory in directories) {
            val transport = UploadTransport().apply { keyDir = directory }
            assertThrows(OfficialForumException.ImageUploadFailed::class.java) {
                runBlocking { service(transport).uploadCommentImage(image()) }
            }
            assertEquals(1, transport.requests.size)
            assertEquals(RisingStonesHttpMethod.Get, transport.requests.single().method)
        }
    }

    @Test
    fun invalidCredentialLifetimesAreRejectedBeforePut() {
        val now = Instant.now().epochSecond
        for ((start, expiry) in listOf(now + 300 to now + 600, now - 600 to now - 1, now to now - 1, 0L to now + 600)) {
            val transport = UploadTransport().apply { startTime = start; expiredTime = expiry }
            assertThrows(OfficialForumException.ImageUploadFailed::class.java) {
                runBlocking { service(transport).uploadCommentImage(image()) }
            }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun emptyAndControlCharacterCredentialsAreRejectedWithoutExposingThem() {
        val badValues = listOf("", " ", "private-fixture\nheader")
        for (field in listOf("sessionToken", "tmpSecretId", "tmpSecretKey")) {
            for (value in badValues) {
                val transport = UploadTransport().apply { credentials[field] = value }
                val error = assertThrows(OfficialForumException.ImageUploadFailed::class.java) {
                    runBlocking { service(transport).uploadCommentImage(image()) }
                }
                assertNull(error.cause)
                assertFalse(error.toString().contains("private-fixture"))
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test
    fun invalidNormalizedImagesFailBeforeRequestingCredentials() {
        val transport = UploadTransport()
        val images = listOf(
            OfficialForumCommentImageUpload(byteArrayOf(), "image/png"),
            OfficialForumCommentImageUpload(ByteArray(21 * 1024 * 1024 + 1), "image/jpeg"),
        ) + listOf("image/gif", "image/webp", "text/html", "image/png\r\nInjected: value").map {
            OfficialForumCommentImageUpload(byteArrayOf(1), it)
        }
        images.forEach { image ->
            assertThrows(OfficialForumException.ImageUploadFailed::class.java) {
                runBlocking { service(transport).uploadCommentImage(image) }
            }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun uploadCapabilityRevocationDuringTokenReadStopsTheWrite() {
        val session = UploadSession()
        val transport = UploadTransport().apply { afterToken = { session.capabilities = emptySet() } }
        assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
            runBlocking { service(transport, session).uploadCommentImage(image()) }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun malformedTokenAndUploadFailuresStaySanitizedAndNeverRetryPut() {
        val malformed = UploadTransport().apply { tokenOverride = """{"code":10000,"data":{"credentials":"private-fixture"}}""" }
        val parseError = assertThrows(OfficialForumException.ImageUploadFailed::class.java) {
            runBlocking { service(malformed).uploadCommentImage(image()) }
        }
        assertFalse(parseError.toString().contains("private-fixture"))
        assertNull(parseError.cause)
        assertEquals(1, malformed.requests.size)
        for (throwFailure in listOf(false, true)) {
            val transport = UploadTransport().apply {
                uploadStatus = 500
                if (throwFailure) uploadFailure = IllegalStateException("private-fixture")
            }
            val error = assertThrows(OfficialForumException.ImageUploadFailed::class.java) {
                runBlocking { service(transport).uploadCommentImage(image()) }
            }
            assertFalse(error.toString().contains("private-fixture"))
            assertNull(error.cause)
            assertEquals(1, transport.requests.count { it.method == RisingStonesHttpMethod.Put })
        }
    }

    @Test
    fun tokenAndUploadCancellationRemainCancellation() {
        for (duringUpload in listOf(false, true)) {
            val cancellation = CancellationException("fixture-cancellation")
            val transport = UploadTransport().apply {
                if (duringUpload) uploadFailure = cancellation else tokenFailure = cancellation
            }
            val error = assertThrows(CancellationException::class.java) {
                runBlocking { service(transport).uploadCommentImage(image()) }
            }
            assertSame(cancellation, error)
            assertEquals(if (duringUpload) 2 else 1, transport.requests.size)
        }
    }

    @Test
    fun uploadAuthenticationErrorsAreSanitizedAndNeverRetryThePutOrRefreshToken() {
        for (status in listOf(401, 403)) {
            for (throwFailure in listOf(false, true)) {
                val session = UploadSession()
                val transport = UploadTransport().apply {
                    uploadStatus = status
                    if (throwFailure) uploadFailure = RisingStonesHttpException.ServerResponse(status, "private-fixture".encodeToByteArray())
                }
                val error = assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
                    runBlocking { service(transport, session).uploadCommentImage(image()) }
                }
                assertNull(error.cause)
                assertEquals(listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Put), transport.requests.map { it.method })
                assertEquals(1, session.refreshes)
            }
        }
    }

    private fun image() = OfficialForumCommentImageUpload(byteArrayOf(1), "image/png")
    private fun service(transport: UploadTransport, session: UploadSession = UploadSession()) = OfficialForumApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session,
    )
}

private class UploadSession : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.ForumImageUpload)
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("User-Agent", "fixture-paired-agent")
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? { refreshes++; return null }
}

private class UploadTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var keyDir = "default/fixture"
    var startTime = Instant.now().epochSecond - 10
    var expiredTime = Instant.now().epochSecond + 600
    val credentials = mutableMapOf("sessionToken" to "fixture-token", "tmpSecretId" to "fixture-id", "tmpSecretKey" to "fixture-key")
    var afterToken: () -> Unit = {}
    var tokenOverride: String? = null
    var tokenFailure: Exception? = null
    var uploadFailure: Exception? = null
    var uploadStatus = 200

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        if (request.method == RisingStonesHttpMethod.Put) {
            uploadFailure?.let { throw it }
            return RisingStonesHttpResponse(uploadStatus, emptyMap(), byteArrayOf())
        }
        tokenFailure?.let { throw it }
        val token = tokenOverride ?: buildJsonObject {
            put("code", 10000)
            put("data", buildJsonObject {
                put("credentials", buildJsonObject { credentials.forEach { (key, value) -> put(key, value) } })
                put("startTime", startTime)
                put("expiredTime", expiredTime)
                put("keyDir", keyDir)
            })
        }.toString()
        afterToken()
        return RisingStonesHttpResponse(200, emptyMap(), token.encodeToByteArray())
    }
}
