package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.glamour.domain.*
import top.cxmeow.risingstones.network.*

class GlamourBrowsingApiTest {
    @Test fun followingUsesDedicatedEndpointAndCursorOnlyAfterFirstPage() = runBlocking {
        val transport = BrowseTransport()
        val service = service(transport)
        val first = service.fetchBrowsePage(GlamourBrowseRequest(following = true, pageTime = "ignored"))
        service.fetchBrowsePage(GlamourBrowseRequest(GlamourListRequest(page = 2), following = true, pageTime = first.nextPageTime))
        val urls = transport.requests.map { it.url.toHttpUrl() }
        assertTrue(urls.all { it.encodedPath.endsWith("/glamoursFollowList") })
        assertNull(urls.first().queryParameter("pageTime"))
        assertEquals("cursor-1", urls.last().queryParameter("pageTime"))
        assertNull(urls.last().queryParameter("order"))
        assertNull(urls.last().queryParameter("uuid"))
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
        assertTrue(first.page.hasNextPage)
        assertTrue(transport.requests.all { it.headers["User-Agent"] == "fixture-paired-agent" })
    }

    @Test fun filtersUseOfficialNamesAndDeterministicTagSelection() = runBlocking {
        val transport = BrowseTransport()
        service(transport).fetchBrowsePage(GlamourBrowseRequest(
            GlamourListRequest(filter = GlamourFilter(GlamourListOrder.Latest, 4, 2, "lastWeek")),
            tagIds = setOf(57, 1), tribeId = 8))
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("1,57", url.queryParameter("tag_ids"))
        assertEquals("8", url.queryParameter("tribe_id"))
        assertEquals("lastWeek", url.queryParameter("createTime"))
        assertEquals("4", url.queryParameter("race_id"))
        assertEquals("2", url.queryParameter("gender_id"))
    }

    @Test fun communityCursorContinuesUntilEmptyAndOwnWorksKeepTheirQueryContract() = runBlocking {
        val transport = BrowseTransport()
        val service = service(transport)
        val first = service.fetchBrowsePage(GlamourBrowseRequest())
        assertTrue(first.page.hasNextPage)
        transport.data["glamoursList"] = """{"rows":[],"pageTime":"cursor-2"}"""
        val next = service.fetchBrowsePage(GlamourBrowseRequest(GlamourListRequest(page = 2), pageTime = first.nextPageTime))
        assertFalse(next.page.hasNextPage)
        assertEquals("cursor-1", transport.requests.last().url.toHttpUrl().queryParameter("pageTime"))
        transport.data["myGlamoursList"] = """{"rows":[],"count":"0"}"""
        val own = service.fetchBrowsePage(GlamourBrowseRequest(GlamourListRequest(source = GlamourListSource.Profile)))
        assertTrue(own.page.items.isEmpty())
        assertFalse(own.page.hasNextPage)
        val ownUrl = transport.requests.last().url.toHttpUrl()
        assertNull(ownUrl.queryParameter("uuid"))
        assertEquals("latest", ownUrl.queryParameter("order"))
        assertEquals("", ownUrl.queryParameter("title"))
        service.fetchBrowsePage(GlamourBrowseRequest(GlamourListRequest(source = GlamourListSource.Profile, authorId = "fixture-author")))
        assertEquals("fixture-author", transport.requests.last().url.toHttpUrl().queryParameter("uuid"))
    }

    @Test fun legacyRowsRemainTolerantWhileNewBrowsingRejectsMalformedPayloads() = runBlocking {
        val transport = BrowseTransport()
        val service = service(transport)
        for (payload in listOf("""{"count":"0"}""", """{"rows":null,"count":"0"}""", """{"rows":[{}],"count":"0"}""")) {
            transport.data["glamoursList"] = payload
            assertTrue(service.fetchGlamours(GlamourListRequest()).items.isEmpty())
            try { service.fetchBrowsePage(GlamourBrowseRequest()); fail() }
            catch (_: GlamourException.MissingPayload) { }
        }
        transport.data["glamoursList"] = """{"rows":[{"id":"1"}],"count":"1"}"""
        assertFalse(service.fetchGlamours(GlamourListRequest()).hasNextPage)
        assertTrue(service.fetchBrowsePage(GlamourBrowseRequest()).page.hasNextPage)
    }

