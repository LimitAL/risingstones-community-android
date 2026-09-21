package top.cxmeow.risingstones.auth.webview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesGuildCapabilityScopeTest {
    @Test
    fun capturingRequiresGuildReadAndNeitherCaptureNorReadGrantsWrites() = runTest {
        val fixture = GuildScopeFixture(setOf(RisingStonesCapability.AccountRead))
        fixture.activate()
        assertNull(fixture.provider.captureCapabilityScope(GuildActions))
        fixture.capabilities = setOf(RisingStonesCapability.GuildRead)
        assertNotNull(fixture.provider.refreshAuthorizer())

        val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        assertTrue(scope.isCurrent())
        val headerNames = mutableSetOf<String>()
        repeat(2) {
            scope.authorizer.authorize(GuildReadContext, RisingStonesHeaderSink { name, _ -> headerNames += name })
        }
        assertTrue("cookie" in headerNames)
        assertTrue("user-agent" in headerNames)
        assertEquals(setOf(RisingStonesCapability.GuildRead), fixture.provider.capabilities)
        scope.close()
    }

    @Test
    fun completedUploadAndFailedRegistrationCanKeepTheSameScopeForExplicitRetry() = runTest {
        val fixture = GuildScopeFixture()
        fixture.activate()
        val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        val upload = requireNotNull(scope.beginCapabilityAttempt(GuildImageContext))
        authorize(upload, GuildImageContext)
        assertTrue((upload as RisingStonesCapabilityAttemptGuard).isCurrent())
        assertTrue(upload.complete())
        upload.close()
        assertTrue(scope.isCurrent())
        assertEquals(setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.GuildImageUpload),
            fixture.provider.capabilities)

        val failedRegistration = requireNotNull(scope.beginCapabilityAttempt(GuildWriteContext))
        authorize(failedRegistration, GuildWriteContext)
        failedRegistration.close()
        assertFalse(failedRegistration.complete())
        assertTrue(scope.isCurrent())
        assertFalse(RisingStonesCapability.GuildWrite in fixture.provider.capabilities)

        val retry = requireNotNull(scope.beginCapabilityAttempt(GuildWriteContext))
        authorize(retry, GuildWriteContext)
        assertTrue(retry.complete())
        assertFalse(retry.complete())
        assertTrue(scope.isCurrent())
        assertEquals(GuildActions + RisingStonesCapability.GuildRead, fixture.provider.capabilities)
        scope.close()
        assertFalse(scope.isCurrent())
    }

    @Test
    fun closingScopeInvalidatesUnusedAndAuthorizedChildren() = runTest {
        val fixture = GuildScopeFixture()
        fixture.activate()
        val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        val unused = requireNotNull(scope.beginCapabilityAttempt(GuildWriteContext))
        val authorized = requireNotNull(scope.beginCapabilityAttempt(GuildImageContext))
        authorize(authorized, GuildImageContext)
        scope.close()
        scope.close()

        assertFalse(scope.isCurrent())
        assertNull(scope.beginCapabilityAttempt(GuildWriteContext))
        assertRejectedWithoutHeaders(scope.authorizer, GuildReadContext)
        assertRejectedWithoutHeaders(unused.authorizer, GuildWriteContext)
        assertFalse((authorized as RisingStonesCapabilityAttemptGuard).isCurrent())
        assertFalse(unused.complete())
        assertFalse(authorized.complete())
        assertEquals(setOf(RisingStonesCapability.GuildRead), fixture.provider.capabilities)
    }

    @Test
    fun credentialMutationBetweenCheckpointAndNextAttemptCannotSwitchTheOperationToNewCredentials() = runTest {
        for (mutation in GuildScopeMutation.entries) {
            val fixture = GuildScopeFixture()
            fixture.activate()
            val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
            val unused = requireNotNull(scope.beginCapabilityAttempt(GuildWriteContext))
            val upload = requireNotNull(scope.beginCapabilityAttempt(GuildImageContext))
            authorize(upload, GuildImageContext)
            assertTrue(scope.isCurrent())

            when (mutation) {
                GuildScopeMutation.Accept -> fixture.activate("replacement")
                GuildScopeMutation.Restore -> fixture.provider.restore()
                GuildScopeMutation.SignOut -> assertTrue(fixture.provider.signOut().isSuccess)
            }

            assertFalse(scope.isCurrent())
            assertNull(scope.beginCapabilityAttempt(GuildWriteContext))
            assertRejectedWithoutHeaders(scope.authorizer, GuildReadContext)
            assertRejectedWithoutHeaders(unused.authorizer, GuildWriteContext)
            assertFalse((upload as RisingStonesCapabilityAttemptGuard).isCurrent())
            assertFalse(upload.complete())
            assertFalse(unused.complete())
            GuildActions.forEach { assertFalse(it in fixture.provider.capabilities) }
            scope.close()
        }
    }

    @Test
    fun losingAndRegainingGuildReadPermanentlyInvalidatesScopeAndItsChildren() = runTest {
        val fixture = GuildScopeFixture()
        fixture.activate()
        val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        val child = requireNotNull(scope.beginCapabilityAttempt(GuildWriteContext))
        authorize(child, GuildWriteContext)
        val credentialRevision = fixture.provider.credentialRevision.value

        fixture.capabilities = setOf(RisingStonesCapability.AccountRead)
        assertNotNull(fixture.provider.refreshAuthorizer())
        assertFalse(scope.isCurrent())
        fixture.capabilities = setOf(RisingStonesCapability.GuildRead)
        assertNotNull(fixture.provider.refreshAuthorizer())
        assertEquals(credentialRevision, fixture.provider.credentialRevision.value)
        assertTrue(fixture.provider.canAttemptCapability(RisingStonesCapability.GuildWrite))
        assertFalse(scope.isCurrent())
        assertNull(scope.beginCapabilityAttempt(GuildWriteContext))
        assertRejectedWithoutHeaders(scope.authorizer, GuildReadContext)
        assertFalse((child as RisingStonesCapabilityAttemptGuard).isCurrent())
        assertFalse(child.complete())
        assertEquals(setOf(RisingStonesCapability.GuildRead), fixture.provider.capabilities)
        val replacementScope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        assertTrue(replacementScope.isCurrent())
        replacementScope.close()
        scope.close()
    }

    @Test
    fun successfulReadRevalidationKeepsScopeAndChildrenBoundWithoutGranting() = runTest {
        val fixture = GuildScopeFixture()
        fixture.activate()
        val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        val child = requireNotNull(scope.beginCapabilityAttempt(GuildImageContext))
        authorize(child, GuildImageContext)
        assertNotNull(fixture.provider.refreshAuthorizer())
        assertTrue(scope.isCurrent())
        assertTrue((child as RisingStonesCapabilityAttemptGuard).isCurrent())
        assertEquals(setOf(RisingStonesCapability.GuildRead), fixture.provider.capabilities)
        assertTrue(child.complete())
        assertTrue(scope.isCurrent())
        scope.close()
    }

    @Test
    fun scopeRejectsUnrequestedCapabilitiesAndInvalidReadContexts() = runTest {
        val fixture = GuildScopeFixture(setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.AccountRead))
        fixture.activate()
        assertNull(fixture.provider.captureCapabilityScope(emptySet()))
        assertNull(fixture.provider.captureCapabilityScope(setOf(RisingStonesCapability.ForumWrite)))
        assertNull(fixture.provider.captureCapabilityScope(GuildActions + RisingStonesCapability.ForumWrite))
        val requested = mutableSetOf(RisingStonesCapability.GuildWrite)
        val scope = requireNotNull(fixture.provider.captureCapabilityScope(requested))
        requested += RisingStonesCapability.GuildImageUpload
        assertNull(scope.beginCapabilityAttempt(GuildImageContext))
        assertNull(scope.beginCapabilityAttempt(GuildWriteContext.copy(requirement = RisingStonesAuthenticationRequirement.Optional)))
        assertNull(scope.beginCapabilityAttempt(GuildWriteContext.copy(path = "")))
        for (context in listOf(
            GuildWriteContext,
            GuildReadContext.copy(capability = RisingStonesCapability.AccountRead),
            GuildReadContext.copy(requirement = RisingStonesAuthenticationRequirement.Anonymous),
            GuildReadContext.copy(path = " "),
        )) {
            assertRejectedWithoutHeaders(scope.authorizer, context)
        }
        assertTrue(scope.isCurrent())
        scope.close()
    }

    @Test
    fun cancelledScopeOperationsPropagateWithoutAuthorizingOrGranting() = runTest {
        val fixture = GuildScopeFixture()
        fixture.activate()
        val scope = requireNotNull(fixture.provider.captureCapabilityScope(GuildActions))
        var appliedHeaders = 0
        val operations: List<suspend () -> Unit> = listOf(
            { fixture.provider.captureCapabilityScope(GuildActions); Unit },
            { scope.isCurrent(); Unit },
            { scope.beginCapabilityAttempt(GuildWriteContext); Unit },
            { scope.authorizer.authorize(GuildReadContext, RisingStonesHeaderSink { _, _ -> appliedHeaders++ }) },
        )
        for (operation in operations) {
            val cancelled = async {
                currentCoroutineContext().cancel(CancellationException("cancel guild scope"))
                operation()
            }
            try {
                cancelled.await()
                throw AssertionError("Scope swallowed cancellation")
            } catch (_: CancellationException) { }
        }
        assertEquals(0, appliedHeaders)
        assertEquals(setOf(RisingStonesCapability.GuildRead), fixture.provider.capabilities)
        assertTrue(scope.isCurrent())
        scope.close()
    }
}

