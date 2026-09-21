package top.cxmeow.risingstones.feature.profile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.network.*

class ProfileApiServiceTest {
    @Test fun acceptedCodesReadProfilesWithoutRefreshingOnAuthenticationLookingMessages() = runTest {
        for (code in listOf(10000, 10002)) {
            val transport = Transport(Profile).apply { this.code = code; message = "未登录" }
            val provider = Provider()
            val service = ProfileApiService(client(transport), provider)
            val self = service.fetchProfile(ProfileOwner.Self)
            assertTrue(self.isSelf)
            val other = service.fetchProfile(ProfileOwner.User("other-fixture"))
            assertFalse(other.isSelf)
            assertFalse(other.toString().contains("Hidden"))
            assertEquals(2, transport.requests.size)
            assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get &&
                it.url.toHttpUrl().encodedPath == "/api/home/userInfo/getUserInfo" })
            assertEquals(0, provider.refreshes)
        }
    }

    @Test fun alternateAcceptedCodeReadsEverySectionAndKeepsEmptyResultsValid() = runTest {
        val transport = Transport("{}").apply { code = 10002 }
        val provider = Provider()
        val service = ProfileApiService(client(transport), provider)
        val sections = ProfileSection.entries.filter { it != ProfileSection.Overview }
        for (section in sections) {
            transport.data = """{"rows":[{"id":17,"posts_id":45,"uuid":"fixture-person","title":"Fixture"}],"count":1}"""
            assertEquals(1, service.readSection(ProfileListQuery(ProfileOwner.Self, section)).items.size)
            transport.data = """{"rows":[],"count":"0"}"""
            val empty = service.readSection(ProfileListQuery(ProfileOwner.Self, section))
            assertTrue(empty.items.isEmpty())
            assertFalse(empty.hasMore)
        }
        assertEquals(sections.size * 2, transport.requests.size)
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
        assertEquals(0, provider.refreshes)
    }

    @Test fun alternateAcceptedCodeStillRejectsMissingProfileAndListPayloadsWithoutRefreshing() = runTest {
        val transport = Transport("null").apply { code = 10002 }
        val provider = Provider()
        val service = ProfileApiService(client(transport), provider)
        for (data in listOf("null", "[]", "{}", """{"rows":[{}]}""")) {
            transport.data = data
            val previous = transport.requests.size
            try { service.fetchProfile(ProfileOwner.Self); fail() } catch (_: ProfileException.InvalidResponse) { }
            try { service.readSection(ProfileListQuery(ProfileOwner.Self, ProfileSection.Posts)); fail() }
            catch (_: ProfileException.InvalidResponse) { }
            assertEquals(previous + 2, transport.requests.size)
        }
        assertEquals(setOf(RisingStonesCapability.AccountRead), provider.capabilities)
        assertEquals(0, provider.refreshes)
    }

    @Test fun hiddenValuesAreRemovedFromOtherProfilesIncludingUnknownVisibilityFlags() = runTest {
        val transport = Transport(Profile)
        val profile = service(transport).fetchProfile(ProfileOwner.User("other-fixture"))
        assertFalse(profile.isSelf)
        assertTrue(profile.facts.filter { it.kind != ProfileFactKind.Housing }.all {
            it.visibility == ProfileVisibility.Private && it.value == null
        })
        assertEquals("Public house", profile.facts.single { it.kind == ProfileFactKind.Housing }.value)
        assertTrue(profile.careers.isEmpty())
        assertTrue(profile.achievements.isEmpty())
        assertEquals(ProfileVisibility.Private, profile.careerVisibility)
        assertEquals(ProfileVisibility.Private, profile.achievementVisibility)
        assertFalse(profile.toString().contains("Hidden"))
        assertNull(profile.avatarUrl)
        assertEquals("other-fixture", transport.requests.single().url.toHttpUrl().queryParameter("uuid"))
    }

    @Test fun selfProfileUsesNoUuidAndMaySeeOwnPrivateValues() = runTest {
        val transport = Transport(Profile)
        val profile = service(transport).fetchProfile(ProfileOwner.Self)
        assertTrue(profile.isSelf)
        assertEquals("Hidden guild", profile.facts.single { it.kind == ProfileFactKind.Guild }.value)
        assertEquals("Hidden career", profile.careers.single().name)
        assertEquals("Hidden achievement", profile.achievements.single().name)
        assertNull(transport.requests.single().url.toHttpUrl().queryParameter("uuid"))
    }

    @Test fun publicFlagsExposeOnlyTheirCorrespondingFields() = runTest {
        val transport = Transport(Profile.replace("\"guild_publish\":0", "\"guild_publish\":1")
            .replace("\"career_publish\":0", "\"career_publish\":1")
            .replace("\"achieve_publish\":2", "\"achieve_publish\":1"))
        val profile = service(transport).fetchProfile(ProfileOwner.User("fixture"))
        assertEquals("Hidden guild", profile.facts.single { it.kind == ProfileFactKind.Guild }.value)
        assertEquals(1, profile.careers.size)
        assertEquals(1, profile.achievements.size)
        assertNull(profile.facts.single { it.kind == ProfileFactKind.PlayTime }.value)
    }

    @Test fun favoritesAreSelfOnlyAndPostsUsePostsIdRatherThanAnotherRecordId() = runTest {
        val transport = Transport("""{"count":"12","rows":[{"posts_id":"45","id":99,"title":"Fixture post","content_pre":"Summary","posts_created_at":"Fixture date"}]}""")
        val service = service(transport)
        try { service.readSection(ProfileListQuery(ProfileOwner.User("fixture"), ProfileSection.FavoritePosts)); fail()
        } catch (_: ProfileException.Unavailable) { }
        assertTrue(transport.requests.isEmpty())
        val favorites = service.readSection(ProfileListQuery(ProfileOwner.Self, ProfileSection.FavoriteGuides))
        assertEquals(ProfileContentTarget(ProfileContentKind.Guide, 45), (favorites.items.single() as ProfileListItem.Content).target)
        assertTrue(favorites.hasMore)
        val request = transport.requests.single().url.toHttpUrl()
        assertEquals("/api/home/userInfo/myStarPosts", request.encodedPath)
        assertEquals("2", request.queryParameter("type"))
        assertNull(request.queryParameter("uuid"))
        assertEquals("10", request.queryParameter("limit"))
        val posts = service.readSection(ProfileListQuery(ProfileOwner.User("fixture"), ProfileSection.Posts))
        assertEquals(45, (posts.items.single() as ProfileListItem.Content).target.id)
    }

    @Test fun dynamicAndRelationListsKeepTheirOwnIdentifiersAndEndpoints() = runTest {
        val transport = Transport("""{"count":1,"rows":[{"id":17,"mask_content":"Fixture update","pic_url":"https://static.web.sdo.com/fixture.png"}]}""")
        val dynamic = service(transport).readSection(ProfileListQuery(ProfileOwner.Self, ProfileSection.Dynamics))
        assertEquals(ProfileContentTarget(ProfileContentKind.Dynamic, 17), (dynamic.items.single() as ProfileListItem.Content).target)
        assertFalse(dynamic.hasMore)
        transport.data = """{"count":"1","rows":[{"uuid":"fixture-person","character_name":"Fixture person"}]}"""
        for ((section, endpoint) in listOf(ProfileSection.Following to "followList", ProfileSection.Followers to "fansList")) {
            val page = service(transport).readSection(ProfileListQuery(ProfileOwner.Self, section))
            assertEquals("fixture-person", (page.items.single() as ProfileListItem.Person).uuid)
            assertTrue(transport.requests.last().url.toHttpUrl().encodedPath.endsWith(endpoint))
        }
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
    }

    @Test fun malformedPayloadIsNotAnEmptyProfileOrListAndEmptyRowsAreValid() = runTest {
        val transport = Transport("{}")
        try { service(transport).fetchProfile(ProfileOwner.Self); fail() } catch (_: ProfileException.InvalidResponse) { }
        try { service(transport).readSection(ProfileListQuery(ProfileOwner.Self, ProfileSection.Posts)); fail()
        } catch (_: ProfileException.InvalidResponse) { }
        transport.data = """{"rows":[]}"""
        val page = service(transport).readSection(ProfileListQuery(ProfileOwner.Self, ProfileSection.Posts))
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test fun authRetriesOnceAndUnavailableCapabilitySendsNothing() = runTest {
        for (code in listOf(10001, 10403, 10105)) {
            val transport = Transport("{}").apply { this.code = code }
            val provider = Provider()
            val service = ProfileApiService(client(transport), provider)
            try { service.fetchProfile(ProfileOwner.Self); fail() } catch (_: ProfileException.AuthenticationRequired) { }
            assertEquals(1, provider.refreshes)
            assertEquals(2, transport.requests.size)
            provider.capabilities = emptySet()
            try { service.fetchProfile(ProfileOwner.Self); fail() } catch (_: ProfileException.Unavailable) { }
            assertEquals(2, transport.requests.size)
            assertTrue(transport.requests.all { it.headers["User-Agent"] == "Fixture login UA" })
        }
    }

    @Test fun businessErrorsAreSanitizedAndCancellationPropagates() = runTest {
        val transport = Transport("{}").apply { code = 10401 }
        val provider = Provider()
        try { ProfileApiService(client(transport), provider).fetchProfile(ProfileOwner.Self); fail()
        } catch (error: ProfileException.Business) {
            assertEquals(10401, error.code)
            assertFalse(error.message.orEmpty().contains("private"))
        }
        assertEquals(0, provider.refreshes)
        transport.cancel = true
        try { service(transport).fetchProfile(ProfileOwner.Self); fail() } catch (_: CancellationException) { }
    }
    private fun client(transport: Transport) = RisingStonesPublicApiClient(transport, listOf("https://rising.test"))
    private fun service(transport: Transport) = ProfileApiService(client(transport), Provider())
}

private val Authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("User-Agent", "Fixture login UA") }
private class Provider : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.AccountRead)
    var refreshes = 0
    override suspend fun currentAuthorizer() = Authorizer
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return Authorizer }
}
private class Transport(var data: String) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var code = 10000
    var message = "private server detail"
    var cancel = false
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), """{"code":$code,"msg":"$message","data":$data}""".encodeToByteArray())
    }
}
private val Profile = """{"uuid":"fixture-person","character_name":"Fixture person","avatar":"https://untrusted.test/avatar", "career_publish":0,"guild_publish":0,"house_info_publish":1,"achieve_publish":2,"characterDetail":[{"guild_name":"Hidden guild","play_time":"Hidden play time","house_info":"Public house"}],"careerLevel":[{"career":"Hidden career","character_level":"90"}],"achieveInfo":[{"achieve_id":"1","achieve_name":"Hidden achievement","achieve_detail":"Hidden detail"}],"followFansiNum":{"followNum":2,"fansNum":3},"beLikedNum":"4"}"""
