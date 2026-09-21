package top.cxmeow.risingstones.core.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * A capability is exposed only after the corresponding current official endpoint has been
 * verified with the active credential source.
 */
enum class RisingStonesCapability {
    AccountRead,
    DailySignIn,
    ForumWrite,
    ForumImageUpload,
    RecruitmentAuthenticated,
    RecruitmentWrite,
    GlamourAuthenticated,
    PersonalData,
    DynamicRead,
    MessageRead,
    GuildRead,
    GuildWrite,
    GuildImageUpload,
}

enum class RisingStonesCredentialSource {
    WebCookie,
    HostProvided,
}

enum class RisingStonesAuthenticationRequirement {
    Anonymous,
    Optional,
    Required,
}

data class RisingStonesRequestContext(
    val path: String,
    val requirement: RisingStonesAuthenticationRequirement,
    val capability: RisingStonesCapability? = null,
)

sealed interface RisingStonesSessionState {
    data object SignedOut : RisingStonesSessionState
    data object Restoring : RisingStonesSessionState
    data object Validating : RisingStonesSessionState

    data class Active(
        val source: RisingStonesCredentialSource,
        val capabilities: Set<RisingStonesCapability>,
        val displayName: String? = null,
    ) : RisingStonesSessionState

    data class Invalid(val reason: String?) : RisingStonesSessionState
}

fun interface RisingStonesHeaderSink {
    fun set(name: String, value: String)
}

/**
 * Applies sensitive request headers directly to a caller-provided sink.
 *
 * Implementations must not expose credentials through [toString], logs, analytics, or exceptions.
 */
fun interface RisingStonesRequestAuthorizer {
    suspend fun authorize(context: RisingStonesRequestContext, sink: RisingStonesHeaderSink)
}

/**
 * Authentication-source-independent contract consumed by reusable Rising Stones features.
 *
 * The standalone app supplies a WebView-cookie implementation. A host app may supply a different
 * implementation without changing feature repositories or presentation state.
 */
interface RisingStonesSessionProvider {
    val capabilities: Set<RisingStonesCapability>

    suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer?

    suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? = null
}

/**
 * Optional extension for credential sources that require explicit user confirmation before a
 * session active on another device may be reclaimed.
 */
interface RisingStonesIdentityConflictResolver {
    suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer?
}

interface ObservableRisingStonesSessionProvider : RisingStonesSessionProvider {
    val sessionState: StateFlow<RisingStonesSessionState>
}

fun RisingStonesSessionState.supports(capability: RisingStonesCapability): Boolean =
    this is RisingStonesSessionState.Active && capability in capabilities
