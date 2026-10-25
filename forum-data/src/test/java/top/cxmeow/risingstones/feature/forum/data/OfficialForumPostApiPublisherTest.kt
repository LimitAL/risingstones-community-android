package top.cxmeow.risingstones.feature.forum.data
import kotlinx.coroutines.*
import java.net.URLDecoder
import org.junit.Test
import org.junit.Assert.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.network.*
import top.cxmeow.risingstones.feature.forum.domain.*
class OfficialForumPostApiPublisherTest {
    private class Session:RisingStonesSessionProvider,RisingStonesCapabilityScopeProvider {
        var current=true;var captured=0;var closed=0;var completed=0;var refreshed=0;var authorized=0
        var onAuthorize:()->Unit={}
        override val capabilities=setOf(RisingStonesCapability.ForumWrite)
        override suspend fun currentAuthorizer():RisingStonesRequestAuthorizer?=error("Must capture scope")
        override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer?{refreshed++;error("No refresh")}
        override suspend fun captureCapabilityScope(capabilities:Set<RisingStonesCapability>):RisingStonesCapabilityScope? {
            assertEquals(setOf(RisingStonesCapability.ForumWrite),capabilities);captured++;if(!current)return null
            return object:RisingStonesCapabilityScope {
                override val authorizer=RisingStonesRequestAuthorizer{_,_->error("No prerequisite read")}
                override suspend fun isCurrent()=current
                override fun close(){closed++}
                override suspend fun beginCapabilityAttempt(context:RisingStonesRequestContext):RisingStonesCapabilityAttempt {
                    assertEquals("api/home/posts/create",context.path);assertEquals(RisingStonesAuthenticationRequirement.Required,context.requirement)
                    return object:RisingStonesCapabilityAttempt,RisingStonesCapabilityAttemptGuard {
                        override val authorizer=RisingStonesRequestAuthorizer{_,sink->authorized++;sink.set("Cookie","fixture-only");onAuthorize()}
                        override suspend fun isCurrent()=current
                        override suspend fun complete():Boolean{completed++;return current}
                        override fun close(){}
                    }
                }
            }
        }
    }
    private val draft=OfficialForumPostCreateDraft(8,"中文 & title","<p>A &amp; B</p>")
    private fun response(body:String,status:Int=200)=RisingStonesHttpResponse(status,emptyMap(),body.toByteArray())
    private fun client(block:suspend(RisingStonesHttpRequest)->RisingStonesHttpResponse)=RisingStonesPublicApiClient(object:RisingStonesHttpClient{override suspend fun execute(request:RisingStonesHttpRequest)=block(request)},listOf("https://fixture.invalid","https://must-never-replay.invalid"))
    @Test fun exactFormIsTextOnlyAndUsesIndependentTemporaryIds()=runBlocking {
        val session=Session();var calls=0;var next=0
        val api=OfficialForumPostApiPublisher(client{r->calls++;assertTrue(r.url.startsWith("https://fixture.invalid/api/home/posts/create?tempsuid="));assertEquals(RisingStonesHttpMethod.Post,r.method)
            assertTrue(r.body!!.toString(Charsets.UTF_8).contains("title=%E4%B8%AD%E6%96%87%20%26%20title"));assertEquals("application/x-www-form-urlencoded; charset=UTF-8",r.contentType)
            val fields=r.body!!.toString(Charsets.UTF_8).split('&').associate{val p=it.split('=',limit=2);URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8")}
            assertEquals(setOf("id","updated_at","type","part_id","title","is_share","content","scope","cover_pic","has_vote","tempsuid"),fields.keys)
            assertEquals("1",fields["type"]);assertEquals("",fields["cover_pic"]);assertEquals("0",fields["has_vote"]);assertEquals(draft.contentHtml,fields["content"]);assertEquals(draft.title,fields["title"])
            assertFalse(r.url.endsWith(fields["tempsuid"]!!));response("{\"code\":10000,\"data\":{\"id\":\"901\"}}")},session,temporarySessionId={"fixture-${++next}"})
        assertEquals(901,api.createPost(draft).postId);assertEquals(1,calls);assertEquals(1,session.completed);assertEquals(1,session.closed);assertEquals(0,session.refreshed)
    }
    @Test fun onlyExactNumericBusinessSuccessAndPositiveIdComplete()=runBlocking {
        for(body in listOf("{}","{\"code\":\"10000\",\"data\":{\"id\":1}}","{\"code\":10000}","{\"code\":10000,\"data\":{\"id\":0}}","not-json")){
            val s=Session();var calls=0;val api=OfficialForumPostApiPublisher(client{calls++;response(body)},s)
            assertNotNull(runCatching{api.createPost(draft)}.exceptionOrNull());assertEquals(1,calls);assertEquals(0,s.completed);assertEquals(1,s.closed)
        }
    }
    @Test fun authorizationAndBusinessRefusalNeverRefreshOrReplayPost()=runBlocking {
        for(status in listOf(401,403,200)){
            val s=Session();var calls=0;val api=OfficialForumPostApiPublisher(client{calls++;response("{\"code\":10001,\"msg\":\"refused\"}",status)},s)
            assertTrue(runCatching{api.createPost(draft)}.exceptionOrNull() is OfficialForumPostRejectedException);assertEquals(1,calls);assertEquals(0,s.refreshed);assertEquals(0,s.completed)
        }
    }
    @Test fun transportFailureNeverFailsOverAndClosesScope()=runBlocking {
        val s=Session();var calls=0;val api=OfficialForumPostApiPublisher(client{calls++;throw java.io.IOException("fixture")},s)
        assertNotNull(runCatching{api.createPost(draft)}.exceptionOrNull());assertEquals(1,calls);assertEquals(1,s.closed)
    }
    @Test fun credentialChangeDuringAuthorizationCannotSendWithNewSession()=runBlocking {
        val s=Session();s.onAuthorize={s.current=false};var calls=0;val api=OfficialForumPostApiPublisher(client{calls++;error("No network")},s)
        assertTrue(runCatching{api.createPost(draft)}.exceptionOrNull() is OfficialForumPostRejectedException);assertEquals(0,calls);assertEquals(0,s.completed)
    }
    @Test fun staleResponseAndCancellationCannotPublishSuccess()=runBlocking {
        val s=Session();val api=OfficialForumPostApiPublisher(client{s.current=false;response("{\"code\":10000,\"data\":{\"id\":901}}")},s)
        assertTrue(runCatching{api.createPost(draft)}.exceptionOrNull() is CancellationException);assertEquals(0,s.completed)
        val c=Session();val cancel=OfficialForumPostApiPublisher(client{throw CancellationException("fixture")},c)
        assertTrue(runCatching{cancel.createPost(draft)}.exceptionOrNull() is CancellationException);assertEquals(1,c.closed)
    }
}
