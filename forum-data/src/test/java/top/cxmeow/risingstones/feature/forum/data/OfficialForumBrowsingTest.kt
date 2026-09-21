package top.cxmeow.risingstones.feature.forum.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.network.*

class OfficialForumBrowsingTest {
    @Test
    fun guideCategoriesPreserveChildrenAndExcludeDisabledNodes() = runBlocking {
        val transport = BrowseTransport()
        val categories = service(transport).fetchCategories(OfficialForumContentKind.Guide)
        assertEquals("2", transport.requests.single().url.toHttpUrl().queryParameter("type"))
        assertEquals(listOf(3), categories.map { it.part.id })
        assertEquals(listOf(8), categories.single().children.map { it.part.id })
    }

    @Test
    fun browseFiltersAndCursorUseObservedOfficialContract() = runBlocking {
        val transport = BrowseTransport()
        val service = service(transport)
        val first = service.fetchBrowsePage(OfficialForumBrowseQuery(
            OfficialForumListQuery(limit = 2), OfficialForumFeedFilter.Latest, "ignored-first-page"))
        assertEquals("cursor-one", first.pageTime)
        assertEquals(3, first.page.total)
        assertEquals("latest", transport.requests.last().url.toHttpUrl().queryParameter("order"))
        assertNull(transport.requests.last().url.toHttpUrl().queryParameter("pageTime"))
        service.fetchBrowsePage(OfficialForumBrowseQuery(
            OfficialForumListQuery(OfficialForumContentKind.Guide, 2, 2, listOf(8)),
            OfficialForumFeedFilter.Refined, first.pageTime))
        val next = transport.requests.last().url.toHttpUrl()
        assertEquals("cursor-one", next.queryParameter("pageTime"))
        assertEquals("8", next.queryParameter("part_id"))
        assertEquals("1", next.queryParameter("is_refine"))
        assertEquals("", next.queryParameter("order"))
        service.fetchBrowsePage(OfficialForumBrowseQuery(filter = OfficialForumFeedFilter.Pinned))
        assertEquals("1", transport.requests.last().url.toHttpUrl().queryParameter("is_top"))
    }

    @Test
    fun searchKeepsCursorAndEmptyPageIsConfirmedEmpty() = runBlocking {
        val transport = BrowseTransport()
        val service = service(transport)
        val result = service.searchBrowsePage(OfficialForumSearchQuery(
            keywords = "guide", page = 2, partIds = listOf(8)), "search-cursor")
        assertEquals("search-cursor", transport.requests.last().url.toHttpUrl().queryParameter("pageTime"))
        assertEquals("cursor-one", result.pageTime)
        transport.body = """{"code":10000,"data":{"rows":[],"pageTime":"end"}}"""
        val empty = service.fetchBrowsePage(OfficialForumBrowseQuery())
        assertTrue(empty.page.items.isEmpty())
        assertEquals(0, empty.page.total)
    }

    @Test
    fun missingPayloadIsNotPresentedAsConfirmedEmpty() {
        val transport = BrowseTransport().apply { body = """{"code":10000,"data":null}""" }
        assertThrows(OfficialForumException.MissingPayload::class.java) {
            runBlocking { service(transport).fetchBrowsePage(OfficialForumBrowseQuery()) }
        }
    }

    private fun service(transport: BrowseTransport) = OfficialForumApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")))
}

private class BrowseTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var body = """{"code":10000,"data":{"rows":[{"posts_id":1,"title":"One"},{"posts_id":2,"title":"Two"}],"pageTime":"cursor-one"}}"""
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val response = if (request.url.toHttpUrl().encodedPath.endsWith("partList")) {
            """{"code":10000,"data":[{"id":3,"name":"Battle","weight":2,"children":[{"id":8,"name":"Job"},{"id":9,"status":0}]},{"id":4,"status":0}]}"""
        } else body
        return RisingStonesHttpResponse(200, emptyMap(), response.encodeToByteArray())
    }
}
