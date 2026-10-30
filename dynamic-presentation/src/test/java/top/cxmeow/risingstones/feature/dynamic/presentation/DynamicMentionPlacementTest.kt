package top.cxmeow.risingstones.feature.dynamic.presentation

import org.junit.Test
import org.junit.Assert.*
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentMention

class DynamicMentionPlacementTest {
    private val a=DynamicCommentMention("synthetic-a","晨星")
    private val b=DynamicCommentMention("synthetic-b","猫猫")
    @Test fun toolbarSeparatesPreviousNonWhitespaceAndTracksOnlyTheName(){
        val r=DynamicCommentEditorState("Hello",5,5).previewMentionInsertion(a)
        assertEquals("Hello @晨星 ",r.text);assertEquals(6,r.mentions.single().start);assertEquals(9,r.mentions.single().end);assertEquals(10,r.selectionEnd)
    }
    @Test fun whitespaceAndBeginningDoNotGainAnotherSeparator(){
        for(prefix in listOf(""," ","\n","\t","\u00a0","\u0085","\u3000"))assertEquals(prefix+"@晨星 ",DynamicCommentEditorState(prefix,prefix.length,prefix.length).previewMentionInsertion(a).text)
    }
    @Test fun forwardAndReverseSelectionPreserveOriginalBody(){
        for((start,end)in listOf(2 to 5,5 to 2))assertEquals("Hello @晨星  tail",DynamicCommentEditorState("Hello tail",start,end).previewMentionInsertion(a).text)
    }
    @Test fun caretWithinVerifiedNamePreservesItsIdentity(){
        val s=DynamicCommentEditorState().insert("@晨星 ",a);val r=s.copy(selectionStart=1,selectionEnd=2).previewMentionInsertion(b)
        assertEquals("@晨星 @猫猫 ",r.text);assertEquals(listOf(a,b),r.prepareComment().mentions)
    }
    @Test fun typedAtHasPriorityWithoutExtraLeadingSpace(){
        for(at in listOf('@','＠')){val r=DynamicCommentEditorState("Hi"+at+" tail",3,3).previewMentionInsertion(a,2);assertEquals("Hi@晨星  tail",r.text);assertEquals(2,r.mentions.single().start)}
    }
    @Test fun invalidOrSelectedAtIsNotAReplacementAuthority(){
        assertEquals("HiX @晨星 ",DynamicCommentEditorState("HiX",3,3).previewMentionInsertion(a,2).text)
        assertEquals("Hi@ @晨星 ",DynamicCommentEditorState("Hi@",0,3).previewMentionInsertion(a,2).text)
        val s=DynamicCommentEditorState().insert("@晨星 ",a);assertEquals("@晨星 @猫猫 ",s.copy(selectionStart=1,selectionEnd=1).previewMentionInsertion(b,0).text)
    }
    @Test fun staleNameIsNotProtectedAndPreviewIsImmutable(){
        val s=DynamicCommentEditorState("wrong",2,2,listOf(DynamicCommentMentionRange(0,3,a)));val before=s.copy();val r=s.previewMentionInsertion(b)
        assertEquals("wr @猫猫 ong",r.text);assertEquals(before,s);assertEquals(listOf(b),r.prepareComment().mentions)
    }
    @Test fun unicodeBoundaryAndQuotaCountPreserveWholeOriginalText(){
        val s=DynamicCommentEditorState("👩 tail",1,1);assertEquals("@晨星 👩 tail",s.previewMentionInsertion(a).text)
        val full="a".repeat(1000);assertEquals(1005,DynamicCommentEditorState(full,0,1000).previewMentionInsertion(a).text.length)
    }
}
