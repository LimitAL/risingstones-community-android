package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationSectionKind.*
import top.cxmeow.risingstones.network.*

class ExplorationApiTest {
    @Test fun accepted10002OverviewReadsNeverRefreshDespiteAuthenticationMessages() = runBlocking {
        val transport = ExplorationTransport().apply {
            defaultCode = 10002
            message = "未登录"
            payloads["getMKDTotal1"] = """[{"gold_num":"14"}]"""
        }
        val session = ExplorationSession()

        val result = service(transport, session).fetchExplorationOverview(ExplorationBoard.OccultCrescent)

        assertTrue(result.available)
        assertEquals("14", result.metrics.single().value)
        assertTrue(result.sections.all { it.failure == null })
        assertEquals(8, transport.requests.size)
        assertEquals(8, transport.requests.map { it.url.toHttpUrl().pathSegments.last() }.distinct().size)
        assertEquals(0, session.refreshes)
    }

    @Test fun acceptedCodesStillRequireAvailabilityPayloadAndFlagsWithoutRefresh() = runBlocking {
        for (code in listOf(10000, 10002)) {
            for (payload in listOf("null", "[]", "{}")) {
                val transport = ExplorationTransport().apply {
                    defaultCode = code
                    message = "未登录"
                    payloads["dataOpenStatus"] = payload
                }
                val session = ExplorationSession()

                try { service(transport, session).fetchExplorationOverview(ExplorationBoard.OccultCrescent); fail() }
                catch (_: ExplorationException.InvalidResponse) { }

                assertEquals(1, transport.requests.size)
                assertEquals(0, session.refreshes)
            }
        }
    }

    @Test fun accepted10002InvalidSectionPayloadRemainsSectionFailure() = runBlocking {
        val transport = ExplorationTransport().apply {
            defaultCode = 10002
            payloads["getMKDItemUse3"] = "null"
            payloads["getMKDTotal1"] = """[{"gold_num":"14"}]"""
        }
        val session = ExplorationSession()

        val result = service(transport, session).fetchExplorationOverview(ExplorationBoard.OccultCrescent)

        assertEquals("14", result.metrics.single().value)
        assertEquals(ExplorationFailure.InvalidResponse, result.sections.first { it.kind == ItemUsage }.failure)
        assertEquals(8, transport.requests.size)
        assertEquals(0, session.refreshes)
    }