private suspend fun authorize(attempt: RisingStonesCapabilityAttempt, context: RisingStonesRequestContext) {
    attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
}

private suspend fun assertRejectedWithoutHeaders(authorizer: RisingStonesRequestAuthorizer, context: RisingStonesRequestContext) {
    var appliedHeaders = 0
    try {
        authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> appliedHeaders++ })
        throw AssertionError("Revoked or mismatched scope authorized a request")
    } catch (_: IllegalStateException) { }
    assertEquals(0, appliedHeaders)
}

private val GuildActions = setOf(RisingStonesCapability.GuildWrite, RisingStonesCapability.GuildImageUpload)
private val GuildReadContext = RisingStonesRequestContext(
    "api/home/guild/fixture-read", RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.GuildRead)
private val GuildWriteContext = RisingStonesRequestContext(
    "api/home/guild/fixture-action", RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.GuildWrite)
private val GuildImageContext = RisingStonesRequestContext(
    "api/common/fixture-image-token", RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.GuildImageUpload)
private enum class GuildScopeMutation { Accept, Restore, SignOut }

private class GuildScopeFixture(var capabilities: Set<RisingStonesCapability> = setOf(RisingStonesCapability.GuildRead)) {
    private var stored: RisingStonesCookieCredential? = null
    val provider = RisingStonesWebCookieSessionProvider(
        store = object : RisingStonesCookieStore {
            override suspend fun read(): RisingStonesCookieCredential? = stored
            override suspend fun write(credential: RisingStonesCookieCredential) { stored = credential }
            override suspend fun clear() { stored = null }
        },
        sessionValidator = object : RisingStonesSessionValidator {
            override suspend fun validateSession(authorizer: RisingStonesRequestAuthorizer) =
                RisingStonesSessionValidation(displayName = "Fixture", code = 10000, capabilities = capabilities)
        },
    )

    suspend fun activate(value: String = "fixture-current") {
        assertTrue(provider.accept(RisingStonesCookieCredential(value, "GuildScopeTest/1.0")).isSuccess)
    }
}
