package top.cxmeow.risingstones.feature.dynamic.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.network.*

class DynamicApiServiceTest {
    @Test
    fun feedMapsOriginalAndSharedContentAndCarriesOpaqueCursor() = runTest {
        val transport = Transport()
        val service = service(transport)
        val page = service.fetchFeed(DynamicListQuery(page = 2, limit = 2, pageTime = "cursor-before"))
        assertEquals(2, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("cursor-next", page.pageTime)
        assertNull(page.items[0].reference)
        assertEquals(DynamicOrigin.Glamour, page.items[1].reference?.origin)
        assertEquals(listOf("https://ff14risingstones.gcloud.com.cn/dynamic/sample.png"), page.items[0].imageUrls)
        assertEquals("cursor-before", transport.requests.single().url.toHttpUrl().queryParameter("pageTime"))
        assertEquals(RisingStonesHttpMethod.Get, transport.requests.single().method)
        assertEquals("fixture", transport.requests.single().headers["X-Test-Authorization"])
    }

    @Test
    fun missingCapabilityDoesNotReachTransport() = runTest {
        val transport = Transport()
        val provider = Provider().apply { capabilities = emptySet() }
        try { service(transport, provider).fetchFeed(DynamicListQuery()); fail() }
        catch (_: DynamicException.Unavailable) { }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun authFailureRefreshesOnceAndNeverLeaksResponseText() = runTest {
        for (code in listOf(10001, 10403, 10105)) {
            val transport = Transport().apply { body = """{"code":$code,"msg":"private response"}""" }
            val provider = Provider()
            try { service(transport, provider).fetchFeed(DynamicListQuery()); fail() }
            catch (error: DynamicException.AuthenticationRequired) { assertFalse(error.toString().contains("private")) }
            assertEquals(1, provider.refreshes)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun acceptedCodesReadAllEndpointsWithoutRefreshingOnAuthenticationLookingMessages() = runTest {
        for (code in listOf(10000, 10002)) {
            val transport = Transport().apply {
                body = """{"code":$code,"msg":"未登录","data":{"rows":[$Original]}}"""
            }
            val provider = Provider()
            val service = service(transport, provider)
            assertEquals(1, service.fetchFeed(DynamicListQuery()).items.single().id)
            transport.body = """{"code":$code,"msg":"未登录","data":$Original}"""
            assertEquals(1, service.fetchDetail(1).id)
            transport.body = """{"code":$code,"msg":"未登录","data":{"rows":[{"id":3,"mask_content":"Fixture"}]}}"""
            assertEquals(3, service.fetchComments(1, DynamicListQuery()).items.single().id)
            assertEquals(3, service.fetchReplies(3, DynamicListQuery()).items.single().id)
            assertEquals(listOf("getFollowDynamicList", "dynamicDetail", "dynamicCommentDetail", "dynamicSubCommentDetail"),
                transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
            assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
            assertEquals(0, provider.refreshes)
        }
    }

    @Test
    fun alternateAcceptedCodeKeepsEmptyAndMissingFeedPayloadsDistinct() = runTest {
        val transport = Transport().apply { body = """{"code":10002,"data":{"rows":[],"count":0}}""" }
        val provider = Provider()
        val service = service(transport, provider)
        val empty = service.fetchFeed(DynamicListQuery())
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
        for (body in listOf(
            """{"code":10002}""", """{"code":10002,"data":null}""",
            """{"code":10002,"data":{}}""", """{"code":10002,"data":{"rows":[{}]}}""",
        )) {
            transport.body = body
            val previous = transport.requests.size
            try { service.fetchFeed(DynamicListQuery()); fail() } catch (_: DynamicException.InvalidResponse) { }
            assertEquals(previous + 1, transport.requests.size)
        }
        assertEquals(0, provider.refreshes)
    }

    @Test
    fun alternateAcceptedProbeCodeNeedsFeedRowsAndPreservesOtherCapabilities() = runTest {
        val transport = Transport().apply {
            body = """{"code":10002,"msg":"未登录","data":{"rows":[],"count":0}}"""
        }
        val base = RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10002,
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead)) }
        val validator = RisingStonesDynamicSessionValidator(base, client(transport))
        val validated = validator.validateSession(Authorizer)
        assertEquals(10002, validated.code)
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead), validated.capabilities)
        for (body in listOf(
            """{"code":10002}""", """{"code":10002,"data":null}""",
            """{"code":10002,"data":{}}""", """{"code":10002,"data":{"rows":null}}""",
            """{"code":10403,"data":{"rows":[]}}""",
        )) {
            transport.body = body
            val previous = transport.requests.size
            assertEquals(setOf(RisingStonesCapability.AccountRead), validator.validateSession(Authorizer).capabilities)
            assertEquals(previous + 1, transport.requests.size)
        }
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get &&
            it.url.toHttpUrl().encodedPath == "/api/home/dynamic/getFollowDynamicList" &&
            it.url.toHttpUrl().queryParameter("limit") == "1" })
    }

    @Test
    fun emptyAndMalformedPayloadsRemainDistinct() = runTest {
        val transport = Transport().apply { body = """{"code":10000,"data":{"rows":[],"count":"0"}}""" }
        val page = service(transport).fetchFeed(DynamicListQuery())
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        transport.body = """{"code":10000,"data":{}}"""
        try { service(transport).fetchFeed(DynamicListQuery()); fail() }
        catch (_: DynamicException.InvalidResponse) { }
    }

    @Test
    fun detailCommentsAndRepliesUseDifferentReadEndpoints() = runTest {
        val transport = Transport().apply { body = """{"code":10000,"data":$Original}""" }
        assertEquals(1, service(transport).fetchDetail(1).id)
        transport.body = """{"code":10000,"data":{"count":"1","rows":[{"id":"3","mask_content":"Reply","children_count":2,"to_cname":"Member"}]}}"""
        val comments = service(transport).fetchComments(1, DynamicListQuery())
        assertEquals(2, comments.items.single().childCount)
        assertEquals("Member", comments.items.single().replyToName)
        service(transport).fetchReplies(3, DynamicListQuery())
        assertEquals("/api/home/dynamic/dynamicSubCommentDetail", transport.requests.last().url.toHttpUrl().encodedPath)
        assertEquals("3", transport.requests.last().url.toHttpUrl().queryParameter("root_parent"))
        assertEquals("earliest", transport.requests.last().url.toHttpUrl().queryParameter("order"))
    }

    @Test
    fun probeGrantsOnlyValidatedReadAndRevokesStaleCapabilityOnFailure() = runTest {
        val transport = Transport()
        val base = RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000,
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead)) }
        val validator = RisingStonesDynamicSessionValidator(base, client(transport))
        assertTrue(RisingStonesCapability.DynamicRead in validator.validateSession(Authorizer).capabilities)
        transport.body = """{"code":10403,"data":[]}"""
        val failed = validator.validateSession(Authorizer)
        assertEquals(setOf(RisingStonesCapability.AccountRead), failed.capabilities)
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
        assertTrue(transport.requests.all { it.url.toHttpUrl().queryParameter("limit") == "1" })
    }

    @Test
    fun cancellationPropagatesThroughProbeAndService() = runTest {
        val transport = Transport().apply { cancel = true }
        val validator = RisingStonesDynamicSessionValidator(
            RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000) }, client(transport))
        try { validator.validateSession(Authorizer); fail() } catch (_: CancellationException) { }
        try { service(transport).fetchFeed(DynamicListQuery()); fail() } catch (_: CancellationException) { }
    }

    private fun service(transport: Transport, provider: Provider = Provider()) = DynamicApiService(client(transport), provider)
    private fun client(transport: Transport) = RisingStonesPublicApiClient(transport, listOf("https://rising.test"))
}

private val Authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("X-Test-Authorization", "fixture") }
private class Provider : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.DynamicRead)
    var refreshes = 0
    override suspend fun currentAuthorizer() = Authorizer
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return Authorizer }
}
private class Transport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var cancel = false
    var body = """{"code":10000,"data":{"rows":[$Original,$Shared],"pageTime":"cursor-next"}}"""
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
    }
}
private val Original = """{"id":1,"from":1,"uuid":"fixture-author","character_name":"Member","mask_content":"<p>Activity</p>","pic_url":"https://ff14risingstones.gcloud.com.cn/dynamic/sample.png,http://untrusted.test/image.png,https://untrusted.test/image.png","created_at":"2026-09-18 10:00:00"}"""
private val Shared = """{"id":2,"from":10,"from_id":"23","from_info":{"title":"Outfit","desc":"Description","main_image":"https://ff14risingstones.gcloud.com.cn/glamour/sample.png"}}"""
