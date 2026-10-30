package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import top.cxmeow.risingstones.feature.dynamic.domain.*

/** Pure nonmutating preview and local editor commands; never submits or constructs an HTTP client. */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicEmojiCaretPlacementTest {
    @get:Rule val main=MainDispatcherRule()
    private val a=DynamicCommentMention("synthetic-a","晨星")
    private val b=DynamicCommentMention("synthetic-b","甜")
    private fun selected()=DynamicCommentEditorState().insert("@晨星 ",a)
    @Test fun collapsedCaretInsideSelectedNameMovesAfterItAndOneAsciiSpace(){
        val s=selected().copy(selectionStart=1,selectionEnd=1);val r=requireNotNull(s.previewEmojiInsertion(1))
        assertEquals("@晨星 [emo1]",r.text);assertEquals(10,r.selectionStart);assertEquals(10,r.selectionEnd);assertEquals(listOf(a),r.prepareComment().mentions)
    }
    @Test fun onlyOneFollowingAsciiSpaceIsSkipped(){
        for(suffix in listOf(""," ","  tail","\t","\u00a0","\n")){
            val s=DynamicCommentEditorState("@晨星"+suffix,1,1,listOf(DynamicCommentMentionRange(0,3,a)))
            val r=requireNotNull(s.previewEmojiInsertion(1));val expected=if(suffix.startsWith(" "))"@晨星 [emo1]"+suffix.drop(1)else "@晨星[emo1]"+suffix
            assertEquals(expected,r.text);assertEquals(listOf(a),r.preparePublishingContent().mentions)
        }
    }
    @Test fun exactBoundariesRemainTheirOriginalInsertionPoints(){
        for((point,expected)in listOf(0 to "[emo1]@晨星 ",3 to "@晨星[emo1] ",4 to "@晨星 [emo1]")){
            val r=requireNotNull(selected().copy(selectionStart=point,selectionEnd=point).previewEmojiInsertion(1));assertEquals(expected,r.text);assertEquals(listOf(a),r.prepareComment().mentions)
        }
    }
    @Test fun supplementaryNameAndPrefixPreserveOtherRecipientsAndUtf16Offsets(){
        val prefix="👩‍💻 ";val m=a.copy(characterName="猫🙂");val s=DynamicCommentEditorState(prefix,prefix.length,prefix.length).insert("@猫🙂 ",m).insert("tail ").insert("@甜 ",b).copy(selectionStart=9,selectionEnd=9)
        val point=s.mentions.first().end+1;val r=requireNotNull(s.previewEmojiInsertion(46));assertEquals(s.text.substring(0,point)+"[emo46]"+s.text.substring(point),r.text);assertEquals(point+7,r.selectionStart);assertEquals(listOf(m,b),r.prepareComment().mentions);assertTrue(r.mentions.all{it.matches(r.text)})
    }
    @Test fun ordinaryAtTextAndStaleMetadataNeverAcquireProtectionOrIdentity(){
        val plain=DynamicCommentEditorState("@晨星 ",1,1);val stale=plain.copy(mentions=listOf(DynamicCommentMentionRange(-1,99,a),DynamicCommentMentionRange(0,3,b)))
        for(s in listOf(plain,stale)){val r=requireNotNull(s.previewEmojiInsertion(1));assertEquals("@[emo1]晨星 ",r.text);assertTrue(r.prepareComment().mentions.isEmpty())}
    }
    @Test fun noncollapsedSelectionUsesItsEndAndPreservesTheSelectedName(){
        val r=requireNotNull(selected().copy(selectionStart=1,selectionEnd=2).previewEmojiInsertion(1));assertEquals("@晨星 [emo1]",r.text);assertEquals(listOf(a),r.prepareComment().mentions)
    }
    @Test fun previewIsImmutableAndInvalidNumbersReturnNull(){
        val s=selected().copy(selectionStart=1,selectionEnd=1,emojiPickerOpen=true);val original=s.copy();val r=requireNotNull(s.previewEmojiInsertion(1));assertEquals(original,s);assertTrue(r.emojiPickerOpen)
        for(n in listOf(-1,0,47,Int.MAX_VALUE))assertNull(s.previewEmojiInsertion(n));assertEquals(original,s)
    }
    @Test fun bothPublicViewModelsUseTheSamePreviewWithoutSending()=runTest {
        val actions=Actions();val publishing=object:DynamicPublishingService{override suspend fun publish(scope:DynamicActionScope,draft:DynamicPublishDraft)=error("No publishing");override suspend fun relayPost(scope:DynamicActionScope,draft:DynamicPostRelayDraft)=error("No relay")}
        val p=DynamicPublishingViewModel(actions,publishing);val c=DynamicActionViewModel(actions);val store=ViewModelStore().apply{put("p",p);put("c",c)}
        try{p.initialize();c.bind(7);advanceUntilIdle();c.openComment();p.selectMention(a);c.selectMention(a);p.updateEditor(p.state.value.editor.text,1,1);c.updateEditor(c.state.value.editor.text,1,1)
            p.insertEmoji(1);c.insertEmoji(1);assertEquals("@晨星 [emo1]",p.state.value.editor.text);assertEquals(p.state.value.editor.text,c.state.value.editor.text);assertEquals(listOf(a),p.state.value.editor.preparePublishingContent().mentions);assertEquals(listOf(a),c.state.value.editor.prepareComment().mentions)
            val beforeP=p.state.value;val beforeC=c.state.value;p.insertEmoji(0);c.insertEmoji(47);assertEquals(beforeP,p.state.value);assertEquals(beforeC,c.state.value)
        }finally{store.clear()}
    }
    private class Actions:DynamicActionService {
        override val canPerformAuthenticatedWrites=true;override val canAttemptAuthenticatedWrites=true
        override suspend fun beginActionScope()=object:DynamicActionScope{override suspend fun isCurrent()=true;override fun close(){}}
        override suspend fun entryEligibility(scope:DynamicActionScope,dynamicId:Int)=DynamicEntryActionEligibility(dynamicId,DynamicActionEligibility.Unknown)
        override suspend fun commentEligibility(scope:DynamicActionScope,commentId:Int)=error("No eligibility read")
        override suspend fun comment(scope:DynamicActionScope,draft:DynamicCommentDraft)=error("No comment")
        override suspend fun fetchComments(scope:DynamicActionScope,dynamicId:Int,query:DynamicListQuery)=error("No read")
        override suspend fun fetchReplies(scope:DynamicActionScope,rootParentId:Int,query:DynamicListQuery)=error("No read")
        override suspend fun fetchMentionCandidates(scope:DynamicActionScope,query:DynamicListQuery)=error("No candidates")
        override suspend fun toggleDynamicLike(scope:DynamicActionScope,dynamicId:Int)=error("No like")
        override suspend fun deleteOwnComment(scope:DynamicActionScope,commentId:Int)=error("No delete")
        override suspend fun deleteOwnDynamic(scope:DynamicActionScope,dynamicId:Int)=error("No delete")
    }
}
