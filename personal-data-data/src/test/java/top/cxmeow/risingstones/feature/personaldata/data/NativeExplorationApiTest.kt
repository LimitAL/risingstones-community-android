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

/** Synthetic source-contract fixtures from iOS PersonalDataStatisticsAPIService, not live DD data. */
class NativeExplorationApiTest {
    private val native = ExplorationQuery(ExplorationQueryProfile.AstriaNative)
    @Test fun nativeDetailsRetainNestedPublicFieldsAndRecursivelyRemoveIdentityFields() = runBlocking {
        val transport = NativeTransport().apply {
            data["getDDGaoNan2"] = """{"name":"Fixture challenge","job_clear_times":"19:3","stats":{"clear_count":3,"uid":"private-fixture"},"party":[{"job_name":"Paladin","roleId":"private-fixture"}],"character_id":"private-fixture","tempsuid":"private-fixture"}"""
        }
        val result = service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon, native)
        val row = result.sections.first { it.kind == SpecialBattle }.records.single()
        assertEquals("Fixture challenge", row.title)
        assertEquals(mapOf("name" to "Fixture challenge", "job_clear_times" to "19:3", "stats.clear_count" to "3", "party[0].job_name" to "Paladin"), row.detailFields.associate { it.key to it.value })
        assertFalse(result.toString().contains("private-fixture"))
        val web = service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon)
        assertTrue(web.sections.flatMap { it.records }.all { it.detailFields.isEmpty() })
    }
    @Test fun nativeOccultLoadsOnlyImplementedPanelsAndPreservesRawDuplicateJobs() = runBlocking {
        val transport = NativeTransport().apply {
            data["getMKDSupportJob2"] = """[{"support_job":"1","now_level":"2"},{"support_job":"1","now_level":"6"},{"support_job":"0","now_level":"999"}]"""
            data["getMKDTotal1"] = """{"now_level":"60","silver_num":"3","character_id":"private-fixture"}"""
        }
        val overview = service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent, native)
        assertEquals(setOf("dataOpenStatus", "getMKDTotal1", "getMKDSupportJob2", "getMKDItemGet4", "getMKDItemBox5", "getMKDLight8"), transport.urls.map { it.pathSegments.last() }.toSet())
        assertEquals(3, overview.sections.first { it.kind == PhantomJobs }.records.size)
        assertEquals("60", overview.metrics.first().value)
        assertFalse(overview.toString().contains("private-fixture"))
        assertTrue(transport.urls.all { it.queryParameter("tempsuid") == "fixture-session-id" })
        assertTrue(transport.requests.all { it.headers["User-Agent"] == "fixture-agent" })
    }
    @Test fun nativeOccultClosedStateSkipsPanelsAndNonzeroStatusMatchesIos() = runBlocking {
        val transport = NativeTransport().apply { data["dataOpenStatus"] = """{"mkd":"0"}""" }
        assertFalse(service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent, native).available)
        assertEquals(1, transport.requests.size)
        transport.data["dataOpenStatus"] = """{"mkd":"2"}"""
        assertTrue(service(transport).fetchExplorationOverview(ExplorationBoard.OccultCrescent, native).available)
    }
    @Test fun everyNativeDeepDungeonTypeUsesAllSevenReviewedEndpointsWithoutUnverifiedFlags() = runBlocking {
        for (type in DeepDungeonType.entries) {
            val transport = NativeTransport().apply {
                data["getDDTerr1"] = """{"rows":[{"clearTimes":"9","armor_level":"5","enchantedLevel":"7","character_id":"private-fixture"}]}"""
            }
            val overview = service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon, native.copy(deepDungeonType = type))
            assertTrue(overview.available)
            assertEquals(listOf("9", "5", "7"), overview.metrics.map { it.value })
            assertEquals(listOf(ExplorationFieldKind.Clears, ExplorationFieldKind.ArmorLevel, ExplorationFieldKind.EnchantedLevel), overview.metrics.map { it.kind })
            assertEquals(setOf("getDDTerr1", "getDDGaoNan2", "getDDItem3", "getDDHistory4", "getDDAchieve5", "getDDDeadPoint6", "getDDFirstTeam7"), transport.urls.map { it.pathSegments.last() }.toSet())
            assertTrue(transport.urls.all { it.queryParameter("dd_type") == type.wireValue && it.queryParameter("tempsuid") == "fixture-session-id" })
            assertTrue(transport.urls.all { it.queryParameter("territory_type") == null && it.queryParameter("catalog_type") == null })
            assertFalse(overview.toString().contains("private-fixture"))
        }
    }
    @Test fun nativeEmptyNullAndPartialFailureRemainDistinct() = runBlocking {
        val transport = NativeTransport().apply {
            data["getDDTerr1"] = "null"
            codes["getDDGaoNan2"] = 12345
            data["getDDItem3"] = """{"list":[{"catalog_id":"1","get_num":"2"}]}"""
        }
        val overview = service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon, native)
        assertTrue(overview.sections.first { it.kind == Overview }.records.isEmpty())
        assertNull(overview.sections.first { it.kind == Overview }.failure)
        assertEquals(ExplorationFailure.Business, overview.sections.first { it.kind == SpecialBattle }.failure)
        assertEquals(1, overview.sections.first { it.kind == AcquiredItems }.records.size)
    }
    @Test fun historyUsesRareFilterForOccultAndUnfilteredTypedNativeDeepRead() = runBlocking {
        val transport = NativeTransport()
        val service = service(transport)
        service.fetchExplorationHistory(ExplorationBoard.OccultCrescent, TreasureHistory, native)
        service.fetchExplorationHistory(ExplorationBoard.DeepDungeon, ItemHistory, native.copy(deepDungeonType = DeepDungeonType.DD2))
        assertEquals("稀有道具", transport.urls.first().queryParameter("catalog_type"))
        assertEquals("dd2", transport.urls.last().queryParameter("dd_type"))
        assertNull(transport.urls.last().queryParameter("catalog_type"))
    }
    @Test fun defaultStrategyRemainsWebWithFixedTypeTerritoryAndStatusFlag() = runBlocking {
        val transport = NativeTransport()
        service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon)
        assertEquals("dataOpenStatus", transport.urls.first().pathSegments.last())
        val special = transport.urls.filter { it.pathSegments.last() in listOf("getDDGaoNan2", "getDDFirstTeam7") }
        assertTrue(special.all { it.queryParameter("territory_type") == "1311" && it.queryParameter("dd_type") == null })
        assertTrue(transport.urls.all { it.queryParameter("tempsuid") == null })
    }
    @Test fun nativeCapabilityGateAndBoundedRefreshAndCancellationAreRetained() = runBlocking {
        val transport = NativeTransport()
        val denied = NativeSession().apply { capabilities = emptySet() }
        try { service(transport, denied).fetchExplorationOverview(ExplorationBoard.DeepDungeon, native); fail() }
        catch (_: ExplorationException.Unavailable) { }
        assertTrue(transport.requests.isEmpty())
        val expired = NativeSession()
        transport.codes["getDDHistory4"] = 10105
        try { service(transport, expired).fetchExplorationHistory(ExplorationBoard.DeepDungeon, ItemHistory, native); fail() }
        catch (_: ExplorationException.AuthenticationRequired) { }
        assertEquals(1, expired.refreshes)
        assertEquals(2, transport.requests.size)
        transport.cancel = true
        try { service(transport).fetchExplorationHistory(ExplorationBoard.DeepDungeon, ItemHistory, native); fail() }
        catch (_: CancellationException) { }
    }
    private fun service(transport: NativeTransport, session: NativeSession = NativeSession()) = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session, temporarySessionId = "fixture-session-id")
}
private class NativeSession : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.PersonalData)
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink -> sink.set("User-Agent", "fixture-agent") }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return currentAuthorizer() }
}
private class NativeTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    val urls get() = requests.map { it.url.toHttpUrl() }
    val data = mutableMapOf("dataOpenStatus" to """{"mkd":"1","page_a":"1"}""")
    val codes = mutableMapOf<String, Int>()
    var cancel = false
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        val key = request.url.toHttpUrl().pathSegments.last()
        return RisingStonesHttpResponse(200, emptyMap(), """{"code":${codes[key] ?: 10000},"data":${data[key] ?: "[]"}}""".encodeToByteArray())
    }
}
