package top.cxmeow.risingstones.feature.guild.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildMemberRegistration
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class GuildApiServiceTest {
    @Test
    fun ownGuildKeepsLargeDecimalIdAndZeroMeansNoGuild() = runTest {
        val transport = GuildTransport().apply { body = envelope("""{"gc_id":"0"}""") }
        val service = service(transport)
        assertEquals(OwnGuild.None, service.ownGuild())

        transport.body = envelope("""{"gc_id":"999999999999999999999999"}""")
        assertEquals(
            GuildId("999999999999999999999999"),
            (service.ownGuild() as OwnGuild.Joined).guildId,
        )
        assertTrue(transport.requests.all { it.url.toHttpUrl().encodedPath == "/$BasicInfoPath" })
    }

    @Test
    fun infoSupportsObservedBooleanVisibilityAndNeverRetainsPrivateHousing() = runTest {
        val transport = GuildTransport().apply {
            body = envelope(INFO.replace("\"house_public\":true", "\"house_public\":false")
                .replace("\"isGuildMember\":1", "\"isGuildMember\":0"))
        }
        val hidden = service(transport).info(GuildId("123"))
        assertEquals(8, hidden.rank)
        assertEquals(GuildHousingVisibility.Private, hidden.housing.visibility)
        assertNull(hidden.housing.description)
        assertNull(hidden.housing.remainingDays)

        transport.body = envelope(INFO)
        val visible = service(transport).info(GuildId("123"))
        assertEquals(8, visible.rank)
        assertEquals(GuildHousingVisibility.Visible, visible.housing.visibility)
        assertEquals("Mist 1-1", visible.housing.description)
        assertEquals("3 days", visible.housing.remainingDays)
        assertEquals("123", transport.requests.last().url.toHttpUrl().queryParameter("guild_id"))

        transport.body = envelope(INFO.replace("\"guild_rank\":\"8.0\"", "\"guild_rank\":null"))
        assertNull(service(transport).info(GuildId("123")).rank)
    }

    @Test
    fun responseContextIdsMustMatchTheRequestedGuildAndPhoto() = runTest {
        val transport = GuildTransport().apply {
            body = envelope(INFO.replace("\"guild_id\":\"123\"", "\"guild_id\":\"124\""))
        }
        try {
            service(transport).info(GuildId("123"))
            fail()
        } catch (_: GuildException.InvalidResponse) {
        }

        transport.body = envelope(PHOTO_DETAIL.replace("\"id\":\"7\"", "\"id\":\"8\""))
        try {
            service(transport).photo(7)
            fail()
        } catch (_: GuildException.InvalidResponse) {
        }
    }

    @Test
    fun membersKeepRegistrationBoundaryAndNeverInventUnregisteredUuid() = runTest {
        val transport = GuildTransport().apply { body = envelope(MEMBERS) }
        val result = service(transport).members(GuildId("123"))
        assertEquals("registered-uuid", result.registered.single().authorUuid)
        assertEquals("Registered Name", result.registered.single().characterName)
        assertEquals(GuildMemberRegistration.Registered, result.registered.single().registration)
        assertNull(result.unregistered.single().authorUuid)
        assertEquals(GuildMemberRegistration.Unregistered, result.unregistered.single().registration)
    }

    @Test
    fun activityAndPhotoPagesUseTheirOfficialLimitsAndTerminationRules() = runTest {
        val transport = GuildTransport().apply { body = envelope(ACTIVITIES) }
        val service = service(transport)
        val activities = service.activities(GuildId("123"), 2)
        assertEquals(1, activities.items.single().id)
        assertEquals("activity-uuid", activities.items.single().authorUuid)
        assertFalse(activities.hasMore)
        assertEquals("30", transport.requests.last().url.toHttpUrl().queryParameter("limit"))

        transport.body = envelope(PHOTOS)
        val photos = service.photos(GuildId("123"), 1)
        assertTrue(photos.hasMore)
        assertEquals(7, photos.items.single().id)
        assertEquals(0, photos.items.single().commentCount)
        assertEquals("20", transport.requests.last().url.toHttpUrl().queryParameter("limit"))

        transport.body = envelope("""{"count":"1","rows":[]}""")
        assertFalse(service.photos(GuildId("123"), 2).hasMore)
    }

    @Test
    fun emptyActivityPageStopsEvenWhenCountIsStale() = runTest {
        val transport = GuildTransport().apply {
            body = envelope("""{"count":"999","rows":[]}""")
        }
        val result = service(transport).activities(GuildId("123"), 1)
        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test
    fun photoNeedsOnlyIdAndKeepsMissingAuthorUuidAbsent() = runTest {
        val transport = GuildTransport().apply { body = envelope(PHOTO_DETAIL) }
        val detail = service(transport).photo(7)
        assertEquals(GuildId("123"), detail.guildId)
        assertNull(detail.authorUuid)
        assertEquals("Detail Author", detail.characterName)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("/api/home/guild/getGuildPhotoDetail", url.encodedPath)
        assertEquals("7", url.queryParameter("id"))
        assertNull(url.queryParameter("guild_id"))
    }

    @Test
    fun commentsUseExactCaseOpaqueCursorAndEmptyPageTermination() = runTest {
        val transport = GuildTransport().apply { body = envelope(COMMENTS) }
        val service = service(transport)
        val first = service.comments(photoId = 7, page = 1, pageTime = "must-not-send")
        assertTrue(first.hasMore)
        assertEquals("cursor-next", first.nextPageTime)
        assertEquals(7, first.items.single().photoId)
        var url = transport.requests.last().url.toHttpUrl()
        assertEquals("/api/home/guild/GuildPhotoCommentDetail", url.encodedPath)
        assertNull(url.queryParameter("pageTime"))

        transport.body = envelope("""{"rows":[],"pageTime":"cursor-final"}""")
        val empty = service.comments(photoId = 7, page = 2, pageTime = "cursor-next")
        assertFalse(empty.hasMore)
        url = transport.requests.last().url.toHttpUrl()
        assertEquals("cursor-next", url.queryParameter("pageTime"))
    }

    @Test
    fun repliesUseRootEarliestAndShortPageTermination() = runTest {
        val transport = GuildTransport().apply { body = envelope(REPLIES) }
        val page = service(transport).replies(rootParentId = 31, page = 1)
        assertFalse(page.hasMore)
        assertEquals(7, page.items.single().photoId)
        assertEquals(31, page.items.single().rootParentId)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("/api/home/guild/guildPhotoSubCommentDetail", url.encodedPath)
        assertEquals("31", url.queryParameter("root_parent"))
        assertEquals("earliest", url.queryParameter("order"))
        assertNull(url.queryParameter("pageTime"))
    }

    @Test
    fun acceptedCodesRemainPayloadStrictAndDoNotRefresh() = runTest {
        for (code in listOf(10000, 10002)) {
            val transport = GuildTransport().apply { body = envelope("""{"gc_id":"0"}""", code) }
            val provider = GuildProvider()
            assertEquals(OwnGuild.None, service(transport, provider).ownGuild())
            assertEquals(0, provider.refreshes)

            transport.body = """{"code":$code,"data":{"gc_id":"-1"}}"""
            try {
                service(transport, provider).ownGuild()
                fail()
            } catch (_: GuildException.InvalidResponse) {
            }
            assertEquals(0, provider.refreshes)
        }
    }

    @Test
    fun authenticationRefreshesOnceWhileMissingOrRevokedCapabilitySendsNothingFurther() = runTest {
        val transport = GuildTransport().apply { body = """{"code":10403,"data":null}""" }
        val provider = GuildProvider()
        try {
            service(transport, provider).ownGuild()
            fail()
        } catch (_: GuildException.AuthenticationRequired) {
        }
        assertEquals(1, provider.refreshes)
        assertEquals(2, transport.requests.size)

        val unavailableTransport = GuildTransport()
        val unavailable = GuildProvider().apply { capabilities = emptySet() }
        try {
            service(unavailableTransport, unavailable).ownGuild()
            fail()
        } catch (_: GuildException.Unavailable) {
        }
        assertTrue(unavailableTransport.requests.isEmpty())

        val revokedTransport = GuildTransport().apply { body = """{"code":10001,"data":null}""" }
        val revoked = GuildProvider().apply { revokeOnRefresh = true }
        try {
            service(revokedTransport, revoked).ownGuild()
            fail()
        } catch (_: GuildException.Unavailable) {
        }
        assertEquals(1, revokedTransport.requests.size)
    }

    @Test
    fun cancellationPropagatesWithoutRefreshing() = runTest {
        val transport = GuildTransport().apply { cancel = true }
        val provider = GuildProvider()
        try {
            service(transport, provider).ownGuild()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(0, provider.refreshes)
    }

    @Test
    fun lateSuccessfulResponseIsDiscardedAfterGuildCapabilityRevocation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = GuildTransport().apply {
            body = envelope(INFO)
            this.entered = entered
            this.release = release
        }
        val provider = GuildProvider()
        val result = async {
            runCatching { service(transport, provider).info(GuildId("123")) }
        }

        entered.await()
        provider.capabilities = emptySet()
        release.complete(Unit)

        val outcome = result.await()
        assertNull(outcome.getOrNull())
        assertTrue(outcome.exceptionOrNull() is GuildException.Unavailable)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun validatorDoesNotStartInfoReadAfterNonCooperativeLateCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = GuildTransport().apply {
            body = envelope("""{"gc_id":"123"}""")
            this.entered = entered
            this.release = release
            ignoreCancellation = true
        }
        val validator = RisingStonesGuildSessionValidator(
            RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000) },
            client(transport),
        )
        val result = async {
            validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
        }

        entered.await()
        result.cancel()
        release.complete(Unit)

        try {
            result.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun validatorGrantsNoGuildStateAfterOnlyTheBasicRead() = runTest {
        val transport = GuildTransport().apply {
            body = envelope("""{"gc_id":"0"}""", 10002)
        }
        val base = RisingStonesSessionValidator {
            RisingStonesSessionValidation(
                displayName = "Fixture",
                code = 10002,
                capabilities = setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GuildRead),
            )
        }
        val contexts = mutableListOf<RisingStonesRequestContext>()
        val authorizer = RisingStonesRequestAuthorizer { context, _ -> contexts += context }
        val result = RisingStonesGuildSessionValidator(base, client(transport)).validateSession(authorizer)
        assertEquals(
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GuildRead),
            result.capabilities,
        )
        assertEquals(listOf("/$BasicInfoPath"), transport.requests.map { it.url.toHttpUrl().encodedPath })
        assertEquals(listOf(RisingStonesCapability.GuildRead), contexts.map { it.capability })
    }

    @Test
    fun validatorNeedsMatchingParseableInfoBeforeGrantingJoinedGuild() = runTest {
        val transport = GuildTransport().apply {
            responder = { request ->
                if (request.url.toHttpUrl().encodedPath == "/$BasicInfoPath") {
                    envelope("""{"gc_id":"123"}""")
                } else {
                    envelope(INFO)
                }
            }
        }
        val base = RisingStonesSessionValidator {
            RisingStonesSessionValidation(
                displayName = null,
                code = 10000,
                capabilities = setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GuildRead),
            )
        }
        val validator = RisingStonesGuildSessionValidator(base, client(transport))
        val granted = validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
        assertTrue(RisingStonesCapability.GuildRead in granted.capabilities)
        assertEquals(2, transport.requests.size)
        assertEquals("123", transport.requests.last().url.toHttpUrl().queryParameter("guild_id"))

        transport.requests.clear()
        transport.responder = { request ->
            if (request.url.toHttpUrl().encodedPath == "/$BasicInfoPath") {
                envelope("""{"gc_id":"123"}""")
            } else {
                envelope(INFO.replace("\"guild_id\":\"123\"", "\"guild_id\":\"124\""))
            }
        }
        val mismatched = validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
        assertEquals(setOf(RisingStonesCapability.AccountRead), mismatched.capabilities)
    }

    @Test
    fun validatorRevokesStaleCapabilityForMalformedOrFailedBasicRead() = runTest {
        val transport = GuildTransport()
        val base = RisingStonesSessionValidator {
            RisingStonesSessionValidation(
                displayName = null,
                code = 10000,
                capabilities = setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GuildRead),
            )
        }
        val validator = RisingStonesGuildSessionValidator(base, client(transport))
        for (body in listOf(
            envelope("""{"gc_id":"-1"}"""),
            envelope("""{"gc_id":"１"}"""),
            """{"code":10000,"data":null}""",
            """{"code":10403,"data":{"gc_id":"123"}}""",
        )) {
            transport.body = body
            val result = validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
            assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
        }
    }

    @Test
    fun validatorCancellationPropagates() = runTest {
        val transport = GuildTransport().apply { cancel = true }
        val validator = RisingStonesGuildSessionValidator(
            RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000) },
            client(transport),
        )
        try {
            validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
            fail()
        } catch (_: CancellationException) {
        }
    }

    private fun service(
        transport: GuildTransport,
        provider: GuildProvider = GuildProvider(),
    ) = GuildApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        provider,
    )

    private fun client(transport: GuildTransport) =
        RisingStonesPublicApiClient(transport, listOf("https://rising.test"))
}

