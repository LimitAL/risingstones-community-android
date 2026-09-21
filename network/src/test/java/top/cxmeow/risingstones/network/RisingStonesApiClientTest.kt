package top.cxmeow.risingstones.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer

class RisingStonesApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun validatesCookieSessionWithoutAuthorizationHeader() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"code":10000,"msg":"ok","data":{"characterName":"Meteor"}}""",
            ),
        )
        val client = RisingStonesApiClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )
        val authorizer = RisingStonesRequestAuthorizer { _, sink ->
            sink.set("cookie", "ff14risingstones=session-value")
            sink.set("user-agent", "test-webview")
        }

        val result = client.validateSession(authorizer)
        val request = server.takeRequest()

        assertEquals("Meteor", result.displayName)
        assertEquals("ff14risingstones=session-value", request.headers["cookie"])
        assertEquals("test-webview", request.headers["user-agent"])
        assertTrue(request.headers["authorization"].isNullOrEmpty())
    }

    @Test
    fun sessionUserAgentOverridesEveryCaseVariantOfDefaultHeaders() = runTest {
        server.enqueue(MockResponse(code = 200, body = "{}"))
        val transport = OkHttpRisingStonesHttpClient(OkHttpClient(), linkedMapOf(
            "User-Agent" to "NativeDefault/1.0", "user-agent" to "SecondDefault/1.0",
        ))
        transport.execute(RisingStonesHttpRequest(server.url("/api/home/test").toString(),
            headers = mapOf("User-Agent" to "LoginWebView/1.0", "Cookie" to "ff14risingstones=fixture")))
        val request = server.takeRequest()
        assertEquals(listOf("LoginWebView/1.0"), request.headers.values("user-agent"))
        assertEquals("ff14risingstones=fixture", request.headers["cookie"])
    }

    @Test
    fun publicApiTransportPreservesLoginUserAgentForReadAndWriteMethods() = runTest {
        val client = RisingStonesPublicApiClient(
            OkHttpRisingStonesHttpClient(OkHttpClient(), mapOf("User-Agent" to "NativeDefault/1.0")),
            listOf(server.url("/").toString()),
        )
        listOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Post,
            RisingStonesHttpMethod.Put, RisingStonesHttpMethod.Delete).forEach { method ->
            server.enqueue(MockResponse(code = 200, body = "{}"))
            client.execute(RisingStonesApiRequest("api/home/test", method,
                headers = mapOf("cookie" to "ff14risingstones=fixture", "user-agent" to "LoginWebView/1.0")))
            val request = server.takeRequest()
            assertEquals(listOf("LoginWebView/1.0"), request.headers.values("user-agent"))
            assertEquals("ff14risingstones=fixture", request.headers["cookie"])
        }
    }

    @Test(expected = RisingStonesApiException::class)
    fun rejectsBusinessAuthenticationFailure() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"code":10001,"msg":"未登录","data":[]}""",
            ),
        )
        val client = RisingStonesApiClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )

        client.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
    }

    @Test
    fun alternateAcceptedCodeValidatesOnlyTheSessionAndIgnoresConflictingMessage() = runTest {
        server.enqueue(MockResponse(code = 200,
            body = """{"code":10002,"msg":"未登录","data":{"characterName":"Fixture"}}"""))
        val client = RisingStonesApiClient(OkHttpClient(), server.url("/").toString())

        val result = client.validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(10002, result.code)
        assertEquals("Fixture", result.displayName)
        assertTrue(result.capabilities.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun acceptedCodeDoesNotGrantCapabilitiesWhenOptionalDisplayDataIsAbsent() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"code":10002}"""))
        val result = RisingStonesApiClient(OkHttpClient(), server.url("/").toString())
            .validateSession(RisingStonesRequestAuthorizer { _, _ -> })
        assertEquals(null, result.displayName)
        assertTrue(result.capabilities.isEmpty())
    }

    @Test
    fun responsePolicyDoesNotAcceptMissingOrOtherBusinessCodes() {
        assertTrue(RisingStonesResponsePolicy.accepts(10000))
        assertTrue(RisingStonesResponsePolicy.accepts(10002))
        listOf(null, 0, 200, 401, 403, 10001, 10003, 10004, 10005, 10105, 10107, 10403, 10502)
            .forEach { assertFalse("Unexpected accepted code: $it", RisingStonesResponsePolicy.accepts(it)) }
    }

    @Test
    fun acceptedEnvelopeCannotOverrideAnHttpAuthenticationFailure() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"code":10002,"data":{}}"""))
        val failure = runCatching {
            RisingStonesApiClient(OkHttpClient(), server.url("/").toString())
                .validateSession(RisingStonesRequestAuthorizer { _, _ -> })
        }.exceptionOrNull()
        assertTrue(failure is RisingStonesApiException)
        assertEquals(401, (failure as RisingStonesApiException).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun requestAndResponseDescriptionsDoNotExposeSensitiveValues() {
        val request = RisingStonesHttpRequest(
            url = "https://example.invalid/api?account=secret-query",
            headers = mapOf("Cookie" to "secret-cookie", "Authorization" to "secret-token"),
            body = "secret-body".encodeToByteArray(),
        )
        val response = RisingStonesHttpResponse(
            statusCode = 200,
            headers = mapOf("Set-Cookie" to listOf("secret-response-cookie")),
            body = "personal-response".encodeToByteArray(),
        )
        val apiRequest = RisingStonesApiRequest(
            path = "api/home/test",
            query = listOf(RisingStonesApiQueryItem("account", "secret-query")),
            headers = request.headers,
            body = request.body,
        )
        val descriptions = listOf(request.toString(), response.toString(), apiRequest.toString())

        assertTrue(descriptions.all { "Cookie" in it })
        assertFalse(descriptions.any { "secret" in it || "personal-response" in it })
    }
}
