package top.cxmeow.risingstones.feature.dynamic.data

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.network.*

/** Independent strict-media contract; no historical dynamic test is edited. */
class DynamicMediaCreateContractTest {
 private class Fixture {
  var current=true;var calls=0;var completed=0;var body="""{"code":10000,"data":{"id":99}}""";var status=200;var after:()->Unit={}
  lateinit var request:RisingStonesHttpRequest
  val authorizer=RisingStonesRequestAuthorizer{_,sink->check(current);sink.set("Cookie","synthetic")}
  val provider=object:RisingStonesSessionProvider{override val capabilities=setOf(RisingStonesCapability.DynamicWrite)
   override suspend fun currentAuthorizer()=authorizer;override suspend fun refreshAuthorizer():RisingStonesRequestAuthorizer?=null}
  val delegate=object:RisingStonesCapabilityScope {
   override val authorizer=this@Fixture.authorizer;override suspend fun isCurrent()=current;override fun close(){current=false}
   override suspend fun beginCapabilityAttempt(context:RisingStonesRequestContext):RisingStonesCapabilityAttempt? {
    if(!current)return null
    check(context.path=="api/home/dynamic/create" && context.capability==RisingStonesCapability.DynamicWrite)
    return object:RisingStonesCapabilityAttempt,RisingStonesCapabilityAttemptGuard {
     override val authorizer=this@Fixture.authorizer;override suspend fun isCurrent()=current
     override suspend fun complete():Boolean{completed++;return current};override fun close(){}
    }
   }
  }
  val scope=OfficialDynamicActionScope(delegate,provider)
  val api=DynamicApiService(RisingStonesPublicApiClient(object:RisingStonesHttpClient {
   override suspend fun execute(r:RisingStonesHttpRequest):RisingStonesHttpResponse {
    check(r.method==RisingStonesHttpMethod.Post && URI(r.url).path=="/api/home/dynamic/create");request=r;calls++;after()
    return RisingStonesHttpResponse(status,emptyMap(),body.toByteArray())
   }
  },listOf("https://fixture.invalid","https://never-replay.invalid")),provider)
  fun image(n:Int,owner:OfficialDynamicActionScope=scope,p:DynamicImagePurpose=DynamicImagePurpose.Publishing)=object:BoundDynamicUploadedImage {
   override val actionScope=owner;override val url="https://ff14risingstones.gcloud.com.cn/dynamic/fixture/$n.png";override val purpose=p
  }
 }
 @Test fun imageOnlyOrderAndIndependentNoncesAreStrict()=runTest {
  val f=Fixture();val entry=f.api.publishImagesStrict(f.scope,DynamicPublishDraft("",DynamicVisibility.OnlyMe,listOf(DynamicCommentMention("explicit-uuid","Name")),listOf(f.image(2),f.image(1))))
  assertEquals(99,entry.id);val fields=f.request.body!!.decodeToString().split('&').associate{val p=it.split('=',limit=2);URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8")}
  assertEquals("",fields["content"]);assertEquals("3",fields["scope"]);assertEquals("explicit-uuid",fields["atInfo[0][uuid]"])
  assertEquals(listOf(f.image(2).url,f.image(1).url).joinToString(","),fields["pic_url"]);assertNotEquals(URI(f.request.url).rawQuery.substringAfter("tempsuid="),fields["tempsuid"]);assertEquals(1,f.calls)
 }
 @Test fun nonStrictSuccessOrMissingCreatedEntryNeverCompletes()=runTest {
  for(raw in listOf("{}","""{"code":200,"data":{"id":99}}""","""{"code":0,"data":{"id":99}}""","""{"code":"10000","data":{"id":99}}""","""{"code":10000,"data":null}""","""{"code":10000,"data":{"id":0}}""")) {
   val f=Fixture();f.body=raw;assertTrue(raw,runCatching{f.api.publishImagesStrict(f.scope,DynamicPublishDraft("",images=listOf(f.image(1))))}.isFailure);assertEquals(1,f.calls);assertEquals(0,f.completed)
  }
 }
 @Test fun foreignScopeAndCommentPurposeCannotWrite()=runTest {
  val f=Fixture();val other=Fixture()
  for(image in listOf(f.image(1,other.scope),f.image(1,p=DynamicImagePurpose.Comment)))assertTrue(runCatching{f.api.publishImagesStrict(f.scope,DynamicPublishDraft("",images=listOf(image)))}.isFailure)
  assertEquals(0,f.calls)
 }
 @Test fun authorizationRefusalAndLateIdentityDoNotReplayOrComplete()=runTest {
  for(status in listOf(401,403,500)){val f=Fixture();f.status=status;assertTrue(runCatching{f.api.publishImagesStrict(f.scope,DynamicPublishDraft("",images=listOf(f.image(1))))}.isFailure);assertEquals(1,f.calls);assertEquals(0,f.completed)}
  val f=Fixture();f.after={f.current=false};assertTrue(runCatching{f.api.publishImagesStrict(f.scope,DynamicPublishDraft("",images=listOf(f.image(1))))}.isFailure);assertEquals(0,f.completed)
 }
}
