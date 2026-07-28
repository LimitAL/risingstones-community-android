package top.cxmeow.risingstones.feature.glamour.data

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListOrder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GlamourApiServiceTest {
    @Test
    fun communityListUsesFrozenQueryAndMapsFlexiblePayload() = runBlocking {
        val transport = GlamourTransport()
        val service = service(transport)

        val page = service.fetchGlamours(
            GlamourListRequest(
                page = 2,
                limit = 12,
                filter = GlamourFilter(
                    order = GlamourListOrder.Hottest,
                    raceId = 4,
                    genderId = 2,
                    createTime = "2026-07",
                ),
            ),
        )

        val request = transport.requests.single()
        val url = request.url.toHttpUrl()
        assertTrue(url.encodedPath.endsWith("/api/home/glamour/glamoursList"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("12", url.queryParameter("limit"))
        assertEquals("hottest", url.queryParameter("order"))
        assertEquals("4", url.queryParameter("race_id"))
        assertEquals("2", url.queryParameter("gender_id"))
        assertEquals("2026-07", url.queryParameter("createTime"))
        assertFalse(url.queryParameter("tempsuid").isNullOrBlank())
        assertEquals("host token", request.headers["Authorization"])
        assertEquals(2, page.currentPage)
        assertTrue(page.hasNextPage)
        assertEquals(42, page.items.single().id)
        assertEquals("Hero", page.items.single().author.characterName)
        assertEquals(listOf(19, 20), page.items.single().jobIds)
        assertEquals(2, page.items.single().imageUrls.size)
    }

    @Test
    fun favoritesProfileAndSearchUseTheIosEndpointContracts() = runBlocking {
        val transport = GlamourTransport()
        val service = service(transport)

        service.fetchGlamours(
            GlamourListRequest(
                source = GlamourListSource.Favorites,
                favoriteFolderId = 7,
                authorId = "author-1",
                limit = 16,
            ),
        )
        service.fetchGlamours(
            GlamourListRequest(
                source = GlamourListSource.Favorites,
                favoriteFolderId = 7,
                authorId = "author-1",
                search = GlamourSearchSelection(
                    keywords = "100",
                    searchByEquipment = true,
                    displayTitle = "Hempen Camise",
                ),
            ),
        )
        service.fetchGlamours(
            GlamourListRequest(
                source = GlamourListSource.Profile,
                authorId = "author-2",
                search = GlamourSearchSelection(
                    keywords = "200",
                    searchByGlasses = true,
                    displayTitle = "Classic Spectacles",
                ),
            ),
        )
        service.fetchGlamours(
            GlamourListRequest(source = GlamourListSource.Profile, authorId = "author-2"),
        )
        service.fetchGlamours(
            GlamourListRequest(
                page = 3,
                search = GlamourSearchSelection(
                    keywords = "white mage",
                    searchByEquipment = true,
                    searchByGlasses = true,
                    searchByOrnament = true,
                ),
            ),
        )

        val favorite = transport.requests[0].url.toHttpUrl()
        assertTrue(favorite.encodedPath.endsWith("/myFavoriteItemsList"))
        assertEquals("7", favorite.queryParameter("favorite_id"))
        assertEquals("author-1", favorite.queryParameter("uuid"))
        val profile = transport.requests[3].url.toHttpUrl()
        assertTrue(profile.encodedPath.endsWith("/myGlamoursList"))
        assertEquals("latest", profile.queryParameter("order"))
        assertEquals("", profile.queryParameter("title"))
        assertEquals("author-2", profile.queryParameter("uuid"))
        val search = transport.requests[4].url.toHttpUrl()
        assertTrue(search.encodedPath.endsWith("/api/common/search"))
        assertEquals("7", search.queryParameter("type"))
        assertEquals("white mage", search.queryParameter("keywords"))
        assertEquals("1", search.queryParameter("searchByEquipment"))
        assertEquals("1", search.queryParameter("searchByGlasses"))
        assertEquals("1", search.queryParameter("searchByOrnament"))
        assertEquals("3", search.queryParameter("page"))
        val favoriteSearch = transport.requests[1].url.toHttpUrl()
        assertTrue(favoriteSearch.encodedPath.endsWith("/myFavoriteItemsList"))
        assertEquals("7", favoriteSearch.queryParameter("favorite_id"))
        assertEquals("100", favoriteSearch.queryParameter("equip_id"))
        assertEquals(null, favoriteSearch.queryParameter("keywords"))
        val profileSearch = transport.requests[2].url.toHttpUrl()
        assertTrue(profileSearch.encodedPath.endsWith("/myGlamoursList"))
        assertEquals("200", profileSearch.queryParameter("glasses_id"))
        assertEquals("", profileSearch.queryParameter("title"))
    }

    @Test
    fun detailFoldersAndMutationsPreserveMethodsBodiesAndDomainFields() = runBlocking {
        val transport = GlamourTransport()
        val service = service(transport)

        val folders = service.fetchFavoriteFolders()
        service.createFavoriteFolder("Raid looks", true)
        service.deleteFavoriteFolder(9)
        service.favorite(42)
        service.cancelFavorite(42)
        val liked = service.toggleLike(42)
        val detail = service.fetchDetail(42)

        assertEquals(7, folders.single().id)
        assertTrue(folders.single().isDefault)
        val create = transport.requests.first { it.url.toHttpUrl().encodedPath.endsWith("/createFavorites") }
        assertEquals(RisingStonesHttpMethod.Post, create.method)
        assertEquals("Raid looks", create.form()["name"])
        assertEquals("1", create.form()["is_public"])
        val delete = transport.requests.first { it.url.toHttpUrl().encodedPath.endsWith("/deleteFavorites") }
        assertEquals(RisingStonesHttpMethod.Delete, delete.method)
        assertEquals("{\"id\":\"9\"}", requireNotNull(delete.body).decodeToString())
        val favorite = transport.requests.first { it.url.toHttpUrl().encodedPath.endsWith("/favorite") }
        assertEquals("7", favorite.form()["favorite_id"])
        assertEquals("42", favorite.form()["id"])
        assertTrue(liked)
        assertEquals("Look 42", detail.title)
        assertEquals("Hero", detail.author.characterName)
        assertEquals(listOf("Hyur"), detail.raceNames)
        assertEquals("Body", detail.equipments.single().name)
        assertEquals("Snow White", detail.equipments.single().dye(0)?.name)
        assertEquals("Classic Spectacles", detail.faceAccessory?.name)
        assertEquals("Parasol", detail.fashionAccessory?.name)
    }

    @Test
    fun everyOperationRequiresRisingStonesIdentity() {
        val service = GlamourApiService(
            RisingStonesPublicApiClient(GlamourTransport(), listOf("https://rising.test")),
            MissingCredentialProvider,
        )
        assertThrows(GlamourException.AuthenticationRequired::class.java) {
            runBlocking { service.fetchGlamours(GlamourListRequest()) }
        }
    }

    @Test
    fun authorizationIsRequestedWithTheGlamourCapability() = runBlocking {
        val provider = RecordingGlamourSessionProvider()
        GlamourApiService(
            RisingStonesPublicApiClient(GlamourTransport(), listOf("https://rising.test")),
            provider,
            temporarySessionId = "session-1",
        ).fetchGlamours(GlamourListRequest())

        assertEquals(
            RisingStonesAuthenticationRequirement.Required,
            provider.lastContext?.requirement,
        )
        assertEquals(RisingStonesCapability.GlamourAuthenticated, provider.lastContext?.capability)
        assertEquals("api/home/glamour/glamoursList", provider.lastContext?.path)
    }

    private fun service(transport: RisingStonesHttpClient) = GlamourApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        GlamourCredentialProvider,
        temporarySessionId = "session-1",
    )
}

private object GlamourCredentialProvider : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.GlamourAuthenticated)
    override suspend fun currentAuthorizer() = authorizer("Authorization" to "host token")
}

