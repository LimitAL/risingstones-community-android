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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState
import top.cxmeow.risingstones.network.RisingStonesApiException
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesExplicitCapabilityAttemptTest {
    @Test
    fun guildActionsRequireGuildReadAndDoNotGrantOnEligibilityOrAuthorization() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val provider = provider(validator = validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        for (context in guildContexts()) {
            assertFalse(provider.canAttemptCapability(requireNotNull(context.capability)))
            assertNull(provider.beginCapabilityAttempt(context))
        }

        validator.capabilities = setOf(RisingStonesCapability.GuildRead)
        assertNotNull(provider.refreshAuthorizer())
        for (context in guildContexts()) {
            val capability = requireNotNull(context.capability)
            assertTrue(provider.canAttemptCapability(capability))
            val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
            assertFalse(attempt.complete())
            val headers = linkedMapOf<String, String>()
            attempt.authorizer.authorize(context, RisingStonesHeaderSink(headers::set))
            assertTrue(headers.isNotEmpty())
            assertEquals(setOf(RisingStonesCapability.GuildRead), provider.capabilities)
            assertAuthorizationRejected(attempt, context, linkedMapOf())
            attempt.close()
            assertFalse(attempt.complete())
        }
    }

    @Test
    fun guildWriteAndImageUploadAreGrantedIndependentlyInEitherOrder() = runTest {
        for (contexts in listOf(guildContexts(), guildContexts().reversed())) {
            val provider = provider(validator = MutableCapabilityValidator(setOf(RisingStonesCapability.GuildRead)))
            assertTrue(provider.accept(explicitCredential("current")).isSuccess)
            grant(provider, contexts.first())
            assertEquals(
                setOf(RisingStonesCapability.GuildRead, requireNotNull(contexts.first().capability)),
                provider.capabilities,
            )
            assertFalse(requireNotNull(contexts.last().capability) in provider.capabilities)
            grant(provider, contexts.last())
            assertEquals(
                setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.GuildWrite,
                    RisingStonesCapability.GuildImageUpload),
                provider.capabilities,
            )
        }
    }

    @Test
    fun concurrentGuildCompletionsRetainBothVerifiedCapabilities() = runTest {
        val provider = provider(validator = MutableCapabilityValidator(setOf(RisingStonesCapability.GuildRead)))
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        val attempts = guildContexts().map { context ->
            requireNotNull(provider.beginCapabilityAttempt(context)).also {
                it.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
            }
        }
        val completions = attempts.map { async { it.complete() } }
        completions.forEach { assertTrue(it.await()) }
        attempts.forEach { assertFalse(it.complete()) }
        assertEquals(
            setOf(RisingStonesCapability.GuildRead, RisingStonesCapability.GuildWrite,
                RisingStonesCapability.GuildImageUpload),
            provider.capabilities,
        )
    }

    @Test
    fun losingGuildReadRevokesBothGuildWritesAndPreservesUnrelatedVerifiedWrite() = runTest {
        val validator = MutableCapabilityValidator(setOf(
            RisingStonesCapability.AccountRead, RisingStonesCapability.GuildRead))
        val provider = provider(validator = validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        guildContexts().forEach { grant(provider, it) }
        grant(provider, dailyContext("api/home/sign/signIn"))
        val beforeRefresh = provider.capabilities
        val credentialRevision = provider.credentialRevision.value
        assertNotNull(provider.refreshAuthorizer())
        assertEquals(beforeRefresh, provider.capabilities)

        validator.capabilities = setOf(RisingStonesCapability.AccountRead)
        assertNotNull(provider.refreshAuthorizer())
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DailySignIn),
            provider.capabilities)
        assertEquals(credentialRevision, provider.credentialRevision.value)
        guildContexts().forEach {
            assertFalse(provider.canAttemptCapability(requireNotNull(it.capability)))
            assertNull(provider.beginCapabilityAttempt(it))
        }
    }

    @Test
    fun regainedGuildReadCannotRevivePreviouslyRevokedAttempts() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.GuildRead))
        val provider = provider(validator = validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        val unused = guildContexts().map { requireNotNull(provider.beginCapabilityAttempt(it)) }
        val authorized = guildContexts().map { context ->
            requireNotNull(provider.beginCapabilityAttempt(context)).also {
                it.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
            }
        }
        validator.capabilities = setOf(RisingStonesCapability.AccountRead)
        assertNotNull(provider.refreshAuthorizer())
        authorized.forEach {
            assertFalse((it as RisingStonesCapabilityAttemptGuard).isCurrent())
            assertFalse(it.complete())
        }
        validator.capabilities = setOf(RisingStonesCapability.GuildRead)
        assertNotNull(provider.refreshAuthorizer())
        guildContexts().forEachIndexed { index, context ->
            assertTrue(provider.canAttemptCapability(requireNotNull(context.capability)))
            assertOldAttemptIsRevoked(unused[index], context)
            assertFalse((authorized[index] as RisingStonesCapabilityAttemptGuard).isCurrent())
            assertFalse(authorized[index].complete())
        }
        assertEquals(setOf(RisingStonesCapability.GuildRead), provider.capabilities)
        guildContexts().forEach { grant(provider, it) }
        assertTrue(RisingStonesCapability.GuildWrite in provider.capabilities)
        assertTrue(RisingStonesCapability.GuildImageUpload in provider.capabilities)
    }

    @Test
    fun replacingRestoringOrSigningOutInvalidatesUnusedAndAuthorizedGuildAttempts() = runTest {
        for (mutation in ExplicitCapabilityMutation.entries) {
            val provider = provider(validator = MutableCapabilityValidator(setOf(RisingStonesCapability.GuildRead)))
            assertTrue(provider.accept(explicitCredential("current")).isSuccess)
            guildContexts().forEach { grant(provider, it) }
            val unused = guildContexts().map { requireNotNull(provider.beginCapabilityAttempt(it)) }
            val authorized = guildContexts().map { context ->
                requireNotNull(provider.beginCapabilityAttempt(context)).also {
                    it.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
                }
            }
            when (mutation) {
                ExplicitCapabilityMutation.SignOut -> assertTrue(provider.signOut().isSuccess)
                ExplicitCapabilityMutation.Accept -> assertTrue(provider.accept(explicitCredential("replacement")).isSuccess)
                ExplicitCapabilityMutation.Restore -> provider.restore()
            }
            guildContexts().forEachIndexed { index, context ->
                assertOldAttemptIsRevoked(unused[index], context)
                assertFalse((authorized[index] as RisingStonesCapabilityAttemptGuard).isCurrent())
                assertFalse(authorized[index].complete())
                assertFalse(requireNotNull(context.capability) in provider.capabilities)
            }
        }
    }

    @Test
    fun cancelledGuildCompletionDoesNotGrantEitherCapability() = runTest {
        val provider = provider(validator = MutableCapabilityValidator(setOf(RisingStonesCapability.GuildRead)))
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        for (context in guildContexts()) {
            val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
            attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
            val cancelled = async {
                try {
                    currentCoroutineContext().cancel(CancellationException("cancel guild action"))
                    attempt.complete()
                } finally {
                    attempt.close()
                }
            }
            try {
                cancelled.await()
                throw AssertionError("Completion swallowed cancellation")
            } catch (_: CancellationException) { }
            assertFalse(attempt.complete())
            assertEquals(setOf(RisingStonesCapability.GuildRead), provider.capabilities)
        }
    }

    @Test
    fun cancelledRestoreRetainsGuildReadButDropsWritesAndOldAttempts() = runTest {
        val store = ExplicitCapabilityStore()
        val provider = provider(store, MutableCapabilityValidator(setOf(RisingStonesCapability.GuildRead)))
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        guildContexts().forEach { grant(provider, it) }
        val attempts = guildContexts().map { requireNotNull(provider.beginCapabilityAttempt(it)) }
        store.readFailure = CancellationException("cancel guild restore")
        assertRestoreCancellation("cancel guild restore") { provider.restore() }
        assertEquals(setOf(RisingStonesCapability.GuildRead), provider.capabilities)
        guildContexts().forEachIndexed { index, context -> assertOldAttemptIsRevoked(attempts[index], context) }
    }

    @Test
    fun stagedCheckpointDoesNotAuthorizeOrGrantAndExpiresOnCompletionOrClose() = runTest {
        val provider = activeProvider()
        val context = dailyContext("api/common/getCOSTokenI").copy(capability = RisingStonesCapability.ForumImageUpload)
        val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
        val guard = attempt as RisingStonesCapabilityAttemptGuard
        assertFalse(guard.isCurrent())
        attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
        assertTrue(guard.isCurrent())
        assertTrue(guard.isCurrent())
        assertFalse(RisingStonesCapability.ForumImageUpload in provider.capabilities)
        assertTrue(attempt.complete())
        assertFalse(guard.isCurrent())

        val closed = requireNotNull(provider.beginCapabilityAttempt(context))
        closed.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
        closed.close()
        assertFalse((closed as RisingStonesCapabilityAttemptGuard).isCurrent())
    }

    @Test
    fun stagedCheckpointRejectsReplacedRestoredAndSignedOutCredentials() = runTest {
        for (mutation in ExplicitCapabilityMutation.entries) {
            val provider = activeProvider()
            val context = dailyContext("api/common/getCOSTokenI").copy(capability = RisingStonesCapability.ForumImageUpload)
            val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
            attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
            when (mutation) {
                ExplicitCapabilityMutation.SignOut -> provider.signOut()
                ExplicitCapabilityMutation.Accept -> provider.accept(explicitCredential("replacement"))
                ExplicitCapabilityMutation.Restore -> provider.restore()
            }
            assertFalse((attempt as RisingStonesCapabilityAttemptGuard).isCurrent())
            assertFalse(attempt.complete())
            assertFalse(RisingStonesCapability.ForumImageUpload in provider.capabilities)
        }
    }

    @Test
    fun stagedCheckpointObservesReadRevocationAndPropagatesCancellation() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val provider = provider(validator = validator)
        provider.accept(explicitCredential("current"))
        val context = dailyContext("api/common/getCOSTokenI").copy(capability = RisingStonesCapability.ForumImageUpload)
        val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
        val guard = attempt as RisingStonesCapabilityAttemptGuard
        attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
        assertNotNull(provider.refreshAuthorizer())
        assertTrue(guard.isCurrent())
        val cancelled = async {
            currentCoroutineContext().cancel()
            guard.isCurrent()
        }
        try {
            cancelled.await()
            throw AssertionError("Checkpoint swallowed cancellation")
        } catch (_: CancellationException) { }
        validator.capabilities = emptySet()
        assertNotNull(provider.refreshAuthorizer())
        assertFalse(guard.isCurrent())
        assertFalse(attempt.complete())
    }

    @Test
    fun credentialRevisionChangesOnlyWhenTheCredentialGenerationChanges() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val store = ExplicitCapabilityStore()
        val provider = provider(store, validator)
        val initialRevision = provider.credentialRevision.value

        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        val acceptedRevision = provider.credentialRevision.value
        assertTrue(acceptedRevision > initialRevision)

        assertNotNull(provider.refreshAuthorizer())
        assertEquals(acceptedRevision, provider.credentialRevision.value)

        grant(provider, dailyContext("api/home/sign/signIn"))
        assertEquals(acceptedRevision, provider.credentialRevision.value)

        validator.failure = IllegalStateException("invalid replacement")
        assertTrue(provider.accept(explicitCredential("replacement")).isFailure)
        assertEquals(acceptedRevision, provider.credentialRevision.value)
        assertEquals("current", store.credential?.cookie)
        assertEquals(
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DailySignIn),
            provider.capabilities,
        )

        validator.failure = null
        provider.restore()
        val restoredRevision = provider.credentialRevision.value
        assertTrue(restoredRevision > acceptedRevision)
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)

        assertTrue(provider.signOut().isSuccess)
        assertTrue(provider.credentialRevision.value > restoredRevision)
        assertSame(RisingStonesSessionState.SignedOut, provider.sessionState.value)
    }

    @Test
    fun explicitlyRejectedRefreshAdvancesCredentialRevision() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val provider = provider(validator = validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        val activeRevision = provider.credentialRevision.value
        validator.failure = RisingStonesApiException("session expired", code = 10001)

        assertNull(provider.refreshAuthorizer())

        assertTrue(provider.credentialRevision.value > activeRevision)
        assertTrue(provider.sessionState.value is RisingStonesSessionState.Invalid)
        assertTrue(provider.capabilities.isEmpty())
        assertNull(provider.currentAuthorizer())
    }

    @Test
    fun accountReadMakesWritesEligibleWithoutGrantingThem() = runTest {
        val validator = MutableCapabilityValidator(emptySet())
        val provider = provider(validator = validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)

        assertFalse(provider.canAttemptCapability(RisingStonesCapability.DailySignIn))
        assertNull(provider.beginCapabilityAttempt(dailyContext("api/home/sign/signIn")))

        validator.capabilities = setOf(RisingStonesCapability.AccountRead)
        assertNotNull(provider.refreshAuthorizer())

        assertTrue(provider.canAttemptCapability(RisingStonesCapability.DailySignIn))
        assertTrue(provider.canAttemptCapability(RisingStonesCapability.ForumWrite))
        assertTrue(provider.canAttemptCapability(RisingStonesCapability.RecruitmentWrite))
        assertFalse(provider.canAttemptCapability(RisingStonesCapability.AccountRead))
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
        assertNull(
            provider.beginCapabilityAttempt(
                dailyContext("api/home/sign/signIn").copy(
                    requirement = RisingStonesAuthenticationRequirement.Optional,
                ),
            ),
        )
    }

    @Test
    fun attemptAuthorizesTheExactRequiredContextOnlyOnce() = runTest {
        val provider = activeProvider()
        val context = dailyContext("api/home/sign/signIn")
        val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
        val firstHeaders = linkedMapOf<String, String>()

        attempt.authorizer.authorize(context, RisingStonesHeaderSink(firstHeaders::set))

        assertEquals("ff14risingstones=current", firstHeaders["cookie"])
        val repeatedHeaders = linkedMapOf<String, String>()
        assertAuthorizationRejected(attempt, context, repeatedHeaders)
        assertTrue(repeatedHeaders.isEmpty())

        val mismatched = requireNotNull(provider.beginCapabilityAttempt(context))
        val mismatchedHeaders = linkedMapOf<String, String>()
        assertAuthorizationRejected(
            mismatched,
            context.copy(path = "api/home/sign/getSignReward"),
            mismatchedHeaders,
        )
        assertTrue(mismatchedHeaders.isEmpty())
        assertFalse(mismatched.complete())
        assertFalse(RisingStonesCapability.DailySignIn in provider.capabilities)
    }

    @Test
    fun capabilityIsGrantedOnlyAfterAuthorizationAndOneSuccessfulCompletion() = runTest {
        val provider = activeProvider()
        val context = dailyContext("api/home/sign/signIn")
        val attempt = requireNotNull(provider.beginCapabilityAttempt(context))

        assertFalse(attempt.complete())
        assertFalse(RisingStonesCapability.DailySignIn in provider.capabilities)

        attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })

        assertFalse(RisingStonesCapability.DailySignIn in provider.capabilities)
        assertTrue(attempt.complete())
        assertTrue(RisingStonesCapability.DailySignIn in provider.capabilities)
        assertFalse(attempt.complete())
    }

    @Test
    fun closedFailedAndCancelledAttemptsNeverGrantCapability() = runTest {
        val provider = activeProvider()
        val context = dailyContext("api/home/sign/signIn")

        val closed = requireNotNull(provider.beginCapabilityAttempt(context))
        closed.close()
        assertFalse(closed.complete())

        val failed = requireNotNull(provider.beginCapabilityAttempt(context))
        failed.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
        failed.close()
        assertFalse(failed.complete())

        val cancelled = requireNotNull(provider.beginCapabilityAttempt(context))
        cancelled.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
        val completion = async {
            currentCoroutineContext().cancel(CancellationException("cancel action"))
            cancelled.complete()
        }
        var cancellation: CancellationException? = null
        try {
            completion.await()
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertNotNull(cancellation)
        assertFalse(RisingStonesCapability.DailySignIn in provider.capabilities)
    }

    @Test
    fun attemptsFromPreviousCredentialGenerationsCannotAuthorizeOrComplete() = runTest {
        for (mutation in ExplicitCapabilityMutation.entries) {
            val store = ExplicitCapabilityStore()
            val provider = activeProvider(store)
            val context = dailyContext("api/home/sign/signIn")
            val attempt = requireNotNull(provider.beginCapabilityAttempt(context))

            when (mutation) {
                ExplicitCapabilityMutation.SignOut -> assertTrue(provider.signOut().isSuccess)
                ExplicitCapabilityMutation.Accept ->
                    assertTrue(provider.accept(explicitCredential("replacement")).isSuccess)
                ExplicitCapabilityMutation.Restore -> provider.restore()
            }

            val headers = linkedMapOf<String, String>()
            assertAuthorizationRejected(attempt, context, headers)
            assertTrue(headers.isEmpty())
            assertFalse(attempt.complete())
            assertFalse(RisingStonesCapability.DailySignIn in provider.capabilities)
        }
    }

    @Test
    fun sameCredentialRefreshKeepsWriteUntilItsReadPrerequisiteDisappears() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val store = ExplicitCapabilityStore()
        val provider = provider(store, validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        grant(provider, dailyContext("api/home/sign/signIn"))

        assertNotNull(provider.refreshAuthorizer())
        assertEquals(
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DailySignIn),
            provider.capabilities,
        )
        assertEquals(1, store.writeCount)
        assertEquals("current", store.credential?.cookie)

        validator.capabilities = emptySet()
        assertNotNull(provider.refreshAuthorizer())
        assertTrue(provider.capabilities.isEmpty())
        assertEquals(1, store.writeCount)
        assertEquals("current", store.credential?.cookie)
    }

    @Test
    fun independentImageUploadAndForumWriteRemainUntilAccountReadDisappears() =
        runTest {
            val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
            val provider = provider(validator = validator)
            assertTrue(provider.accept(explicitCredential("current")).isSuccess)
            grant(provider, RisingStonesRequestContext("api/common/getCOSTokenI",
                RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.ForumImageUpload))
            assertFalse(RisingStonesCapability.ForumWrite in provider.capabilities)
            grant(provider, RisingStonesRequestContext("api/home/posts/comment",
                RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.ForumWrite))

            assertNotNull(provider.refreshAuthorizer())
            assertEquals(
                setOf(
                    RisingStonesCapability.AccountRead,
                    RisingStonesCapability.ForumWrite,
                    RisingStonesCapability.ForumImageUpload,
                ),
                provider.capabilities,
            )

            validator.capabilities = emptySet()
            assertNotNull(provider.refreshAuthorizer())
            assertTrue(provider.capabilities.isEmpty())
        }

    @Test
    fun restoredCredentialDoesNotRestoreExplicitWriteCapabilities() = runTest {
        val store = ExplicitCapabilityStore()
        val provider = activeProvider(store)
        grant(provider, dailyContext("api/home/sign/signIn"))
        assertTrue(RisingStonesCapability.DailySignIn in provider.capabilities)

        provider.restore()

        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
        assertEquals("current", store.credential?.cookie)
    }

    @Test
    fun cancellationWhileReadingRestoreKeepsOnlyThePreviousReadSession() = runTest {
        val store = ExplicitCapabilityStore()
        val provider = activeProvider(store)
        grant(provider, dailyContext("api/home/sign/signIn"))
        val context = dailyContext("api/home/sign/getSignReward")
        val oldAttempt = requireNotNull(provider.beginCapabilityAttempt(context))
        store.readFailure = CancellationException("cancel stored credential read")

        assertRestoreCancellation("cancel stored credential read") { provider.restore() }

        assertTrue(provider.sessionState.value is RisingStonesSessionState.Active)
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
        assertEquals("current", store.credential?.cookie)
        assertOldAttemptIsRevoked(oldAttempt, context)

        val signedOutStore = ExplicitCapabilityStore(
            readFailure = CancellationException("cancel signed-out read"),
        )
        val signedOut = provider(store = signedOutStore)
        assertRestoreCancellation("cancel signed-out read") { signedOut.restore() }
        assertSame(RisingStonesSessionState.SignedOut, signedOut.sessionState.value)
        assertNull(signedOut.currentAuthorizer())
    }

    @Test
    fun cancellationWhileValidatingRestoreKeepsOnlyThePreviousReadSession() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val store = ExplicitCapabilityStore()
        val provider = provider(store, validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        grant(provider, dailyContext("api/home/sign/signIn"))
        val context = dailyContext("api/home/sign/getSignReward")
        val oldAttempt = requireNotNull(provider.beginCapabilityAttempt(context))
        validator.failure = CancellationException("cancel restored credential validation")

        assertRestoreCancellation("cancel restored credential validation") { provider.restore() }

        assertTrue(provider.sessionState.value is RisingStonesSessionState.Active)
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
        assertEquals("current", store.credential?.cookie)
        assertOldAttemptIsRevoked(oldAttempt, context)

        val signedOutValidator = MutableCapabilityValidator(
            setOf(RisingStonesCapability.AccountRead),
        ).apply {
            failure = CancellationException("cancel signed-out validation")
        }
        val signedOut = provider(
            store = ExplicitCapabilityStore(explicitCredential("stored")),
            validator = signedOutValidator,
        )
        assertRestoreCancellation("cancel signed-out validation") { signedOut.restore() }
        assertSame(RisingStonesSessionState.SignedOut, signedOut.sessionState.value)
        assertNull(signedOut.currentAuthorizer())
    }

    @Test
    fun failedReloginPreservesPreviousCredentialAndExplicitCapabilities() = runTest {
        val validator = MutableCapabilityValidator(setOf(RisingStonesCapability.AccountRead))
        val store = ExplicitCapabilityStore()
        val provider = provider(store, validator)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        grant(provider, dailyContext("api/home/sign/signIn"))
        validator.failure = IllegalStateException("invalid replacement")

        assertTrue(provider.accept(explicitCredential("replacement")).isFailure)

        assertEquals("current", store.credential?.cookie)
        assertEquals(
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DailySignIn),
            provider.capabilities,
        )
        assertEquals(
            setOf("ff14risingstones=current"),
            requireNotNull(provider.currentAuthorizer()).cookieHeadersForAttemptTest(),
        )
    }

    @Test
    fun concurrentSignInAndRewardCompletionsUseTheCurrentGenerationWithoutLosingCapabilities() =
        runTest {
            val validator = MutableCapabilityValidator(
                setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead),
            )
            val provider = provider(validator = validator)
            assertTrue(provider.accept(explicitCredential("current")).isSuccess)
            val signInContext = dailyContext("api/home/sign/signIn")
            val rewardContext = dailyContext("api/home/sign/getSignReward")
            val signIn = requireNotNull(provider.beginCapabilityAttempt(signInContext))
            val reward = requireNotNull(provider.beginCapabilityAttempt(rewardContext))
            signIn.authorizer.authorize(signInContext, RisingStonesHeaderSink { _, _ -> })
            reward.authorizer.authorize(rewardContext, RisingStonesHeaderSink { _, _ -> })

            val signInCompletion = async { signIn.complete() }
            val rewardCompletion = async { reward.complete() }

            assertTrue(signInCompletion.await())
            assertTrue(rewardCompletion.await())
            assertEquals(
                setOf(
                    RisingStonesCapability.AccountRead,
                    RisingStonesCapability.DynamicRead,
                    RisingStonesCapability.DailySignIn,
                ),
                provider.capabilities,
            )
        }

    private suspend fun grant(
        provider: RisingStonesWebCookieSessionProvider,
        context: RisingStonesRequestContext,
    ) {
        val attempt = requireNotNull(provider.beginCapabilityAttempt(context))
        attempt.authorizer.authorize(context, RisingStonesHeaderSink { _, _ -> })
        assertTrue(attempt.complete())
    }

    private suspend fun assertAuthorizationRejected(
        attempt: top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt,
        context: RisingStonesRequestContext,
        headers: MutableMap<String, String>,
    ) {
        var failure: IllegalStateException? = null
        try {
            attempt.authorizer.authorize(context, RisingStonesHeaderSink(headers::set))
        } catch (error: IllegalStateException) {
            failure = error
        }
        assertNotNull(failure)
    }

    private suspend fun assertOldAttemptIsRevoked(
        attempt: top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt,
        context: RisingStonesRequestContext,
    ) {
        val headers = linkedMapOf<String, String>()
        assertAuthorizationRejected(attempt, context, headers)
        assertTrue(headers.isEmpty())
        assertFalse(attempt.complete())
    }

    private suspend fun assertRestoreCancellation(
        expectedMessage: String,
        block: suspend () -> Unit,
    ) {
        var cancellation: CancellationException? = null
        try {
            block()
        } catch (error: CancellationException) {
            cancellation = error
        }
        assertEquals(expectedMessage, cancellation?.message)
    }

    private suspend fun activeProvider(
        store: ExplicitCapabilityStore = ExplicitCapabilityStore(),
    ): RisingStonesWebCookieSessionProvider {
        val provider = provider(store)
        assertTrue(provider.accept(explicitCredential("current")).isSuccess)
        return provider
    }

    private fun provider(
        store: ExplicitCapabilityStore = ExplicitCapabilityStore(),
        validator: MutableCapabilityValidator = MutableCapabilityValidator(
            setOf(RisingStonesCapability.AccountRead),
        ),
    ) = RisingStonesWebCookieSessionProvider(
        store = store,
        sessionValidator = validator,
    )
}

