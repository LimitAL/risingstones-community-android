package top.cxmeow.risingstones.feature.dynamic.presentation

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.*
import org.junit.Assert.*
import top.cxmeow.risingstones.feature.dynamic.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicPublishingScopeFactoryTest {
 @get:Rule val dispatcher=MainDispatcherRule()
 private class Scope:DynamicActionScope {var closed=false;override suspend fun isCurrent()=!closed;override fun close(){closed=true}}
 @Test fun textToExplicitImagesRebindsOnlyAfterSelectionAndRetainsNormalRetryScope()=runTest {
  val flags=mutableListOf<Boolean>();val scopes=mutableListOf<Scope>();var posts=0;var uploads=0
  val actions=Proxy.newProxyInstance(DynamicActionService::class.java.classLoader,arrayOf(DynamicActionService::class.java,DynamicPublishingScopeFactory::class.java)){_,m,args->when(m.name){
   "getCanPerformAuthenticatedWrites","getCanAttemptAuthenticatedWrites"->true
   "beginPublishingScope"->{flags+=args!![0] as Boolean;Scope().also{scopes+=it}}
   else->error("Unexpected scope factory method ${m.name}")
  }} as DynamicActionService
  val publisher=object:DynamicPublishingService{override suspend fun publish(scope:DynamicActionScope,draft:DynamicPublishDraft){posts++;if(posts<3)throw DynamicException.Business(12345)};override suspend fun relayPost(scope:DynamicActionScope,draft:DynamicPostRelayDraft)=error("No relay")}
  val images=object:DynamicImageUploadService,DynamicPublishingImageUploadService {
   override val canUploadImages=true;override val canAttemptImageUpload=true
   override suspend fun uploadCommentImage(scope:DynamicActionScope,input:DynamicImageUploadInput)=error("No comment")
   override suspend fun uploadPublishingImage(scope:DynamicActionScope,input:DynamicImageUploadInput):DynamicUploadedImage{uploads++;return object:DynamicUploadedImage{}}
  }
  val model=DynamicPublishingViewModel(actions,publisher,images);model.updateEditor("text",4,4);model.submit();runCurrent();assertEquals(listOf(false),flags)
  model.addImages(listOf(DynamicPublishingImageSource{DynamicImageUploadInput(byteArrayOf(1),"image/png")}));model.submit();runCurrent();assertEquals(listOf(false,true),flags);assertTrue(scopes.first().closed);assertEquals(1,uploads)
  model.submit();runCurrent();assertEquals(listOf(false,true),flags);assertEquals(1,uploads);assertEquals(3,posts);assertNotNull(model.state.value.completedId)
 }
 @Test fun lateReadFromRetiredTextScopeCannotEraseNewImageResult()=runTest {
  val gate=kotlinx.coroutines.CompletableDeferred<Unit>();val flags=mutableListOf<Boolean>()
  val base=Proxy.newProxyInstance(DynamicActionService::class.java.classLoader,arrayOf(DynamicActionService::class.java)){_,m,_->when(m.name){"getCanPerformAuthenticatedWrites","getCanAttemptAuthenticatedWrites"->true;else->error("Unexpected ${m.name}")}} as DynamicActionService
  val actions=object:DynamicActionService by base,DynamicPublishingScopeFactory {
   override suspend fun beginPublishingScope(includeImages:Boolean):DynamicActionScope{flags+=includeImages;return Scope()}
   override suspend fun fetchMentionCandidates(scope:DynamicActionScope,query:DynamicListQuery):DynamicPage<DynamicCommentMention>{kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){gate.await()};return DynamicPage(emptyList(),1,false)}
  }
  val publishing=object:DynamicPublishingService{override suspend fun publish(scope:DynamicActionScope,draft:DynamicPublishDraft){};override suspend fun relayPost(scope:DynamicActionScope,draft:DynamicPostRelayDraft)=error("No relay")}
  val images=object:DynamicImageUploadService,DynamicPublishingImageUploadService{override val canUploadImages=true;override val canAttemptImageUpload=true;override suspend fun uploadCommentImage(scope:DynamicActionScope,input:DynamicImageUploadInput)=error("No comment");override suspend fun uploadPublishingImage(scope:DynamicActionScope,input:DynamicImageUploadInput):DynamicUploadedImage=object:DynamicUploadedImage{}}
  val model=DynamicPublishingViewModel(actions,publishing,images);model.loadMentions(true);runCurrent()
  model.addImages(listOf(DynamicPublishingImageSource{DynamicImageUploadInput(byteArrayOf(1),"image/png")}));model.submit();runCurrent();assertNotNull(model.state.value.completedId)
  gate.complete(Unit);runCurrent();assertEquals(listOf(false,true),flags);assertNotNull(model.state.value.completedId);assertNull(model.state.value.error)
 }

}
