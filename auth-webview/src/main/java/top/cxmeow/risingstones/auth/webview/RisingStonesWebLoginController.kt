package top.cxmeow.risingstones.auth.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import top.cxmeow.risingstones.network.OfficialRisingStonesEndpoints
import java.net.URI

data class RisingStonesWebLoginState(
    val isLoading: Boolean = false,
    val currentHost: String? = null,
)

class RisingStonesWebLoginController(
    private val context: Context,
    private val onCredentialCandidate: (RisingStonesCookieCredential) -> Unit,
    private val onStateChange: (RisingStonesWebLoginState) -> Unit = {},
    private val onBlockedNavigation: (String) -> Unit = {},
    private val navigationPolicy: RisingStonesOfficialNavigationPolicy =
        RisingStonesOfficialNavigationPolicy(),
) {
    private val candidateGate = RisingStonesCookieCandidateGate()
    private val navigationTracker = RisingStonesAuthenticationNavigationTracker()
    private var pageLoading = false

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        candidateGate.startAttempt()
        navigationTracker.startAttempt()
        pageLoading = false
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.safeBrowsingEnabled = true
            removeJavascriptInterface("searchBoxJavaBridge_")
            removeJavascriptInterface("accessibility")
            removeJavascriptInterface("accessibilityTraversal")
            cookieManager.setAcceptThirdPartyCookies(this, true)
            webViewClient = LoginWebViewClient()
            loadUrl(OfficialRisingStonesEndpoints.LoginUrl)
        }
    }

    fun retryCredentialInspection(webView: WebView) {
        candidateGate.startAttempt()
        inspectCookie(webView)
    }

    private inner class LoginWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            if (navigationPolicy.allows(url)) {
                navigationTracker.record(url)
                return false
            }
            onBlockedNavigation(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            url?.let(navigationTracker::record)
            pageLoading = true
            onStateChange(
                RisingStonesWebLoginState(
                    isLoading = true,
                    currentHost = url?.let(Uri::parse)?.host,
                ),
            )
        }

        override fun onPageFinished(view: WebView, url: String?) {
            url?.let(navigationTracker::record)
            pageLoading = false
            onStateChange(
                RisingStonesWebLoginState(
                    isLoading = false,
                    currentHost = url?.let(Uri::parse)?.host,
                ),
            )
            inspectCookie(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (!pageLoading) {
                inspectCookie(view)
            }
        }
    }

    private fun inspectCookie(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        val candidate = sequenceOf(
            OfficialRisingStonesEndpoints.WebBaseUrl,
            OfficialRisingStonesEndpoints.ApiBaseUrl,
        ).mapNotNull(cookieManager::getCookie)
            .mapNotNull { header -> risingStonesCookieValue(header) }
            .firstOrNull()
            ?: return
        val userAgent = webView.settings.userAgentString
        if (!candidateGate.shouldEmit(candidate, userAgent, navigationTracker.returnGeneration)) return
        cookieManager.flush()
        onCredentialCandidate(
            RisingStonesCookieCredential(
                cookie = candidate,
                userAgent = userAgent,
            ),
        )
    }
}

internal class RisingStonesCookieCandidateGate {
    private var lastCandidateKey: Triple<String, String, Int>? = null

    fun startAttempt() {
        lastCandidateKey = null
    }

    fun shouldEmit(candidate: String, userAgent: String, returnGeneration: Int): Boolean {
        val candidateKey = Triple(candidate, userAgent, returnGeneration)
        if (candidateKey == lastCandidateKey) return false
        lastCandidateKey = candidateKey
        return true
    }
}

internal class RisingStonesAuthenticationNavigationTracker(
    private val forumHosts: Set<String> = setOf(
        "ff14risingstones.web.sdo.com",
        "apiff14risingstones.web.sdo.com",
    ),
) {
    var returnGeneration: Int = 0
        private set
    private var hasVisitedAuthenticationHost: Boolean = false

    fun startAttempt() {
        returnGeneration = 0
        hasVisitedAuthenticationHost = false
    }

    fun record(rawUrl: String) {
        val host = runCatching { URI(rawUrl).host?.lowercase()?.trimEnd('.') }
            .getOrNull()
            ?: return
        if (host in forumHosts) {
            if (hasVisitedAuthenticationHost) {
                returnGeneration += 1
                hasVisitedAuthenticationHost = false
            }
        } else {
            hasVisitedAuthenticationHost = true
        }
    }
}

class RisingStonesOfficialNavigationPolicy(
    private val allowedRootDomains: Set<String> = setOf("sdo.com", "daoyu8.com"),
) {
    fun allows(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return allowedRootDomains.any { root ->
            host == root || host.endsWith(".$root")
        }
    }
}

internal fun risingStonesCookieValue(cookieHeader: String): String? =
    cookieHeader.split(';')
        .asSequence()
        .map(String::trim)
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            entry.substring(0, separator) to entry.substring(separator + 1)
        }
        .firstOrNull { (name, value) ->
            name == RisingStonesCookieCredential.CookieName && value.isNotBlank()
        }
        ?.second
