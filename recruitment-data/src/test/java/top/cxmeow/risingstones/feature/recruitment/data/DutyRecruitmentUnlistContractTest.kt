package top.cxmeow.risingstones.feature.recruitment.data

import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.*

@OptIn(ExperimentalCoroutinesApi::class)
class DutyRecruitmentUnlistContractTest {
 private class F:RisingStonesSessionProvider,RisingStonesCapabilityScopeProvider {
  var current=true;var refreshed=0;var completed=0;var captures=0;var status=200;var body="""{"code":10000,"data":null}"""
  var authorize:()->Unit={};var sending:suspend()->Unit={};val requests=mutableListOf<RisingStonesHttpRequest>()
  override val capabilities=setOf(RisingStonesCapability.RecruitmentWrite)
  override suspend fun currentAuthorizer():RisingStonesRequestAuthorizer?=error("Scope required")
  override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer?{refreshed++;error("No refresh")}
  override suspend fun captureCapabilityScope(capabilities:Set<RisingStonesCapability>):RisingStonesCapabilityScope? {
   assertEquals(this.capabilities,capabilities);captures++;if(!current)return null
   return object:RisingStonesCapabilityScope {
    override val authorizer=RisingStonesRequestAuthorizer{_,_->error("No extra reads")}
    override suspend fun isCurrent()=current
    override fun close(){}
    override suspend fun beginCapabilityAttempt(context:RisingStonesRequestContext):RisingStonesCapabilityAttempt {
     assertEquals("api/home/recruit/shelveRecruitFb",context.path);assertEquals(RisingStonesCapability.RecruitmentWrite,context.capability);assertEquals(RisingStonesAuthenticationRequirement.Required,context.requirement)
     return object:RisingStonesCapabilityAttempt,RisingStonesCapabilityAttemptGuard {
      override val authorizer=RisingStonesRequestAuthorizer{_,sink->sink.set("X-Fixture","synthetic");authorize()}
      override suspend fun isCurrent()=current
      override suspend fun complete():Boolean{completed++;return current}
      override fun close(){}
     }
    }
   }
  }
  val api=DutyRecruitmentUnlistApiService(RisingStonesPublicApiClient(object:RisingStonesHttpClient{
   override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
    check(URI(request.url).host in setOf("fixture.invalid","second.invalid"));requests+=request;sending()
    return RisingStonesHttpResponse(status,emptyMap(),body.toByteArray())
   }
  },listOf("https://fixture.invalid","https://second.invalid")),this,{"synthetic-attempt"})
 }
 @Test fun exactPutJsonQueryAndStrictSuccessWithNullData()=runTest {
  val f=F();f.api.unlist(71);val r=f.requests.single();assertEquals(RisingStonesHttpMethod.Put,r.method);assertEquals("/api/home/recruit/shelveRecruitFb",URI(r.url).path)
  assertEquals("tempsuid=synthetic-attempt",URI(r.url).query);assertEquals("application/json",r.contentType)
  val b=Json.parseToJsonElement(requireNotNull(r.body).toString(Charsets.UTF_8)).jsonObject;assertEquals(setOf("id","status"),b.keys);assertEquals(71,b.getValue("id").jsonPrimitive.int);assertFalse(b.getValue("id").jsonPrimitive.isString);assertEquals(JsonPrimitive("0"),b["status"])
  assertEquals(1,f.completed);assertEquals(0,f.refreshed)
 }
 @Test fun malformedAuthenticationAndTransportOutcomesNeverReplay()=runTest {
  for(body in listOf("{}","not-json","""{"code":"10000"}""","""{"code":10002}""","""{"code":10003}""","""{"code":401}""")){
   val f=F();f.body=body;val error=runCatching{f.api.unlist(71)}.exceptionOrNull();if(body.contains("10002"))assertTrue(error is DutyUnlistRejected)else assertTrue(error.toString(),error is DutyUnlistUnconfirmed)
   assertEquals(1,f.requests.size);assertEquals(0,f.refreshed)
  }
  for(status in listOf(401,403,500)){val f=F();f.status=status;assertTrue(runCatching{f.api.unlist(71)}.exceptionOrNull() is DutyUnlistUnconfirmed);assertEquals(1,f.requests.size)}
  val f=F();f.sending={error("Offline synthetic")};assertTrue(runCatching{f.api.unlist(71)}.exceptionOrNull() is DutyUnlistUnconfirmed);assertEquals(1,f.requests.size)
 }
 @Test fun businessRefusalAndInvalidOrMissingActorDoNotBecomeSuccess()=runTest {
  val f=F();f.body="""{"code":12345}""";assertTrue(runCatching{f.api.unlist(71)}.exceptionOrNull() is DutyUnlistRejected);assertEquals(0,f.completed)
  for(id in listOf(0,-1)){val bad=F();assertTrue(runCatching{bad.api.unlist(id)}.isFailure);assertTrue(bad.requests.isEmpty());assertEquals(0,bad.captures)}
  val missing=F();missing.current=false;assertTrue(runCatching{missing.api.unlist(71)}.exceptionOrNull() is DutyUnlistRejected);assertTrue(missing.requests.isEmpty())
 }
 @Test fun actorChangesBeforeAndAfterDispatchAndCancellationAreBound()=runTest {
  val early=F();early.authorize={early.current=false};assertTrue(runCatching{early.api.unlist(71)}.isFailure);assertTrue(early.requests.isEmpty())
  val late=F();late.sending={late.current=false};assertTrue(runCatching{late.api.unlist(71)}.exceptionOrNull() is CancellationException);assertEquals(1,late.requests.size);assertEquals(0,late.completed)
  val cancelled=F();val gate=CompletableDeferred<Unit>();cancelled.sending={withContext(NonCancellable){gate.await()}}
  val job=launch{cancelled.api.unlist(71)};runCurrent();job.cancel();gate.complete(Unit);job.join();assertTrue(job.isCancelled);assertEquals(1,cancelled.requests.size);assertEquals(0,cancelled.completed)
 }
}
