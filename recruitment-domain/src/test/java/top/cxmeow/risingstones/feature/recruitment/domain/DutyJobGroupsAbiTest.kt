package top.cxmeow.risingstones.feature.recruitment.domain
import org.junit.Test
import org.junit.Assert.*
class DutyJobGroupsAbiTest {
    private val type=DutyRecruitmentCatalogs::class.java
    private val a=DutyRecruitmentJob("synthetic-a","A",null,"wire type")
    @Test fun oldConstructorsAndEveryMaskKeepUnknownSourceGroups(){
        val ctor=type.getConstructor(Map::class.java,List::class.java)
        val value=ctor.newInstance(mapOf(a.id to a),emptyList<Any>())
        assertNull(value.jobGroups);val (jobs,duties)=value;assertEquals(mapOf(a.id to a),jobs);assertTrue(duties.isEmpty())
        val defaults=type.getConstructor(Map::class.java,List::class.java,Int::class.javaPrimitiveType,kotlin.jvm.internal.DefaultConstructorMarker::class.java)
        for(mask in 0..3){val c=defaults.newInstance(mapOf(a.id to a),emptyList<Any>(),mask,null);assertEquals(if(mask and 1!=0)emptyMap<String,DutyRecruitmentJob>()else mapOf(a.id to a),c.jobs);assertNull(c.jobGroups)}
        assertNull(type.getConstructor().newInstance().jobGroups)
    }
    @Test fun oldCopyAndEveryMaskRetainRawGroupFacts(){
        val groups=listOf(DutyRecruitmentJobGroup("Source",listOf(a)));val source=DutyRecruitmentCatalogs(mapOf(a.id to a),emptyList(),groups)
        val copy=type.getMethod("copy",Map::class.java,List::class.java);assertEquals(groups,(copy.invoke(source,emptyMap<String,Any>(),emptyList<Any>())as DutyRecruitmentCatalogs).jobGroups)
        val defaults=type.getMethod("copy\$default",type,Map::class.java,List::class.java,Int::class.javaPrimitiveType,Any::class.java)
        for(mask in 0..3){val c=defaults.invoke(null,source,emptyMap<String,Any>(),emptyList<Any>(),mask,null)as DutyRecruitmentCatalogs;assertEquals(groups,c.jobGroups);assertEquals(if(mask and 1!=0)source.jobs else emptyMap<String,DutyRecruitmentJob>(),c.jobs)}
        assertEquals(groups,source.copy().jobGroups);assertEquals(groups,source.copy(duties=emptyList()).jobGroups);assertNull(source.copy(jobGroups=null).jobGroups)
    }
    @Test fun missingAndConfirmedEmptyAndChangedGroupsRemainDistinctFacts(){
        val old=DutyRecruitmentCatalogs();assertNull(old.jobGroups);assertNotEquals(old,old.copy(jobGroups=emptyList()))
        val first=old.copy(jobGroups=listOf(DutyRecruitmentJobGroup("First",listOf(a))));assertNotEquals(first,first.copy(jobGroups=listOf(DutyRecruitmentJobGroup("Other",listOf(a)))))
        assertEquals(first.jobGroups,first.component3())
    }
}
