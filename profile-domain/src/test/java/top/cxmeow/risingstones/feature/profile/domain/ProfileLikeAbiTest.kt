package top.cxmeow.risingstones.feature.profile.domain
import org.junit.Assert.*
import org.junit.Test
class ProfileLikeAbiTest {
    @Test fun originalContentDescriptorsAndAllMasksRetainRawFacts() {
        val type=ProfileListItem.Content::class.java;val fields=arrayOf(ProfileContentTarget::class.java,String::class.java,String::class.java,List::class.java,String::class.java)
        val target=ProfileContentTarget(ProfileContentKind.Dynamic,17);val args=arrayOf<Any?>(target,"Mock","Body",emptyList<String>(),null)
        val old=type.getConstructor(*fields).newInstance(*args);assertNull(old.sourceLikeFacts)
        val facts=ProfileDynamicLikeFacts(null,7);val source=old.copy(sourceLikeFacts=facts)
        assertSame(facts,(type.getMethod("copy",*fields).invoke(source,*args)as ProfileListItem.Content).sourceLikeFacts)
        val bridge=type.getMethod("copy\$default",type,*fields,Int::class.javaPrimitiveType,Any::class.java)
        val changed=arrayOf<Any?>(target.copy(id=18),"Changed","Other",listOf("mock-image"),"mock-time")
        for(mask in 0..31){val result=bridge.invoke(null,source,*changed,mask,null)as ProfileListItem.Content;assertSame(facts,result.sourceLikeFacts)
            (1..5).forEach{val m=type.getMethod("component$it");assertEquals(if(mask and(1 shl(it-1))!=0)m.invoke(source)else changed[it-1],m.invoke(result))}}
        assertNull(source.copy(sourceLikeFacts=null).sourceLikeFacts)
    }
}
