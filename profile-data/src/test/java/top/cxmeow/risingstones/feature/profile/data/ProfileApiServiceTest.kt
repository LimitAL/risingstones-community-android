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
    @Test fun badgeIntegerFormsMatchFoundationWithoutRoundingOrChangingQuotedText()=runTest {
        val transport=Transport("{}");val api=service(transport)
        for((wire,expected)in listOf("1.0" to 1L,"1e0" to 1L,"1.5" to null,"\"1.0\"" to null,"true" to 1L,"9223372036854775807" to Long.MAX_VALUE,"9223372036854775808" to null)){
            val raw="""[{"badgeField":"future","badgeName":"Future","badgeLevel":$wire,"badgeWeight":0.0}]"""
            transport.data="""{"uuid":"mock","character_name":"Mock","badge":${kotlinx.serialization.json.JsonPrimitive(raw)}}"""
            val rows=api.fetchReadSnapshot(ProfileOwner.User("mock")).badges!!
            if(expected==null)assertTrue(rows.isEmpty())else{assertEquals(expected,rows.single().level);assertEquals(0L,rows.single().weight)}
        }
    }

    @Test fun badgesUseOneExistingResponseAndDistinguishUnknownFromNoDisplayedItems() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for(raw in listOf("null","{}","[]","\"bad\"", "\"[null]\"")){
            transport.data="""{"uuid":"mock","character_name":"Mock","badge":$raw}"""
            assertNull(api.fetchReadSnapshot(ProfileOwner.User("mock")).badges)
        }
        transport.data="""{"uuid":"mock","character_name":"Mock","badge":"[]"}"""
        assertEquals(emptyList<ProfileBadge>(),api.fetchReadSnapshot(ProfileOwner.User("mock")).badges)
        assertTrue(transport.requests.all{it.method==RisingStonesHttpMethod.Get})
    }
    @Test fun badgeFiltersAndStableWeightsUseCatalogOrderWithoutInventingUnknownDescription() = runTest {
        val payload="""[{"badgeField":"future_b","badgeName":"Future B","badgeLevel":1,"badgeWeight":2},{"badgeField":"warrior_badge","badgeName":"Raw warrior","badgeLevel":"3","badgeWeight":2},{"badgeField":"collapse_badge","badgeName":"Raw collapse","badgeLevel":1,"badgeWeight":2},{"badgeField":"future_a","badgeName":"Future A","badgeLevel":1,"badgeWeight":2},{"badgeField":"hidden","badgeName":"Hidden","badgeLevel":2,"badgeWeight":-1},{"badgeField":"zero","badgeName":"Zero","badgeLevel":0},{"badgeField":"default","badgeName":"Default","badgeLevel":1,"badgeWeight":0},{"badgeField":"late","badgeName":"Late","badgeLevel":1,"badgeWeight":1000},{"badgeField":"early","badgeName":"Early","badgeLevel":1,"badgeWeight":-2}]"""
        val transport=Transport("""{"uuid":"mock","character_name":"Mock","badge":${kotlinx.serialization.json.JsonPrimitive(payload)}}""")
        val rows=service(transport).fetchReadSnapshot(ProfileOwner.User("mock")).badges!!
        assertEquals(listOf("early","collapse_badge","warrior_badge","future_b","future_a","default","late"),rows.map{it.field})
        assertEquals("荣誉光之战士（金）",ProfileBadgeCatalog.presentation(rows[2]).name)
        assertNull(ProfileBadgeCatalog.presentation(rows[3]).description);assertEquals("Future B",ProfileBadgeCatalog.presentation(rows[3]).name)
        assertEquals(0L,rows[5].weight);assertEquals(1,transport.requests.size)
    }
    @Test fun adminNonzeroOnlyShowsAdminWithoutChangingSelfOrAuthorization() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for(marker in listOf("1","\"2\"","-1","true")){
            transport.data="""{"uuid":"mock","character_name":"Mock","relation":0,"admin_tag":$marker,"badge":"bad"}"""
            val value=api.fetchReadSnapshot(ProfileOwner.User("mock"));assertFalse(value.profile.isSelf);assertEquals("admin_tag",value.badges!!.single().field)
        }
        transport.data="""{"uuid":"mock","character_name":"Mock","admin_tag":0}""";assertNull(api.fetchReadSnapshot(ProfileOwner.User("mock")).badges)
    }
    @Test fun invalidBadgeRowsAreSkippedWithoutZeroLevelOrExtraQueries() = runTest {
        val payload="""[{"badgeField":"ok","badgeName":"Known raw","badgeLevel":true,"badgeWeight":"bad"},{"badgeField":"bad","badgeName":"Bad","badgeLevel":-1},{"badgeField":"bad2","badgeName":"Bad","badgeLevel":1.5},{"badgeField":"bad3","badgeName":"Bad","badgeLevel":9223372036854775808},{"badgeField":"bad4","badgeName":"***","badgeLevel":1}]"""
        val transport=Transport("""{"uuid":"mock","character_name":"Mock","badge":${kotlinx.serialization.json.JsonPrimitive(payload)}}""")
        val row=service(transport).fetchReadSnapshot(ProfileOwner.User("mock")).badges!!.single();assertEquals("ok",row.field);assertEquals(1L,row.level);assertNull(row.weight)
    }

    @Test fun rawCountsDistinguishUnknownZeroAndLongValuesWithoutBorrowingTotals() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for((wire,expected) in listOf("null" to null,"0" to 0L,"\"0\"" to 0L,"7" to 7L,"2147483648" to 2147483648L,"-1" to null,"1.5" to null,"true" to null,"{}" to null,"[]" to null,"\"bad\"" to null,"9223372036854775808" to null)) {
            transport.data="""{"uuid":"mock","character_name":"Mock","followFansiNum":{"followNum":$wire,"fansNum":9},"beLikedNum":$wire,"count":777}"""
            val snap=api.fetchReadSnapshot(ProfileOwner.User("mock"));assertEquals(expected,snap.counts.following);assertEquals(expected,snap.counts.liked);assertEquals(9L,snap.counts.followers)
        }
        assertTrue(transport.requests.all{it.method==RisingStonesHttpMethod.Get})
    }
    @Test fun equivalentDetailShapesRespectOtherPrivacyAndNeverPromoteMismatchedSelf() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for(detail in listOf("""{"house_info":"Hidden","fc_id":"42","guild_name":"Guild"}""","""[{"house_info":"Hidden","fc_id":"42","guild_name":"Guild"}]""")) {
            transport.data="""{"uuid":"mock","character_name":"Mock","relation":-1,"guild_publish":0,"house_info_publish":0,"characterDetail":$detail}"""
            val other=api.fetchReadSnapshot(ProfileOwner.User("different"));assertFalse(other.profile.isSelf);assertNull(other.guild);assertFalse(other.toString().contains("Hidden"))
            val self=api.fetchReadSnapshot(ProfileOwner.User("mock"));assertEquals("Hidden",self.profile.facts.single{it.kind==ProfileFactKind.Housing}.value);assertEquals("42",self.guild?.id)
        }
    }

    @Test fun missingCountCannotEstablishZero() = runTest {
        val transport=Transport("""{"uuid":"mock","character_name":"Mock"}""")
        assertNull(service(transport).fetchReadSnapshot(ProfileOwner.User("mock")).counts.following)
    }

    @Test fun objectDetailRetainsVisibleFactsAndExplicitGuild() = runTest {
        val transport=Transport("""{"uuid":"mock","character_name":"Mock","guild_publish":1,"house_info_publish":1,"characterDetail":{"fc_id":"42","guild_name":"Guild","house_info":"House"}}""")
        val snapshot=service(transport).fetchProfileWithGuild(ProfileOwner.User("mock"))
        assertEquals("House",snapshot.profile.facts.single{it.kind==ProfileFactKind.Housing}.value)
        assertEquals("42",snapshot.guild?.id)
        assertEquals(1,transport.requests.size)
    }

    @Test fun sameResponseSelfRelationOpensOnlyTheExplicitPrivateGuildWithoutAnotherQuery() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for(marker in listOf("-1","\"-1\"","\" -1 \"")){
            transport.data="""{"uuid":"mock-self","character_name":"Mock self","relation":$marker,"guild_publish":0,"characterDetail":[{"fc_id":"999999999999999999999999","guild_name":"Private company"}]}"""
            val value=api.fetchProfileWithGuild(ProfileOwner.User("mock-self"))
            assertTrue(value.profile.isSelf);assertEquals("999999999999999999999999",value.guild?.id)
            assertEquals("Private company",value.profile.facts.single{it.kind==ProfileFactKind.Guild}.value)
        }
        assertEquals(3,transport.requests.size)
        assertTrue(transport.requests.all{it.method==RisingStonesHttpMethod.Get&&it.url.toHttpUrl().queryParameter("uuid")=="mock-self"})
    }
    @Test fun unknownOtherMalformedOrMismatchedSelfRelationsNeverExposePrivateFacts() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for(marker in listOf("null","0","1","3","-2","\"bad\"","-1.0","true","[]")){
            transport.data="""{"uuid":"mock-self","character_name":"Mock","relation":$marker,"guild_publish":0,"characterDetail":[{"fc_id":"2","guild_name":"Hidden company"}]}"""
            val value=api.fetchProfileWithGuild(ProfileOwner.User("mock-self"));assertFalse(value.profile.isSelf);assertNull(value.guild);assertFalse(value.toString().contains("Hidden company"))
        }
        transport.data="""{"uuid":"mock-self","relation":-1,"guild_publish":0,"characterDetail":[{"fc_id":"2","guild_name":"Hidden company"}]}"""
        val mismatch=api.fetchProfileWithGuild(ProfileOwner.User("another-requested-user"))
        assertFalse(mismatch.profile.isSelf);assertNull(mismatch.guild);assertFalse(mismatch.toString().contains("Hidden company"))
    }
    @Test fun selfMarkerDoesNotInventMissingIdsOrUnmaskStarAndDashFacts() = runTest {
        val transport=Transport("{}");val api=service(transport)
        for(id in listOf("","0","-1","2.0","２")){
            transport.data="""{"uuid":"mock-self","relation":-1,"guild_publish":0,"characterDetail":[{"fc_id":"$id","guild_name":"Private company"}]}"""
            val value=api.fetchProfileWithGuild(ProfileOwner.User("mock-self"));assertTrue(value.profile.isSelf);assertNull(value.guild)
        }
        for(placeholder in listOf(" ****** ","-","")){
            transport.data="""{"uuid":"mock-self","relation":-1,"guild_publish":0,"characterDetail":[{"fc_id":"2","guild_name":"$placeholder"}]}"""
            val value=api.fetchProfileWithGuild(ProfileOwner.User("mock-self"));assertTrue(value.profile.isSelf)
            assertNull(value.guild?.name);val fact=value.profile.facts.single{it.kind==ProfileFactKind.Guild};assertNull(fact.value);assertEquals(ProfileVisibility.Unavailable,fact.visibility)
        }
    }

    @Test fun explicitGuildReferenceIsTakenFromTheSameVisibleResponseWithoutGuessing() = runTest {
        val transport=Transport("{}");val service=ProfileApiService(client(transport),Provider())
        for(id in listOf("2","999999999999999999999999999999")) {
            transport.data="""{"uuid":"mock","character_name":"Mock","guild_publish":1,"characterDetail":[{"fc_id":"$id","guild_name":"Guild"}]}"""
            val snapshot=service.fetchProfileWithGuild(ProfileOwner.User("mock"))
            assertEquals(id,snapshot.guild?.id);assertEquals("Guild",snapshot.guild?.name)
            assertEquals("mock",snapshot.profile.uuid)
        }
        assertEquals(2,transport.requests.size)
        assertTrue(transport.requests.all{it.method==RisingStonesHttpMethod.Get})
    }
    @Test fun privateOrInvalidGuildCannotBecomeARouteAndSelfMayReadPrivateOwnedId() = runTest {
        val transport=Transport("{}");val service=ProfileApiService(client(transport),Provider())
        for(id in listOf("", "0","000","-1","2.0","２","abc","https://example.test/2")) {
            transport.data="""{"uuid":"mock","guild_publish":1,"characterDetail":[{"fc_id":"$id","guild_name":"Named"}]}"""
            assertNull(service.fetchProfileWithGuild(ProfileOwner.User("mock")).guild)
        }
        transport.data="""{"uuid":"mock","guild_publish":0,"characterDetail":[{"fc_id":"2","guild_name":"Hidden"}]}"""
        val other=service.fetchProfileWithGuild(ProfileOwner.User("mock"));assertNull(other.guild);assertFalse(other.toString().contains("Hidden"))
        assertEquals("2",service.fetchProfileWithGuild(ProfileOwner.Self).guild?.id)
        transport.data="""{"uuid":"mock","guild_publish":1,"characterDetail":[{"guild_name":"Not an ID"}]}"""
        assertNull(service.fetchProfileWithGuild(ProfileOwner.User("mock")).guild)
    }

    @Test fun dynamicRowsRetainOnlyTheirOwnOptionalLikeFacts() = runTest {
        val transport=Transport("{}");val service=ProfileApiService(client(transport),Provider())
        for((fields,count,liked) in listOf(Triple("",null,null),Triple("\"like_count\":0,",0L,null),Triple("\"like_count\":\"7\",\"is_like\":1,",7L,true),Triple("\"like_count\":9,\"is_like\":\"0\",",9L,false),Triple("\"like_count\":2147483648,\"is_like\":2,",2147483648L,null),Triple("\"like_count\":-1,\"is_like\":null,",null,null),Triple("\"like_count\":\"bad\",\"is_like\":true,",null,null))) {
            transport.data="""{"rows":[{$fields"id":17,"posts_id":18,"beLikedNum":900,"comment_count":80,"from_info":{"likes":700}}],"count":1}"""
            val row=service.readSection(ProfileListQuery(ProfileOwner.User("mock"),ProfileSection.Dynamics)).items.single() as ProfileListItem.Content
            assertEquals(count,row.sourceLikeFacts?.count);assertEquals(liked,row.sourceLikeFacts?.isLiked)
            if(count==null&&liked==null)assertNull(row.sourceLikeFacts)
            val post=service.readSection(ProfileListQuery(ProfileOwner.Self,ProfileSection.Posts)).items.single() as ProfileListItem.Content
            assertNull(post.sourceLikeFacts)
        }
    }

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
    @Test fun userDetailAndSharedPersonAvatarKeepTheSameWholeSafeUri()=runTest {
        val url="https://static.web.sdo.com/avatar.png?x-oss-process=image/resize,w_100,h_100"
        val avatar=kotlinx.serialization.json.JsonPrimitive(url)
        val transport=Transport("""{"uuid":"one","avatar":$avatar}""")
        val api=service(transport)
        assertEquals(url,api.fetchProfile(ProfileOwner.User("one")).avatarUrl)
        transport.data="""{"rows":[{"uuid":"one","avatar":$avatar}]}"""
        val person=api.readSection(ProfileListQuery(ProfileOwner.User("one"),ProfileSection.Following)).items.single() as ProfileListItem.Person
        assertEquals(url,person.avatarUrl)
        assertTrue(transport.requests.all{it.method==RisingStonesHttpMethod.Get})
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
