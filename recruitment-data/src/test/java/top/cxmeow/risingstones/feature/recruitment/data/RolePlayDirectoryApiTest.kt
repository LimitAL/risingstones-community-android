package top.cxmeow.risingstones.feature.recruitment.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.*

class RolePlayDirectoryApiTest {
    @Test
    fun memberPaginationPassesOpaqueCursorUnchangedAndUsesRawRowLength() = runBlocking {
        val transport = DirectoryTransport("""{"rows":[{"id":8,"member_name":"Member"},{}],"pageTime":"next & + /游标"}""")
        val service = transport.service()
        val first = service.fetchRolePlayMemberPage(RolePlayMemberQuery(42, limit = 2))
        assertEquals(1, first.items.size)
        assertTrue(first.hasMore)
        assertEquals("next & + /游标", first.pageTime)
        assertNull(transport.requests.single().url.toHttpUrl().queryParameter("pageTime"))
        transport.data = """{"rows":[],"pageTime":"next & + /游标"}"""
        val second = service.fetchRolePlayMemberPage(RolePlayMemberQuery(42, 2, 2, first.pageTime))
        assertFalse(second.hasMore)
        val request = transport.requests.last()
        val url = request.url.toHttpUrl()
        assertEquals("/api/home/recruit/getRecruitRpMemberListByRpId", url.encodedPath)
        assertEquals(setOf("id", "page", "limit", "pageTime", "tempsuid"), url.queryParameterNames)
        assertEquals("42", url.queryParameter("id"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("2", url.queryParameter("limit"))
        assertEquals(first.pageTime, url.queryParameter("pageTime"))
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get && it.body == null })
    }

    @Test
    fun legacyMemberReadRemainsOnePageAndPreservesLiteralTextAndSinglePicture() = runBlocking {
        val transport = DirectoryTransport("""{"rows":[{"id":8,"member_name":"Member","member_identity":"Role","detail_mask":"<b>literal</b> &amp;\nsecond line","detail_pic":"https://static.web.sdo.com/one.png","avatar_pic":"https://static.web.sdo.com/avatar.png"}]}""")
        val legacy: DutyRecruitmentService = transport.service()
        val member = legacy.fetchRolePlayMembers(42).single()
        assertEquals("<b>literal</b> &amp;\nsecond line", member.description)
        assertEquals(listOf("https://static.web.sdo.com/one.png"), member.detailImageUrls)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("1", url.queryParameter("page"))
        assertEquals("10", url.queryParameter("limit"))
        assertNull(url.queryParameter("pageTime"))
    }

    @Test
    fun memberDetailUsesOnlyItemIdAndKeepsParentAndPlainTextWithoutIdentityReads() = runBlocking {
        val transport = DirectoryTransport("""{"id":"8","rp_id":"42","member_name":"Member","member_identity":null,"detail_mask":"<b>literal</b> &lt;text&gt;","detail_pic":"https://static.web.sdo.com/one.png","avatar_pic":"http://static.web.sdo.com/insecure.png"}""")
        val detail = transport.service().fetchRolePlayMemberDetail(8)
        assertEquals(8, detail.id)
        assertEquals(42, detail.recruitmentId)
        assertEquals("<b>literal</b> &lt;text&gt;", detail.description)
        assertEquals("https://static.web.sdo.com/one.png", detail.detailImageUrl)
        assertNull(detail.avatarUrl)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("/api/home/recruit/getMemberDetail", url.encodedPath)
        assertEquals(setOf("id", "tempsuid"), url.queryParameterNames)
        assertEquals("8", url.queryParameter("id"))
        transport.data = """{"member_name":"Member"}"""
        val unknownParent = transport.service().fetchRolePlayMemberDetail(9)
        assertEquals(9, unknownParent.id)
        assertNull(unknownParent.recruitmentId)
    }

    @Test
    fun activityListKeepsServerOrderAndSeparatesPublicationFromActivityState() = runBlocking {
        val transport = DirectoryTransport("""{"rows":[{"id":5,"rp_id":42,"act_name":"Hidden","status":0,"act_status":1},{"id":8,"rp_id":42,"act_name":"Upcoming","status":1,"act_status":"0","begin_time":"2026-09-20 10:30","end_time":"2026-09-21 18:00"},{"id":6,"act_name":"Hidden string","status":"0"},{"id":3,"act_name":"Unknown","status":9,"act_status":-1},{"id":2,"act_name":"No status"}]}""")
        val activities = transport.service().fetchRolePlayActivities(42)
        assertEquals(listOf(8, 3, 2), activities.map { it.id })
        assertEquals(1, activities[0].status)
        assertEquals(0, activities[0].activityStatus)
        assertEquals("2026-09-20 10:30", activities[0].beginTime)
        assertEquals(9, activities[1].status)
        assertEquals(-1, activities[1].activityStatus)
        assertNull(activities[2].status)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("/api/home/recruit/getRecruitRpActListByRpId", url.encodedPath)
        assertEquals(setOf("id", "tempsuid"), url.queryParameterNames)
    }

