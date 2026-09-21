package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowseRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GlamourResponseCodeTest {
    @Test
    fun alternateAcceptedCodeReturnsTheValidatedReadPayload() = runBlocking {
        val transport = ResponseCodeTransport()
        val session = ResponseCodeSession()

        val page = service(transport, session).fetchBrowsePage(GlamourBrowseRequest())

        assertEquals(42, page.page.items.single().id)
        assertEquals("Fixture", page.page.items.single().title)
        assertEquals(RisingStonesHttpMethod.Get, transport.requests.single().method)
        assertEquals(0, session.refreshes)
    }

    @Test
    fun alternateAcceptedWriteCodeNeverRefreshesOrReplaysMutations() = runBlocking {
        val transport = ResponseCodeTransport().apply {
            body = """{"code":10002,"data":null}"""
        }
        val session = ResponseCodeSession()
        val service = service(transport, session)
        val actions = listOf<suspend () -> Unit>(
            { service.createFavoriteFolder("Fixture", true) },
            { service.updateFavoriteFolder(7, "Updated", false) },
            { service.deleteFavoriteFolder(7) },
            { service.claimCoupon("fixture-invite", 42) },
            { service.favoriteInFolder(42, 7) },
            { service.cancelFavorite(42) },
        )
        val paths = listOf("createFavorites", "updateFavorites", "deleteFavorites", "claimCoupon", "favorite", "cancelFavorite")

        actions.forEachIndexed { index, action ->
            action()
            assertEquals(index + 1, transport.requests.size)
            assertEquals(paths[index], transport.requests.last().url.toHttpUrl().pathSegments.last())
            assertEquals(if (index == 2) RisingStonesHttpMethod.Delete else RisingStonesHttpMethod.Post,
                transport.requests.last().method)
            assertEquals(0, session.refreshes)
        }
    }

    @Test
    fun alternateAcceptedCodeStillRequiresAValidReadPayload() = runBlocking {
        for (body in listOf(
            """{"code":10002}""",
            """{"code":10002,"data":null}""",
            """{"code":10002,"data":{}}""",
        )) {
            val transport = ResponseCodeTransport().apply { this.body = body }
            val session = ResponseCodeSession()
            try {
                service(transport, session).fetchBrowsePage(GlamourBrowseRequest())
                fail("An accepted envelope must not bypass payload validation")
            } catch (_: GlamourException.MissingPayload) { }
            assertEquals(1, transport.requests.size)
            assertEquals(0, session.refreshes)
        }
    }

    @Test
    fun acceptedCodesTakePriorityOverAuthenticationLookingMessages() = runBlocking {
        for (code in listOf(10000, 10002)) {
            val transport = ResponseCodeTransport().apply {
                body = """{"code":$code,"msg":"未登录","data":{"rows":[{"id":42,"title":"Fixture"}]}}"""
            }
            val session = ResponseCodeSession()
            val service = service(transport, session)

            assertEquals(42, service.fetchBrowsePage(GlamourBrowseRequest()).page.items.single().id)
            service.claimCoupon("fixture-invite", 42)

            assertEquals(listOf("glamoursList", "claimCoupon"),
                transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
            assertEquals(0, session.refreshes)
        }
    }

    private fun service(transport: ResponseCodeTransport, session: ResponseCodeSession) = GlamourApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session,
    )
}

private class ResponseCodeSession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.GlamourAuthenticated)
    var refreshes = 0

    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("User-Agent", "fixture-paired-agent")
    }

    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshes++
        return currentAuthorizer()
    }
}

private class ResponseCodeTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var body = """{"code":10002,"data":{"rows":[{"id":42,"title":"Fixture"}]}}"""

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
    }
}
