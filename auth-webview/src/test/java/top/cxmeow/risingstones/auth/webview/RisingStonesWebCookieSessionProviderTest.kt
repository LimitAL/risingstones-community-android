package top.cxmeow.risingstones.auth.webview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState
import top.cxmeow.risingstones.network.RisingStonesApiException
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesWebCookieSessionProviderTest {
    @Test
    fun validatedCandidateBecomesActiveAndIsPersisted() = runTest {
        val store = FakeCookieStore()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            sessionValidator = RisingStonesSessionValidator {
                RisingStonesSessionValidation(
                    displayName = "光之战士",
                    code = 10000,
                    capabilities = setOf(RisingStonesCapability.AccountRead),
                )
            },
        )
        val candidate = credential("current")

        assertTrue(provider.accept(candidate).isSuccess)

        assertSame(candidate, store.credential)
        val active = provider.sessionState.value as RisingStonesSessionState.Active
        assertEquals("光之战士", active.displayName)
        assertEquals(setOf(RisingStonesCapability.AccountRead), active.capabilities)
        assertEquals(setOf("ff14risingstones=current"), provider.currentCookieHeaders())
    }

    @Test
    fun validStoredCredentialIsRestored() = runTest {
        val stored = credential("stored")
        val provider = RisingStonesWebCookieSessionProvider(
            store = FakeCookieStore(credential = stored),
            sessionValidator = successfulValidator(displayName = "Restored"),
        )

        provider.restore()

        val active = provider.sessionState.value as RisingStonesSessionState.Active
        assertEquals("Restored", active.displayName)
        assertEquals(setOf("ff14risingstones=stored"), provider.currentCookieHeaders())
    }

    @Test
    fun rejectedStoredCredentialIsClearedWithoutEscapingRestore() = runTest {
        val store = FakeCookieStore(credential = credential("expired"))
        val webCookieJar = FakeWebCookieJar()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            webCookieJar = webCookieJar,
            sessionValidator = RisingStonesSessionValidator {
                error("session expired")
            },
        )

        provider.restore()

        assertTrue(provider.sessionState.value is RisingStonesSessionState.Invalid)
        assertNull(store.credential)
        assertEquals(1, store.clearCount)
        assertEquals(1, webCookieJar.clearCount)
        assertTrue(provider.currentCookieHeaders().isEmpty())
    }

    @Test
    fun storeReadFailureBecomesInvalidStateInsteadOfEscapingRestore() = runTest {
        val provider = RisingStonesWebCookieSessionProvider(
            store = FakeCookieStore(readFailure = IllegalStateException("store unavailable")),
            sessionValidator = successfulValidator(),
        )

        provider.restore()

        val invalid = provider.sessionState.value as RisingStonesSessionState.Invalid
        assertEquals("store unavailable", invalid.reason)
        assertTrue(provider.currentCookieHeaders().isEmpty())
    }

    @Test
    fun cancelledValidationRestoresPreviousStateAndPropagatesCancellation() = runTest {
        val provider = RisingStonesWebCookieSessionProvider(
            store = FakeCookieStore(),
            sessionValidator = RisingStonesSessionValidator {
                throw CancellationException("cancel validation")
            },
        )

        var cancellation: CancellationException? = null
        try {
            provider.accept(credential("candidate"))
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertEquals("cancel validation", cancellation?.message)
        assertSame(RisingStonesSessionState.SignedOut, provider.sessionState.value)
    }

    @Test
    fun failedReloginKeepsPreviouslyValidatedCredential() = runTest {
        var shouldFail = false
        val store = FakeCookieStore()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            sessionValidator = RisingStonesSessionValidator {
                if (shouldFail) error("invalid candidate")
                RisingStonesSessionValidation(
                    displayName = "Existing",
                    code = 10000,
                    capabilities = setOf(RisingStonesCapability.AccountRead),
                )
            },
        )
        val existing = credential("existing")
        assertTrue(provider.accept(existing).isSuccess)
        shouldFail = true

        assertTrue(provider.accept(credential("replacement")).isFailure)

        assertSame(existing, store.credential)
        assertEquals("Existing", (provider.sessionState.value as RisingStonesSessionState.Active).displayName)
        assertEquals(setOf("ff14risingstones=existing"), provider.currentCookieHeaders())
    }

    @Test
    fun explicitlyRejectedFirstCandidateStaysAvailableForWebLoginContinuation() = runTest {
        val store = FakeCookieStore()
        val webCookieJar = FakeWebCookieJar()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            webCookieJar = webCookieJar,
            sessionValidator = RisingStonesSessionValidator {
                throw RisingStonesApiException("请先登录", code = 10001)
            },
        )

        val result = provider.accept(credential("stale"))

        assertTrue(result.isFailure)
        assertSame(RisingStonesSessionState.SignedOut, provider.sessionState.value)
        assertEquals(0, store.clearCount)
        assertEquals(0, webCookieJar.clearCount)
        assertTrue(provider.currentCookieHeaders().isEmpty())
    }

    @Test
    fun transientFirstCandidateFailureKeepsWebCookieAvailableForRetry() = runTest {
        val store = FakeCookieStore()
        val webCookieJar = FakeWebCookieJar()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            webCookieJar = webCookieJar,
            sessionValidator = RisingStonesSessionValidator {
                error("temporary network failure")
            },
        )

        val result = provider.accept(credential("retryable"))

        assertTrue(result.isFailure)
        assertEquals(0, store.clearCount)
        assertEquals(0, webCookieJar.clearCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun concurrentCandidatesAreValidatedAndPersistedInArrivalOrder() = runTest {
        val firstValidationStarted = CompletableDeferred<Unit>()
        val releaseFirstValidation = CompletableDeferred<Unit>()
        var validationCalls = 0
        val store = FakeCookieStore()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            sessionValidator = RisingStonesSessionValidator { authorizer ->
                validationCalls += 1
                val cookieHeader = authorizer.cookieHeaders().single()
                if (cookieHeader == "ff14risingstones=first") {
                    firstValidationStarted.complete(Unit)
                    releaseFirstValidation.await()
                }
                RisingStonesSessionValidation(
                    displayName = cookieHeader,
                    code = 10000,
                    capabilities = setOf(RisingStonesCapability.AccountRead),
                )
            },
        )

        val first = async { provider.accept(credential("first")) }
        firstValidationStarted.await()
        val second = async { provider.accept(credential("second")) }
        runCurrent()

        assertEquals(1, validationCalls)

        releaseFirstValidation.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(2, validationCalls)
        assertEquals(setOf("ff14risingstones=second"), provider.currentCookieHeaders())
        assertEquals("second", store.credential?.cookie)
    }

    @Test
    fun signOutClearsEncryptedAndWebViewCredentials() = runTest {
        val store = FakeCookieStore()
        val webCookieJar = FakeWebCookieJar()
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            sessionValidator = successfulValidator(),
            webCookieJar = webCookieJar,
        )
        assertTrue(provider.accept(credential("active")).isSuccess)

        val result = provider.signOut()

        assertTrue(result.isSuccess)
        assertEquals(1, store.clearCount)
        assertEquals(1, webCookieJar.clearCount)
        assertSame(RisingStonesSessionState.SignedOut, provider.sessionState.value)
        assertTrue(provider.currentCookieHeaders().isEmpty())
    }

    @Test
    fun signOutFailureRevokesInMemoryCredentialAndReportsInvalidState() = runTest {
        val store = FakeCookieStore()
        val webCookieJar = FakeWebCookieJar(
            clearFailure = IllegalStateException("web cookie unavailable"),
        )
        val provider = RisingStonesWebCookieSessionProvider(
            store = store,
            sessionValidator = successfulValidator(),
            webCookieJar = webCookieJar,
        )
        assertTrue(provider.accept(credential("active")).isSuccess)

        val result = provider.signOut()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RisingStonesSignOutException)
        assertTrue(provider.sessionState.value is RisingStonesSessionState.Invalid)
        assertTrue(provider.currentCookieHeaders().isEmpty())
        assertEquals(1, store.clearCount)
        assertEquals(1, webCookieJar.clearCount)
    }
}

