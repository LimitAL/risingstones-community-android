package top.cxmeow.risingstones.feature.dynamic.domain
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class DynamicEntryLikeAbiTest {
    @Test fun originalEntryDescriptorsAndAllMasksRetainRawFacts() {
        val type=DynamicEntry::class.java;val fields=arrayOf(Int::class.javaPrimitiveType,DynamicAuthor::class.java,String::class.java,List::class.java,Instant::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,DynamicReference::class.java)
        val a=DynamicAuthor("mock","Mock","","",null);val args=arrayOf<Any?>(17,a,"Mock",emptyList<String>(),null,2,3,false,null)
        val legacy=type.getConstructor(*fields).newInstance(*args);assertNull(legacy.sourceLikeFacts)
        val facts=DynamicSourceLikeFacts(null,7);val source=legacy.copy(sourceLikeFacts=facts)
        assertSame(facts,(type.getMethod("copy",*fields).invoke(source,*args)as DynamicEntry).sourceLikeFacts)
        val bridge=type.getMethod("copy\$default",type,*fields,Int::class.javaPrimitiveType,Any::class.java)
        val changed=arrayOf<Any?>(18,a.copy(name="Other"),"Changed",listOf("mock-image"),Instant.EPOCH,4,5,true,null)
        val components=(1..9).map{type.getMethod("component$it")}
        for(mask in 0..511){val result=bridge.invoke(null,source,*changed,mask,null)as DynamicEntry;assertSame(facts,result.sourceLikeFacts)
            components.forEachIndexed{i,m->assertEquals(if(mask and (1 shl i)!=0)m.invoke(source)else changed[i],m.invoke(result))}}
        assertNull(source.copy(sourceLikeFacts=null).sourceLikeFacts)
    }
}
