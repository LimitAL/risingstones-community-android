package top.cxmeow.risingstones.core.auth

/** Optional first-use verification through a user-selected write. Eligibility is not a capability. */
interface RisingStonesExplicitCapabilityProvider {
    fun canAttemptCapability(capability: RisingStonesCapability): Boolean

    /** Binds one explicit action to the current credential and exact required request context. */
    suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt?
}

/**
 * Opaque single-request authorization. Validate the official success payload before [complete].
 * Always [close] in finally. Never retry a write whose outcome is unknown.
 */
interface RisingStonesCapabilityAttempt : AutoCloseable {
    val authorizer: RisingStonesRequestAuthorizer
    suspend fun complete(): Boolean
    override fun close()
}

/**
 * Optional checkpoint for a staged action, such as a token read followed by an object upload.
 * Checks the already-authorized attempt without granting a capability or exposing credentials.
 * Call immediately before the next network stage; cancellation must continue to propagate.
 */
interface RisingStonesCapabilityAttemptGuard {
    suspend fun isCurrent(): Boolean
}

/** Optional credential binding for one user-selected operation containing multiple network stages. */
interface RisingStonesCapabilityScopeProvider {
    /** Returns null when the requested capability set is unsupported or its prerequisites are absent. */
    suspend fun captureCapabilityScope(
        capabilities: Set<RisingStonesCapability>,
    ): RisingStonesCapabilityScope?
}

/**
 * Opaque credential and prerequisite binding. No capture or check grants a capability.
 * A lost prerequisite permanently invalidates the scope, even if the same credential regains it.
 * Close when the operation and any user-selected retry are discarded or finished.
 */
interface RisingStonesCapabilityScope : AutoCloseable {
    /** Authorizes prerequisite reads using the captured credential while the scope remains current. */
    val authorizer: RisingStonesRequestAuthorizer

    suspend fun isCurrent(): Boolean

    /** Atomically checks this scope and binds one of its requested capabilities to an exact context. */
    suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt?

    /** Invalidates this scope and its unfinished attempts. Completing an attempt does not close it. */
    override fun close()
}
