package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.glamour.domain.*
import top.cxmeow.risingstones.network.*

/** Finite in-memory HTTP transport; no real authorizer, DNS, socket or account. */
class GlamourFollowSingleAttemptTest {
 private class Wire:RisingStonesHttpClient {
  var code=10000;var status=200;var cancel=false;var broken=false
  val requests=mutableListOf<RisingStonesHttpRequest>()
  override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
   val url=request.url.toHttpUrl();val path=url.encodedPath
   check(url.host in listOf("fixture.invalid","fallback.invalid"))
   check((path=="/api/home/userRelation/follow" && request.method==RisingStonesHttpMethod.Post) || (path=="/api/home/userRelation/cancelFollow" && request.method==RisingStonesHttpMethod.Put))
   requests+=request
   if(cancel)throw CancellationException()
   if(broken)throw java.io.IOException("Synthetic transport failure")
   return RisingStonesHttpResponse(status,emptyMap(),("{\"code\":$code,\"msg\":\"Synthetic rejection\",\"data\":null}").encodeToByteArray())
  }
 }
 private class Session:RisingStonesSessionProvider {
  override var capabilities=setOf(RisingStonesCapability.GlamourAuthenticated);var refreshes=0
  override suspend fun currentAuthorizer()=RisingStonesRequestAuthorizer{context,sink->
   check(context.capability==RisingStonesCapability.GlamourAuthenticated)
   sink.set("User-Agent","Synthetic fixture")
  }
  override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer{refreshes++;return currentAuthorizer()}
 }
 private suspend fun invoke(cancel:Boolean,service:GlamourApiService){if(cancel)service.cancelFollowAuthor("mock-user")else service.followAuthor("mock-user")}
 private fun api(w:Wire,s:Session)=GlamourApiService(RisingStonesPublicApiClient(w,listOf("https://fixture.invalid","https://fallback.invalid")),s,temporarySessionId="synthetic-attempt")
 @Test fun strictSuccessAndWireShapesRemainExact()=runTest {
  for(cancel in listOf(false,true)){
   val w=Wire();val s=Session();invoke(cancel,api(w,s));val r=w.requests.single();val url=r.url.toHttpUrl()
   assertEquals(if(cancel)RisingStonesHttpMethod.Put else RisingStonesHttpMethod.Post,r.method)
   assertEquals(setOf("tempsuid"),url.queryParameterNames);assertEquals("synthetic-attempt",url.queryParameter("tempsuid"));assertEquals(0,s.refreshes)
   val body=r.body!!.decodeToString()
   if(cancel)assertEquals("{\"follow_uuid\":\"mock-user\"}",body)else assertEquals("follow_uuid=mock-user&tempsuid=synthetic-attempt",body)
  }
 }
 @Test fun secondarySuccessCodeMustNotReportFollowOrUnfollowSuccess()=runTest {
  for(cancel in listOf(false,true)){
   val w=Wire().apply{code=10002};val s=Session()
   try{invoke(cancel,api(w,s));fail("Only 10000 confirms a relation mutation")}catch(e:GlamourException.Business){assertEquals(10002,e.code)}
   assertEquals(1,w.requests.size);assertEquals(0,s.refreshes)
  }
 }
 @Test fun httpAndBusinessAuthenticationFailuresNeverRefreshOrReplayMutation()=runTest {
  for(cancel in listOf(false,true))for((status,code)in listOf(401 to 10000,403 to 10000,200 to 10001,200 to 10105)){
   val w=Wire().apply{this.status=status;this.code=code};val s=Session()
   try{invoke(cancel,api(w,s));fail()}catch(_:GlamourException.AuthenticationRequired){}
   assertEquals("$cancel/$status/$code",1,w.requests.size);assertEquals(0,s.refreshes)
  }
 }
 @Test fun cancellationTransportFailureAndMissingCapabilityNeverCreateHiddenAttempts()=runTest {
  for(cancel in listOf(false,true)){
   val w=Wire().apply{this.cancel=true};val s=Session();try{invoke(cancel,api(w,s));fail()}catch(_:CancellationException){};assertEquals(1,w.requests.size);assertEquals(0,s.refreshes)
   w.cancel=false;w.broken=true;try{invoke(cancel,api(w,s));fail()}catch(_:GlamourTransportException){};assertEquals(2,w.requests.size);assertEquals(0,s.refreshes)
   s.capabilities=emptySet();try{invoke(cancel,api(w,s));fail()}catch(_:GlamourException.AuthenticationRequired){};assertEquals(2,w.requests.size)
  }
 }
}
