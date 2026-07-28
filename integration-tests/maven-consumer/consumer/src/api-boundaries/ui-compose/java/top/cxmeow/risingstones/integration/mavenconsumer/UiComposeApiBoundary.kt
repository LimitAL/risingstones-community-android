package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.ui.compose.RisingStonesWebLoginScreen

@Composable
fun PublishedLoginScreen(
    provider: RisingStonesWebCookieSessionProvider,
    modifier: Modifier = Modifier,
) {
    RisingStonesWebLoginScreen(
        sessionProvider = provider,
        modifier = modifier,
    )
}
