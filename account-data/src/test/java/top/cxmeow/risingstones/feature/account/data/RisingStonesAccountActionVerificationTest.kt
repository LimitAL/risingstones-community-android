package top.cxmeow.risingstones.feature.account.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountException
import top.cxmeow.risingstones.network.*

class RisingStonesAccountActionVerificationTest {
    @Test fun acceptedSignInGrantsOnlyAfterValidPayloadWithExactlyOnePost() = runBlocking {
        for (code in listOf(10000, 10002)) {
            val fixture = Fixture("""{"code":$code,"data":{"totalDays":"5","sqExp":8,"shopExp":9,"sqMsg":"Synthetic signed"}}""")
            assertTrue(fixture.service.canVerifyDailySignIn)
            assertEquals(0, fixture.requests.size)
            val result = fixture.service.verifyDailySignIn()
            assertEquals(5, result.totalDays)
            assertEquals("Synthetic signed", result.message)
            assertEquals(1, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
            assertEquals(1, fixture.requests.size)
            val request = fixture.requests.single()
            assertEquals(RisingStonesHttpMethod.Post, request.method)
            assertEquals("", request.body!!.decodeToString())
            assertEquals("https://first.invalid/api/home/sign/signIn", request.url)
            assertEquals("SyntheticLoginUA/1", request.headers["User-Agent"])
            assertEquals(RisingStonesCapability.DailySignIn, fixture.provider.context?.capability)
            assertEquals(RisingStonesAuthenticationRequirement.Required, fixture.provider.context?.requirement)
        }
    }

    @Test fun claimUsesSelectedRewardAndFrozenMonthWithNoRequiredDataObject() = runBlocking {
        val fixture = Fixture("""{"code":10002,"msg":"Synthetic claimed"}""")
        assertEquals("Synthetic claimed", fixture.service.verifyClaimReward(7))
        val request = fixture.requests.single()
        assertEquals("https://first.invalid/api/home/sign/getSignReward", request.url)
        assertEquals("id=7&month=2026-09", request.body!!.decodeToString())
        assertEquals(1, fixture.provider.completed)
    }

    @Test fun rejectedAndMalformedSignInNeverGrantOrRetry() = runBlocking {
        listOf("""{"code":10000}""", """{"code":10000,"data":[]}""",
            """{"code":10000,"data":{"totalDays":-1}}""", """{"code":10002,"data":{"totalDays":"?"}}""",
            """{"code":10001,"msg":"Already signed"}""", """{"code":10105}""",
            """{"code":401}""", "not-json", "[]").forEach { body ->
            val fixture = Fixture(body)
            assertNotNull(runCatching { fixture.service.verifyDailySignIn() }.exceptionOrNull())
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
            assertEquals(1, fixture.requests.size)
            assertEquals(if (body.contains("\"code\":401")) 1 else 0, fixture.provider.refreshes)
        }
    }

    @Test fun unknownFailureAndCancellationNeverReplayOrGrant() = runBlocking {
        for (failure in listOf(IOException("Synthetic unknown outcome"), CancellationException("Synthetic cancellation"))) {
            val fixture = Fixture("{}", failure = failure)
            val caught = runCatching { fixture.service.verifyDailySignIn() }.exceptionOrNull()
            assertNotNull(caught)
            if (failure is CancellationException) assertSame(failure, caught)
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
        }
    }

    @Test fun httpFailureCannotGrantEvenWithAcceptedBody() = runBlocking {
        val fixture = Fixture("""{"code":10000,"data":{"totalDays":5}}""", status = 401)
        assertTrue(runCatching { fixture.service.verifyDailySignIn() }.exceptionOrNull() is RisingStonesHttpException.ServerResponse)
        assertEquals(0, fixture.provider.completed)
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.refreshes)
    }

    @Test fun expiredAttemptDoesNotReturnSuccessToAnotherSession() = runBlocking {
        val fixture = Fixture("""{"code":10000,"data":{"totalDays":5}}""")
        fixture.provider.current = false
        assertSame(RisingStonesAccountException.AuthenticationRequired,
            runCatching { fixture.service.verifyDailySignIn() }.exceptionOrNull())
        assertEquals(1, fixture.provider.closed)
    }

