package top.cxmeow.risingstones.feature.recruitment.data
import kotlinx.coroutines.*
import java.net.URLDecoder
import org.junit.Test
import org.junit.Assert.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.network.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
class DutyRecruitmentCreateContractTest {
    private class Session:RisingStonesSessionProvider,RisingStonesCapabilityScopeProvider {
        var current=true;var captured=0;var closed=0;var completed=0;var refreshed=0;var authorized=0
        var onAuthorize:()->Unit={}
        override val capabilities=setOf(RisingStonesCapability.RecruitmentWrite)
        override suspend fun currentAuthorizer():RisingStonesRequestAuthorizer?=error("Must capture scope")
        override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer?{refreshed++;error("No refresh")}
        override suspend fun captureCapabilityScope(capabilities:Set<RisingStonesCapability>):RisingStonesCapabilityScope? {
            assertEquals(setOf(RisingStonesCapability.RecruitmentWrite),capabilities);captured++;if(!current)return null
            return object:RisingStonesCapabilityScope {
                override val authorizer=RisingStonesRequestAuthorizer{_,_->error("No prerequisite read")}
                override suspend fun isCurrent()=current
                override fun close(){closed++}
                override suspend fun beginCapabilityAttempt(context:RisingStonesRequestContext):RisingStonesCapabilityAttempt {
                    assertEquals("api/home/recruit/createRecruitFb",context.path);assertEquals(RisingStonesAuthenticationRequirement.Required,context.requirement)
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
    private val catalog=DutyRecruitmentFilterCatalog(DutyRecruitmentCatalogs(mapOf("1" to DutyRecruitmentJob("1","Job",null,"防护职业")),listOf(DutyRecruitmentDutyConfig("1","绝境战","Duty","满编小队",1))),listOf(DutyRecruitmentLabel("1","Label",1)),listOf(CommunityRecruitmentArea("1","Area",emptyList())))
    private val draft=DutyRecruitmentCreateDraft(DutyRecruitmentPublishForm(dutyName="Duty",targetAreaId=1,neededJobIds=listOf("1"),labelIds=listOf("1"),texts=mapOf(DutyPublishField.Progress to "中文 & progress",DutyPublishField.Strategy to "Strategy",DutyPublishField.Schedule to "Schedule",DutyPublishField.Contact to "Contact")),true)
    private fun response(body:String,status:Int=200)=RisingStonesHttpResponse(status,emptyMap(),body.toByteArray())
    private fun client(block:suspend(RisingStonesHttpRequest)->RisingStonesHttpResponse)=RisingStonesPublicApiClient(object:RisingStonesHttpClient{override suspend fun execute(request:RisingStonesHttpRequest)=block(request)},listOf("https://fixture.invalid","https://must-never-replay.invalid"))
    @Test fun exactFormIsTextOnlyAndUsesIndependentTemporaryIds()=runBlocking {
        val session=Session();var calls=0;var next=0
        val api=DutyRecruitmentCreateApiPublisher(client{r->calls++;assertTrue(r.url.startsWith("https://fixture.invalid/api/home/recruit/createRecruitFb?tempsuid="));assertEquals(RisingStonesHttpMethod.Post,r.method)
            assertTrue(r.body!!.toString(Charsets.UTF_8).contains("progress=%E4%B8%AD%E6%96%87%20%26%20progress"));assertEquals("application/x-www-form-urlencoded; charset=UTF-8",r.contentType)
            val fields=r.body!!.toString(Charsets.UTF_8).split('&').associate{val p=it.split('=',limit=2);URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8")}
            assertEquals(setOf("fb_type","fb_name","target_area_id","progress","strategy","fb_time","need_job","contact_info","label","custom_label","team_detail","recruit_require","strategy_desc","team_composition","T","H","MT","ST","H1","H2","D1","D2","D3","D4","due_day","status","is_share","scope","tempsuid"),fields.keys)
            assertEquals("1",fields["status"]);assertEquals("0",fields["is_share"]);assertEquals("满编小队",fields["team_composition"]);assertEquals("0",fields["T"]);assertEquals("Contact",fields["contact_info"])
            assertFalse(r.url.endsWith(fields["tempsuid"]!!));response("{\"code\":10000,\"data\":\"901\"}")},session,catalog,temporarySessionId={"fixture-${++next}"})

        assertEquals(901,api.create(draft).recruitmentId);assertEquals(1,calls);assertEquals(1,session.completed);assertEquals(1,session.closed);assertEquals(0,session.refreshed)
    }
    @Test fun onlyExactNumericBusinessSuccessAndPositiveIdComplete()=runBlocking {
        for(body in listOf("{}","{\"code\":\"10000\",\"data\":{\"id\":1}}","{\"code\":10000}","{\"code\":10000,\"data\":{\"id\":0}}","not-json")){
            val s=Session();var calls=0;val api=DutyRecruitmentCreateApiPublisher(client{calls++;response(body)},s,catalog)
            assertNotNull(runCatching{api.create(draft)}.exceptionOrNull());assertEquals(1,calls);assertEquals(0,s.completed);assertEquals(1,s.closed)
        }
    }
    @Test fun authorizationAndBusinessRefusalNeverRefreshOrReplayPost()=runBlocking {
        for(status in listOf(401,403,200)){
            val s=Session();var calls=0;val api=DutyRecruitmentCreateApiPublisher(client{calls++;response("{\"code\":10001,\"msg\":\"refused\"}",status)},s,catalog)
            assertTrue(runCatching{api.create(draft)}.exceptionOrNull() is DutyRecruitmentPublishRejected);assertEquals(1,calls);assertEquals(0,s.refreshed);assertEquals(0,s.completed)
        }
    }
    @Test fun transportFailureNeverFailsOverAndClosesScope()=runBlocking {
        val s=Session();var calls=0;val api=DutyRecruitmentCreateApiPublisher(client{calls++;throw java.io.IOException("fixture")},s,catalog)
        assertNotNull(runCatching{api.create(draft)}.exceptionOrNull());assertEquals(1,calls);assertEquals(1,s.closed)
    }
    @Test fun credentialChangeDuringAuthorizationCannotSendWithNewSession()=runBlocking {
        val s=Session();s.onAuthorize={s.current=false};var calls=0;val api=DutyRecruitmentCreateApiPublisher(client{calls++;error("No network")},s,catalog)
        assertTrue(runCatching{api.create(draft)}.exceptionOrNull() is DutyRecruitmentPublishRejected);assertEquals(0,calls);assertEquals(0,s.completed)
    }
    @Test fun staleResponseAndCancellationCannotPublishSuccess()=runBlocking {
        val s=Session();val api=DutyRecruitmentCreateApiPublisher(client{s.current=false;response("{\"code\":10000,\"data\":901}")},s,catalog)
        assertTrue(runCatching{api.create(draft)}.exceptionOrNull() is CancellationException);assertEquals(0,s.completed)
        val c=Session();val cancel=DutyRecruitmentCreateApiPublisher(client{throw CancellationException("fixture")},c,catalog)
        assertTrue(runCatching{cancel.create(draft)}.exceptionOrNull() is CancellationException);assertEquals(1,c.closed)
    }
    @Test fun validationFieldAndUnlistedAllianceFormRemainExact()=runBlocking {
        val s=Session();var calls=0
        val f=draft.form.copy(dutyType="其他",customDutyName="Alliance",dutyName="",customComposition=DutyTeamComposition.Alliance,occupiedJobs=mapOf("A.MT" to "1"))
        val api=DutyRecruitmentCreateApiPublisher(client{r -> calls++
            val fields=r.body!!.toString(Charsets.UTF_8).split('&').associate{val p=it.split('=',limit=2);URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8")}
            assertEquals("0",fields["status"]);assertEquals("团队",fields["team_composition"]);assertFalse(fields.containsKey("T"));assertTrue(fields["team_position"]!!.contains("\"MT\":\"1\""))
            response("{\"code\":10003,\"data\":{\"field\":\"need_job\",\"msg\":\"Required\"}}")},s,catalog)
        val error=runCatching{api.create(DutyRecruitmentCreateDraft(f,false))}.exceptionOrNull() as DutyRecruitmentPublishRejected
        assertEquals(DutyPublishField.Needed,error.field);assertEquals("Required",error.message);assertEquals(1,calls)
        assertTrue(runCatching{api.create(draft.copy(form=draft.form.copy(sharesToDynamics=true)))}.isFailure);assertEquals(1,calls)
    }

}
