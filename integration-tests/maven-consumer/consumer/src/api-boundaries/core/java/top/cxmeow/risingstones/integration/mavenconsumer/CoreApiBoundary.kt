package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.core.auth.ObservableRisingStonesSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

class CoreApiBoundary(
    provider: ObservableRisingStonesSessionProvider,
) {
    val state: StateFlow<RisingStonesSessionState> = provider.sessionState
}
