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

    @Test(expected = RisingStonesApiException::class)
    fun rejectsBusinessAuthenticationFailure() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"code":10002,"msg":"未登录","data":[]}""",
            ),
        )
        val client = RisingStonesApiClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )

        client.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
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