    @Test fun tagCategoriesAndTribesAreStrictSortedProjections() = runBlocking {
        val transport = BrowseTransport()
        transport.data["tagList"] = """{"categories":[{"id":"2","name":"Style","sort":2,"tags":[{"id":"4","name":"Second","sort":2},{"id":"3","name":"First","sort":1}],"internal":"private-fixture"},{"id":"1","name":"Color","sort":1,"tags":[]}]}"""
        transport.data["tribeList"] = """{"list":[{"id":"2","race_id":"1","name":"Fixture tribe","sort":1,"internal":"private-fixture"}]}"""
        val service = service(transport)
        val tags = service.fetchTagCategories()
        assertEquals(listOf(1, 2), tags.map { it.id })
        assertEquals(listOf(3, 4), tags.last().tags.map { it.id })
        assertEquals(1, service.fetchTribes().single().raceId)
        assertFalse(tags.toString().contains("private-fixture"))
        transport.data["tagList"] = """{"categories":[{}]}"""
        try { service.fetchTagCategories(); fail() } catch (_: GlamourException.MissingPayload) { }
    }

    @Test fun invalidQueriesAndMissingCapabilityCannotReachNetwork() = runBlocking {
        val transport = BrowseTransport()
        val service = service(transport)
        try { service.fetchBrowsePage(GlamourBrowseRequest(following = true, tagIds = setOf(1))); fail() }
        catch (_: IllegalArgumentException) { }
        try { service.fetchBrowsePage(GlamourBrowseRequest(GlamourListRequest(authorId = "fixture-author"), following = true)); fail() }
        catch (_: IllegalArgumentException) { }
        val session = BrowseSession().apply { capabilities = emptySet() }
        try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (_: GlamourException.AuthenticationRequired) { }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun refreshKeepsPairedAgentAndStopsWhenCapabilityIsRevoked() = runBlocking {
        val transport = BrowseTransport().apply { code = 10105 }
        val session = BrowseSession().apply { onRefresh = { transport.code = 10000 } }
        service(transport, session).fetchBrowsePage(GlamourBrowseRequest())
        assertEquals(1, session.refreshes)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.headers["User-Agent"] == "fixture-paired-agent" })
        transport.requests.clear(); transport.code = 10403
        session.onRefresh = { session.capabilities = emptySet() }
        try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (_: GlamourException.AuthenticationRequired) { }
        assertEquals(1, transport.requests.size)
    }

    @Test fun httpAndBusinessAuthenticationFailuresHaveOneRetryThenTypedRejection() = runBlocking {
        for ((http, code) in listOf(401 to 10000, 403 to 10000, 200 to 10001, 200 to 10403, 200 to 10105)) {
            val transport = BrowseTransport().apply { status = http; this.code = code }
            val session = BrowseSession()
            try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
            catch (_: GlamourException.AuthenticationRequired) { }
            assertEquals(2, transport.requests.size)
            assertEquals(1, session.refreshes)
        }
    }

    @Test fun malformedAndBusinessResponsesDoNotExposeRawBodies() = runBlocking {
        val transport = BrowseTransport()
        transport.data["glamoursList"] = """{"rows":[{"private":"private-fixture"}]}"""
        try { service(transport).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (error: GlamourException.MissingPayload) { assertFalse(error.toString().contains("private-fixture")) }
        transport.code = 12500
        try { service(transport).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (error: GlamourException.Business) { assertNull(error.reason); assertFalse(error.toString().contains("private-fixture")) }
    }

    @Test fun returnedAndThrownHttpConflictsUseOneExplicitResolutionWithThePairedAgent() = runBlocking {
        for (throwsResponse in listOf(false, true)) {
            val transport = BrowseTransport().apply {
                status = 409
                code = 10105
                if (throwsResponse) failure = RisingStonesHttpException.ServerResponse(
                    409, """{"code":10105,"msg":"private-fixture"}""".encodeToByteArray())
            }
            val session = BrowseConflictSession().apply {
                onResolution = { transport.status = 200; transport.code = 10000; transport.failure = null }
            }
            service(transport, session).fetchBrowsePage(GlamourBrowseRequest())
            assertEquals(1, session.resolutions)
            assertEquals(0, session.refreshes)
            assertEquals(2, transport.requests.size)
            assertTrue(transport.requests.all { it.headers["User-Agent"] == "fixture-paired-agent" })
        }
    }

    @Test fun declinedOrRepeatedConflictDoesNotTriggerAnotherRefresh() = runBlocking {
        for (declined in listOf(false, true)) {
            val transport = BrowseTransport().apply { code = 10105 }
            val session = BrowseConflictSession().apply { declineResolution = declined }
            try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
            catch (_: GlamourException.AuthenticationRequired) { }
            assertEquals(1, session.resolutions)
            assertEquals(0, session.refreshes)
            assertEquals(if (declined) 1 else 2, transport.requests.size)
        }
    }

    @Test fun authorizerAcquisitionIsSanitizedAndItsCancellationStillPropagates() = runBlocking {
        val transport = BrowseTransport()
        val session = BrowseSession().apply { onCurrentAuthorizer = { throw IllegalStateException("private-fixture") } }
        try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (error: GlamourTransportException) { assertNull(error.cause); assertFalse(error.toString().contains("private-fixture")) }
        val cancellation = CancellationException("fixture-cancel")
        session.onCurrentAuthorizer = { throw cancellation }
        try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (error: CancellationException) { assertSame(cancellation, error) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun refreshAndResolutionCancellationStopBeforeRetry() = runBlocking {
        val cancellation = CancellationException("fixture-cancel")
        for (conflict in listOf(false, true)) {
            val transport = BrowseTransport().apply { code = if (conflict) 10105 else 10403 }
            val session = if (conflict) BrowseConflictSession().apply { onResolution = { throw cancellation } }
                else BrowseSession().apply { onRefresh = { throw cancellation } }
            try { service(transport, session).fetchBrowsePage(GlamourBrowseRequest()); fail() }
            catch (error: CancellationException) { assertSame(cancellation, error) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun transportExceptionsAreSanitizedAndCancellationPropagates() = runBlocking {
        val transport = BrowseTransport().apply { failure = IllegalStateException("private-fixture") }
        try { service(transport).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (error: GlamourTransportException) { assertNull(error.cause); assertFalse(error.toString().contains("private-fixture")) }
        transport.failure = CancellationException("fixture-cancel")
        try { service(transport).fetchBrowsePage(GlamourBrowseRequest()); fail() }
        catch (_: CancellationException) { }
    }

    private fun service(transport: BrowseTransport, session: BrowseSession = BrowseSession()) =
        GlamourApiService(RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session)
}

private open class BrowseSession : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.GlamourAuthenticated)
    var refreshes = 0
    var onRefresh: () -> Unit = {}
    var onCurrentAuthorizer: () -> Unit = {}
    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer {
        onCurrentAuthorizer()
        return RisingStonesRequestAuthorizer { context, sink ->
            assertEquals(RisingStonesCapability.GlamourAuthenticated, context.capability)
            sink.set("User-Agent", "fixture-paired-agent")
        }
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; onRefresh(); return currentAuthorizer() }
}

private class BrowseConflictSession : BrowseSession(), RisingStonesIdentityConflictResolver {
    var resolutions = 0
    var declineResolution = false
    var onResolution: () -> Unit = {}
    override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer? {
        resolutions++
        onResolution()
        return if (declineResolution) null else currentAuthorizer()
    }
}

private class BrowseTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    val data = mutableMapOf<String, String>()
    var status = 200
    var code = 10000
    var failure: Exception? = null
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        failure?.let { throw it }
        val body = data[request.url.toHttpUrl().pathSegments.last()] ?: """{"rows":[{"id":"1","title":"Fixture"}],"pageTime":"cursor-1","count":"1"}"""
        return RisingStonesHttpResponse(status, emptyMap(), """{"code":$code,"msg":"private-fixture","data":$body}""".encodeToByteArray())
    }
}