    @Test fun noEligibleProviderMeansNoRequestAndExistingWriteGateStaysClosed() = runBlocking {
        val fixture = Fixture("{}")
        fixture.provider.eligible = false
        assertFalse(fixture.service.canVerifyDailySignIn)
        assertSame(RisingStonesAccountException.AuthenticationRequired,
            runCatching { fixture.service.verifyDailySignIn() }.exceptionOrNull())
        assertSame(RisingStonesAccountException.AuthenticationRequired,
            runCatching { fixture.service.signIn() }.exceptionOrNull())
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun establishedWriteStillUsesBoundAttemptAndPreservesAlreadySignedResult() = runBlocking {
        val fixture = Fixture("""{"code":10001,"msg":"Synthetic already signed"}""")
        fixture.provider.capabilities += RisingStonesCapability.DailySignIn
        assertTrue(fixture.service.signIn().isAlreadyCheckedIn)
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.completed)
        assertEquals(0, fixture.provider.refreshes)
    }

    @Test fun invalidRewardDoesNotBeginAnAttempt() = runBlocking {
        val fixture = Fixture("{}")
        assertTrue(runCatching { fixture.service.verifyClaimReward(0) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(fixture.provider.context)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun legacyProvidersNeverReplaySignInOrClaimAfterAuthenticationOrConflict() = runBlocking {
        for (signIn in listOf(true, false)) for (code in listOf(401, 10105)) for (http in listOf(true, false)) {
            var calls = 0
            var refreshes = 0
            var conflicts = 0
            val provider = object : RisingStonesSessionProvider, RisingStonesIdentityConflictResolver {
                override val capabilities = setOf(RisingStonesCapability.DailySignIn)
                override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, _ -> }
                override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++;return currentAuthorizer() }
                override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer { conflicts++;return currentAuthorizer() }
            }
            val bytes = """{"code":$code}""".encodeToByteArray()
            val service = RisingStonesAccountApiService(RisingStonesPublicApiClient(object : RisingStonesHttpClient {
                override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                    calls++
                    if (http) throw RisingStonesHttpException.ServerResponse(401, bytes)
                    return RisingStonesHttpResponse(200, emptyMap(), bytes)
                }
            }, listOf("https://first.invalid", "https://second.invalid")), provider)
            assertNotNull(runCatching { if (signIn) service.signIn() else service.claimReward(7) }.exceptionOrNull())
            assertEquals(1, calls)
            assertEquals(if (http || code == 401) 1 else 0, refreshes)
            assertEquals(0, conflicts)
        }
    }

    private class Fixture(body: String, failure: Exception? = null, status: Int = 200) {
        val provider = AttemptProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = RisingStonesAccountApiService(RisingStonesPublicApiClient(object : RisingStonesHttpClient {
            override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                requests += request
                failure?.let { throw it }
                return RisingStonesHttpResponse(status, emptyMap(), body.encodeToByteArray())
            }
        }, listOf("https://first.invalid", "https://second.invalid")), provider, signInMonth = { "2026-09" })
    }

    private class AttemptProvider : RisingStonesSessionProvider, RisingStonesExplicitCapabilityProvider {
        override var capabilities = setOf(RisingStonesCapability.AccountRead)
        var eligible = true
        var current = true
        var completed = 0
        var closed = 0
        var refreshes = 0
        var context: RisingStonesRequestContext? = null
        override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = null
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? { refreshes++;return null }
        override fun canAttemptCapability(capability: RisingStonesCapability) = eligible
        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? {
            if (!eligible) return null
            this.context = context
            return object : RisingStonesCapabilityAttempt {
                override val authorizer = RisingStonesRequestAuthorizer { actual, sink ->
                    assertEquals(context, actual)
                    sink.set("User-Agent", "SyntheticLoginUA/1")
                }
                override suspend fun complete(): Boolean { if (current) completed++;return current }
                override fun close() { closed++ }
            }
        }
    }
}
