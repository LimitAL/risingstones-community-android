package top.cxmeow.risingstones.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RisingStonesWriteReplayTest {
    @Test fun writesNeverFallBackAfterUnknownTransportFailure() = runBlocking {
        listOf(RisingStonesHttpMethod.Post, RisingStonesHttpMethod.Put,
            RisingStonesHttpMethod.Patch, RisingStonesHttpMethod.Delete).forEach { method ->
            var calls = 0
            val client = RisingStonesPublicApiClient(object : RisingStonesHttpClient {
                override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                    calls++
                    throw IOException("Synthetic unknown outcome")
                }
            }, listOf("https://first.invalid", "https://second.invalid"))
            assertTrue(runCatching { client.execute(RisingStonesApiRequest("action", method)) }.exceptionOrNull()
                is RisingStonesEndpointException)
            assertEquals(1, calls)
        }
    }

    @Test fun readsRetainEndpointFallback() = runBlocking {
        var calls = 0
        val client = RisingStonesPublicApiClient(object : RisingStonesHttpClient {
            override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                if (++calls == 1) throw IOException("Synthetic failure")
                return RisingStonesHttpResponse(200, emptyMap(), byteArrayOf())
            }
        }, listOf("https://first.invalid", "https://second.invalid"))
        assertEquals(200, client.execute(RisingStonesApiRequest("read")).statusCode)
        assertEquals(2, calls)
    }

    @Test fun retryAfterAndRedirectNeverResendWriteBody() = runBlocking {
        listOf(408, 503, 307, 308, 401).forEach { status ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().code(status).addHeader("Retry-After", "0")
                    .addHeader("Location", server.url("/again")).body("{}").build())
                server.enqueue(MockResponse(code = 200, body = "{}"))
                val client = OkHttpRisingStonesHttpClient(OkHttpClient.Builder()
                    .authenticator { _, response -> response.request.newBuilder().header("X-Test", "retry").build() }
                    .build())
                val failure = runCatching { client.execute(RisingStonesHttpRequest(server.url("/once").toString(),
                    RisingStonesHttpMethod.Post, body = "synthetic".encodeToByteArray())) }.exceptionOrNull()
                assertTrue(failure is RisingStonesHttpException.ServerResponse)
                assertEquals(status, (failure as RisingStonesHttpException.ServerResponse).statusCode)
                assertEquals(1, server.requestCount)
            }
        }
    }
}