private class GuildProvider : RisingStonesSessionProvider {
    override var capabilities: Set<RisingStonesCapability> = setOf(RisingStonesCapability.GuildRead)
    var refreshes = 0
    var revokeOnRefresh = false
    val contexts = mutableListOf<RisingStonesRequestContext>()
    private val authorizer = RisingStonesRequestAuthorizer { context, sink ->
        contexts += context
        sink.set("X-Test-Authorization", "fixture")
    }

    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer = authorizer

    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshes++
        if (revokeOnRefresh) capabilities = emptySet()
        return authorizer
    }
}

private class GuildTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var body = envelope("""{"gc_id":"0"}""")
    var statusCode = 200
    var cancel = false
    var entered: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null
    var ignoreCancellation = false
    var responder: ((RisingStonesHttpRequest) -> String)? = null

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        entered?.complete(Unit)
        try {
            release?.await()
        } catch (error: CancellationException) {
            if (!ignoreCancellation) throw error
            withContext(NonCancellable) { release?.await() }
        }
        return RisingStonesHttpResponse(
            statusCode,
            emptyMap(),
            (responder?.invoke(request) ?: body).encodeToByteArray(),
        )
    }
}

private fun envelope(data: String, code: Int = 10000): String =
    """{"code":$code,"msg":"fixture","data":$data}"""

