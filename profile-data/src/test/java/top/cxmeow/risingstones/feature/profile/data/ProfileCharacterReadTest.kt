package top.cxmeow.risingstones.feature.profile.data

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.network.*

class ProfileCharacterReadTest {
    private class Fixture {
        val requests=mutableListOf<RisingStonesHttpRequest>();var info="""{"uuid":"mock-id","character_name":"Mock","area_name":"Area","group_name":"World","career_publish":0,"resently_publish":0,"careerLevel":[{"career":"骑士","character_level":"90","career_type":"防护职业"}]}"""
        var events="""[{"event_type":"成就","detail":"Mock event","log_time":"2026-10-24 00:15:00"}]""";var eventsCode=10000;var cancel=false
        val client=RisingStonesPublicApiClient(object:RisingStonesHttpClient {
            override suspend fun execute(request:RisingStonesHttpRequest):RisingStonesHttpResponse {
                requests+=request;assertEquals(RisingStonesHttpMethod.Get,request.method)
                val recent=request.url.contains("getResently")
                if(recent&&cancel)throw CancellationException("Mock cancellation")
                return RisingStonesHttpResponse(200,emptyMap(),"""{"code":${if(recent)eventsCode else 10000},"data":${if(recent)events else info}}""".toByteArray())
            }
        },listOf("https://fixture.invalid/"))
        val service=ProfileApiService(client,object:RisingStonesSessionProvider {
            override val capabilities=setOf(RisingStonesCapability.AccountRead)
            override suspend fun currentAuthorizer()=RisingStonesRequestAuthorizer{_,_->}
        })
    }
    @Test fun selfReadRetainsCategoryPrivateFlagsAndBeijingTimeWithoutUuidQuery()=runTest {
        val f=Fixture();val p=f.service.fetchSelfCharacterProfile()
        assertEquals(ProfileCharacterJob("骑士",90,"防护职业"),p.jobs!!.single());assertTrue(p.jobsUnpublished);assertTrue(p.eventsUnpublished)
        assertEquals(Instant.parse("2026-10-23T16:15:00Z"),p.events!!.single().occurredAt)
        assertEquals(2,f.requests.size);assertTrue(f.requests.none{it.url.contains("uuid=")});assertTrue(f.requests.last().url.contains("tempsuid="))
    }
    @Test fun recentFailureUnknownAndSuccessfulEmptyRemainDifferent()=runTest {
        val f=Fixture();f.eventsCode=500;assertNull(f.service.fetchSelfCharacterProfile().events)
        f.eventsCode=10000;f.events="[]";assertEquals(emptyList<ProfileCharacterEvent>(),f.service.fetchSelfCharacterProfile().events)
        f.events="{}";assertNull(f.service.fetchSelfCharacterProfile().events)
        f.events="""[{"detail":"No date"}]""";assertNull(f.service.fetchSelfCharacterProfile().events)
    }
    @Test fun malformedOrDuplicateRosterCannotImplyMissingJobsAreLocked()=runTest {
        val f=Fixture()
        for(rows in listOf("null","[{}]","""[{"career":"骑士","character_level":-1}]""","""[{"career":"骑士","character_level":0},{"career":"骑士","character_level":1}]""")){
            f.info="""{"uuid":"mock","character_name":"Mock","careerLevel":$rows}""";assertNull(f.service.fetchSelfCharacterProfile().jobs)
        }
        f.info="""{"uuid":"mock","character_name":"Mock","careerLevel":[]}""";assertEquals(emptyList<ProfileCharacterJob>(),f.service.fetchSelfCharacterProfile().jobs)
    }
    @Test fun cancellationMustNotBecomeAnUnavailableRecentSection()=runTest {
        val f=Fixture();f.cancel=true
        try{f.service.fetchSelfCharacterProfile();fail("Expected cancellation")}catch(_:CancellationException){}
    }
    @Test fun eventRequiresARealTimeAndNeverNormalizesAnInvalidCalendarDate()=runTest {
        val f=Fixture()
        for(value in listOf("2026-10-24","2026-02-30 00:15:00","not-a-date")) {
            f.events="""[{"detail":"Mock event","log_time":"$value"}]""";assertNull(f.service.fetchSelfCharacterProfile().events)
        }
        f.events="""[{"detail":"Mock event","log_time":" 2026-10-24 00:15:00 "}]"""
        assertEquals(Instant.parse("2026-10-23T16:15:00Z"),f.service.fetchSelfCharacterProfile().events!!.single().occurredAt)
    }
    @Test fun duplicateEventsDeduplicateAndKeepNewestFirst()=runTest {
        val f=Fixture();f.events="""[{"detail":"B","log_time":"2026-10-24 00:15:00"},{"detail":"A","log_time":"2026-10-24 00:15:00"},{"detail":"A","log_time":"2026-10-24 00:15:00"},{"detail":"Older","log_time":"2026-10-23 00:15:00"}]"""
        assertEquals(listOf("A","B","Older"),f.service.fetchSelfCharacterProfile().events!!.map{it.detail})
    }
    @Test fun sixSelfFactsRetainZeroPrivateMarkersAndExactOfficialText()=runTest {
        val f=Fixture();f.info="""{"uuid":"mock","character_name":"Mock","create_time_publish":0,"newrank_publish":0,"characterDetail":[{"create_time":"2020-01-02","last_login_time":"2026-10-24 00:10:00","play_time":"0小时0分钟","house_info":"无房产","crystal_rank":"青铜3阶","newrank":0}]}"""
        val p=f.service.fetchSelfCharacterProfile();val facts=p.facts!!
        assertEquals(6,facts.size);assertEquals("0",facts.single{it.kind==ProfileFactKind.IslandSanctuaryRank}.value)
        assertEquals("0小时0分钟",facts.single{it.kind==ProfileFactKind.PlayTime}.value)
        assertTrue(facts.single{it.kind==ProfileFactKind.CreatedAt}.unpublished)
        assertTrue(facts.single{it.kind==ProfileFactKind.IslandSanctuaryRank}.unpublished)
        assertTrue(f.requests.all{it.method==RisingStonesHttpMethod.Get&&!it.url.contains("uuid=")})
    }
    @Test fun objectDetailsAndUnavailablePlaceholdersDoNotBecomeZero()=runTest {
        val f=Fixture();f.info="""{"uuid":"mock","character_name":"Mock","characterDetail":{"create_time":"-","last_login_time":"***","house_info":"  ","newrank":"0"}}"""
        val facts=f.service.fetchSelfCharacterProfile().facts!!
        assertEquals(5,facts.count{it.value==null});assertEquals("0",facts.single{it.kind==ProfileFactKind.IslandSanctuaryRank}.value)
        for(detail in listOf("null","[]","[null]","42")){
            f.info="""{"uuid":"mock","character_name":"Mock","characterDetail":$detail}""";assertNull(f.service.fetchSelfCharacterProfile().facts)
        }
    }
    @Test fun recentFailureDoesNotDiscardFactsAndNoExtraEndpointIsRequested()=runTest {
        val f=Fixture();f.eventsCode=500;f.info="""{"uuid":"mock","character_name":"Mock","characterDetail":{"play_time":"未知原文","house_info":"官方原文"}}"""
        val p=f.service.fetchSelfCharacterProfile();assertNull(p.events);assertEquals("未知原文",p.facts!!.single{it.kind==ProfileFactKind.PlayTime}.value);assertEquals(2,f.requests.size)
    }
    @Test fun flexiblePrivateSwitchesAndBooleanZeroFollowExistingIosContract()=runTest {
        val f=Fixture();f.info="""{"uuid":"mock","character_name":"Mock","newrank_publish":false,"house_info_publish":" FALSE ","create_time_publish":" 0 ","characterDetail":{"newrank":false,"house_info":"Private house","create_time":"2020-01-02"}}"""
        val facts=f.service.fetchSelfCharacterProfile().facts!!
        assertEquals("0",facts.single{it.kind==ProfileFactKind.IslandSanctuaryRank}.value)
        assertTrue(facts.filter{it.kind in setOf(ProfileFactKind.IslandSanctuaryRank,ProfileFactKind.Housing,ProfileFactKind.CreatedAt)}.all{it.unpublished})
    }
    @Test fun legacyCopyDefaultAndConstructorKeepFactsCompatibleLocally(){
        val p=ProfileCharacterData("id","Mock","Area","World",null,false,null,false,Instant.EPOCH,listOf(ProfileCharacterFact(ProfileFactKind.Housing,"house",false)))
        val result=ProfileCharacterData.`copy$default`(p,null,null,null,null,null,false,null,false,null,511,null)
        assertEquals(p,result)
        assertEquals(p.facts,p.copy("id","Mock","Area","World",null,false,null,false,Instant.EPOCH).facts)
        assertNotNull(ProfileCharacterData::class.java.constructors.singleOrNull{it.parameterCount==9})
    }
}
