package top.cxmeow.risingstones.feature.profile.domain

import org.junit.Assert.*
import org.junit.Test

class ProfileBadgesTest {
    @Test fun catalogKeepsOfficialStagesFallbackAndUnknownFacts(){
        assertEquals("achieve_silver.png",ProfileBadgeCatalog.presentation(ProfileBadge("warrior_badge","raw",2)).iconFile)
        assertEquals("adminvip_2.png",ProfileBadgeCatalog.presentation(ProfileBadge("admin_tag","raw",2)).iconFile)
        assertEquals("荣誉光之战士",ProfileBadgeCatalog.presentation(ProfileBadge("warrior_badge","raw",99)).name)
        assertEquals(ProfileBadgePresentation("Future",null,null),ProfileBadgeCatalog.presentation(ProfileBadge("future","Future",8)))
        assertEquals(10,ProfileBadgeCatalog.order("future"));assertEquals(0,ProfileBadgeCatalog.order("test_limited_badge"))
    }
    @Test fun originalThreeFieldConstructorCopyAndAllMasksRemainAvailable(){
        val profile=CommunityProfile("mock","Mock","","",null,"",false,0,0,0);val counts=ProfileReadCounts(null,0,null)
        val rows=listOf(ProfileBadge("warrior_badge","raw",1));val source=ProfileReadSnapshot(profile,null,counts,rows)
        val constructor=ProfileReadSnapshot::class.java.getConstructor(CommunityProfile::class.java,ProfileGuildReference::class.java,ProfileReadCounts::class.java)
        assertNull(constructor.newInstance(profile,null,counts).badges)
        val replacement=profile.copy(name="Replacement");val guild=ProfileGuildReference("42",null);val nextCounts=ProfileReadCounts(2,3,4)
        val method=ProfileReadSnapshot::class.java.getDeclaredMethod("copy\$default",ProfileReadSnapshot::class.java,CommunityProfile::class.java,ProfileGuildReference::class.java,ProfileReadCounts::class.java,Int::class.javaPrimitiveType,Any::class.java)
        for(mask in 0..7){val result=method.invoke(null,source,replacement,guild,nextCounts,mask,null)as ProfileReadSnapshot
            assertEquals(if(mask and 1!=0)profile else replacement,result.profile);assertEquals(if(mask and 2!=0)null else guild,result.guild);assertEquals(if(mask and 4!=0)counts else nextCounts,result.counts);assertEquals(rows,result.badges)
        }
        assertEquals(rows,source.copy(replacement,guild,nextCounts).badges)
    }
}
