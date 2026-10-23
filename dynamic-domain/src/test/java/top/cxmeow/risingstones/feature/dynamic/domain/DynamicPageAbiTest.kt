package top.cxmeow.risingstones.feature.dynamic.domain
import org.junit.Test
import org.junit.Assert.*
class DynamicPageAbiTest {
    private val type = DynamicPage::class.java
    private val fields = arrayOf(List::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
    @Test fun legacyConstructorsAndDestructuringRemainAvailable() {
        val page = type.getConstructor(*fields).newInstance(listOf("mock"),2,true,"cursor")
        assertNull(page.total)
        val oldDefault = type.getConstructor(*fields, Int::class.javaPrimitiveType, kotlin.jvm.internal.DefaultConstructorMarker::class.java)
        val defaultPage = oldDefault.newInstance(emptyList<Any>(),1,false,"ignored",8,null)
        assertNull(defaultPage.pageTime); assertNull(defaultPage.total)
        val (rows,number,more,cursor) = page
        assertEquals(listOf("mock"),rows);assertEquals(2,number);assertTrue(more);assertEquals("cursor",cursor)
    }
    @Test fun originalCopyAndEveryDefaultMaskPreserveSourceTotal() {
        val source = DynamicPage(listOf("mock"),2,true,"cursor",41)
        val copy = type.getMethod("copy",*fields)
        assertEquals(41,(copy.invoke(source,emptyList<Any>(),3,false,null) as DynamicPage<*>).total)
        val defaults = type.getMethod("copy\$default",type,*fields,Int::class.javaPrimitiveType,Any::class.java)
        for(mask in 0..15) {
            val p=defaults.invoke(null,source,emptyList<Any>(),3,false,null,mask,null) as DynamicPage<*>
            assertEquals(41,p.total);assertEquals(if(mask and 1!=0)source.items else emptyList<Any>(),p.items)
            assertEquals(if(mask and 2!=0)2 else 3,p.page);assertEquals(mask and 4!=0,p.hasMore)
            assertEquals(if(mask and 8!=0)"cursor" else null,p.pageTime)
        }
        assertNull(source.copy(total=null).total);assertEquals(0,source.copy(total=0).total)
    }
    @Test fun invalidSourceTotalIsNotAValidPage() {
        assertNull(DynamicPage(emptyList<Any>(),1,false).total)
        assertThrows(IllegalArgumentException::class.java){DynamicPage(emptyList<Any>(),1,false,total=-1)}
    }
}
