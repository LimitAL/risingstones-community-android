package top.cxmeow.risingstones.feature.account.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class AccountResponseCodeTest {
    @Test
    fun acceptedDashboardResponsesWinOverAuthenticationMessagesWithoutRefresh() = runBlocking {
        for (code in listOf(10000, 10002)) {
            val transport = ResponseCodeAccountTransport { request ->
                val data = when (request.url.toHttpUrl().pathSegments.last()) {
                    "getUserInfo" -> """{"characterDetail":{"character_name":"Fixture Hero"}}"""
                    "mySignLog" -> """{"count":2,"rows":[]}"""
                    "signRewardList" -> "[]"
                    else -> error("Unexpected endpoint")
                }
                """{"code":$code,"msg":"未登录","data":$data}"""
            }
            val session = ResponseCodeAccountSession()

            val dashboard = service(transport, session).fetchDashboard()

            assertEquals("Fixture Hero", dashboard.character?.characterName)
            assertEquals(2, dashboard.signInCount)
            assertEquals(3, transport.requests.size)
            assertEquals(0, session.refreshes)
        }
    }

    @Test
    fun accepted10002SignInIsSubmittedOnceWithoutAuthorizerRefresh() = runBlocking {
        val transport = ResponseCodeAccountTransport {
            """{"code":10002,"msg":"session expired","data":{"sqMsg":"Signed in","continuousDays":3}}"""
        }
        val session = ResponseCodeAccountSession()

        val result = service(transport, session).signIn()

        assertEquals("Signed in", result.message)
        assertEquals(3, result.continuousDays)
        assertFalse(result.isAlreadyCheckedIn)
        assertEquals(0, session.refreshes)
        assertEquals(1, transport.requests.size)
        assertEquals(RisingStonesHttpMethod.Post, transport.requests.single().method)
        assertEquals("signIn", transport.requests.single().url.toHttpUrl().pathSegments.last())
    }

    @Test
    fun accepted10002RewardClaimIsSubmittedOnceWithoutAuthorizerRefresh() = runBlocking {
        val transport = ResponseCodeAccountTransport { """{"code":10002,"msg":"登录失效"}""" }
        val session = ResponseCodeAccountSession()

        val result = service(transport, session).claimReward(2)

        assertEquals("登录失效", result)
        assertEquals(0, session.refreshes)
        assertEquals(1, transport.requests.size)
        assertEquals(RisingStonesHttpMethod.Post, transport.requests.single().method)
        assertEquals("getSignReward", transport.requests.single().url.toHttpUrl().pathSegments.last())
    }

    @Test
    fun alreadyCheckedIn10001RemainsSpecificToSignIn() = runBlocking {
        val transport = ResponseCodeAccountTransport { """{"code":10001,"msg":"Already checked in"}""" }
        val session = ResponseCodeAccountSession()
        val service = service(transport, session)

        assertTrue(service.signIn().isAlreadyCheckedIn)
        val failure = assertThrows(RisingStonesAccountException.Business::class.java) {
            runBlocking { service.claimReward(2) }
        }

        assertEquals(10001, failure.code)
        assertEquals(2, transport.requests.size)
        assertEquals(0, session.refreshes)
    }

    @Test
    fun acceptedDashboardCodeDoesNotReplaceRequiredPayload() {
        for (code in listOf(10000, 10002)) {
            for (data in listOf("", ",\"data\":null", ",\"data\":[]")) {
                val transport = ResponseCodeAccountTransport { """{"code":$code,"msg":"未登录"$data}""" }
                val session = ResponseCodeAccountSession()

                assertThrows(RisingStonesAccountException.MissingPayload::class.java) {
                    runBlocking { service(transport, session).fetchDashboard() }
                }

                assertEquals(1, transport.requests.size)
                assertEquals(0, session.refreshes)
            }
        }
    }

    private fun service(transport: RisingStonesHttpClient, session: RisingStonesSessionProvider) =
        RisingStonesAccountApiService(
            RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
            session,
            signInMonth = { "2026-09" },
        )
}

private class ResponseCodeAccountSession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DailySignIn)
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, _ -> }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshes++
        return currentAuthorizer()
    }
}

private class ResponseCodeAccountTransport(
    private val response: (RisingStonesHttpRequest) -> String,
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), response(request).encodeToByteArray())
    }
}