    @Test fun occultUsesReviewedReadsAndPairedAuthorizerAndDropsIdentifiers() = runBlocking {
        val transport = ExplorationTransport()
        transport.payloads["getMKDTotal1"] = """[{"now_level":"60","gold_num":"14","character_id":"private-fixture","unknown":"private-fixture"}]"""
        transport.payloads["getMKDSupportJob2"] = """[{"support_job":"2","now_level":"3"},{"support_job":"1","now_level":"2"}]"""
        val result = service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent)
        assertEquals("60", result.metrics.first().value)
        assertFalse(result.toString().contains("private-fixture"))
        assertEquals("1", result.sections.first { it.kind == PhantomJobs }.records.first().fields.last().value)
        assertEquals(setOf("dataOpenStatus", "getMKDTotal1", "getMKDSupportJob2", "getMKDItemUse3", "getMKDItemGet4",
            "getMKDItemBox5", "getMKDAchieve7", "getMKDLight8"), transport.requests.map { it.url.toHttpUrl().pathSegments.last() }.toSet())
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get && it.headers["User-Agent"] == "fixture-login-agent" })
    }

    @Test fun closedBoardDoesNotCallChildEndpointsAndMissingFlagFails() = runBlocking {
        val transport = ExplorationTransport()
        transport.payloads["dataOpenStatus"] = """{"mkd":"0"}"""
        assertFalse(service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent).available)
        assertEquals(1, transport.requests.size)
        try { service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon); fail() }
        catch (_: ExplorationException.InvalidResponse) { }
    }

    @Test fun missingCapabilityNeverAccessesNetwork() = runBlocking {
        val transport = ExplorationTransport()
        val session = ExplorationSession().apply { capabilities = emptySet() }
        try { service(transport, session).fetchExplorationOverview(ExplorationBoard.OccultCrescent); fail() }
        catch (_: ExplorationException.Unavailable) { }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun historyUsesExactOfficialFiltersAndRejectsInvalidCombinations() = runBlocking {
        val transport = ExplorationTransport()
        val service = service(transport)
        for (kind in listOf(TreasureHistory, RelicHistory)) service.fetchExplorationHistory(ExplorationBoard.OccultCrescent, kind)
        for (kind in listOf(TreasureHistory, ItemHistory)) service.fetchExplorationHistory(ExplorationBoard.DeepDungeon, kind)
        val urls = transport.requests.map { it.url.toHttpUrl() }
        assertEquals(listOf("稀有道具", "半魂晶", "treasure", "item"), urls.map { it.queryParameter("catalog_type") })
        assertTrue(urls.take(2).all { it.pathSegments.last() == "getMKDIHistory6" })
        assertTrue(urls.drop(2).all { it.pathSegments.last() == "getDDHistory4" && it.queryParameter("dd_type") == "dd4" })
        try { service.fetchExplorationHistory(ExplorationBoard.DeepDungeon, RelicHistory); fail() }
        catch (_: ExplorationException.Unavailable) { }
        assertEquals(4, transport.requests.size)
    }

    @Test fun deepSeparatesSoloAndPartyJobsAndResolvesOnlyOfficialItemNames() = runBlocking {
        val transport = ExplorationTransport()
        transport.payloads["getDDTerr1"] = """[{"is_solo":"1","total_clear_time":"5","job_clear_times":"19:3,21:2,invalid","annihilation_num":"1281:3,1290:4"},{"is_solo":"0","total_clear_time":"2","job_clear_times":"40:2"}]"""
        transport.payloads["getDDItem3"] = """[{"catalog_id":"47342","get_num":"1"},{"catalog_id":"99999999","get_num":"2"}]"""
        transport.payloads["getDDAchieve5"] = """[{"achieve_id":"1234","log_time":"2026-01-01"}]"""
        transport.payloads["getDDFirstTeam7"] = """[{"character_name":"Fixture Ally","job_name":"40","character_id":"private-fixture"}]"""
        val result = service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon)
        assertEquals(2, result.sections.first { it.kind == Challenges }.records.size)
        assertEquals(listOf("1–10", "91–100"), result.sections.first { it.kind == FailureFloors }.records.map { row ->
            row.fields.first { it.kind == ExplorationFieldKind.Floor }.value
        })
        assertFalse(result.sections.first { it.kind == Challenges }.toString().contains("1281:3"))
        assertEquals(listOf("1", "1", "0"), result.sections.first { it.kind == ChallengeJobs }.records.map { it.fields.first().value })
        assertEquals("光照油", result.sections.first { it.kind == AcquiredItems }.records.first().title)
        assertEquals("", result.sections.first { it.kind == AcquiredItems }.records.last().title)
        assertEquals(1234, result.sections.first { it.kind == Achievements }.records.single().achievementId)
        assertFalse(result.toString().contains("private-fixture"))
        val special = transport.requests.filter { it.url.contains("getDDGaoNan2") || it.url.contains("getDDFirstTeam7") }
        assertTrue(special.all { it.url.toHttpUrl().queryParameter("territory_type") == "1311" })
    }

    @Test fun sectionFailuresAreSanitizedAndDoNotDiscardOtherSections() = runBlocking {
        val transport = ExplorationTransport()
        transport.payloads["getMKDTotal1"] = """[{"gold_num":"10"}]"""
        transport.payloads["getMKDItemUse3"] = """{"not":"an array"}"""
        transport.payloads["getMKDItemBox5"] = """[{"character_id":"private-fixture"}]"""
        transport.codes["getMKDLight8"] = 12345
        val result = service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent)
        assertEquals("10", result.metrics.single().value)
        assertEquals(ExplorationFailure.InvalidResponse, result.sections.first { it.kind == ItemUsage }.failure)
        assertEquals(ExplorationFailure.InvalidResponse, result.sections.first { it.kind == TreasureChests }.failure)
        assertEquals(ExplorationFailure.Business, result.sections.first { it.kind == Aether }.failure)
        assertFalse(result.toString().contains("private-server-message"))
    }

    @Test fun authenticationRefreshIsBoundedAndCancellationPropagates() = runBlocking {
        val transport = ExplorationTransport()
        transport.codes["dataOpenStatus"] = 10105
        val session = ExplorationSession()
        try { service(transport, session).fetchExplorationOverview(ExplorationBoard.OccultCrescent); fail() }
        catch (_: ExplorationException.AuthenticationRequired) { }
        assertEquals(1, session.refreshes)
        assertEquals(2, transport.requests.size)
        transport.cancel = true
        try { service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent); fail() }
        catch (_: CancellationException) { }
    }

    private fun service(transport: ExplorationTransport, session: ExplorationSession = ExplorationSession()) =
        PersonalDataApiService(RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session)
}

private class ExplorationSession : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.PersonalData)
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, sink ->
        assertEquals(RisingStonesCapability.PersonalData, context.capability)
        sink.set("User-Agent", "fixture-login-agent")
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return currentAuthorizer() }
}

private class ExplorationTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    val payloads = mutableMapOf("dataOpenStatus" to """{"mkd":"1","page_a":"1"}""")
    val codes = mutableMapOf<String, Int>()
    var defaultCode = 10000
    var message = "private-server-message"
    var cancel = false
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        val endpoint = request.url.toHttpUrl().pathSegments.last()
        val payload = """{"code":${codes[endpoint] ?: defaultCode},"msg":"$message","data":${payloads[endpoint] ?: "[]"}}"""
        return RisingStonesHttpResponse(200, emptyMap(), payload.encodeToByteArray())
    }
}
