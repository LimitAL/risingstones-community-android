package top.cxmeow.risingstones.auth.webview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cxmeow.risingstones.core.auth.ObservableRisingStonesSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState
import top.cxmeow.risingstones.network.RisingStonesApiException
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesWebCookieSessionProvider(
    private val store: RisingStonesCookieStore,
    private val sessionValidator: RisingStonesSessionValidator,
    private val webCookieJar: RisingStonesWebCookieJar = RisingStonesWebCookieJar.NoOp,
    private val rejectionClassifier: RisingStonesCredentialRejectionClassifier =
        RisingStonesCredentialRejectionClassifier.Default,
) : ObservableRisingStonesSessionProvider {
    private val mutableSessionState = MutableStateFlow<RisingStonesSessionState>(
        RisingStonesSessionState.SignedOut,
    )
    private val mutationMutex = Mutex()

    @Volatile
    private var credential: RisingStonesCookieCredential? = null

    override val sessionState: StateFlow<RisingStonesSessionState> =
        mutableSessionState.asStateFlow()

    override val capabilities: Set<RisingStonesCapability>
        get() = (mutableSessionState.value as? RisingStonesSessionState.Active)
            ?.capabilities
            .orEmpty()

    suspend fun restore() = mutationMutex.withLock {
        mutableSessionState.value = RisingStonesSessionState.Restoring
        val restored = try {
            store.read()
        } catch (error: CancellationException) {
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
            throw error
        } catch (_: Exception) {
            // validateAndActivate already cleared the rejected credential and exposed Invalid.
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
                credential = null
                mutableSessionState.value = RisingStonesSessionState.Invalid(error.message)
                try {
                    clearStoredAndWebCredentials()
                } catch (clearError: CancellationException) {
                    error.addSuppressed(clearError)
                    throw clearError
                } catch (clearError: Exception) {
                    error.addSuppressed(clearError)
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
                code in AuthenticationFailureCodes ||
                    AuthenticationFailureMessages.any(
                        cause.message.orEmpty().lowercase()::contains,
                    )
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
