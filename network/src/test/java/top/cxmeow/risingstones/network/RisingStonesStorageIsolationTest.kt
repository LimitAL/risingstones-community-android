package top.cxmeow.risingstones.network

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RisingStonesStorageIsolationTest {
    @Test fun storageWireRequestCannotInheritHostLoginHeadersCookiesOrInterceptors() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 200, body = ""))
            val jarReads = AtomicInteger()
            val intercepts = AtomicInteger()
            val client = OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl): List<Cookie> {
                        jarReads.incrementAndGet()
                        return listOf(Cookie.Builder().name("fixture").value("private").domain(url.host).build())
                    }
                })
                .addInterceptor { chain ->
                    intercepts.incrementAndGet()
                    chain.proceed(chain.request().newBuilder().header("Cookie", "private").build())
                }
                .addNetworkInterceptor { chain ->
                    intercepts.incrementAndGet()
                    chain.proceed(chain.request().newBuilder().header("User-Agent", "PrivateWebView").build())
                }.build()
            val transport = OkHttpRisingStonesHttpClient(client,
                mapOf("Cookie" to "private", "User-Agent" to "PrivateWebView", "X-Host-Login" to "private"))
            // Loopback HTTP exercises the transport boundary without real service access.
            val url = server.url("/synthetic.png").newBuilder().host("ff14risingstones.gcloud.com.cn").build()
            transport.execute(RisingStonesHttpRequest(url.toString(), RisingStonesHttpMethod.Put,
                mapOf("Cookie" to "private", "User-Agent" to "PrivateWebView",
                    "Authorization" to "fixture-cos-signature", "x-cos-security-token" to "fixture-token"),
                byteArrayOf(1, 2, 3), "image/png"))
            val wire = server.takeRequest()
            assertNull(wire.headers["Cookie"])
            assertNull(wire.headers["X-Host-Login"])
            assertNotEquals("PrivateWebView", wire.headers["User-Agent"])
            assertEquals("fixture-cos-signature", wire.headers["Authorization"])
            assertEquals("fixture-token", wire.headers["x-cos-security-token"])
            assertEquals(0, jarReads.get())
            assertEquals(0, intercepts.get())
            assertEquals(1, server.requestCount)
        }
    }
}
