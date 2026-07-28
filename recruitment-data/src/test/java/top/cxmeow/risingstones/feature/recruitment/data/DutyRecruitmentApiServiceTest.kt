package top.cxmeow.risingstones.feature.recruitment.data

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
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentException
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentPosition
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class DutyRecruitmentApiServiceTest {
    @Test
    fun dutyListDetailCatalogAndResponseUseFrozenContracts() = runBlocking {
        val transport = RecruitmentTransport()
        val service = service(transport)

        val page = service.fetchDutyRecruitments(
            DutyRecruitmentListQuery(2, 20, "Omega", "High-end", DutyRecruitmentPosition.Healer1),
        )
        val detail = service.fetchDutyRecruitmentDetail(42)
        val catalogs = service.fetchCatalogs()
        val contact = service.respondToDutyRecruitment(42, "Discord: hero")

        val listUrl = transport.requests[0].url.toHttpUrl()
        assertTrue(listUrl.encodedPath.endsWith("/recruitFbList"))
        assertEquals("2", listUrl.queryParameter("page"))
        assertEquals("Omega", listUrl.queryParameter("fb_name"))
        assertEquals("High-end", listUrl.queryParameter("fb_type"))
        assertEquals("H1", listUrl.queryParameter("position"))
        assertEquals(42, page.items.single().id)
        assertEquals(listOf("MT" to 1, "H1" to 1), page.items.single().roleCounts.nonEmptyEntries())
        assertEquals("Recruit requirements", detail.recruitRequirements)
        assertEquals("Body", catalogs.jobs.getValue("19").name)
        assertEquals(listOf("High-end"), catalogs.dutyTypes)
        assertEquals("Leader contact", contact)
        val response = transport.requests.last()
        assertEquals(RisingStonesHttpMethod.Post, response.method)
        assertEquals("42", response.form()["id"])
        assertEquals("Discord: hero", response.form()["contact_info"])
        assertEquals("Host token", response.headers["Authorization"])
    }

    @Test
    fun allCommunityKindsUseTheirExactIosQueriesAndDedicatedCards() = runBlocking {
        val transport = RecruitmentTransport()
        val service = service(transport)

        val beginner = service.fetchCommunityRecruitments(
            CommunityRecruitmentQuery(
                CommunityRecruitmentKind.Beginner,
                styleIds = listOf("1", "3"),
                identity = "2",
                areaId = "1",
                groupId = "10",
            ),
        )
        val guild = service.fetchCommunityRecruitments(
            CommunityRecruitmentQuery(
                CommunityRecruitmentKind.Guild,
                keyword = "Meteor",
                guildLabelIds = listOf("5"),
                activeMemberCounts = "20",
            ),
        )
        val other = service.fetchCommunityRecruitments(
            CommunityRecruitmentQuery(CommunityRecruitmentKind.Other, categoryIds = listOf("7", "8")),
        )
        val rolePlay = service.fetchCommunityRecruitments(
            CommunityRecruitmentQuery(
                CommunityRecruitmentKind.RolePlay,
                keyword = "Cafe",
                rolePlayTypes = listOf("1", "2"),
                rolePlayStatus = "1",
                order = "scoreDesc",
                areaId = "2",
                groupId = "20",
            ),
        )

        val beginnerUrl = transport.requests[0].url.toHttpUrl()
        assertTrue(beginnerUrl.encodedPath.endsWith("/recruitNeList"))
        assertEquals("1,3", beginnerUrl.queryParameter("style"))
        assertEquals("2", beginnerUrl.queryParameter("identity"))
        assertEquals("New Hero", beginner.items.single().beginner?.publisherName)
        assertEquals(listOf("Casual"), beginner.items.single().beginner?.styles?.map { it.name })
        val guildUrl = transport.requests[1].url.toHttpUrl()
        assertEquals("Meteor", guildUrl.queryParameter("guild_name"))
        assertEquals("20", guildUrl.queryParameter("active_member_num"))
        assertEquals("5", guildUrl.queryParameter("label"))
        assertEquals("Host token", transport.requests[1].headers["Authorization"])
        assertEquals("Meteor FC", guild.items.single().guild?.guildName)
        val otherUrl = transport.requests[2].url.toHttpUrl()
        assertEquals("7,8", otherUrl.queryParameter("category"))
        assertEquals("Treasure map", other.items.single().other?.title)
        assertEquals(
            "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/default_recruit_cover.jpg",
            other.items.single().other?.coverUrl,
        )
        val rolePlayUrl = transport.requests[3].url.toHttpUrl()
        assertEquals("Cafe", rolePlayUrl.queryParameter("rp_name"))
        assertEquals("1,2", rolePlayUrl.queryParameter("rp_type"))
        assertEquals("1", rolePlayUrl.queryParameter("act_status"))
        assertEquals("scoreDesc", rolePlayUrl.queryParameter("order"))
        assertEquals("Cafe Moon", rolePlay.items.single().rolePlay?.name)
    }

    @Test
    fun beginnerDetailResponseAndRolePlayCommunityDataAreFullyMapped() = runBlocking {
        val transport = RecruitmentTransport()
        val service = service(transport)

        val beginner = service.fetchCommunityRecruitmentDetail(11, CommunityRecruitmentKind.Beginner)
        val responseContact = service.respondToBeginnerRecruitment(11, "QQ 123")
        val rolePlay = service.fetchCommunityRecruitmentDetail(14, CommunityRecruitmentKind.RolePlay)
        val members = service.fetchRolePlayMembers(14)
        val reviews = service.fetchRolePlayReviews(14, 1, 10)
        val replies = service.fetchRolePlaySubcomments("review-1", 1, 3)
        val rating = service.fetchRolePlayRating(14)
        val like = service.likeRolePlayReview("review-1")

        assertEquals("New Hero", beginner.summary.authorName)
        assertEquals("Masked contact", beginner.summary.beginner?.recruiterContactInfo)
        assertEquals("Recruiter contact", responseContact)
        assertEquals("Cafe Moon", rolePlay.summary.title)
        assertEquals(listOf("https://cdn.test/footer.jpg"), rolePlay.summary.footerImageUrls)
        assertEquals(listOf("https://cdn.test/body.jpg"), rolePlay.content.single().imageUrls)
        assertEquals("2026-07-21", rolePlay.information.first { it.kind.name == "CreatedAt" }.value)
        assertEquals("Maid One", members.single().name)
        assertEquals("Good venue", reviews.items.single().content)
        assertEquals("Thanks", replies.items.single().content)
        assertEquals(listOf(1, 2, 3, 4, 5), rating.counts)
        assertEquals(1, like)
    }

    @Test
    fun filtersMapOfficialOptionsAndGuildWritesRequireIdentity() = runBlocking {
        val transport = RecruitmentTransport()
        val service = service(transport)

        val catalog = service.fetchCommunityFilterCatalog(CommunityRecruitmentKind.Beginner)

        assertEquals(listOf("Casual"), catalog.styles.map { it.name })
        assertEquals("陆行鸟", catalog.areas.single().name)
        assertEquals("红玉海", catalog.areas.single().servers.single().name)

        val unauthenticated = DutyRecruitmentApiService(
            RisingStonesPublicApiClient(RecruitmentTransport(), listOf("https://rising.test")),
            MissingRecruitmentCredential,
        )
        assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
            runBlocking {
                unauthenticated.fetchCommunityRecruitments(
                    CommunityRecruitmentQuery(CommunityRecruitmentKind.Guild),
                )
            }
        }
        assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
            runBlocking { unauthenticated.respondToDutyRecruitment(42, "contact") }
        }
        Unit
    }

    private fun service(transport: RisingStonesHttpClient) = DutyRecruitmentApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        RecruitmentCredential,
        temporarySessionId = "session-1",
    )
}

