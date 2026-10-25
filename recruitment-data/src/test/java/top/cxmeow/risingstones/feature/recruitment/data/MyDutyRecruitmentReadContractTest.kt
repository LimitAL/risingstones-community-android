package top.cxmeow.risingstones.feature.recruitment.data

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.net.URI
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.network.*

/** Standalone read tests; protected publishing regressions remain untouched. */
class MyDutyRecruitmentReadContractTest {
    private class Fixture {
        var body = """{"code":10000,"data":{"count":0,"rows":[]}}"""
        val requests = mutableListOf<RisingStonesHttpRequest>()
        val provider = object : RisingStonesSessionProvider {
            override val capabilities = setOf(RisingStonesCapability.AccountRead)
            override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { c, sink ->
                check(c.capability == RisingStonesCapability.AccountRead && c.requirement == RisingStonesAuthenticationRequirement.Required)
                check(c.path == "api/home/recruit/myFbRecruitList"); sink.set("X-Fixture", "read")
            }
            override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? = null
        }
        val api = DutyRecruitmentApiService(RisingStonesPublicApiClient(object : RisingStonesHttpClient {
            override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                check(request.method == RisingStonesHttpMethod.Get && URI(request.url).path == "/api/home/recruit/myFbRecruitList")
                requests += request; return RisingStonesHttpResponse(200, emptyMap(), body.toByteArray())
            }
        }, listOf("https://fixture.invalid")), provider, temporarySessionId = "fixture-nonce")
    }
    @Test fun exactAuthenticatedReadHasNoBodyAndPreservesZeroOrUnknownCount() = runTest {
        val f = Fixture(); assertEquals(0L, f.api.fetchMyDutyRecruitmentPage(1).sourceCount)
        val r = f.requests.single(); assertEquals("page=1&limit=10&tempsuid=fixture-nonce", URI(r.url).rawQuery); assertNull(r.body)
        for (count in listOf("null", "-1", "true", "1.5", "\"bad\"", "9223372036854775808")) {
            f.body = """{"code":10000,"data":{"count":$count,"rows":[]}}"""; assertNull(f.api.fetchMyDutyRecruitmentPage(1).sourceCount)
        }
        f.body = """{"code":10000,"data":{"count":"9000000000","rows":[]}}"""; assertEquals(9000000000L, f.api.fetchMyDutyRecruitmentPage(2).sourceCount)
    }
    @Test fun rawRowsControlPaginationAndStatusIsNotInferredFromClock() = runTest {
        val f = Fixture(); val rows = listOf("""{"id":1,"status":1,"fb_name":"Listed","end_time":1}""", """{"id":2,"status":7}""", """{"id":3}""", """{"id":1}""", """{"id":0.5}""", "null", "false", "{}", "[]", "\"bad\"")
        f.body = """{"code":10000,"data":{"rows":[${rows.joinToString(",")}]}}"""
        val p = f.api.fetchMyDutyRecruitmentPage(3); assertEquals(3,p.page);assertEquals(10,p.rawRows);assertEquals(6,p.invalidRows);assertEquals(1,p.duplicateRows);assertTrue(p.hasMore)
        assertEquals(listOf(1,7,null), p.items.map { it.sourceStatus }); assertEquals(1,p.items.first().summary.status);assertNull(p.sourceCount)
    }
    @Test fun malformedPayloadAndNonStrictSuccessFailRatherThanEmpty() = runTest {
        val f = Fixture()
        for (body in listOf("{}", """{"code":10000.9,"data":{"rows":[]}}""", """{"code":0,"data":{"rows":[]}}""", """{"code":200,"data":{"rows":[]}}""", """{"code":10000,"data":null}""", """{"code":10000,"data":{"rows":null}}""", """{"code":10000,"data":{"rows":{}}}""")) {
            f.body = body; assertTrue(body, runCatching { f.api.fetchMyDutyRecruitmentPage(1) }.isFailure)
        }
        val calls = f.requests.size; assertTrue(runCatching { f.api.fetchMyDutyRecruitmentPage(0) }.isFailure); assertEquals(calls,f.requests.size)
    }
}
