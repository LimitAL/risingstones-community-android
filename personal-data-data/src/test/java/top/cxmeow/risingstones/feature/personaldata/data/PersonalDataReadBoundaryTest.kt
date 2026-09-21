package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.network.*

class PersonalDataReadBoundaryTest {
    @Test fun authorizationSourceFailuresCannotExposeSecretsOrHideAuthenticationFailure() = runTest {
        for (boundary in listOf("lookup", "authorize", "refresh", "conflict")) {
            val session = Session()
            val fail: suspend () -> RisingStonesRequestAuthorizer? = {
                throw RisingStonesHttpException.ServerResponse(401, "PRIVATE_RESPONSE".encodeToByteArray())
            }
            when (boundary) {
                "lookup" -> session.lookup = { fail() }
                "authorize" -> session.authorizer = RisingStonesRequestAuthorizer { _, _ -> fail() }
                "refresh" -> session.refresh = { fail() }
                "conflict" -> session.resolve = { fail() }
            }
            val transport = Transport { response(if (boundary == "conflict") "{\"code\":10105}" else "{\"code\":10001}") }
            assertAuthentication { service(session, transport).fetchIdentity() }
            assertTrue(session.refreshes + session.conflicts <= 1)
            assertEquals(if (boundary in listOf("refresh", "conflict")) 1 else 0, transport.requests.size)
        }
    }

    @Test fun ordinaryAuthorizerExceptionIsSanitizedForDirectDataConsumers() = runTest {
        val session = Session().apply {
            authorizer = RisingStonesRequestAuthorizer { _, _ -> error("PRIVATE_RESPONSE") }
        }
        val transport = Transport()
        try {
            service(session, transport).fetchIdentity()
            fail("Expected authorization error")
        } catch (failure: java.io.IOException) {
            assertFalse(failure.toString().contains("PRIVATE_RESPONSE"))
            assertNull(failure.cause)
        }
        assertEquals(0, transport.requests.size)
    }

    @Test fun cancelledAuthorizationSourceCannotBeginTransportOrRecovery() = runTest {
        val session = Session().apply { lookup = { throw CancellationException("Fixture cancellation") } }
        val transport = Transport()
        try {
            service(session, transport).fetchIdentity()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, transport.requests.size)
        assertEquals(0, session.refreshes)
    }

    @Test fun revocationDuringAuthorizerLookupStopsBeforeTransport() = runTest {
        val session = Session().apply { lookup = { enabled = false; authorizer } }
        val transport = Transport()
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(0, transport.requests.size)
    }

    @Test fun revocationDuringHeaderAuthorizationStopsBeforeTransport() = runTest {
        val session = Session().apply { authorizer = RisingStonesRequestAuthorizer { _, sink ->
            sink.set("Authorization", "Fixture token"); enabled = false
        } }
        val transport = Transport()
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(0, transport.requests.size)
    }

    @Test fun emptyAuthorizerDoesNotSendAnonymousPersonalDataRequest() = runTest {
        val session = Session().apply { authorizer = RisingStonesRequestAuthorizer { _, _ -> } }
        val transport = Transport()
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(0, transport.requests.size)
    }

    @Test fun revocationDuringSuccessfulTransportDiscardsPayload() = runTest {
        val session = Session()
        val transport = Transport { session.enabled = false; response(Identity) }
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(0, session.refreshes)
    }

    @Test fun revocationDuringRefreshStopsRetry() = runTest {
        val session = Session().apply { refresh = { enabled = false; authorizer } }
        val transport = Transport { response("{\"code\":10001}") }
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(1, session.refreshes)
        assertEquals(1, transport.requests.size)
    }

    @Test fun httpFailureThenEnvelopeFailureSharesOneRecoveryBudget() = runTest {
        val session = Session()
        var requests = 0
        val transport = Transport {
            if (++requests == 1) throw RisingStonesHttpException.ServerResponse(401, byteArrayOf())
            response("{\"code\":10001}")
        }
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(1, session.refreshes)
        assertEquals(2, requests)
    }

    @Test fun identityConflictThenAuthenticationDoesNotStartSecondRecovery() = runTest {
        val session = Session()
        var requests = 0
        val transport = Transport {
            response(if (++requests == 1) "{\"code\":10105}" else "{\"code\":10001}")
        }
        assertAuthentication { service(session, transport).fetchIdentity() }
        assertEquals(1, session.conflicts)
        assertEquals(0, session.refreshes)
        assertEquals(2, requests)
    }

    @Test fun returnedHttp401RecoversOnceAndUsesReplacementAuthorizer() = runTest {
        val session = Session().apply { refresh = { RisingStonesRequestAuthorizer { _, sink ->
            sink.set("Authorization", "Replacement fixture")
        } } }
        var requests = 0
        val transport = Transport {
            if (++requests == 1) response("{}", 401) else response(Identity)
        }
        assertEquals("Fixture", service(session, transport).fetchIdentity().characterName)
        assertEquals(1, session.refreshes)
        assertEquals("Replacement fixture", transport.requests.last().headers["Authorization"])
    }

