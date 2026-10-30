package top.cxmeow.risingstones.feature.recruitment.data
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.Assert.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.*

class DutyJobGroupsReadTest {
    private class Fixture(val payload:String,val code:Int=10000):RisingStonesHttpClient {
        val paths=mutableListOf<String>()
        override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
            check(request.method==RisingStonesHttpMethod.Get);val path=request.url.toHttpUrl().pathSegments.last();paths+=path
            val data=when(path){"getJobConfigList"->payload;"getFbConfigList"->"[]";else->error("Unmatched request fails closed")}
            return RisingStonesHttpResponse(200,emptyMap(),("{\"code\":$code,\"data\":$data}").encodeToByteArray())
        }
        fun service()=DutyRecruitmentApiService(RisingStonesPublicApiClient(this,listOf("https://rising.test")),object:RisingStonesSessionProvider{override val capabilities=emptySet<RisingStonesCapability>();override suspend fun currentAuthorizer():RisingStonesRequestAuthorizer?=null},temporarySessionId="synthetic-local")
    }
    @Test fun sourceKeysOrderAndRowOrderDoNotComeFromWireRoleType()=runBlocking {
        val f=Fixture("""{"Zulu":[{"id":"z","value":"Z"}],"治疗职业":[{"id":"h","value":"H"}],"Alpha":[{"id":"b","value":"B"},{"id":"a","value":"A","jobType":"wrong source"}],"防护职业":[{"id":"t","value":"T"}],"职能分类":[{"id":"r","value":"Role"}]}""")
        val c=f.service().fetchCatalogs();assertEquals(listOf("职能分类","防护职业","治疗职业","Alpha","Zulu"),c.jobGroups!!.map{it.name});assertEquals(listOf("b","a"),c.jobGroups!![3].jobs.map{it.id});assertEquals("wrong source",c.jobGroups!![3].jobs[1].roleType);assertEquals(listOf("getJobConfigList","getFbConfigList"),f.paths)
    }
    @Test fun badRowsAndEmptyGroupsUseTheExistingRowDecoder()=runBlocking {
        val c=Fixture("""{"Empty":[null,7,{}],"Keep":[null,{"value":"Only valid"}]}""").service().fetchCatalogs()
        assertEquals(listOf("Keep"),c.jobGroups!!.map{it.name});assertEquals("Only valid",c.jobGroups!!.single().jobs.single().name);assertEquals(1,c.jobs.size)
    }
    @Test fun confirmedEmptyIsNotLegacyMissing()=runBlocking {val c=Fixture("{}").service().fetchCatalogs();assertEquals(emptyList<DutyRecruitmentJobGroup>(),c.jobGroups);assertTrue(c.jobs.isEmpty());assertNull(DutyRecruitmentCatalogs().jobGroups)}
    @Test fun flatDuplicateWinnerStaysUnchangedWhileBothSourceRowsSurvive()=runBlocking {
        val c=Fixture("""{"Zulu":[{"id":"same","value":"First"}],"Alpha":[{"id":"same","value":"Last"}]}""").service().fetchCatalogs();assertEquals("Last",c.jobs.getValue("same").name);assertEquals(listOf("Last","First"),c.jobGroups!!.flatMap{it.jobs}.map{it.name})
    }
    @Test fun malformedGroupsAndBusinessErrorsCannotBecomeAnEmptySuccess(){
        for(payload in listOf("null","[]","{\"Bad\":{}}"))assertThrows(DutyRecruitmentException::class.java){runBlocking{Fixture(payload).service().fetchCatalogs()}}
        val f=Fixture("{}",999);assertThrows(DutyRecruitmentException::class.java){runBlocking{f.service().fetchCatalogs()}};assertEquals(listOf("getJobConfigList"),f.paths)
    }
}
