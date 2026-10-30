package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import top.cxmeow.risingstones.feature.dynamic.domain.*

/** Local editing only; no API fixtures, credentials or transport. */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicMentionRemovalTest {
 @get:Rule val main=MainDispatcherRule()
 private val a=DynamicCommentMention("synthetic-a","Same")
 private val b=DynamicCommentMention("synthetic-b","Same")
 private fun DynamicCommentEditorState.add(m:DynamicCommentMention)=insert("@${m.characterName} ",m)
 @Test fun duplicateUuidIsRemovedEverywhereButOrdinaryTextIsPreserved(){
  val prefix="ordinary @Same 🙂 + ";val s=DynamicCommentEditorState(prefix,prefix.length,prefix.length).add(a).insert("mid ").add(b).add(a).insert("tail")
  val r=s.removeMention(a.uuid);assertEquals(prefix+"mid @Same tail",r.text);assertEquals(listOf(b),r.prepareComment().mentions);assertEquals(prefix.length,r.selectionStart);assertEquals(r.selectionStart,r.selectionEnd)
 }
 @Test fun sameNameDifferentUuidAndRenamedSameUuidUseIdentity(){
  val renamed=a.copy(characterName="New");val r=DynamicCommentEditorState().add(a).add(renamed).add(b).removeMention(a.uuid)
  assertEquals("@Same ",r.text);assertEquals(listOf(b),r.preparePublishingContent().mentions)
 }
 @Test fun unicodeCombiningAndEmojiOffsetsAndRemainingRangesStayValid(){
  val prefix="🙂e\u0301👩‍💻 ";val m=a.copy(characterName="猫🐈")
  val s=DynamicCommentEditorState(prefix,prefix.length,prefix.length).add(m).insert("tail\n").add(b)
  val r=s.removeMention(a.uuid);assertEquals(prefix+"tail\n@Same ",r.text);assertEquals(prefix.length,r.selectionStart);assertTrue(r.mentions.single().matches(r.text));assertEquals(listOf(b),r.prepareComment().mentions)
 }
 @Test fun consumesExactlyOneAsciiSpaceAndNeverTabNbspOrNewline(){
  for((suffix,expected)in listOf("  \n" to " \n","\t\n" to "\t\n","\u00a0" to "\u00a0","\n" to "\n","" to "")){
   val s=DynamicCommentEditorState().insert("@Same",a).insert(suffix);assertEquals(expected,s.removeMention(a.uuid).text)
  }
 }
 @Test fun missingAndBlankIdentityAreNoOps(){val s=DynamicCommentEditorState().add(a);assertEquals(s,s.removeMention("other"));assertSame(s,s.removeMention(""))}
 @Test fun staleAndOverlappingMetadataCannotDeleteUnselectedText(){
  val x=DynamicCommentMention("synthetic-x","Other");val y=DynamicCommentMention("synthetic-y","Beta")
  val s=DynamicCommentEditorState("@Same @Beta ",12,12,listOf(DynamicCommentMentionRange(0,5,a),DynamicCommentMentionRange(0,5,x),DynamicCommentMentionRange(6,11,y),DynamicCommentMentionRange(-1,100,a)))
  val r=s.removeMention(a.uuid);assertEquals("@Beta ",r.text);assertEquals(listOf(y),r.prepareComment().mentions)
 }
 @Test fun editedMentionBecomesOrdinaryAndIsNotRemovedByOldUuid(){
  val s=DynamicCommentEditorState().add(a).edit("@Other ",7,7,DynamicCommentTextChange(1,5));assertEquals("@Other ",s.removeMention(a.uuid).text);assertTrue(s.removeMention(a.uuid).mentions.isEmpty())
 }
 @Test fun bothViewModelsProtectSubmittingAndCompletedDrafts()=runTest {
  val gate=CompletableDeferred<Unit>();val actions=Actions(gate)
  val publishing=object:DynamicPublishingService{override suspend fun publish(scope:DynamicActionScope,draft:DynamicPublishDraft){gate.await()};override suspend fun relayPost(scope:DynamicActionScope,draft:DynamicPostRelayDraft)=error("No relay")}
  val p=DynamicPublishingViewModel(actions,publishing);val c=DynamicActionViewModel(actions);val store=ViewModelStore().apply{put("p",p);put("c",c)}
  try{p.initialize();c.bind(7);advanceUntilIdle();c.openComment();p.selectMention(a);c.selectMention(a);p.submit();c.submit();runCurrent()
   assertTrue(p.state.value.isSubmitting);assertTrue(c.state.value.isSubmitting);p.removeMention(a.uuid);c.removeMention(a.uuid);assertEquals("@Same ",p.state.value.editor.text);assertEquals("@Same ",c.state.value.editor.text)
   gate.complete(Unit);advanceUntilIdle();assertNotNull(p.state.value.completedId);val completed=p.state.value;p.removeMention(a.uuid);assertEquals(completed,p.state.value)
  }finally{gate.complete(Unit);store.clear()}
 }
 private class Actions(private val gate:CompletableDeferred<Unit>):DynamicActionService {
  override val canPerformAuthenticatedWrites=true;override val canAttemptAuthenticatedWrites=true
  override suspend fun beginActionScope()=object:DynamicActionScope{override suspend fun isCurrent()=true;override fun close(){}}
  override suspend fun entryEligibility(scope:DynamicActionScope,dynamicId:Int)=DynamicEntryActionEligibility(dynamicId,DynamicActionEligibility.Unknown)
  override suspend fun comment(scope:DynamicActionScope,draft:DynamicCommentDraft){gate.await()}
  override suspend fun commentEligibility(scope:DynamicActionScope,commentId:Int)=error("No eligibility read")
  override suspend fun fetchComments(scope:DynamicActionScope,dynamicId:Int,query:DynamicListQuery)=error("No read")
  override suspend fun fetchReplies(scope:DynamicActionScope,rootParentId:Int,query:DynamicListQuery)=error("No read")
  override suspend fun fetchMentionCandidates(scope:DynamicActionScope,query:DynamicListQuery)=error("No candidates")
  override suspend fun toggleDynamicLike(scope:DynamicActionScope,dynamicId:Int)=error("No like")
  override suspend fun deleteOwnComment(scope:DynamicActionScope,commentId:Int)=error("No delete")
  override suspend fun deleteOwnDynamic(scope:DynamicActionScope,dynamicId:Int)=error("No delete")
 }
}