private object RecruitmentCredential : RisingStonesSessionProvider {
    override val capabilities = setOf(
        RisingStonesCapability.RecruitmentAuthenticated,
        RisingStonesCapability.RecruitmentWrite,
    )

    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("Authorization", "Host token")
    }
}

private object MissingRecruitmentCredential : RisingStonesSessionProvider {
    override val capabilities = emptySet<RisingStonesCapability>()
    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? = null
}

private class RecruitmentTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val path = request.url.toHttpUrl().encodedPath
        val body = when {
            path.endsWith("/recruitFbList") -> DUTY_LIST
            path.endsWith("/getRecruitFbDetail") -> DUTY_DETAIL
            path.endsWith("/getJobConfigList") -> JOB_CONFIG
            path.endsWith("/getFbConfigList") -> DUTY_CONFIG
            path.endsWith("/responseRecruitFb") -> """{"code":10000,"data":{"recruit_contact_info":"Leader contact"}}"""
            path.endsWith("/responseNoviceEntertain") -> """{"code":10000,"data":{"recruit_contact_info":"Recruiter contact"}}"""
            path.endsWith("/recruitNeList") -> BEGINNER_LIST
            path.endsWith("/recruitGuildList") -> GUILD_LIST
            path.endsWith("/recruitOtherList") -> OTHER_LIST
            path.endsWith("/recruitRpList") -> ROLEPLAY_LIST
            path.endsWith("/getNeDetail") -> BEGINNER_DETAIL
            path.endsWith("/getRpDetail") -> ROLEPLAY_DETAIL
            path.endsWith("/getRecruitRpMemberListByRpId") -> MEMBERS
            path.endsWith("/recruitRpCommentDetail") -> REVIEWS
            path.endsWith("/recruitRpSubCommentDetail") -> SUBCOMMENTS
            path.endsWith("/getRecruitRpScoreListByRpId") -> """{"code":10000,"data":[1,"2",3,4,5]}"""
            path.endsWith("/rpCommentlike") -> """{"code":10000,"data":"1"}"""
            path.endsWith("/styleConfigList") -> """{"code":10000,"data":[{"id":"1","style":"Casual"}]}"""
            path.endsWith("/getAreaAndGroupList") -> AREAS
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

private val DUTY_LIST = """
  {"code":10000,"data":{"count":"21","rows":[{
    "id":"42","uuid":"author","character_name":"Hero","area_name":"陆行鸟","group_name":"红玉海",
    "target_area_name":"陆行鸟","fb_type":"High-end","fb_name":"Omega","team_composition":"8-player",
    "progress":"P2","strategy":"Standard","fb_time":"20:00-23:00","response_num":"3","status":1,
    "label":["1"],"label_info":[{"id":"1","name":"Chill","weight":"2"}],
    "job_info":[{"id":"19","value":"Body","job_pic_url":"1","job_type":"Tank"}],
    "need_job":["MT","H1"],"MT":"1","H1":1,"sort_updated_time":"1784606400000"
  }]}}
""".trimIndent()

private val DUTY_DETAIL = """
  {"code":10000,"data":{
    "id":"42","uuid":"author","character_name":"Hero","area_name":"陆行鸟","group_name":"红玉海",
    "target_area_name":"陆行鸟","target_group_name":"红玉海","fb_type":"High-end","fb_name":"Omega",
    "team_composition":"8-player","progress":"P2","strategy":"Standard","fb_time":"20:00-23:00",
    "response_num":"3","status":1,"MT":"1","H1":1,"team_detail_mask":"<p>Current team</p>",
    "recruit_require_mask":"<p>Recruit requirements</p>","strategy_desc_mask":"<p>Detailed strategy</p>",
    "contact_info_mask":"Hidden contact","due_day":"7","is_response":"0","is_share":"1"
  }}
""".trimIndent()

private val JOB_CONFIG = """{"code":10000,"data":{"tank":[{"id":"19","value":"Body","job_pic_url":"1","job_type":"Tank"}]}}"""
private val DUTY_CONFIG = """{"code":10000,"data":[{"id":"1","fb_type":"High-end","fb_name":"Omega","team_composition":"8-player","weight":"1"}]}"""
private val BEGINNER_LIST = """
  {"code":10000,"data":{"count":"1","rows":[{"id":"11","character_name":"New Hero","title":"Learning party",
  "identity":"2","area_name":"陆行鸟","group_name":"红玉海","target_area_name":"陆行鸟","target_group_name":"红玉海",
  "detail_mask":"<p>Welcome</p>","styleInfo":[{"style":"Casual","pic_url":"https://cdn.test/style.png"}],"is_response":"0"}]}}
""".trimIndent()
private val GUILD_LIST = """
  {"code":10000,"data":{"count":1,"rows":[{"id":"12","guild_name":"Meteor FC","guild_tag":"AST",
  "area_name":"陆行鸟","group_name":"红玉海","labelInfo":[{"name":"Raids"}],"cover_pic":"https://cdn.test/guild.jpg"}]}}
""".trimIndent()
private val OTHER_LIST = """
  {"code":10000,"data":{"count":1,"rows":[{"id":"13","category_name":"Maps","title":"Treasure map",
  "character_name":"Mapper","area_name":"陆行鸟","group_name":"红玉海","detail_mask":"<p>Maps</p>"}]}}
""".trimIndent()
private val ROLEPLAY_LIST = """
  {"code":10000,"data":{"count":1,"rows":[{"id":"14","rp_name":"Cafe Moon","rp_type":["1","2"],
  "profile":"A quiet cafe","custom_label":"Cafe,Photo","cover_pic":"https://cdn.test/rp.jpg"}]}}
""".trimIndent()
private val BEGINNER_DETAIL = """
  {"code":10000,"data":{"id":"11","character_name":"New Hero","title":"Learning party","identity":"2",
  "area_name":"陆行鸟","group_name":"红玉海","target_area_name":"陆行鸟","target_group_name":"红玉海",
  "detail_mask":"<p>Welcome</p>","contact_info_mask":"Masked contact","styleInfo":[{"style":"Casual"}],"is_response":"1"}}
""".trimIndent()
private val ROLEPLAY_DETAIL = """
  {"code":10000,"data":{"id":"14","rp_name":"Cafe Moon","rp_type":["1"],"profile":"A quiet cafe",
  "detail_mask":"<p>Welcome to the cafe</p><img src=\"https://cdn.test/body.jpg\">",
  "foot_pic":"https://cdn.test/footer.jpg","character_name":"Owner","create_time":"2026-07-21 20:00:00"}}
""".trimIndent()
private val MEMBERS = """{"code":10000,"data":{"rows":[{"id":"1","member_name":"Maid One","member_identity":"Maid","detail_mask":"<p>Hello</p>"}]}}"""
private val REVIEWS = """{"code":10000,"data":{"rows":[{"id":"review-1","character_name":"Visitor","mask_content":"<p>Good venue</p>","score":"5","like_count":"2","is_like":"0","children_count":"1"}]}}"""
private val SUBCOMMENTS = """{"code":10000,"data":{"rows":[{"id":"reply-1","character_name":"Owner","mask_content":"<p>Thanks</p>"}]}}"""
private val AREAS = """{"code":10000,"data":[{"AreaID":"1","AreaName":"陆行鸟","vGroup":[{"GroupID":"10","GroupName":"红玉海"}]}]}"""
