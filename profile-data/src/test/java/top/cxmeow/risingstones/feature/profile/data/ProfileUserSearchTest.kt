package top.cxmeow.risingstones.feature.profile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.network.*

class ProfileUserSearchTest {
    private class Wire: RisingStonesHttpClient {
        var data="[]";var code=10000;var cancel=false
        val requests=mutableListOf<RisingStonesHttpRequest>()
        override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
            check(request.method==RisingStonesHttpMethod.Get && request.url.toHttpUrl().encodedPath=="/api/common/search")
            requests+=request
            if(cancel)throw CancellationException()
            return RisingStonesHttpResponse(200,emptyMap(),"""{"code":$code,"data":$data}""".encodeToByteArray())
        }
    }
    private class Session:RisingStonesSessionProvider {
        override var capabilities=setOf(RisingStonesCapability.AccountRead)
        var refreshes=0
        val authorizer=RisingStonesRequestAuthorizer{context,sink->
            check(context.path=="api/common/search")
            sink.set("User-Agent","Offline fixture")
        }
        override suspend fun currentAuthorizer()=authorizer
        override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer {refreshes++;return authorizer}
    }
    private fun api(wire:Wire,session:Session=Session())=ProfileApiService(RisingStonesPublicApiClient(wire,listOf("https://fixture.test")),session)
    @Test fun exactReadContractPreservesIdentityLocationAvatarAndUnknownVersusZero()=runTest {
        val wire=Wire();wire.data="""[{"uuid":"one","character_name":"同名","area_name":"Area","group_name":"World","avatar":"https://static.web.sdo.com/mock.png"},{"uuid":"two","character_name":"同名","fansNum":"0"}]"""
        val page=api(wire).searchUsers("中文 & A",2,20)
        assertEquals(2,page.page);assertFalse(page.hasMore);assertNull(page.users[0].followerCount);assertEquals(0L,page.users[1].followerCount)
        assertEquals("Area",page.users[0].areaName);assertEquals("World",page.users[0].groupName);assertEquals("https://static.web.sdo.com/mock.png",page.users[0].avatarUrl)
        val q=wire.requests.single().url.toHttpUrl()
        assertEquals(setOf("type","keywords","part_id","orderBy","page","limit","pageTime"),q.queryParameterNames)
        assertEquals("6",q.queryParameter("type"));assertEquals("中文 & A",q.queryParameter("keywords"));assertEquals("comment",q.queryParameter("orderBy"));assertEquals("",q.queryParameter("part_id"));assertEquals("",q.queryParameter("pageTime"))
    }
    @Test fun invalidRowsCountsAndUnsafeImagesNeverCreateIdentityOrZeroFacts()=runTest {
        val wire=Wire();val service=api(wire)
        for(value in listOf("null","-1","1.5","true","{}","[]","9223372036854775808")){
            wire.data="""[{"uuid":"good","fansNum":$value,"avatar":"javascript:alert(1)"},{"uuid":"***"},{"uuid":true},{"uuid":[]},{"uuid":""},null]"""
            val rows=service.searchUsers("x",1,20).users;assertEquals(1,rows.size);assertNull(rows.single().followerCount);assertNull(rows.single().avatarUrl)
        }
        wire.data="""[{"uuid":"large","fansNum":"2147483648"}]""";assertEquals(2147483648L,service.searchUsers("x").users.single().followerCount)
    }
    @Test fun fullWirePageOnlyPredictsContinuationAndMalformedPayloadIsNotEmpty()=runTest {
        val wire=Wire();val service=api(wire)
        wire.data="""[{"uuid":"one"},{"uuid":"***"}]""";val page=service.searchUsers("x",1,2);assertTrue(page.hasMore);assertEquals(1,page.users.size)
        wire.data="[]";assertFalse(service.searchUsers("x").hasMore)
        for(data in listOf("null","{}","{\"rows\":[]}")) {wire.data=data;try{service.searchUsers("x");fail()}catch(_:ProfileException.InvalidResponse){}}
    }
    @Test fun refreshOnceErrorsAndCancellationRemainDistinctAndCapabilityIsReadOnly()=runTest {
        val wire=Wire();val session=Session();val service=api(wire,session)
        wire.code=10001;try{service.searchUsers("x");fail()}catch(_:ProfileException.AuthenticationRequired){}
        assertEquals(1,session.refreshes);assertEquals(2,wire.requests.size)
        wire.code=500;try{service.searchUsers("x");fail()}catch(error:ProfileException.Business){assertEquals(500,error.code)}
        wire.cancel=true;try{service.searchUsers("x");fail()}catch(_:CancellationException){}
        val count=wire.requests.size;session.capabilities=emptySet();try{service.searchUsers("x");fail()}catch(_:ProfileException.Unavailable){}
        assertEquals(count,wire.requests.size)
    }
    @Test fun singleAvatarUrlKeepsItsCommaContainingQueryWithoutSplitting()=runTest {
        val wire=Wire();val url="https://static.web.sdo.com/avatar.png?x-oss-process=image/resize,w_100,h_100"
        wire.data="""[{"uuid":"one","avatar":${kotlinx.serialization.json.JsonPrimitive(url)}}]"""
        assertEquals(url,api(wire).searchUsers("x").users.single().avatarUrl)
    }
}