private class FakeCookieStore(
    var credential: RisingStonesCookieCredential? = null,
    private val readFailure: Exception? = null,
    private val clearFailure: Exception? = null,
) : RisingStonesCookieStore {
    var clearCount: Int = 0

    override suspend fun read(): RisingStonesCookieCredential? {
        readFailure?.let { throw it }
        return credential
    }

    override suspend fun write(credential: RisingStonesCookieCredential) {
        this.credential = credential
    }

    override suspend fun clear() {
        clearCount += 1
        clearFailure?.let { throw it }
        credential = null
    }
}

private class FakeWebCookieJar(
    private val clearFailure: Exception? = null,
) : RisingStonesWebCookieJar {
    var clearCount: Int = 0

    override suspend fun clearRisingStonesCredential() {
        clearCount += 1
        clearFailure?.let { throw it }
    }
}

private fun credential(cookie: String) = RisingStonesCookieCredential(
    cookie = cookie,
    userAgent = "RisingStonesTest/1.0",
)

private suspend fun RisingStonesWebCookieSessionProvider.currentCookieHeaders(): Set<String> {
    return currentAuthorizer()?.cookieHeaders().orEmpty()
}

private suspend fun top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer.cookieHeaders():
    Set<String> {
    val values = mutableSetOf<String>()
    authorize(
        context = RisingStonesRequestContext(
            path = "test",
            requirement = top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement.Required,
        ),
        sink = RisingStonesHeaderSink { name, value ->
            if (name.equals("cookie", ignoreCase = true)) values += value
        },
    )
    return values
}

private fun successfulValidator(
    displayName: String? = "Valid",
): RisingStonesSessionValidator = RisingStonesSessionValidator {
    RisingStonesSessionValidation(
        displayName = displayName,
        code = 10000,
        capabilities = setOf(RisingStonesCapability.AccountRead),
    )
}
