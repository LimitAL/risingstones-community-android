package top.cxmeow.risingstones.feature.account.data

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountException
import top.cxmeow.risingstones.feature.account.domain.RisingStonesRewardStatus
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class RisingStonesAccountApiServiceTest {
    @Test
    fun dashboardUsesCookieSessionAndMapsOfficialAccountContracts() = runBlocking {
        val transport = AccountTransport()
        val provider = RecordingSessionProvider()
        val service = service(transport, provider)

        val dashboard = service.fetchDashboard()

        assertEquals("Hero", dashboard.character?.characterName)
        assertEquals("陆行鸟", dashboard.character?.areaName)
        assertEquals("神意之地", dashboard.character?.groupName)
        assertEquals("剩余 7 天", dashboard.houseRemainDayText)
        assertEquals(2, dashboard.signInCount)
        assertEquals("2026-07-21 08:00:00", dashboard.signInLogs.last().signTime)
        assertEquals(RisingStonesRewardStatus.Claimable, dashboard.rewards[1].status)
        assertTrue(transport.requests.all { it.headers["Cookie"] == "ff14risingstones=secret" })
        val logRequest = transport.requests.first {
            it.url.toHttpUrl().encodedPath.endsWith("/mySignLog")
        }
        assertEquals("2026-07", logRequest.url.toHttpUrl().queryParameter("month"))
        assertTrue(provider.contexts.all {
            it.requirement == RisingStonesAuthenticationRequirement.Required
        })
        assertTrue(provider.contexts.all {
            it.capability == RisingStonesCapability.AccountRead
        })
    }

    @Test
    fun signInAndRewardClaimUseDailySignInCapabilityAndFrozenForms() = runBlocking {
        val transport = AccountTransport()
        val provider = RecordingSessionProvider()
        val service = service(transport, provider)

        val signIn = service.signIn()
        val claim = service.claimReward(2)

        assertEquals("Signed in", signIn.message)
        assertEquals(5, signIn.continuousDays)
        assertEquals(11, signIn.shopExperience)
        assertEquals("claimed", claim)
        val signRequest = transport.requests.first {
            it.url.toHttpUrl().encodedPath.endsWith("/signIn")
        }
        assertEquals(RisingStonesHttpMethod.Post, signRequest.method)
        assertEquals("", requireNotNull(signRequest.body).decodeToString())
        val claimRequest = transport.requests.first {
            it.url.toHttpUrl().encodedPath.endsWith("/getSignReward")
        }
        assertEquals(mapOf("id" to "2", "month" to "2026-07"), claimRequest.form())
        assertTrue(provider.contexts.all {
            it.capability == RisingStonesCapability.DailySignIn
        })
    }

    @Test
    fun readAndWriteCapabilitiesAreCheckedIndependently() {
        val transport = AccountTransport()
        val readOnlyProvider = RecordingSessionProvider(
            capabilities = setOf(RisingStonesCapability.AccountRead),
        )
        val service = service(transport, readOnlyProvider)

        runBlocking { service.fetchSignInSummary() }

        assertThrows(RisingStonesAccountException.AuthenticationRequired::class.java) {
            runBlocking { service.signIn() }
        }
    }

    private fun service(
        transport: RisingStonesHttpClient,
        provider: RisingStonesSessionProvider,
    ) = RisingStonesAccountApiService(
        client = RisingStonesPublicApiClient(
            transport = transport,
            baseUrls = listOf("https://rising.test"),
        ),
        sessionProvider = provider,
        signInMonth = { "2026-07" },
    )
}

private class RecordingSessionProvider(
    override val capabilities: Set<RisingStonesCapability> = setOf(
        RisingStonesCapability.AccountRead,
        RisingStonesCapability.DailySignIn,
    ),
) : RisingStonesSessionProvider {
    val contexts = mutableListOf<RisingStonesRequestContext>()

    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer =
        RisingStonesRequestAuthorizer { context, sink ->
            contexts += context
            sink.set("Cookie", "ff14risingstones=secret")
        }
}

private class AccountTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val path = request.url.toHttpUrl().encodedPath
        val body = when {
            path.endsWith("/getUserInfo") ->
                """{"code":10000,"data":{"characterDetail":{"character_name":"Hero","area_name":"陆行鸟","group_name":"神意之地","house_remain_day":"剩余 7 天"}}}"""
            path.endsWith("/mySignLog") ->
                """{"code":10000,"data":{"count":"2","rows":[{"id":"1","sign_time":"2026-07-20 08:00:00","platform":"1"},{"id":"2","signTime":"2026-07-21 08:00:00","platform":2,"ipLocation":"Shanghai"}]}}"""
            path.endsWith("/signRewardList") ->
                """{"code":10000,"data":[{"id":1,"item_desc":"10 days","item_name":"Ticket","rule":"10","is_get":1},{"id":"2","description":"Feather","rule":20,"isGet":"0"},{"id":3,"rule":30,"is_get":-1}]}"""
            path.endsWith("/signIn") ->
                """{"code":10000,"msg":"ok","data":{"sqMsg":"Signed in","continuousDays":"5","totalDays":9,"sqExp":10,"shopExp":"11"}}"""
            path.endsWith("/getSignReward") -> """{"code":10000,"msg":"claimed"}"""
            else -> error("Unexpected request: $request")
        }
        return RisingStonesHttpResponse(
            statusCode = 200,
            headers = emptyMap(),
            body = body.encodeToByteArray(),
        )
    }
}

private fun RisingStonesHttpRequest.form(): Map<String, String> =
    requireNotNull(body).decodeToString()
        .split('&')
        .filter(String::isNotBlank)
        .associate { item ->
            val pieces = item.split('=', limit = 2)
            pieces[0].formDecoded() to pieces.getOrElse(1) { "" }.formDecoded()
        }

private fun String.formDecoded(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
