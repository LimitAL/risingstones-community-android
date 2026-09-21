package top.cxmeow.risingstones.feature.recruitment.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class RecruitmentServerFilterTest {
    @Test
    fun absentOrAllAreasOmitBothParametersEvenWhenOldServerSelectionsRemain() = runBlocking {
        for ((kind, prefix) in kinds) for (area in listOf("", " ", "0", " 0 ")) {
            for (groups in listOf("", "0", "10,20")) {
                val request = request(CommunityRecruitmentQuery(kind, areaId = area, groupId = groups))
                val url = request.url.toHttpUrl()
                assertNull(url.queryParameter("${prefix}_area_id"))
                assertNull(url.queryParameter("${prefix}_group_id"))
                assertEquals(RisingStonesHttpMethod.Get, request.method)
            }
        }
    }

    @Test
    fun rolePlayAllServerTokenOmitsOnlyTheServerFilterInTheSelectedArea() = runBlocking {
        for (groups in listOf("0", "0,10", "10,0", "10, 0 ,20")) {
            val url = request(CommunityRecruitmentQuery(
                CommunityRecruitmentKind.RolePlay, areaId = "2", groupId = groups,
                rolePlayTypes = listOf("1", "3"), order = "scoreDesc",
            )).url.toHttpUrl()
            assertEquals("2", url.queryParameter("rp_area_id"))
            assertNull(url.queryParameter("rp_group_id"))
            assertEquals("1,3", url.queryParameter("rp_type"))
            assertEquals("scoreDesc", url.queryParameter("order"))
        }
    }

    @Test
    fun otherAllServerTokenRemainsExplicitAndConcreteCsvValuesArePreserved() = runBlocking {
        val all = request(CommunityRecruitmentQuery(
            CommunityRecruitmentKind.Other, areaId = "2", groupId = "0", categoryIds = listOf("5", "7"),
        )).url.toHttpUrl()
        assertEquals("2", all.queryParameter("target_area_id"))
        assertEquals("0", all.queryParameter("target_group_id"))
        assertEquals("5,7", all.queryParameter("category"))
        for ((kind, prefix) in kinds) for (groups in listOf("10", "10,20,100")) {
            val url = request(CommunityRecruitmentQuery(kind, areaId = "2", groupId = groups)).url.toHttpUrl()
            assertEquals("2", url.queryParameter("${prefix}_area_id"))
            assertEquals(groups, url.queryParameter("${prefix}_group_id"))
        }
    }

    @Test
    fun emptyServerSelectionKeepsTheAreaAndExistingSingleServerKindsAreUnaffected() = runBlocking {
        for ((kind, prefix) in kinds) {
            val url = request(CommunityRecruitmentQuery(kind, areaId = "2", groupId = " ")).url.toHttpUrl()
            assertEquals("2", url.queryParameter("${prefix}_area_id"))
            assertNull(url.queryParameter("${prefix}_group_id"))
        }
        for (kind in listOf(CommunityRecruitmentKind.Beginner, CommunityRecruitmentKind.Guild)) {
            val url = request(CommunityRecruitmentQuery(kind, areaId = "0", groupId = "0")).url.toHttpUrl()
            assertEquals("0", url.queryParameter("target_area_id"))
            assertEquals("0", url.queryParameter("target_group_id"))
        }
    }

    private val kinds = mapOf(CommunityRecruitmentKind.RolePlay to "rp", CommunityRecruitmentKind.Other to "target")

    private suspend fun request(query: CommunityRecruitmentQuery): RisingStonesHttpRequest {
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val transport = object : RisingStonesHttpClient {
            override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                requests += request
                return RisingStonesHttpResponse(200, emptyMap(), """{"code":10000,"data":{"rows":[],"count":0}}""".encodeToByteArray())
            }
        }
        val session = object : RisingStonesSessionProvider {
            override val capabilities = setOf(RisingStonesCapability.RecruitmentAuthenticated)
            override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink -> sink.set("User-Agent", "fixture-agent") }
        }
        DutyRecruitmentApiService(RisingStonesPublicApiClient(transport), session).fetchCommunityRecruitments(query)
        return requests.single()
    }
}
