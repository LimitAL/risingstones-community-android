package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.domain.GlamourCollectionService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFolderNameMaximumLength
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GlamourCollectionApiTest {
    @Test
    fun createAndUpdateUseTrimmedEscapedFormsAndRetainLegacyServiceCompatibility() = runBlocking {
        val transport = CollectionTransport()
        val collections: GlamourCollectionService = service(transport)
        val legacy: GlamourService = collections
        legacy.createFavoriteFolder("  Look & \"A\"+=  ", true)
        collections.updateFavoriteFolder(7, "  Updated +  ", false)
        val create = transport.requests[0]
        assertTrue(create.url.toHttpUrl().encodedPath.endsWith("/createFavorites"))
        assertEquals("name=Look+%26+%22A%22%2B%3D&is_public=1&tempsuid=fixture-session", create.body?.decodeToString())
        val update = transport.requests[1]
        assertTrue(update.url.toHttpUrl().encodedPath.endsWith("/updateFavorites"))
        assertEquals("id=7&name=Updated+%2B&is_public=0&tempsuid=fixture-session", update.body?.decodeToString())
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Post })
        assertTrue(transport.requests.all { it.contentType == "application/x-www-form-urlencoded; charset=utf-8" })
        assertTrue(transport.requests.all { it.url.toHttpUrl().queryParameter("tempsuid") == "fixture-session" })
        assertTrue(transport.requests.all { it.headers["User-Agent"] == "fixture-paired-agent" })
    }

    @Test
    fun deleteUsesOfficialUrlEncodedBodyInsteadOfJson() = runBlocking {
        val transport = CollectionTransport()
        service(transport).deleteFavoriteFolder(7)
        val request = transport.requests.single()
        assertTrue(request.url.toHttpUrl().encodedPath.endsWith("/deleteFavorites"))
        assertEquals(RisingStonesHttpMethod.Delete, request.method)
        assertEquals("id=7", request.body?.decodeToString())
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
    }

    @Test
    fun favoriteInChosenFolderUsesOnlyTheExplicitPost() = runBlocking {
        val transport = CollectionTransport()
        service(transport).favoriteInFolder(42, 9)
        val request = transport.requests.single()
        assertTrue(request.url.toHttpUrl().encodedPath.endsWith("/favorite"))
        assertEquals(RisingStonesHttpMethod.Post, request.method)
        assertEquals("id=42&favorite_id=9&tempsuid=fixture-session", request.body?.decodeToString())
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
    }

    @Test
    fun legacyFavoriteStillSelectsTheDefaultFolder() = runBlocking {
        val transport = CollectionTransport()
        service(transport).favorite(42)
        assertEquals(listOf("myFavoritesList", "favorite"), transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
        assertEquals("id=42&favorite_id=7&tempsuid=fixture-session", transport.requests.last().body?.decodeToString())
    }

    @Test
    fun invalidFolderNamesAndIdentifiersCannotReachTheNetwork() = runBlocking {
        val transport = CollectionTransport()
        val service = service(transport)
        for (name in listOf("", " \n\t ", "x".repeat(GlamourFolderNameMaximumLength + 1))) {
            expectInvalid { service.createFavoriteFolder(name, true) }
            expectInvalid { service.updateFavoriteFolder(7, name, true) }
        }
        for (id in listOf(-1, 0)) {
            expectInvalid { service.deleteFavoriteFolder(id) }
            expectInvalid { service.updateFavoriteFolder(id, "Fixture", true) }
            expectInvalid { service.favorite(id) }
            expectInvalid { service.favoriteInFolder(id, 7) }
            expectInvalid { service.favoriteInFolder(42, id) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun maximumFolderNameLengthIsAcceptedAfterTrimming() = runBlocking {
        val transport = CollectionTransport()
        val service = service(transport)
        val name = "x".repeat(GlamourFolderNameMaximumLength)
        service.createFavoriteFolder(" $name ", false)
        service.updateFavoriteFolder(7, " $name ", true)
        assertTrue(transport.requests.first().body!!.decodeToString().startsWith("name=$name&is_public=0&"))
        assertTrue(transport.requests.last().body!!.decodeToString().startsWith("id=7&name=$name&is_public=1&"))
    }

    @Test
    fun collectionExtensionRequiresTheExistingAuthenticatedCapability() = runBlocking {
        val transport = CollectionTransport()
        val service = service(transport, authenticated = false)
        for (action in listOf<suspend () -> Unit>(
            { service.updateFavoriteFolder(7, "Fixture", true) },
            { service.favoriteInFolder(42, 7) },
        )) {
            try {
                action()
                fail("Authentication must be required")
            } catch (_: GlamourException.AuthenticationRequired) { }
        }
        assertTrue(transport.requests.isEmpty())
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try {
            block()
            fail("Invalid collection arguments must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    private fun service(transport: CollectionTransport, authenticated: Boolean = true) = GlamourApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        object : RisingStonesSessionProvider {
            override val capabilities = if (authenticated) setOf(RisingStonesCapability.GlamourAuthenticated) else emptySet()
            override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
                sink.set("User-Agent", "fixture-paired-agent")
            }
        },
        temporarySessionId = "fixture-session",
    )
}

private class CollectionTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val body = if (request.url.toHttpUrl().pathSegments.last() == "myFavoritesList") {
            """{"code":10000,"data":{"count":2,"rows":[{"id":9,"name":"Other","is_default":0},{"id":7,"name":"Default","is_default":1}]}}"""
        } else {
            """{"code":10000,"data":1}"""
        }
        return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
    }
}
