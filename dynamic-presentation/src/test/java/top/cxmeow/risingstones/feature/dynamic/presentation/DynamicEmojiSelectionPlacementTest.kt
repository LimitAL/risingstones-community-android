package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import top.cxmeow.risingstones.feature.dynamic.domain.*

/** Selection is an insertion anchor, never a request to erase text; no clients or sending. */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicEmojiSelectionPlacementTest {
    @get:Rule val main=MainDispatcherRule()
    private val a=DynamicCommentMention("synthetic-a","晨星")
    private fun selected()=DynamicCommentEditorState().insert("@晨星 ",a)
    @Test fun forwardSelectionInsertsAfterItsEndWithoutReplacingSelectedText(){
        val s=DynamicCommentEditorState("before SELECT after",7,13);val r=requireNotNull(s.previewEmojiInsertion(1))
        assertEquals("before SELECT[emo1] after",r.text);assertEquals(19,r.selectionStart);assertEquals(19,r.selectionEnd)
    }
    @Test fun reversedSelectionUsesTheSameUpperBoundary(){
        val f=DynamicCommentEditorState("before SELECT after",7,13);val r=f.copy(selectionStart=13,selectionEnd=7)
        assertEquals(f.previewEmojiInsertion(1),r.previewEmojiInsertion(1))
        assertEquals("before SELECT[emo1] after",requireNotNull(r.previewEmojiInsertion(1)).text)
    }
    @Test fun selectionEndingInsideASelectedNamePreservesTheEntireNameAndIdentity(){
        val r=requireNotNull(selected().copy(selectionStart=0,selectionEnd=2).previewEmojiInsertion(1));assertEquals("@晨星 [emo1]",r.text);assertEquals(listOf(a),r.prepareComment().mentions);assertEquals(10,r.selectionStart)
    }
    @Test fun selectingTheWholeMentionKeepsItAtExactEndBoundaries(){
        for((end,expected)in listOf(3 to "@晨星[emo1] ",4 to "@晨星 [emo1]")){
            val s=selected().copy(selectionStart=0,selectionEnd=end);val r=requireNotNull(s.previewEmojiInsertion(1));assertEquals(expected,r.text);assertEquals(s.mentions,r.mentions);assertEquals(listOf(a),r.preparePublishingContent().mentions)
        }
    }
    @Test fun supplementaryPrefixAndCombiningSelectionKeepEveryOriginalCodeUnit(){
        val prefix="👩‍💻 ";val selected="e\u0301";val tail=" tail";val s=DynamicCommentEditorState(prefix+selected+tail,8,6)
        val r=requireNotNull(s.previewEmojiInsertion(46));assertEquals(prefix+selected+"[emo46]"+tail,r.text);assertEquals(15,r.selectionStart)
    }
    @Test fun previewDoesNotChangeTheSourceSelectionOrFlagsAndInvalidNumbersStayNull(){
        val s=DynamicCommentEditorState("selected",0,8,emojiPickerOpen=true);val before=s.copy();val r=requireNotNull(s.previewEmojiInsertion(1));assertEquals(before,s);assertTrue(r.emojiPickerOpen);assertEquals("selected[emo1]",r.text)
        for(n in listOf(0,47))assertNull(s.previewEmojiInsertion(n));assertEquals(before,s)
    }
    @Test fun quotaPreviewMustNotSubtractTheHighlightedText(){
        val full="a".repeat(999)+"Z";val r=requireNotNull(DynamicCommentEditorState(full,0,6).previewEmojiInsertion(1))
        assertEquals(1006,r.text.length);assertEquals(full.take(6)+"[emo1]"+full.drop(6),r.text);assertTrue(r.text.endsWith("Z"))
    }
    @Test fun bothPublicViewModelsPreserveSelectedTextWithoutSending()=runTest {
        val actions=Actions();val publishing=object:DynamicPublishingService{override suspend fun publish(scope:DynamicActionScope,draft:DynamicPublishDraft)=error("No publishing");override suspend fun relayPost(scope:DynamicActionScope,draft:DynamicPostRelayDraft)=error("No relay")}
        val p=DynamicPublishingViewModel(actions,publishing);val c=DynamicActionViewModel(actions);val store=ViewModelStore().apply{put("p",p);put("c",c)}
        try{p.initialize();c.bind(7);advanceUntilIdle();c.openComment();p.updateEditor("before SELECT after",7,13);c.updateEditor("before SELECT after",13,7);p.insertEmoji(1);c.insertEmoji(1)
            assertEquals("before SELECT[emo1] after",p.state.value.editor.text);assertEquals(p.state.value.editor.text,c.state.value.editor.text)
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
