package top.cxmeow.risingstones.feature.account.domain
import org.junit.Test
import org.junit.Assert.*
class AccountDashboardAbiTest {
 private val type=RisingStonesAccountDashboard::class.java
 private val fields=arrayOf(RisingStonesCharacter::class.java,String::class.java,Int::class.javaObjectType,List::class.java,List::class.java)
 @Test fun oldConstructorRemainsAvailable() {
  val value=type.getConstructor(*fields).newInstance(null,null,2,emptyList<Any>(),emptyList<Any>())
  assertNull(value.medalWall);val(a,b,c,d,e)=value;assertNull(a);assertNull(b);assertEquals(2,c);assertTrue(d.isEmpty()&&e.isEmpty())
 }
 @Test fun originalCopyDescriptorsAndMasksPreserveSnapshot() {
  val wall=RisingStonesMedalWall(null,emptyList(),0,0)
  val source=RisingStonesAccountDashboard(RisingStonesCharacter("A","G","N"),"house",2,listOf(RisingStonesSignInLog("old","time",null,null)),listOf(RisingStonesReward(1,"old",null,null,RisingStonesRewardStatus.NotQualified)),wall)
  val copy=type.getMethod("copy",*fields)
  assertSame(wall,(copy.invoke(source,null,null,3,emptyList<Any>(),emptyList<Any>()) as RisingStonesAccountDashboard).medalWall)
  val defaultCopy=type.getMethod("copy\$default",type,*fields,Int::class.javaPrimitiveType,Any::class.java)
  for(mask in 0..31) {
   val result=defaultCopy.invoke(null,source,null,"changed",3,emptyList<Any>(),emptyList<Any>(),mask,null) as RisingStonesAccountDashboard
   assertSame(wall,result.medalWall);assertEquals(if(mask and 1!=0) source.character else null,result.character)
   assertEquals(if(mask and 2!=0) "house" else "changed",result.houseRemainDayText);assertEquals(if(mask and 4!=0) 2 else 3,result.signInCount)
   assertEquals(if(mask and 8!=0) source.signInLogs else emptyList<RisingStonesSignInLog>(),result.signInLogs);assertEquals(if(mask and 16!=0) source.rewards else emptyList<RisingStonesReward>(),result.rewards)
  }
 }
 @Test fun currentCopyStillReplacesSnapshotExplicitly() {
  val wall=RisingStonesMedalWall(null,emptyList(),0,0);val source=RisingStonesAccountDashboard(null,null,null,emptyList(),emptyList(),wall)
  assertSame(wall,source.copy(signInCount=3).medalWall);assertNull(source.copy(medalWall=null).medalWall)
 }
}
