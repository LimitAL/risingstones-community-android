package top.cxmeow.risingstones.auth.webview

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScope
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScopeProvider
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.ObservableRisingStonesSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState
import top.cxmeow.risingstones.network.RisingStonesApiException
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesWebCookieSessionProvider(
    private val store: RisingStonesCookieStore,
    private val sessionValidator: RisingStonesSessionValidator,
    private val webCookieJar: RisingStonesWebCookieJar = RisingStonesWebCookieJar.NoOp,
    private val rejectionClassifier: RisingStonesCredentialRejectionClassifier =
        RisingStonesCredentialRejectionClassifier.Default,
) : ObservableRisingStonesSessionProvider, RisingStonesExplicitCapabilityProvider,
    RisingStonesCapabilityScopeProvider {
    private val mutableSessionState = MutableStateFlow<RisingStonesSessionState>(
        RisingStonesSessionState.SignedOut,
    )
    private val mutationMutex = Mutex()
    private var credentialGeneration = 0L
    // A temporarily lost prerequisite must not revive an operation captured before revocation.
    private val prerequisiteGenerations = mutableMapOf<RisingStonesCapability, Long>()
    private val mutableCredentialRevision = MutableStateFlow(0L)
    /** Opaque lifecycle counter; lets hosts clear protected models even when state emissions conflate. */
    val credentialRevision: StateFlow<Long> = mutableCredentialRevision.asStateFlow()
    private var verifiedWrites = emptySet<RisingStonesCapability>()

    @Volatile
    private var credential: RisingStonesCookieCredential? = null

    override val sessionState: StateFlow<RisingStonesSessionState> =
        mutableSessionState.asStateFlow()

    override val capabilities: Set<RisingStonesCapability>
        get() = (mutableSessionState.value as? RisingStonesSessionState.Active)
            ?.capabilities
            .orEmpty()

    suspend fun restore() = mutationMutex.withLock {
        val previousCredential = credential
        val previousActive = mutableSessionState.value as? RisingStonesSessionState.Active
        fun cancelRestore() {
            credential = previousCredential
            mutableSessionState.value = if (previousCredential != null && previousActive != null) {
                previousActive.copy(capabilities = previousActive.capabilities.filterTo(mutableSetOf()) {
                    prerequisiteFor(it) == null
                })
            } else RisingStonesSessionState.SignedOut
        }
        revokeVerifiedWrites()
        credential = null
        mutableSessionState.value = RisingStonesSessionState.Restoring
        val restored = try {
            store.read()
        } catch (error: CancellationException) {
            cancelRestore()
            throw error
        } catch (error: Exception) {
            credential = null
            mutableSessionState.value = RisingStonesSessionState.Invalid(error.message)
            return@withLock
        }
        if (restored == null) {
            credential = null
            mutableSessionState.value = RisingStonesSessionState.SignedOut
            return@withLock
        }
        try {
            validateAndActivate(restored, clearOnFailure = true)
        } catch (error: CancellationException) {
            cancelRestore()
            throw error
        } catch (_: Exception) {
            // Authorization remains unavailable. Only a confirmed rejection clears persistence.
        }
    }

    suspend fun accept(candidate: RisingStonesCookieCredential): Result<Unit> {
        return try {
            mutationMutex.withLock {
                validateAndActivate(candidate, clearOnFailure = false)
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun signOut(): Result<Unit> = mutationMutex.withLock {
        revokeVerifiedWrites()
        credential = null
        mutableSessionState.value = RisingStonesSessionState.SignedOut
        try {
            clearStoredAndWebCredentials()
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableSessionState.value = RisingStonesSessionState.Invalid(error.message)
            Result.failure(error)
        }
    }

    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? =
        credential?.authorizer()

    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? =
        mutationMutex.withLock {
            val candidate = credential ?: return@withLock null
            try {
                val validation = sessionValidator.validateSession(candidate.authorizer())
                invalidateMissingReadPrerequisites(validation.capabilities)
                verifiedWrites = verifiedWrites.filterTo(mutableSetOf()) {
                    prerequisiteFor(it) in validation.capabilities
                }
                mutableSessionState.value = activeCookieSession(
                    capabilities = validation.capabilities + verifiedWrites,
                    displayName = validation.displayName,
                )
                candidate.authorizer()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (rejectionClassifier.isRejected(error)) {
                    revokeVerifiedWrites()
                    credential = null
                    mutableSessionState.value = RisingStonesSessionState.Invalid(
                        "Rising Stones session expired",
                    )
                    try {
                        clearStoredAndWebCredentials()
                    } catch (clearError: CancellationException) {
                        throw clearError
                    } catch (_: Exception) {
                        // Memory authorization is already revoked even if persistence is unavailable.
                    }
                }
                null
            }
        }

    override fun canAttemptCapability(capability: RisingStonesCapability): Boolean =
        credential != null && mutableSessionState.value is RisingStonesSessionState.Active &&
            prerequisiteFor(capability)?.let { it in capabilities } == true

    override suspend fun beginCapabilityAttempt(
        context: RisingStonesRequestContext,
    ): RisingStonesCapabilityAttempt? = mutationMutex.withLock {
        createCapabilityAttempt(context)
    }

    override suspend fun captureCapabilityScope(
        capabilities: Set<RisingStonesCapability>,
    ): RisingStonesCapabilityScope? = mutationMutex.withLock {
        currentCoroutineContext().ensureActive()
        val requestedCapabilities = capabilities.toSet()
        val prerequisite = requestedCapabilities
            .firstOrNull()
            ?.let(::prerequisiteFor)
            ?: return@withLock null
        if (prerequisite !in setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.DynamicRead)) {
            return@withLock null
        }
        if (requestedCapabilities.isEmpty() || requestedCapabilities.any {
                prerequisiteFor(it) != prerequisite || !canAttemptCapability(it)
            }
        ) return@withLock null
        val capturedCredential = credential ?: return@withLock null
        val generation = credentialGeneration
        val prerequisiteGeneration = prerequisiteGenerations[prerequisite] ?: 0L
        object : RisingStonesCapabilityScope {
            private val closed = AtomicBoolean(false)
            private fun belongsToCurrentScope(): Boolean = !closed.get() &&
                generation == credentialGeneration && credential === capturedCredential &&
                prerequisiteGeneration == (prerequisiteGenerations[prerequisite] ?: 0L) &&
                requestedCapabilities.all(::canAttemptCapability)

            override val authorizer = RisingStonesRequestAuthorizer { context, sink ->
                mutationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    check(context.requirement == RisingStonesAuthenticationRequirement.Required &&
                        context.capability == prerequisite && context.path.isNotBlank() &&
                        belongsToCurrentScope()) {
                        "Rising Stones operation authorization is no longer available"
                    }
                    capturedCredential.authorizer().authorize(context, sink)
                }
            }

            override suspend fun isCurrent(): Boolean = mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                belongsToCurrentScope()
            }

            override suspend fun beginCapabilityAttempt(
                context: RisingStonesRequestContext,
            ): RisingStonesCapabilityAttempt? = mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (context.capability !in requestedCapabilities || !belongsToCurrentScope()) return@withLock null
                createCapabilityAttempt(context, ::belongsToCurrentScope)
            }

            override fun close() { closed.set(true) }
        }
    }

    /** Called only while holding mutationMutex; returned attempts recheck their binding on use. */
    private fun createCapabilityAttempt(
        context: RisingStonesRequestContext,
        scopeIsCurrent: () -> Boolean = { true },
    ): RisingStonesCapabilityAttempt? {
        val capability = context.capability ?: return null
        if (context.requirement != RisingStonesAuthenticationRequirement.Required ||
            context.path.isBlank() || !canAttemptCapability(capability)
        ) return null
        val capturedCredential = credential ?: return null
        val generation = credentialGeneration
        val prerequisite = prerequisiteFor(capability)
        val prerequisiteGeneration = prerequisite?.let { prerequisiteGenerations[it] ?: 0L }
        return object : RisingStonesCapabilityAttempt, RisingStonesCapabilityAttemptGuard {
            // 0 = unused, 1 = authorized once, 2 = closed or completed.
            private val lifecycle = AtomicInteger(0)
            private fun belongsToCurrentCredential() = generation == credentialGeneration &&
                credential === capturedCredential && canAttemptCapability(capability) &&
                (prerequisite == null || prerequisiteGeneration == (prerequisiteGenerations[prerequisite] ?: 0L)) &&
                scopeIsCurrent()

            override suspend fun isCurrent(): Boolean = mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                lifecycle.get() == 1 && belongsToCurrentCredential()
            }

            override val authorizer = RisingStonesRequestAuthorizer { requested, sink ->
                mutationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    check(requested == context && belongsToCurrentCredential() && lifecycle.compareAndSet(0, 1)) {
                        "Rising Stones action authorization is no longer available"
                    }
                    capturedCredential.authorizer().authorize(requested, sink)
                }
            }

            override suspend fun complete(): Boolean = mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!belongsToCurrentCredential() || !lifecycle.compareAndSet(1, 2)) return@withLock false
                val active = mutableSessionState.value as? RisingStonesSessionState.Active
                    ?: return@withLock false
                verifiedWrites = verifiedWrites + capability
                mutableSessionState.value = active.copy(capabilities = active.capabilities + capability)
                true
            }

            override fun close() { lifecycle.set(2) }
        }
    }

    private fun revokeVerifiedWrites() {
        credentialGeneration++
        mutableCredentialRevision.value = credentialGeneration
        verifiedWrites = emptySet()
    }

    private fun prerequisiteFor(capability: RisingStonesCapability): RisingStonesCapability? =
        when (capability) {
            RisingStonesCapability.DailySignIn,
            RisingStonesCapability.ForumWrite,
            RisingStonesCapability.RecruitmentWrite,
            RisingStonesCapability.ForumImageUpload -> RisingStonesCapability.AccountRead
            RisingStonesCapability.GuildWrite,
            RisingStonesCapability.GuildImageUpload -> RisingStonesCapability.GuildRead
            RisingStonesCapability.DynamicWrite,
            RisingStonesCapability.DynamicImageUpload -> RisingStonesCapability.DynamicRead
            else -> null
        }

    private fun invalidateMissingReadPrerequisites(capabilities: Set<RisingStonesCapability>) {
        setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.DynamicRead)
            .filterNot(capabilities::contains)
            .forEach { prerequisite ->
                prerequisiteGenerations[prerequisite] = (prerequisiteGenerations[prerequisite] ?: 0L) + 1L
            }
    }

    private suspend fun validateAndActivate(
        candidate: RisingStonesCookieCredential,
        clearOnFailure: Boolean,
    ) {
        val previousCredential = credential
        val previousState = mutableSessionState.value.let { state ->
            when (state) {
                RisingStonesSessionState.Restoring,
                RisingStonesSessionState.Validating,
                -> RisingStonesSessionState.SignedOut

                else -> state
            }
        }
        mutableSessionState.value = RisingStonesSessionState.Validating
        try {
            val validation = sessionValidator.validateSession(candidate.authorizer())
            store.write(candidate)
            revokeVerifiedWrites()
            credential = candidate
            mutableSessionState.value = activeCookieSession(
                capabilities = validation.capabilities,
                displayName = validation.displayName,
            )
        } catch (error: CancellationException) {
            credential = previousCredential
            mutableSessionState.value = previousState
            throw error
        } catch (error: Exception) {
            if (clearOnFailure) {
                revokeVerifiedWrites()
                credential = null
                mutableSessionState.value = RisingStonesSessionState.Invalid(error.message)
                if (rejectionClassifier.isRejected(error)) {
                    try {
                        clearStoredAndWebCredentials()
                    } catch (clearError: CancellationException) {
                        error.addSuppressed(clearError)
                        throw clearError
                    } catch (clearError: Exception) {
                        error.addSuppressed(clearError)
                    }
                }
            } else if (
                previousCredential == null &&
                rejectionClassifier.isRejected(error)
            ) {
                credential = null
                mutableSessionState.value = RisingStonesSessionState.SignedOut
            } else {
                credential = previousCredential
                mutableSessionState.value = if (previousCredential == null) {
                    RisingStonesSessionState.Invalid(error.message)
                } else {
                    previousState
                }
            }
            throw error
        }
    }

    private suspend fun clearStoredAndWebCredentials() {
        var firstFailure: Exception? = null
        try {
            store.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            firstFailure = error
        }
        try {
            webCookieJar.clearRisingStonesCredential()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            firstFailure?.addSuppressed(error)
            if (firstFailure == null) firstFailure = error
        }
        firstFailure?.let { cause ->
            throw RisingStonesSignOutException(cause)
        }
    }
}

class RisingStonesSignOutException(cause: Throwable) :
    Exception("Unable to fully disconnect the Rising Stones session", cause)

fun interface RisingStonesCredentialRejectionClassifier {
    fun isRejected(error: Throwable): Boolean

    companion object {
        val Default = RisingStonesCredentialRejectionClassifier { error ->
            error.causeChain().any { cause ->
                val code = (cause as? RisingStonesApiException)?.code
                !RisingStonesResponsePolicy.accepts(code) && (code in AuthenticationFailureCodes ||
                    AuthenticationFailureMessages.any(
                        cause.message.orEmpty().lowercase()::contains,
                    ))
            }
        }

        private val AuthenticationFailureCodes = setOf(
            401,
            403,
            10001,
            10003,
            10004,
            10005,
            10403,
        )
        private val AuthenticationFailureMessages = setOf(
            "请先登录",
            "未登录",
            "登录失效",
            "登录过期",
            "session is not valid",
            "session expired",
            "unauthorized",
        )
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause }
