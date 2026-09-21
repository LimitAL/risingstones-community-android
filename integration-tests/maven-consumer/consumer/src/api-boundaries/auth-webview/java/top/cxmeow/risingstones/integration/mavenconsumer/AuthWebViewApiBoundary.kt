package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.auth.webview.RisingStonesCookieStore
import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class AuthWebViewApiBoundary(
    store: RisingStonesCookieStore,
    validator: RisingStonesSessionValidator,
) {
    val provider = RisingStonesWebCookieSessionProvider(
        store = store,
        sessionValidator = validator,
    )
    val credentialRevision: kotlinx.coroutines.flow.StateFlow<Long> = provider.credentialRevision
}

fun publishedExplicitProvider(provider: RisingStonesWebCookieSessionProvider):
    top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider = provider
