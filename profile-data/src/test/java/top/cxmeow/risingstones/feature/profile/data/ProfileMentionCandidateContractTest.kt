package top.cxmeow.risingstones.feature.profile.data

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.net.URI
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.network.*

/** New standalone read-contract tests; existing protected regressions are untouched. */
class ProfileMentionCandidateContractTest {
 private class Fixture {
  val requests=mutableListOf<RisingStonesHttpRequest>();var body="""{"code":10000,"data":{"count":0,"rows":[]}}"""
  val provider=object:RisingStonesSessionProvider {
   override val capabilities=setOf(RisingStonesCapability.AccountRead)
   override suspend fun currentAuthorizer()=RisingStonesRequestAuthorizer{c,s->check(c.capability==RisingStonesCapability.AccountRead);s.set("X-Fixture","read")}
   override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer?=null
  }
  val api=ProfileApiService(RisingStonesPublicApiClient(object:RisingStonesHttpClient {
   override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
    check(request.method==RisingStonesHttpMethod.Get && URI(request.url).path in setOf("/api/home/userRelation/followList","/api/common/search"));requests+=request
    return RisingStonesHttpResponse(200,emptyMap(),body.toByteArray())
   }
  },listOf("https://fixture.invalid")),provider)
 }
 @Test fun followingUsesDedicated5000AndKeepsZeroUnknownAndPartialSeparate()=runTest {
  val f=Fixture();assertEquals(ProfileMentionCompleteness.Complete,f.api.fetchMentionCandidateSnapshot().completeness)
  val r=f.requests.single();assertEquals("page=1&limit=5000",URI(r.url).rawQuery);assertNull(r.body)
  for(count in listOf("null","-1","true","\"bad\"")){
   f.body="""{"code":10000,"data":{"count":$count,"rows":[]}}"""
   val s=f.api.fetchMentionCandidateSnapshot();assertNull(s.sourceCount);assertEquals(ProfileMentionCompleteness.Unknown,s.completeness)
  }
  f.body="""{"code":10000,"data":{"count":"5001","rows":[]}}""";val partial=f.api.fetchMentionCandidateSnapshot();assertEquals(5001L,partial.sourceCount);assertEquals(ProfileMentionCompleteness.Partial,partial.completeness)
 }
 @Test fun scalarUUIDsFilteringDuplicatesAndSafeImagesKeepWireStatistics()=runTest {
  val f=Fixture();f.body="""{"code":10000,"data":{"count":8,"rows":[{"uuid":1234567890123,"character_name":"晨星","avatar":"https://static.web.sdo.com/a.png"},{"uuid":"1234567890123","character_name":"重复"},{"uuid":true,"character_name":"Wrong"},{"uuid":"******","character_name":"Wrong"},{"uuid":"bad#id","character_name":"Wrong"},{"uuid":"ok","character_name":""},{"uuid":"valid","character_name":"Name","avatar":"javascript:bad"},null]}}"""
  val s=f.api.fetchMentionCandidateSnapshot();assertEquals(8,s.rawRows);assertEquals(5,s.invalidRows);assertEquals(1,s.duplicateRows);assertEquals(2,s.candidates.size)
  assertEquals("1234567890123",s.candidates[0].uuid);assertNotNull(s.candidates[0].avatarUrl);assertNull(s.candidates[1].avatarUrl);assertEquals(ProfileMentionCompleteness.Partial,s.completeness)
 }
 @Test fun searchHasNoInventedTotalAndContinuationUsesRawRows()=runTest {
  val f=Fixture();f.body="""{"code":10000,"data":[{"uuid":"x","character_name":"X"},{"uuid":false}]}"""
  val s=f.api.searchMentionCandidates("晨星 &",2,2);assertEquals(2,s.page);assertEquals(2,s.rawRows);assertEquals(1,s.invalidRows);assertTrue(s.hasMore)
  val q=URI(f.requests.single().url).rawQuery;assertTrue(q.contains("type=6"));assertTrue(q.contains("part_id="));assertTrue(q.contains("pageTime="));assertTrue(q.contains("orderBy=comment"));assertTrue(q.contains("page=2"))
 }
 @Test fun missingRowsAndWrongSearchPayloadFailRatherThanBecomeEmpty()=runTest {
  val f=Fixture();for(data in listOf("null","{}","{\"rows\":null}")){
   f.body="""{"code":10000,"data":$data}""";assertTrue(runCatching{f.api.fetchMentionCandidateSnapshot()}.isFailure)
  }
  f.body="""{"code":10000,"data":{"rows":[]}}""";assertTrue(runCatching{f.api.searchMentionCandidates("q")}.isFailure)
 }
}