private object MissingCredentialProvider : RisingStonesSessionProvider {
    override val capabilities = emptySet<RisingStonesCapability>()
    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = null
}

private class RecordingGlamourSessionProvider : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.GlamourAuthenticated)
    var lastContext: RisingStonesRequestContext? = null

    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, sink ->
        lastContext = context
        sink.set("Authorization", "recorded")
    }
}

private fun authorizer(
    vararg headers: Pair<String, String>,
): RisingStonesRequestAuthorizer = RisingStonesRequestAuthorizer { _, sink: RisingStonesHeaderSink ->
    headers.forEach { (name, value) -> sink.set(name, value) }
}

private class GlamourTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val path = request.url.toHttpUrl().encodedPath
        val body = when {
            path.endsWith("/myFavoritesList") -> FOLDERS
            path.endsWith("/glamourDetail") -> DETAIL
            path.endsWith("/like") -> """{"code":10000,"data":"1"}"""
            path.endsWith("/createFavorites") || path.endsWith("/deleteFavorites") ||
                path.endsWith("/favorite") || path.endsWith("/cancelFavorite") ->
                """{"code":10000,"data":1}"""
            path.endsWith("/glamoursList") || path.endsWith("/myFavoriteItemsList") ||
                path.endsWith("/myGlamoursList") || path.endsWith("/api/common/search") -> LIST
            else -> error("Unexpected request ${request.method} ${request.url}")
        }
        return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
    }
}

private fun RisingStonesHttpRequest.form(): Map<String, String> {
    assertNotNull(body)
    return requireNotNull(body).decodeToString().split('&').associate { item ->
        val parts = item.split('=', limit = 2)
        parts.first() to URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
    }
}

private val LIST = """
    {"code":10000,"data":{"count":"12","rows":[{
      "glamour_id":"42","title":"Look 42","desc":"A clean look",
      "main_image":"https://cdn.test/main.jpg",
      "images":"https://cdn.test/main.jpg,https://cdn.test/second.jpg",
      "likes":"8","favorites":3,"is_like":"1","is_favorite":0,
      "job_ids":["19",20],"race_ids":[1],"gender_ids":["2"],
      "glamour_created_at":"2026-07-21 12:00:00","uuid":"author-1",
      "character_name":"Hero","area_name":"World","group_name":"DC"
    }]}}
""".trimIndent()

private val FOLDERS = """
    {"code":10000,"data":{"count":"1","rows":[{
      "id":"7","name":"Default","is_default":"1","is_public":0,"item_count":"3"
    }]}}
""".trimIndent()

private val DETAIL = """
    {"code":10000,"data":{
      "id":"42","title":"Look 42","desc":"A clean look",
      "main_image":"https://cdn.test/main.jpg","images":"https://cdn.test/second.jpg",
      "likes":"8","favorites":3,"is_like":"1","is_favorite":0,
      "created_at":"2026-07-21 12:00:00","uuid":"author-1",
      "character_name":"Hero","area_name":"World","group_name":"DC",
      "race_ids":[{"id":"1","name":"Hyur"}],
      "equipments":[{"slot":"BODY","equipment_id":"100","name":"Body","icon_id":"200",
        "dye_ids":["1"],"dyes":[{"id":"1","name":"Snow White","color":"#eeeeee"}]}],
      "ort_info":{"glasses_id":"5","glasses_name":"Classic Spectacles","glasses_icon":"9",
        "ornament_id":"6","ornament_name":"Parasol","ornament_icon":"10"}
    }}
""".trimIndent()
