package top.cxmeow.risingstones.auth.webview

import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCredentialSource
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

class RisingStonesCookieCredential internal constructor(
    internal val cookie: String,
    internal val userAgent: String,
) {
    init {
        require(cookie.isNotBlank()) { "Rising Stones cookie must not be blank" }
        require(userAgent.isNotBlank()) { "WebView user agent must not be blank" }
    }

    fun authorizer(): RisingStonesRequestAuthorizer =
        RisingStonesRequestAuthorizer { _, sink ->
            applyBrowserHeaders(sink)
        }

    override fun toString(): String = "RisingStonesCookieCredential(redacted)"

    private fun applyBrowserHeaders(sink: RisingStonesHeaderSink) {
        sink.set("accept", "application/json, text/plain, */*")
        sink.set("cache-control", "no-cache")
        sink.set("cookie", "$CookieName=$cookie")
        sink.set("origin", "https://ff14risingstones.web.sdo.com")
        sink.set("pragma", "no-cache")
        sink.set("referer", "https://ff14risingstones.web.sdo.com/")
        sink.set("sec-fetch-dest", "empty")
        sink.set("sec-fetch-mode", "cors")
        sink.set("sec-fetch-site", "same-site")
        sink.set("user-agent", userAgent)
        sink.set("x-requested-with", "com.sdo.sdaccountkey")
    }

    companion object {
        const val CookieName = "ff14risingstones"
    }
}

internal fun activeCookieSession(
    capabilities: Set<RisingStonesCapability>,
    displayName: String?,
): RisingStonesSessionState.Active = RisingStonesSessionState.Active(
    source = RisingStonesCredentialSource.WebCookie,
    capabilities = capabilities,
    displayName = displayName,
)