private val INFO = """{
  "guild_id":"123","guild_name":"Free Company","guild_tag":"TAG","area_name":"Area",
  "group_name":"World","guild_pic":null,"guild_describe":null,"create_time":"2020-01-01",
  "guild_rank":"8.0","member_num":"12","active_member_num":"4","grand_parentname":"Maelstrom",
  "active_time_weekday":null,"active_time_weekend":null,"guild_label":null,
  "house_public":true,"isGuildMember":1,"house_info":"Mist 1-1","house_remain_day":"3 days"
}"""

private val MEMBERS = """{
  "registered":[{"uuid":"registered-uuid","character_name":"Registered Name","area_name":"Area",
    "group_name":"World","avatar":null,"profile":null,"relation":0,"admin_tag":0}],
  "unRegister":[{"uuid":"must-not-leak","character_name":"Game Only","area_name":"Area",
    "group_name":"World"}]
}"""

private val ACTIVITIES = """{"count":"1","rows":[{
  "id":"1","mask_content":"<p>Activity</p>",
  "pic_url":"https://ff14risingstones.gcloud.com.cn/guild/activity.png,http://untrusted.test/a.png",
  "created_at":"2026-09-20 12:00:00","uuid":"activity-uuid","character_name":"Member",
  "area_name":"Area","group_name":"World","avatar":null
}]}"""