private enum class ExplicitCapabilityMutation { SignOut, Accept, Restore }

private class MutableCapabilityValidator(
    var capabilities: Set<RisingStonesCapability>,
) : RisingStonesSessionValidator {
    var failure: Exception? = null

    override suspend fun validateSession(
        authorizer: top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        failure?.let { throw it }
        return RisingStonesSessionValidation(
            displayName = "Fixture",
            code = 10000,
            capabilities = capabilities,
        )
    }
}

private class ExplicitCapabilityStore(
    var credential: RisingStonesCookieCredential? = null,
    var readFailure: Exception? = null,
) : RisingStonesCookieStore {
    var writeCount = 0

    override suspend fun read(): RisingStonesCookieCredential? {
        readFailure?.let { throw it }
        return credential
    }

    override suspend fun write(credential: RisingStonesCookieCredential) {
        writeCount += 1
        this.credential = credential
    }

    override suspend fun clear() {
        credential = null
    }
}

private fun explicitCredential(value: String) = RisingStonesCookieCredential(
    cookie = value,
    userAgent = "ExplicitCapabilityTest/1.0",
)

private fun dailyContext(path: String) = RisingStonesRequestContext(
    path = path,
    requirement = RisingStonesAuthenticationRequirement.Required,
    capability = RisingStonesCapability.DailySignIn,
)

private fun guildContexts() = listOf(
    RisingStonesRequestContext("api/home/guild/fixture-action",
        RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.GuildWrite),
    RisingStonesRequestContext("api/common/fixture-image-token",
        RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.GuildImageUpload),
)

private suspend fun top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
    .cookieHeadersForAttemptTest(): Set<String> {
    val values = mutableSetOf<String>()
    authorize(
        context = RisingStonesRequestContext(
            path = "test",
            requirement = RisingStonesAuthenticationRequirement.Required,
        ),
        sink = RisingStonesHeaderSink { name, value ->
            if (name.equals("cookie", ignoreCase = true)) values += value
        },
    )
    return values
}
