package top.cxmeow.risingstones.feature.dynamic.presentation

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentMention

/** User deletion only, with explicit UTF-16 changes; no transport or credentials. */
class DynamicMentionAtomicDeletionTest {
    private val a=DynamicCommentMention("synthetic-a","晨星")
    private val b=DynamicCommentMention("synthetic-b","甜")
    private fun DynamicCommentEditorState.add(m:DynamicCommentMention)=insert("@${m.characterName} ",m)
    private fun DynamicCommentEditorState.delete(from:Int,to:Int)=edit(text.removeRange(from,to),from,from,DynamicCommentTextChange(from,to))
    @Test fun deletingOneNameCharacterRemovesTheWholeSelectedSpanButKeepsFollowingSpace(){
        val s=DynamicCommentEditorState().add(a).insert("好的");val r=s.delete(2,3)
        assertEquals(" 好的",r.text);assertEquals(0,r.selectionStart);assertEquals(0,r.selectionEnd);assertTrue(r.mentions.isEmpty());assertEquals("<p>好的</p>",r.prepareComment().html)
    }
    @Test fun deletingOnlyTheAtSignAlsoRemovesItsSelectedName(){
        val r=DynamicCommentEditorState().add(a).insert("tail").delete(0,1)
        assertEquals(" tail",r.text);assertEquals(0,r.selectionStart);assertTrue(r.mentions.isEmpty())
    }
    @Test fun deletingAcrossTwoMentionsRemovesBothLeftoversAsIosDoes(){
        val s=DynamicCommentEditorState().add(a).add(b);assertEquals("@晨星 @甜 ",s.text)
        val r=s.delete(2,5);assertEquals(" ",r.text);assertEquals(0,r.selectionStart);assertTrue(r.mentions.isEmpty())
    }
    @Test fun supplementaryCharactersAndOtherRecipientsKeepValidOffsets(){
        val prefix="👩‍💻 ";val m=a.copy(characterName="猫🙂");val s=DynamicCommentEditorState(prefix,prefix.length,prefix.length).add(m).insert("tail ").add(b)
        val span=s.mentions.first();val r=s.delete(span.end-2,span.end)
        assertEquals(prefix+" tail @甜 ",r.text);assertEquals(prefix.length,r.selectionStart);assertEquals(listOf(b),r.prepareComment().mentions);assertTrue(r.mentions.single().matches(r.text))
    }
    @Test fun deletingOnlyASeparatorOrOrdinaryLookalikeNeverDeletesAMention(){
        val s=DynamicCommentEditorState().add(a).insert("tail");val r=s.delete(3,4)
        assertEquals("@晨星tail",r.text);assertEquals(listOf(a),r.prepareComment().mentions)
        val ordinary=DynamicCommentEditorState("@晨星 tail",3,3).delete(2,3);assertEquals("@晨 tail",ordinary.text);assertTrue(ordinary.mentions.isEmpty())
    }
    @Test fun ambiguousRepeatedAtDeletionKeepsTheSelectedToken(){
        val s=DynamicCommentEditorState("@",1,1).add(a);assertEquals("@@晨星 ",s.text)
        val r=s.edit("@晨星 ",0,0,DynamicCommentTextChange(1,2))
        assertEquals("@晨星 ",r.text);assertEquals(listOf(a),r.prepareComment().mentions);assertEquals(0,r.mentions.single().start)
    }
    @Test fun insertionAndMixedReplacementKeepTheirExistingOrdinaryTextRules(){
        val s=DynamicCommentEditorState().add(a)
        val inserted=s.edit("@晨X星 ",3,3,DynamicCommentTextChange(2,2));assertEquals("@晨X星 ",inserted.text);assertTrue(inserted.mentions.isEmpty())
        val replaced=s.edit("@Other ",7,7,DynamicCommentTextChange(1,3));assertEquals("@Other ",replaced.text);assertTrue(replaced.mentions.isEmpty())
    }
    @Test fun staleOrUnvalidatedMetadataCannotExpandAUserDeletion(){
        val s=DynamicCommentEditorState("@普通 tail",3,3,listOf(DynamicCommentMentionRange(0,3,a),DynamicCommentMentionRange(-1,99,a)))
        val r=s.delete(2,3);assertEquals("@普 tail",r.text);assertTrue(r.mentions.isEmpty())
        val invalid=s.edit("@普 tail",2,2,DynamicCommentTextChange(0,1));assertEquals("@普 tail",invalid.text)
    }
    @Test fun explicitReminderRemovalDoesNotShiftOntoIdenticalOrdinaryText(){
        val s=DynamicCommentEditorState().insert("@晨星 ").add(a);val r=s.removeMention(a.uuid)
        assertEquals("@晨星 ",r.text);assertTrue(r.mentions.isEmpty());assertTrue(r.preparePublishingContent().mentions.isEmpty())
    }
}
