package top.cxmeow.risingstones.auth.webview

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import top.cxmeow.risingstones.network.OfficialRisingStonesEndpoints
import kotlin.coroutines.resume

fun interface RisingStonesWebCookieJar {
    suspend fun clearRisingStonesCredential()

    companion object {
        val NoOp = RisingStonesWebCookieJar {}
    }
}

/**
 * Removes only the official forum credential from Android's process-wide WebView cookie jar.
 *
 * Android does not provide a per-WebView cookie store. Deleting all cookies here could sign a host
 * out of unrelated embedded websites, so this implementation expires the exact
 * `ff14risingstones` cookie for every official host/domain form currently used by the forum.
 */
class AndroidRisingStonesWebCookieJar(
    private val cookieManager: CookieManager = CookieManager.getInstance(),
) : RisingStonesWebCookieJar {
    override suspend fun clearRisingStonesCredential() = withContext(Dispatchers.Main.immediate) {
        ExpirationCookies.forEach { cookie ->
            OfficialCookieUrls.forEach { url ->
                cookieManager.setCookieAwait(url, cookie)
            }
        }
        cookieManager.flush()
    }

    private suspend fun CookieManager.setCookieAwait(url: String, value: String) {
        suspendCancellableCoroutine { continuation ->
            setCookie(url, value) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private companion object {
        const val ExpiredAttributes =
            "Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure"

        val OfficialCookieUrls = listOf(
            OfficialRisingStonesEndpoints.WebBaseUrl,
            OfficialRisingStonesEndpoints.ApiBaseUrl,
        )

        val ExpirationCookies = listOf(
            "${RisingStonesCookieCredential.CookieName}=; $ExpiredAttributes",
            "${RisingStonesCookieCredential.CookieName}=; Domain=.web.sdo.com; $ExpiredAttributes",
            "${RisingStonesCookieCredential.CookieName}=; " +
                "Domain=ff14risingstones.web.sdo.com; $ExpiredAttributes",
            "${RisingStonesCookieCredential.CookieName}=; " +
                "Domain=apiff14risingstones.web.sdo.com; $ExpiredAttributes",
        )
    }
}
