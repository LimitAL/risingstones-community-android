package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriodKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class PersonalDataApiServiceTest {
    @Test
    fun sessionWithoutPersonalDataCapabilityCannotReachOfficialApi() = runBlocking {
        val rising = PersonalDataTransport()
        val service = PersonalDataApiService(
            RisingStonesPublicApiClient(rising, listOf("https://rising.test")),
            NoPersonalDataCredential,
            temporarySessionId = "session-1",
        )

        try {
            service.fetchIdentity()
            fail("Expected PersonalData capability to be required")
        } catch (_: PersonalDataException.AuthenticationRequired) {
            // Expected.
        }

        assertFalse(service.hasCommunityIdentity)
        assertTrue(rising.requests.isEmpty())
    }

    @Test
    fun officialApiAuthorizerReceivesRequiredPersonalDataContext() = runBlocking {
        val rising = PersonalDataTransport()
        val credential = RecordingPersonalDataCredential()
        val service = PersonalDataApiService(
            RisingStonesPublicApiClient(rising, listOf("https://rising.test")),
            credential,
            temporarySessionId = "session-1",
        )

        service.fetchIdentity()

        with(credential.contexts.single()) {
            assertEquals("api/home/groupAndRole/getCharacterBindInfo", path)
            assertEquals(RisingStonesCapability.PersonalData, capability)
            assertEquals(
                top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement.Required,
                requirement,
            )
        }
    }

    @Test
    fun identityAvailabilityAndFiveReleaseBoardsUseFrozenIosContracts() = runBlocking {
        val rising = PersonalDataTransport()
        val service = service(rising)

        val identity = service.fetchIdentity()
        val availability = service.fetchAvailability()
        val frontline = service.fetchBoardContent(PersonalDataBoard.Frontline)
        val fishing = service.fetchBoardContent(PersonalDataBoard.Fishing)
        val savage = service.fetchBoardContent(PersonalDataBoard.Savage)
        val glamour = service.fetchBoardContent(PersonalDataBoard.Glamour)

        assertEquals("Hero", identity.characterName)
        assertEquals("陆行鸟 · 红玉海", identity.location)
        assertTrue(availability.hasData(PersonalDataBoard.Ultimate) == true)
        assertEquals("30", frontline.frontlinePeriods.single { it.kind == FrontlinePeriodKind.Total }
            .metrics.single { it.id == "fight_times" }.value)
        assertFalse(frontline.sections.first().entries.first().fields.any { it.key == "character_id" })
        assertEquals("120", fishing.metrics.first().value)
        assertEquals("12", savage.metrics.first().value)
        assertEquals("302", glamour.metrics.single { it.id == "vanity_times" }.value)

        val paths = rising.requests.map { it.url.toHttpUrl().encodedPath }
        val expected = setOf(
            "/api/home/groupAndRole/getCharacterBindInfo", "/api/home/dataCenter/dataOpenStatus",
            "/api/home/dataCenter/frontline1TotalNew", "/api/home/dataCenter/frontline2WeekNew",
            "/api/home/dataCenter/frontline3JobNew", "/api/home/dataCenter/frontline4Best",
            "/api/home/dataCenter/frontline5Map", "/api/home/dataCenter/frontline6MapJob",
            "/api/home/dataCenter/fishTotal1", "/api/home/dataCenter/fishNum2",
            "/api/home/dataCenter/fishBait3", "/api/home/dataCenter/fishBig4",
            "/api/home/dataCenter/fishAchieve5", "/api/home/dataCenter/getLingShiTotal",
            "/api/home/dataCenter/getLingShi", "/api/home/dataCenter/getDressTotal7",
            "/api/home/dataCenter/getDressRace1", "/api/home/dataCenter/getDressColor2",
            "/api/home/dataCenter/getDressOrnament3", "/api/home/dataCenter/getDressVanity4",
            "/api/home/dataCenter/getDressFullset5",
        )
        assertEquals(expected, paths.toSet())
        assertTrue(rising.requests.all { it.headers["Authorization"] == "Host token" })
        assertTrue(rising.requests.all { it.url.toHttpUrl().queryParameter("tempsuid") == "session-1" })
        assertEquals("2", rising.requests.first().url.toHttpUrl().queryParameter("platform"))
    }

    @Test
    fun ultimateDashboardAndFiveDetailDatasetsAreMapped() = runBlocking {
        val rising = PersonalDataTransport()
        val service = service(rising)

        val dashboard = service.fetchUltimateDashboard()
        val summary = requireNotNull(dashboard.summary(968))
        val detail = service.fetchUltimateEncounterDetail(summary)

        assertEquals(41, summary.clearTimes)
        assertEquals("绝枪战士", summary.firstClearJobName)
        assertEquals("陆许", detail.teammates.single().characterName)
        assertEquals(listOf("暗黑骑士", "绝枪战士"), detail.jobs.map { it.jobName })
        assertEquals(listOf(118, 65), detail.partners.map { it.jointBattleTimes })
        assertEquals(listOf("p1", "finish"), detail.phases.map { it.phase })
        assertEquals(1, detail.deathPoints.size)
        val detailRequests = rising.requests.drop(1)
        assertTrue(detailRequests.all { it.url.toHttpUrl().queryParameter("territory_type") == "968" })
        assertEquals(
            setOf("gaoNanTeam2", "gaoNanJob3", "gaoNanFriend4", "gaoNanPhase6", "gaoNanDeadPoint5"),
            detailRequests.map { it.url.toHttpUrl().pathSegments.last() }.toSet(),
        )
    }

    @Test
    fun optionalCatalogDecoderUsesThreeVersionedDocumentsWithoutOwningTheirTransport() {
        val result = PersonalDataCatalogJsonDecoder().decode(
            FishCatalog.encodeToByteArray(),
            SavageCatalog.encodeToByteArray(),
            GlamourCatalog.encodeToByteArray(),
        )

        assertEquals("扎尔艾拉", result.fish.getValue(7678).name)
        assertEquals("轻量级1", result.savageRaids.getValue(1226).name)
        assertEquals(2, result.glamour?.setCount)
        assertEquals("Scholar set", result.glamour?.sets?.first()?.name)
        assertEquals("Ruby red", result.glamour?.stains?.first()?.name)
        assertEquals("Parasol", result.glamour?.fashionAccessories?.first()?.name)
    }

    private fun service(rising: RisingStonesHttpClient) = PersonalDataApiService(
        RisingStonesPublicApiClient(rising, listOf("https://rising.test")),
        PersonalDataCredential,
        temporarySessionId = "session-1",
    )
}