    @Test fun businessFailureNeverExposesOfficialMessage() = runTest {
        val failure = try {
            service(Session(), Transport { response("{\"code\":12345,\"msg\":\"PRIVATE_RESPONSE\"}") }).fetchIdentity()
            error("Expected failure")
        } catch (failure: PersonalDataException.Business) { failure }
        assertEquals(12345, failure.code)
        assertFalse(failure.toString().contains("PRIVATE_RESPONSE"))
    }

    @Test fun malformedEnvelopeIsMissingPayload() = runTest {
        for (body in listOf("<html>PRIVATE_RESPONSE</html>", "[]", "{\"data\":{}}")) {
            try {
                service(Session(), Transport { response(body) }).fetchIdentity()
                fail("Expected invalid payload")
            } catch (_: PersonalDataException.MissingPayload) { }
        }
    }

    @Test fun cancelledNonCooperativeTransportCannotPublishSuccess() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val transport = Transport {
            withContext(NonCancellable) { entered.complete(Unit); finish.await() }
            response(Identity)
        }
        var published = false
        val job = launch { service(Session(), transport).fetchIdentity(); published = true }
        entered.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertFalse(published)
        assertTrue(job.isCancelled)
    }

    @Test fun sectionCancellationIsNotConvertedToPartialContent() = runTest {
        val transport = Transport {
            if (it.url.contains("fishNum2")) throw CancellationException("Fixture cancellation")
            response("{\"code\":10000,\"data\":[]}")
        }
        try {
            service(Session(), transport).fetchBoardContent(PersonalDataBoard.Fishing)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun sectionAuthenticationFailureIsNotConvertedToPartialContent() = runTest {
        val transport = Transport {
            response(if (it.url.contains("fishNum2")) "{\"code\":10001}" else "{\"code\":10000,\"data\":[]}")
        }
        assertAuthentication { service(Session(), transport).fetchBoardContent(PersonalDataBoard.Fishing) }
    }

    @Test fun ordinarySectionFailureIsSanitizedAndSuccessfulSectionsRemain() = runTest {
        val transport = Transport {
            if (it.url.contains("fishNum2")) error("PRIVATE_RESPONSE")
            response("{\"code\":10000,\"data\":[]}")
        }
        val result = service(Session(), transport).fetchBoardContent(PersonalDataBoard.Fishing)
        assertEquals("load_failed", result.sections.first { it.id == "fish" }.error)
        assertTrue(result.sections.filter { it.id != "fish" }.all { it.error == null })
    }

    @Test fun ultimateSectionCancellationAndAuthenticationRemainFatal() = runTest {
        val summary = UltimateEncounterSummary(968, 1, null, null, null, null, null)
        for (cancel in listOf(true, false)) {
            val transport = Transport {
                if (it.url.contains("gaoNanTeam2")) {
                    if (cancel) throw CancellationException("Fixture cancellation")
                    response("{\"code\":10001}")
                } else response("{\"code\":10000,\"data\":[]}")
            }
            try {
                service(Session(), transport).fetchUltimateEncounterDetail(summary)
                fail("Expected protected detail failure")
            } catch (failure: Exception) {
                if (cancel) assertTrue(failure is CancellationException)
                else assertTrue(failure is PersonalDataException.AuthenticationRequired)
            }
        }
    }

    private suspend fun assertAuthentication(block: suspend () -> Unit) {
        try { block(); fail("Expected authentication failure") }
        catch (_: PersonalDataException.AuthenticationRequired) { }
    }
    private fun service(session: Session, transport: Transport) = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session,
        temporarySessionId = "fixture-session")
}

private class Session : RisingStonesSessionProvider, RisingStonesIdentityConflictResolver {
    var enabled = true
    override val capabilities get() = if (enabled) setOf(RisingStonesCapability.PersonalData) else emptySet()
    var authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("Authorization", "Fixture token") }
    var lookup: suspend Session.() -> RisingStonesRequestAuthorizer? = { authorizer }
    var refresh: suspend Session.() -> RisingStonesRequestAuthorizer? = { authorizer }
    var resolve: suspend Session.() -> RisingStonesRequestAuthorizer? = { authorizer }
    var refreshes = 0
    var conflicts = 0
    override suspend fun currentAuthorizer() = lookup()
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? { refreshes++; return refresh() }
    override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer? { conflicts++; return resolve() }
}
private class Transport(private val action: suspend (RisingStonesHttpRequest) -> RisingStonesHttpResponse = { response(Identity) }) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return action(request)
    }
}
private fun response(body: String, status: Int = 200) = RisingStonesHttpResponse(status, emptyMap(), body.encodeToByteArray())
private const val Identity = "{\"code\":10000,\"data\":{\"character_name\":\"Fixture\"}}"
