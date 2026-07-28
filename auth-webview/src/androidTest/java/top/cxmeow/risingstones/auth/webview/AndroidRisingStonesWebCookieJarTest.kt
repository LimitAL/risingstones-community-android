package top.cxmeow.risingstones.auth.webview

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.cxmeow.risingstones.network.OfficialRisingStonesEndpoints
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class AndroidRisingStonesWebCookieJarTest {
    private val cookieManager = CookieManager.getInstance()

    @Before
    fun clearBeforeTest() = runBlocking {
        cookieManager.removeAllCookiesAwait()
    }

    @After
    fun clearAfterTest() = runBlocking {
        cookieManager.removeAllCookiesAwait()
    }

    @Test
    fun clearsOnlyRisingStonesCredentialAcrossOfficialHosts() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            cookieManager.setCookieAwait(
                OfficialRisingStonesEndpoints.WebBaseUrl,
                "ff14risingstones=secret; Domain=.web.sdo.com; Path=/; Secure",
            )
            cookieManager.setCookieAwait(
                OfficialRisingStonesEndpoints.WebBaseUrl,
                "unrelated=keep; Domain=.web.sdo.com; Path=/; Secure",
            )
            cookieManager.flush()
        }

        assertTrue(
            cookieManager.getCookie(OfficialRisingStonesEndpoints.ApiBaseUrl)
                .orEmpty()
                .contains("ff14risingstones=secret"),
        )

        AndroidRisingStonesWebCookieJar(cookieManager).clearRisingStonesCredential()

        listOf(
            OfficialRisingStonesEndpoints.WebBaseUrl,
            OfficialRisingStonesEndpoints.ApiBaseUrl,
        ).forEach { url ->
            val header = cookieManager.getCookie(url).orEmpty()
            assertFalse(header.contains("ff14risingstones="))
            assertTrue(header.contains("unrelated=keep"))
        }
    }

    private suspend fun CookieManager.setCookieAwait(
        url: String,
        value: String,
    ) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            setCookie(url, value) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private suspend fun CookieManager.removeAllCookiesAwait() {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                removeAllCookies {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            flush()
        }
    }
}