private val PHOTOS = """{"count":"1","rows":[{
  "id":"7","photo_url":"https://ff14risingstones.gcloud.com.cn/guild/photo.png",
  "avatar":null,"character_name":"Photographer","like_count":"2","is_like":0
}]}"""

private val PHOTO_DETAIL = """{
  "id":"7","guild_id":"123","photo_url":"https://ff14risingstones.gcloud.com.cn/guild/photo.png",
  "uuid":null,"relay_count":"3","comment_count":"1","like_count":"2","is_like":0,
  "userInfo":{"character_name":"Detail Author","area_name":"Area","group_name":"World","avatar":null}
}"""

private val COMMENTS = """{"rows":[{
  "id":"31","uuid":"comment-uuid","character_name":"Commenter","area_name":"Area",
  "group_name":"World","avatar":null,"mask_content":"Hello","comment_pic":"javascript:bad",
  "created_at":"2026-09-20 12:00:00","parent_id":0,"root_parent":0,"children_count":"1"
}],"pageTime":"cursor-next"}"""

private val REPLIES = """{"rows":[{
  "id":"32","posts_id":"7","parent_id":"31","root_parent":"31","uuid":"reply-uuid",
  "character_name":"Replier","area_name":"Area","group_name":"World","avatar":null,
  "mask_content":"Reply","comment_pic":null,"created_at":"2026-09-20 12:01:00",
  "to_uuid":"comment-uuid","to_cname":"Commenter","children_count":0
}]}"""
