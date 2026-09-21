package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.core.auth.ObservableRisingStonesSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

class CoreApiBoundary(
    provider: ObservableRisingStonesSessionProvider,
) {
    val state: StateFlow<RisingStonesSessionState> = provider.sessionState
}

suspend fun publishedExplicitAction(
    provider: top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider,
    context: top.cxmeow.risingstones.core.auth.RisingStonesRequestContext,
    sink: top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink,
) {
    // Compilation only; consumers must validate an actual explicit action before completing it.
    provider.beginCapabilityAttempt(context)?.use { attempt ->
        attempt.authorizer.authorize(context, sink)
        (attempt as? top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard)?.isCurrent()
    }
}

suspend fun publishedScopedAction(
    provider: top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScopeProvider,
    context: top.cxmeow.risingstones.core.auth.RisingStonesRequestContext,
) {
    provider.captureCapabilityScope(setOf(
        top.cxmeow.risingstones.core.auth.RisingStonesCapability.GuildWrite,
        top.cxmeow.risingstones.core.auth.RisingStonesCapability.GuildImageUpload,
    ))?.use { scope ->
        val readAuthorizer: top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer = scope.authorizer
        if (scope.isCurrent()) scope.beginCapabilityAttempt(context)?.close()
    }
}
