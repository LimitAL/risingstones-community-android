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
}
