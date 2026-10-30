package top.cxmeow.risingstones.feature.profile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.network.*

/** Finite synthetic transport: no socket or real credentials. */
class ProfileRecommendationsReadTest {
    private class Wire: RisingStonesHttpClient {
        var data="[]"; var code=10000; var cancel=false
        val requests=mutableListOf<RisingStonesHttpRequest>()
        override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
            check(request.method==RisingStonesHttpMethod.Get)
            val url=request.url.toHttpUrl()
            check(url.encodedPath=="/api/home/userRelation/getUnFollowFriend" && url.queryParameterNames.isEmpty())
            requests+=request
            if(cancel)throw CancellationException()
            return RisingStonesHttpResponse(200,emptyMap(),("{\"code\":$code,\"data\":$data}").encodeToByteArray())
        }
    }
    private class Session:RisingStonesSessionProvider {
        override var capabilities=setOf(RisingStonesCapability.AccountRead)
        var refreshes=0
        private val authorizer=RisingStonesRequestAuthorizer{context,sink->
            check(context.path=="api/home/userRelation/getUnFollowFriend")
            sink.set("User-Agent","Offline fixture")
        }
        override suspend fun currentAuthorizer()=authorizer
        override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer{refreshes++;return authorizer}
    }
    private fun api(w:Wire,s:Session=Session())=ProfileApiService(RisingStonesPublicApiClient(w,listOf("https://fixture.test")),s)
    @Test fun exactEndpointPreservesSourceGroupsDuplicatesAndIdentity()=runTest {
        val w=Wire();w.data="""[{"uuid":"one","character_name":"甲","area_name":"Area","group_name":"World","recommend_type":"B","profile":"Line"},{"uuid":"two","recommend_type":"A"},{"uuid":"one","recommend_type":"B"},{"uuid":123},{"uuid":true},null,{"uuid":"***"}]"""
        val r=api(w).fetchRecommendedUsers()
        assertEquals(7,r.sourceRows);assertEquals(3,r.invalidRows);assertEquals(listOf(0,1,2,3),r.entries.map{it.sourceIndex})
        assertEquals(listOf("B","A",""),r.groups.map{it.reason});assertEquals(listOf("one","one"),r.groups[0].entries.map{it.person.uuid})
        assertEquals("Line",r.entries.first().person.biography);assertEquals("Area",r.entries.first().person.areaName);assertEquals("World",r.entries.first().person.groupName)
        assertEquals("123",r.entries.last().person.uuid);assertEquals(1,w.requests.size)
    }
    @Test fun emptyMalformedAndAllInvalidRemainDistinct()=runTest {
        val w=Wire();val service=api(w);assertEquals(0,service.fetchRecommendedUsers().sourceRows)
        w.data="""[{"uuid":""},{"uuid":[]},{"uuid":{}}]""";val r=service.fetchRecommendedUsers();assertEquals(3,r.invalidRows);assertTrue(r.entries.isEmpty())
        for(value in listOf("null","{}","{\"rows\":[]}")){w.data=value;try{service.fetchRecommendedUsers();fail()}catch(_:ProfileException.InvalidResponse){}}
    }
    @Test fun readCapabilityAuthenticationBusinessAndCancellationAreNotEmptySuccess()=runTest {
        val w=Wire();val s=Session();val service=api(w,s)
        w.code=10001;try{service.fetchRecommendedUsers();fail()}catch(_:ProfileException.AuthenticationRequired){};assertEquals(1,s.refreshes);assertEquals(2,w.requests.size)
        w.code=10002;try{service.fetchRecommendedUsers();fail()}catch(e:ProfileException.Business){assertEquals(10002,e.code)}
        w.code=500;try{service.fetchRecommendedUsers();fail()}catch(e:ProfileException.Business){assertEquals(500,e.code)}
        w.cancel=true;try{service.fetchRecommendedUsers();fail()}catch(_:CancellationException){}
        val calls=w.requests.size;s.capabilities=emptySet();try{service.fetchRecommendedUsers();fail()}catch(_:ProfileException.Unavailable){};assertEquals(calls,w.requests.size)
    }
}
