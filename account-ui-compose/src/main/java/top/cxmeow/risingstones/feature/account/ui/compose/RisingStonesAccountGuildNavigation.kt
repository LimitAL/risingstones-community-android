package top.cxmeow.risingstones.feature.account.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalAccountGuildNavigation = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Optional host navigation; supply it only after the current session has verified guild reading. */
@Composable
fun RisingStonesAccountGuildNavigation(onOpenGuild: (() -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccountGuildNavigation provides onOpenGuild, content = content)
}