    @Test
    fun activityDetailRetainsOriginalMarkupAndChronologicalImageBlocks() = runBlocking {
        val html = "<p><strong>Before<img src='https://static.web.sdo.com/a.png' alt='Example &amp; image'>After</strong></p>"
        val transport = DirectoryTransport("""{"id":8,"rp_id":42,"act_name":"Activity","begin_time":"2026-09-20 10:30","end_time":null,"detail_mask":"$html","cover_pic":"https://static.web.sdo.com/cover.png"}""")
        val detail = transport.service().fetchRolePlayActivityDetail(8)
        assertEquals(html, detail.contentHtml)
        assertEquals("2026-09-20 10:30", detail.activity.beginTime)
        assertNull(detail.activity.endTime)
        assertEquals(42, detail.activity.recruitmentId)
        assertEquals(listOf(
            RolePlayActivityBodyBlock.Html("<p><strong>Before</strong></p>"),
            RolePlayActivityBodyBlock.Image("https://static.web.sdo.com/a.png", "Example & image"),
            RolePlayActivityBodyBlock.Html("<p><strong>After</strong></p>"),
        ), detail.bodyBlocks)
        assertFalse(detail.hasUnsupportedContent)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("/api/home/recruit/getActDetail", url.encodedPath)
        assertEquals(setOf("id", "tempsuid"), url.queryParameterNames)
    }

    @Test
    fun missingPayloadsNeverBecomeEmptyDirectoriesButValidEmptyRowsAreAccepted() = runBlocking {
        for (action in actions) {
            for (data in listOf("null", "[]", "{}")) {
                val transport = DirectoryTransport(data)
                assertThrows(DutyRecruitmentException.MissingPayload::class.java) {
                    runBlocking { action(transport.service()) }
                }
                assertEquals(1, transport.requests.size)
            }
        }
        val empty = DirectoryTransport("""{"rows":[]}""").service()
        assertFalse(empty.fetchRolePlayMemberPage(RolePlayMemberQuery(42)).hasMore)
        assertTrue(empty.fetchRolePlayActivities(42).isEmpty())
        for (rows in listOf("null", "{}")) {
            val broken = DirectoryTransport("""{"rows":$rows}""").service()
            assertThrows(DutyRecruitmentException.MissingPayload::class.java) {
                runBlocking { broken.fetchRolePlayMemberPage(RolePlayMemberQuery(42)) }
            }
            assertThrows(DutyRecruitmentException.MissingPayload::class.java) {
                runBlocking { broken.fetchRolePlayActivities(42) }
            }
        }
    }

    @Test
    fun accepted10002UsesExistingAuthorizationWithoutRefreshOrAdditionalRequests() = runBlocking {
        for (action in actions) {
            val transport = DirectoryTransport(validCombinedData).apply { code = 10002; message = "未登录" }
            val session = DirectorySession()
            action(transport.service(session))
            assertEquals(1, transport.requests.size)
            assertEquals(0, session.refreshes)
            assertEquals(listOf(RisingStonesCapability.RecruitmentWrite), session.contexts.map { it.capability })
            assertEquals(setOf(RisingStonesCapability.RecruitmentWrite), session.capabilities)
        }
    }

    @Test
    fun finalAuthenticationFailuresAndCancellationFollowTheOwningServicePolicy() = runBlocking {
        for (action in actions) {
            val expired = DirectoryTransport(validCombinedData).apply { code = 10403; message = "未登录" }
            val session = DirectorySession()
            assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
                runBlocking { action(expired.service(session)) }
            }
            assertEquals(1, expired.requests.size)
            assertEquals(1, session.refreshes)
            val http = DirectoryTransport(validCombinedData).apply { failure = RisingStonesHttpException.ServerResponse(401, byteArrayOf()) }
            assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
                runBlocking { action(http.service()) }
            }
            assertEquals(1, http.requests.size)
            val cancelled = DirectoryTransport(validCombinedData).apply { failure = CancellationException("fixture") }
            assertThrows(CancellationException::class.java) { runBlocking { action(cancelled.service()) } }
            assertEquals(1, cancelled.requests.size)
        }
    }

    @Test
    fun invalidItemIdsCannotCauseAnyRequest() = runBlocking {
        val transport = DirectoryTransport(validCombinedData)
        val service = transport.service()
        for (id in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.fetchRolePlayMemberPage(RolePlayMemberQuery(id)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.fetchRolePlayMemberDetail(id) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.fetchRolePlayActivities(id) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.fetchRolePlayActivityDetail(id) } }
        }
        assertTrue(transport.requests.isEmpty())
    }

    private val actions: List<suspend (RolePlayDirectoryService) -> Unit> = listOf(
        { it.fetchRolePlayMemberPage(RolePlayMemberQuery(42)) },
        { it.fetchRolePlayMemberDetail(8) },
        { it.fetchRolePlayActivities(42) },
        { it.fetchRolePlayActivityDetail(8) },
    )

    private val validCombinedData = """{"id":8,"member_name":"Member","act_name":"Activity","rows":[]}"""
}

private class DirectoryTransport(var data: String) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var code = 10000
    var message = "ok"
    var failure: Exception? = null
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        failure?.let { throw it }
        return RisingStonesHttpResponse(200, emptyMap(), """{"code":$code,"msg":"$message","data":$data}""".encodeToByteArray())
    }
    fun service(session: RisingStonesSessionProvider = object : RisingStonesSessionProvider {
        override val capabilities = emptySet<RisingStonesCapability>()
        override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = null
        override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? = null
    }) = DutyRecruitmentApiService(RisingStonesPublicApiClient(this), session, temporarySessionId = "synthetic-directory")
}

private class DirectorySession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.RecruitmentWrite)
    val contexts = mutableListOf<RisingStonesRequestContext>()
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, _ -> contexts += context }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? { refreshes++; return null }
}
