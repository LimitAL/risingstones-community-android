package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel
import top.cxmeow.risingstones.feature.account.ui.compose.RisingStonesAccountGuildNavigation
import top.cxmeow.risingstones.feature.account.ui.compose.RisingStonesAccountScreen

@Composable
fun publishedAccountGuildEntry(model: RisingStonesAccountViewModel, onOpenGuild: (() -> Unit)?) {
    RisingStonesAccountGuildNavigation(onOpenGuild) {
        RisingStonesAccountScreen(model, null, false, {}, {})
    }
}