private object PersonalDataCredential : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.PersonalData)
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("Authorization", "Host token")
    }
}

private object NoPersonalDataCredential : RisingStonesSessionProvider {
    override val capabilities = emptySet<RisingStonesCapability>()
    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? =
        error("Authorizer must not be requested without the PersonalData capability")
}

private class RecordingPersonalDataCredential : RisingStonesSessionProvider {
    val contexts = mutableListOf<RisingStonesRequestContext>()
    override val capabilities = setOf(RisingStonesCapability.PersonalData)

    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, _ ->
        contexts += context
    }
}

private class PersonalDataTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val path = request.url.toHttpUrl().encodedPath
        val payload = when {
            path.endsWith("getCharacterBindInfo") -> """{"code":10000,"data":{"character_name":"Hero","area_name":"陆行鸟","group_name":"红玉海"}}"""
            path.endsWith("dataOpenStatus") -> """{"code":10000,"data":{"pvp":"1","jue4":"1","fishing":"1","lingshi":"1","vanity":"1"}}"""
            path.endsWith("frontline1TotalNew") -> """{"code":10000,"data":[{"data_time":"total","fight_times":"30","win_rate":"0.4"},{"data_time":"30days","fight_times":"10"}]}"""
            path.endsWith("frontline2WeekNew") -> """{"code":10000,"data":[{"part_date":"2026-07-21","fight_times":"2","character_id":"secret"}]}"""
            path.endsWith("fishTotal1") -> """{"code":10000,"data":{"total_times":120,"succ_rate":"84.5","sea_times":"3","max_sea_score":"10321"}}"""
            path.endsWith("getLingShiTotal") -> """{"code":10000,"data":[{"territory_num":"12","enter_num":"48","finish_times":"17","elapsed_time":"920"}]}"""
            path.endsWith("getDressTotal7") -> """{"code":10000,"data":[{"washing_num":"4","color_times":"21","vanity_times":"302"}]}"""
            path.endsWith("gaoNanFirst1") -> """{"code":10000,"data":[{"clear_times":"41","enter_before_clear":"50","job_name":"绝枪战士","territory_type":"968","elapsed_time":"226912","dead_times":"814","log_time":"2022-10-07 23:19:48"}]}"""
            path.endsWith("gaoNanTeam2") -> """{"code":10000,"data":[{"character_namee":"陆许","job_name":"白魔法师","area_name":"陆行鸟","group_name":"红玉海"}]}"""
            path.endsWith("gaoNanJob3") -> """{"code":10000,"data":[{"job_name":"绝枪战士","job_times":"17"},{"job_name":"暗黑骑士","job_times":"24"}]}"""
            path.endsWith("gaoNanFriend4") -> """{"code":10000,"data":[{"team_chara_name":"A","friend_times":"65"},{"team_chara_name":"B","friend_times":"118"}]}"""
            path.endsWith("gaoNanPhase6") -> """{"code":10000,"data":[{"phase":"finish","log_time":"2022-10-07 23:19:48"},{"phase":"p1","log_time":"2022-08-30 16:18:44"}]}"""
            path.endsWith("gaoNanDeadPoint5") -> """{"code":10000,"data":[{"point_x":"1.5","point_y":2,"period":"p2","dead_time":"2022-09-01 10:00:00"}]}"""
            else -> """{"code":10000,"data":[{"name":"row","value":"1"}]}"""
        }
        return RisingStonesHttpResponse(200, emptyMap(), payload.encodeToByteArray())
    }
}

private const val FishCatalog =
    """{"schemaVersion":1,"content":{"fish":[{"itemId":7678,"iconId":1,"name":"扎尔艾拉","patch":"2.0"}]}}"""
private const val SavageCatalog =
    """{"schemaVersion":1,"content":{"series":[{"tiers":[{"raids":[{"instanceId":1226,"name":"轻量级1","imageId":7}]}]}]}}"""
private const val GlamourCatalog =
    """{"schemaVersion":1,"content":{"sets":[{"mirageSetId":1,"name":"Scholar set","iconId":11,"items":[{"slotIndex":1,"itemId":101,"name":"Scholar cap","iconId":21}]},{"mirageSetId":2,"name":"Paladin set","items":[]}],"fashionAccessories":[{"id":3,"iconId":31,"name":"Parasol"}],"stains":[{"stainId":4,"name":"Ruby red","color":16711680,"isMetallic":false},{"stainId":5,"name":"Gold","color":16766720,"isMetallic":true},{"stainId":6,"name":"White","color":16777215,"isMetallic":false}]}}"""
