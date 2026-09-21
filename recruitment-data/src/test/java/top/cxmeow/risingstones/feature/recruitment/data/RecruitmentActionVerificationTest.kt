package top.cxmeow.risingstones.feature.recruitment.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class RecruitmentActionVerificationTest {
    @Test fun firstEligibleResponseUsesOneBoundAttemptAndGrantsAfterRequiredData() = runBlocking {
        val fixture = Fixture("""{"code":10000,"data":{"recruit_contact_info":"Server contact"}}""")
        assertTrue(fixture.service.canAttemptAuthenticatedWrites)
        assertFalse(fixture.service.canPerformAuthenticatedWrites)
        assertFalse(fixture.service.hasCommunityIdentity)
        assertTrue(fixture.requests.isEmpty())

        assertEquals("Server contact", fixture.service.respondToDutyRecruitment(42, "Fixture contact"))

        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
        assertEquals("BoundLoginUA/1", fixture.requests.single().headers["User-Agent"])
        assertEquals(RisingStonesHttpMethod.Post, fixture.requests.single().method)
        assertEquals("api/home/recruit/responseRecruitFb", fixture.provider.context?.path)
        assertEquals(RisingStonesCapability.RecruitmentWrite, fixture.provider.context?.capability)
        assertEquals(RisingStonesAuthenticationRequirement.Required, fixture.provider.context?.requirement)
    }

    @Test fun firstEligibleUnlikeAcceptsTheSignedPayloadAndCompletesOnce() = runBlocking {
        val fixture = Fixture("""{"code":10002,"data":"-1"}""")
        assertEquals(-1, fixture.service.likeRolePlayReview("review-1"))
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
        assertEquals("api/home/recruit/rpCommentlike", fixture.provider.context?.path)
    }

    @Test fun firstEligibleResponseWithoutOptionalContactStillCompletesAndIsNotReplayed() = runBlocking {
        val fixture = Fixture("""{"code":10000,"data":{}}""")
        assertEquals(null, fixture.service.respondToBeginnerRecruitment(11, "Fixture contact"))
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
    }

    @Test fun invalidPayloadBusinessFailureAndMalformedBodyNeverGrantOrReplay() {
        val bodies = listOf(
            """{"code":10000}""",
            """{"code":10000,"data":null}""",
            """{"code":10000,"data":[]}""",
            """{"code":10105,"data":{"recruit_contact_info":"Ignored"}}""",
            "not-json",
            "[]",
        )
        for (body in bodies) {
            val fixture = Fixture(body)
            assertNotNull(runCatching {
                runBlocking { fixture.service.respondToBeginnerRecruitment(11, "Fixture") }
            }.exceptionOrNull())
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
            assertEquals(0, fixture.provider.refreshes)
        }
    }

    @Test fun authenticationFailureRefreshesReadStateButNeverReplaysTheWrite() {
        val fixture = Fixture("""{"code":401,"msg":"Expired"}""")
        assertSame(DutyRecruitmentException.AuthenticationRequired, runCatching {
            runBlocking { fixture.service.respondToDutyRecruitment(42, "Fixture") }
        }.exceptionOrNull())
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.refreshes)
        assertEquals(0, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
    }

    @Test fun httpAuthenticationFailureBecomesDomainAuthenticationWithoutReplay() {
        val fixture = Fixture(
            "{}",
            failure = top.cxmeow.risingstones.network.RisingStonesHttpException.ServerResponse(
                401,
                byteArrayOf(),
            ),
        )
        assertSame(DutyRecruitmentException.AuthenticationRequired, runCatching {
            runBlocking { fixture.service.likeRolePlayReview("review-1") }
        }.exceptionOrNull())
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.provider.refreshes)
        assertEquals(0, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
    }

    @Test fun httpFailureWithAcceptedBusinessBodyNeverCompletesTheAttempt() {
        val fixture = Fixture("""{"code":10000,"data":"1"}""", status = 500)
        assertTrue(runCatching {
            runBlocking { fixture.service.likeRolePlayReview("review-1") }
        }.exceptionOrNull() is top.cxmeow.risingstones.network.RisingStonesHttpException.ServerResponse)
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.provider.refreshes)
        assertEquals(0, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
    }

    @Test fun httpIdentityConflictIsNeverResolvedOrReplayed() {
        val conflict = top.cxmeow.risingstones.network.RisingStonesHttpException.ServerResponse(
            401,
            """{"code":10105,"msg":"Choose identity"}""".encodeToByteArray(),
        )
        val fixture = Fixture("{}", failure = conflict)
        assertNotNull(runCatching {
            runBlocking { fixture.service.respondToDutyRecruitment(42, "Fixture") }
        }.exceptionOrNull())
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.provider.refreshes)
        assertEquals(0, fixture.provider.completed)
        assertEquals(1, fixture.provider.closed)
    }

    @Test fun unknownFailureCancellationAndExpiredAttemptNeverGrantOrReplay() {
        for (failure in listOf(IOException("unknown outcome"), CancellationException("cancelled"))) {
            val fixture = Fixture("{}", failure = failure)
            val caught = runCatching {
                runBlocking { fixture.service.likeRolePlayReview("review-1") }
            }.exceptionOrNull()
            assertNotNull(caught)
            if (failure is CancellationException) assertSame(failure, caught)
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.provider.completed)
            assertEquals(1, fixture.provider.closed)
        }

        val expired = Fixture("""{"code":10000,"data":"1"}""")
        expired.provider.current = false
        assertSame(DutyRecruitmentException.AuthenticationRequired, runCatching {
            runBlocking { expired.service.likeRolePlayReview("review-1") }
        }.exceptionOrNull())
        assertEquals(1, expired.requests.size)
        assertEquals(0, expired.provider.completed)
        assertEquals(1, expired.provider.closed)
    }

    @Test fun missingEligibilityBeginsNoAttemptAndSendsNoRequest() {
        val fixture = Fixture("{}")
        fixture.provider.eligible = false
        assertFalse(fixture.service.canAttemptAuthenticatedWrites)
        assertSame(DutyRecruitmentException.AuthenticationRequired, runCatching {
            runBlocking { fixture.service.respondToDutyRecruitment(42, "Fixture") }
        }.exceptionOrNull())
        assertTrue(fixture.requests.isEmpty())
        assertEquals(0, fixture.provider.closed)
    }

    private class Fixture(body: String, failure: Exception? = null, status: Int = 200) {
        val provider = AttemptProvider()
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val service = DutyRecruitmentApiService(
            RisingStonesPublicApiClient(object : RisingStonesHttpClient {
                override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                    requests += request
                    failure?.let { throw it }
                    return RisingStonesHttpResponse(status, emptyMap(), body.encodeToByteArray())
                }
            }, listOf("https://rising.test")),
            provider,
            temporarySessionId = "fixture-session",
        )
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
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? {
            refreshes++
            return null
        }

        override fun canAttemptCapability(capability: RisingStonesCapability): Boolean =
            eligible && capability == RisingStonesCapability.RecruitmentWrite

        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? {
            if (!eligible || context.capability != RisingStonesCapability.RecruitmentWrite) return null
            this.context = context
            return object : RisingStonesCapabilityAttempt {
                override val authorizer = RisingStonesRequestAuthorizer { actual, sink ->
                    assertEquals(context, actual)
                    sink.set("User-Agent", "BoundLoginUA/1")
                }

                override suspend fun complete(): Boolean {
                    if (current) completed++
                    return current
                }

                override fun close() {
                    closed++
                }
            }
        }
    }
}
