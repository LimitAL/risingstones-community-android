package top.cxmeow.risingstones.feature.personaldata.data

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.network.*

class PersonalDataDashboardApiTest {
    @Test fun fourteenSectionsEachMakeOneFullGetWithOnlyTheCommonQuery() = runTest {
        val session = DashboardSession()
        val transport = DashboardTransport()
        val service: PersonalDataDashboardService = service(transport, session)
        for (section in endpoints.keys) service.fetchDashboardSection(section)

        assertEquals(endpoints.values.toList(), transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
        for (request in transport.requests) {
            assertEquals(RisingStonesHttpMethod.Get, request.method)
            assertNull(request.body)
            assertEquals(setOf("tempsuid"), request.url.toHttpUrl().queryParameterNames)
            assertEquals("dashboard-fixture", request.url.toHttpUrl().queryParameter("tempsuid"))
            assertEquals("Fixture browser UA", request.headers["User-Agent"])
        }
        assertEquals(14, session.contexts.size)
        assertTrue(session.contexts.all { it.capability == RisingStonesCapability.PersonalData &&
            it.requirement == RisingStonesAuthenticationRequirement.Required })
    }

    @Test fun explicitEmptyArraysProduceEmptyTypedSectionsAndNullSummaryRecords() = runTest {
        assertEquals(emptySections.keys, endpoints.keys)
        for ((section, expected) in emptySections) assertEquals(section.name, expected, fetch(section, "[]"))
    }

    @Test fun allSectionsRejectMissingNullScalarAndWrappedDataRoots() = runTest {
        for (body in listOf("""{"code":10000}""", """{"code":10000,"data":null}""",
            """{"code":10000,"data":{}}""", """{"code":10000,"data":{"rows":[]}}""",
            """{"code":10000,"data":false}""", """{"code":10000,"data":"[]"}""")) {
            for (section in endpoints.keys) assertMissing {
                service(DashboardTransport { _, _ -> envelope(body) }).fetchDashboardSection(section)
            }
        }
    }

    @Test fun arraysContainingInvalidMembersCannotMasqueradeAsSuccessfulPartialData() = runTest {
        for (section in endpoints.keys) {
            assertMissing { fetch(section, "[null]") }
            assertMissing { fetch(section, "[{},false]") }
        }
    }

    @Test fun fishingOverviewKeepsFractionAndExactCountsFromOnlyTheFirstRow() = runTest {
        assertEquals(PersonalDataDashboardData.FishingSummary(FishingOverview(9007199254740993L, 0.845, 7, 12345)),
            fetch(PersonalDataDashboardSectionKind.FishingSummary, """[
                {"total_times":9007199254740993,"succ_rate":"0.845","sea_times":"7","max_sea_score":12345},
                {"total_times":100,"succ_rate":1,"sea_times":100,"max_sea_score":99999}]"""))
    }

    @Test fun glamourOverviewMapsFantasiaDyesAndProjectionsWithoutSummingRows() = runTest {
        assertEquals(PersonalDataDashboardData.GlamourSummary(GlamourOverview(4, 21, 302)),
            fetch(PersonalDataDashboardSectionKind.GlamourSummary, """[
                {"washing_num":"4","color_times":21,"vanity_times":"302"},
                {"washing_num":100,"color_times":100,"vanity_times":100}]"""))
    }

    @Test fun savageOverviewRetainsHoursAndDoesNotConvertOrAggregateThem() = runTest {
        assertEquals(PersonalDataDashboardData.SavageSummary(SavageOverview(12, 48, 17, 19.5)),
            fetch(PersonalDataDashboardSectionKind.SavageSummary, """[
                {"territory_num":"12","enter_num":48,"finish_times":"17","elapsed_time":"19.5"},
                {"territory_num":1,"elapsed_time":3600}]"""))
    }

    @Test fun emptyFirstSummaryObjectsKeepUnknownFieldsInsteadOfBorrowingLaterRows() = runTest {
        assertEquals(PersonalDataDashboardData.FishingSummary(FishingOverview(null, null, null, null)),
            fetch(PersonalDataDashboardSectionKind.FishingSummary, """[{}, {"total_times":100,"succ_rate":1}]"""))
        assertEquals(PersonalDataDashboardData.GlamourSummary(GlamourOverview(null, null, null)),
            fetch(PersonalDataDashboardSectionKind.GlamourSummary, """[{}, {"washing_num":100}]"""))
        assertEquals(PersonalDataDashboardData.SavageSummary(SavageOverview(null, null, null, null)),
            fetch(PersonalDataDashboardSectionKind.SavageSummary, """[{}, {"elapsed_time":100}]"""))
    }

    @Test fun invalidSummaryNumbersRemainUnknownRatherThanZeroOrNonFiniteValues() = runTest {
        for (raw in listOf("null", "false", "{}", "[]", "\"\"", "\"NaN\"", "\"Infinity\"", "-1", "1e999")) {
            assertEquals(PersonalDataDashboardData.FishingSummary(FishingOverview(null, null, null, null)),
                fetch(PersonalDataDashboardSectionKind.FishingSummary,
                    """[{"total_times":$raw,"succ_rate":$raw,"sea_times":$raw,"max_sea_score":$raw}]"""))
            assertEquals(PersonalDataDashboardData.GlamourSummary(GlamourOverview(null, null, null)),
                fetch(PersonalDataDashboardSectionKind.GlamourSummary,
                    """[{"washing_num":$raw,"color_times":$raw,"vanity_times":$raw}]"""))
            assertEquals(PersonalDataDashboardData.SavageSummary(SavageOverview(null, null, null, null)),
                fetch(PersonalDataDashboardSectionKind.SavageSummary,
                    """[{"territory_num":$raw,"enter_num":$raw,"finish_times":$raw,"elapsed_time":$raw}]"""))
        }
        val fraction = fetch(PersonalDataDashboardSectionKind.FishingSummary,
            """[{"succ_rate":84.5,"total_times":1.5,"sea_times":9223372036854775808}]""") as PersonalDataDashboardData.FishingSummary
        assertEquals(FishingOverview(null, null, null, null), fraction.record)
    }

    @Test fun rankingSectionsReuseTheExistingTypedReadWithoutCroppingOrExtraGets() = runTest {
        val transport = DashboardTransport { request, _ -> data(if (request.url.contains("fishNum2"))
            """[{"catalog_name":"Fish one","fish_num":3},{"catalog_name":"Fish two","fish_num":"4","fish_type":"Future category"}]"""
            else """[{"catalog_name":"Bait","bait_num":"7","fish_num":999}]""") }
        val service = service(transport)
        assertEquals(PersonalDataDashboardData.FishRanking(listOf(PersonalDataFishingRank("Fish one", 3, null),
            PersonalDataFishingRank("Fish two", 4, "Future category"))), service.fetchDashboardSection(PersonalDataDashboardSectionKind.FishRanking))
        assertEquals(PersonalDataDashboardData.BaitRanking(listOf(PersonalDataFishingRank("Bait", 7, null))),
            service.fetchDashboardSection(PersonalDataDashboardSectionKind.BaitRanking))
        assertEquals(2, transport.requests.size)
        assertMissing { fetch(PersonalDataDashboardSectionKind.FishRanking, """[{"catalog_name":"Fish","fish_num":1.5}]""") }
    }

    @Test fun bigFishKeepNamesDatesAndUnknownCountsWithoutCatalogOrPrivacyGuesses() = runTest {
        assertEquals(PersonalDataDashboardData.BigFish(listOf(
            PersonalDataFishCatch("Literal & <fish>", Instant.parse("2026-09-20T02:30:00Z"), 5),
            PersonalDataFishCatch("Unknown count", null, null),
        )), fetch(PersonalDataDashboardSectionKind.BigFish, """[
            {"catalog_name":"Literal & <fish>","log_time":"2026-09-20 10:30:00","fish_num":"5","character_id":"PRIVATE_RESPONSE"},
            {"catalog_name":"Unknown count","fish_num":"bad"}]"""))
    }

    @Test fun fishingAchievementsUseRequiredIdsAndOptionalOfficialText() = runTest {
        assertEquals(PersonalDataDashboardData.FishingAchievements(listOf(
            PersonalDataAchievementRecord(100, "Title", "Literal <detail>", Instant.parse("2026-09-20T00:00:00Z")),
            PersonalDataAchievementRecord(101, null, null, null),
        )), fetch(PersonalDataDashboardSectionKind.FishingAchievements, """[
            {"achieve_id":"100","achieve_name":"Title","achieve_detail":"Literal <detail>","log_time":"2026-09-20T00:00:00Z"},
            {"achieve_id":101}]"""))
    }

    @Test fun oceanFishingPreservesNearshoreOffshoreAndUnknownTerritoryNumbers() = runTest {
        assertEquals(PersonalDataDashboardData.OceanFishing(listOf(
            PersonalDataOceanRoute(900, 12000, 8), PersonalDataOceanRoute(1163, 14000, 3),
            PersonalDataOceanRoute(9999, null, null),
        )), fetch(PersonalDataDashboardSectionKind.OceanFishing, """[
            {"territory_type":"900","max_sea_score":"12000","sea_times":8},
            {"territory_type":1163,"max_sea_score":14000,"sea_times":"3"},
            {"territory_type":"9999","max_sea_score":-1,"sea_times":"bad"}]"""))
    }

    @Test fun racesKeepActualRateRnAndUsageInOneReadWithoutTopFiveCropping() = runTest {
        val rows = (1..7).joinToString(",", "[", "]") { """{"race":"Race $it","gender":"Female","continue_rate":"0.123",
            "continue_days":"42","rate_rn":"$it","rank_rn":999,"now_rn":${if (it == 7) 1 else 2}}""" }
        val transport = DashboardTransport { _, _ -> data(rows) }
        val result = service(transport).fetchDashboardSection(PersonalDataDashboardSectionKind.Races) as PersonalDataDashboardData.Races
        assertEquals(7, result.rows.size)
        assertEquals((1L..7L).toList(), result.rows.map { it.rank })
        assertEquals(PersonalDataRaceUsage("Race 1", "Female", 0.123, 42, false, true), result.rows.first().usage)
        assertTrue(result.rows.last().usage.isCurrent)
        assertEquals(1, transport.requests.size)
    }

    @Test fun stainsRetainUndyedIdZeroAndRankWithoutScaling() = runTest {
        assertEquals(PersonalDataDashboardData.Stains(listOf(
            PersonalDataStainUsage(0, 10, 2), PersonalDataStainUsage(9999, null, null),
        )), fetch(PersonalDataDashboardSectionKind.Stains, """[
            {"catalog_id":"0","color_times":"10","rn":"2"},
            {"catalog_id":9999,"color_times":1.5,"rn":-1}]"""))
    }

    @Test fun accessoriesUseOrnamentFieldsAndPreserveUnresolvedIds() = runTest {
        assertEquals(PersonalDataDashboardData.Accessories(listOf(
            PersonalDataAccessoryUsage(50, 12, 3), PersonalDataAccessoryUsage(9999, null, null),
        )), fetch(PersonalDataDashboardSectionKind.Accessories, """[
            {"ornament":"50","ornament_times":12,"rn":"3","catalog_id":8},
            {"ornament":9999}]"""))
    }

    @Test fun vanityPreservesPeriodsCategoriesUppercaseNameAndIconAndZeroCountsForLocalFiltering() = runTest {
        assertEquals(PersonalDataDashboardData.Vanity(listOf(
            PersonalDataVanityUsage(PersonalDataVanityPeriod.AllTime, 1, 500, "Uppercase name", 2500, 0),
            PersonalDataVanityUsage(PersonalDataVanityPeriod.LastYear, 4, 501, null, null, 12),
            PersonalDataVanityUsage(PersonalDataVanityPeriod.Unknown, null, null, "Name only", null, null),
        )), fetch(PersonalDataDashboardSectionKind.Vanity, """[
            {"rank_type":"total","dress_type":"1","vanity":"500","Name":"Uppercase name","Icon":"2500","times":"0"},
            {"rank_type":"year","dress_type":4,"vanity":501,"name":"Wrong lowercase","icon":1,"times":12},
            {"rank_type":"future","Name":"Name only","dress_type":"bad","Icon":-1,"times":1.5}]"""))
    }

    @Test fun vanityRequiresAnActualOfficialNameOrValidItemId() = runTest {
        for (row in listOf("{}", """{"name":"Not the official field"}""", """{"Name":" ","vanity":0}""",
            """{"Name":12,"vanity":"bad"}""")) assertMissing { fetch(PersonalDataDashboardSectionKind.Vanity, "[$row]") }
    }

    @Test fun setsDelegateStableKeysAndKeepDuplicatesAndInvalidItemEvidence() = runTest {
        val transport = DashboardTransport { _, _ -> data("""[
            {"setitem":"42","partitem":"1,2,bad"},{"setitem":"42","partitem":"1,2,bad"}]""") }
        val result = service(transport).fetchDashboardSection(PersonalDataDashboardSectionKind.Sets) as PersonalDataDashboardData.Sets
        assertEquals(2, result.rows.size)
        assertEquals(2, result.rows.map { it.key }.toSet().size)
        assertTrue(result.rows.all { it.setId == 42 && it.itemIds == setOf(1, 2) && it.hasInvalidItemIds })
        assertEquals(1, transport.requests.size)
    }

    @Test fun savageRowsKeepSecondsAndOnlyDescribeSupportForUnrestrictedRuns() = runTest {
        assertEquals(PersonalDataDashboardData.SavageRaids(listOf(
            PersonalDataSavageClear(1226, Instant.parse("2026-09-20T02:30:00Z"), true, "Paladin", 7200.5),
            PersonalDataSavageClear(1227, null, false, null, 0.0),
            PersonalDataSavageClear(1228, null, null, null, null),
        )), fetch(PersonalDataDashboardSectionKind.SavageRaids, """[
            {"territory_type":"1226","log_time":"2026-09-20 10:30:00","no_limit":"1","job_name":"Paladin","elapsed_time":"7200.5"},
            {"territory_type":1227,"no_limit":0,"elapsed_time":0},
            {"territory_type":1228,"no_limit":true,"elapsed_time":"NaN"}]"""))
    }

    @Test fun missingOrInvalidCriticalIdentityCannotBecomeAnUnknownEmptyRecord() = runTest {
        val ids = mapOf(PersonalDataDashboardSectionKind.FishingAchievements to "achieve_id",
            PersonalDataDashboardSectionKind.OceanFishing to "territory_type", PersonalDataDashboardSectionKind.Stains to "catalog_id",
            PersonalDataDashboardSectionKind.Accessories to "ornament", PersonalDataDashboardSectionKind.SavageRaids to "territory_type")
        for ((section, field) in ids) {
            assertMissing { fetch(section, "[{}]") }
            for (raw in listOf("null", "false", "{}", "-1", "1.5", "\"1e2\"", "2147483648")) {
                assertMissing { fetch(section, """[{"$field":$raw}]""") }
            }
        }
        for (raw in listOf("null", "12", "\"\"", "\" \"")) {
            assertMissing { fetch(PersonalDataDashboardSectionKind.BigFish, """[{"catalog_name":$raw}]""") }
            assertMissing { fetch(PersonalDataDashboardSectionKind.Races, """[{"race":$raw,"gender":"Female"}]""") }
        }
    }

    @Test fun optionalCountsKeepLongPrecisionAndRejectFractionsOrOverflow() = runTest {
        val result = fetch(PersonalDataDashboardSectionKind.Stains, """[
            {"catalog_id":1,"color_times":9007199254740993,"rn":"9223372036854775807"},
            {"catalog_id":2,"color_times":"12.0","rn":"1e2"},
            {"catalog_id":3,"color_times":9223372036854775808,"rn":1.5}]""") as PersonalDataDashboardData.Stains
        assertEquals(listOf(PersonalDataStainUsage(1, 9007199254740993L, Long.MAX_VALUE),
            PersonalDataStainUsage(2, 12, 100), PersonalDataStainUsage(3, null, null)), result.rows)
    }

    @Test fun datesUseTheSharedStrictRuleWithoutUnixUnitGuessingOrCalendarRollover() = runTest {
        for (date in listOf("2026-09-20T02:30:00Z", "2026-09-20T10:30:00+08:00", "2026-09-20T10:30:00", "2026-09-20 10:30:00")) {
            val result = fetch(PersonalDataDashboardSectionKind.BigFish,
                """[{"catalog_name":"Fish","log_time":"$date"}]""") as PersonalDataDashboardData.BigFish
            assertEquals(Instant.parse("2026-09-20T02:30:00Z"), result.rows.single().caughtAt)
        }
        for (raw in listOf("1710000000000", "\"1710000000\"", "\"2026-02-30 10:00:00\"", "\"bad\"")) {
            val result = fetch(PersonalDataDashboardSectionKind.FishingAchievements,
                """[{"achieve_id":1,"log_time":$raw}]""") as PersonalDataDashboardData.FishingAchievements
            assertNull(result.rows.single().obtainedAt)
        }
    }

    @Test fun noNewSectionCanBypassPersonalDataCapability() = runTest {
        val session = DashboardSession().apply { enabled = false }
        val transport = DashboardTransport()
        for (section in endpoints.keys) assertAuthentication { service(transport, session).fetchDashboardSection(section) }
        assertTrue(transport.requests.isEmpty())
        assertTrue(session.contexts.isEmpty())
    }

    @Test fun revocationWhileReadingDiscardsAllTypesOfSuccessfulPayload() = runTest {
        for (section in endpoints.keys) {
            val session = DashboardSession()
            val transport = DashboardTransport { _, _ -> session.enabled = false; data("[]") }
            assertAuthentication { service(transport, session).fetchDashboardSection(section) }
            assertEquals(1, transport.requests.size)
            assertEquals(0, session.refreshes)
        }
    }

    @Test fun cancelledNonCooperativeReadsNeverReturnAnEmptySuccessForAnySection() = runTest {
        for (section in endpoints.keys) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val transport = DashboardTransport { _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); finish.await() }; data("[]")
            }
            var published = false
            val job = launch { service(transport).fetchDashboardSection(section); published = true }
            entered.await(); job.cancel(); finish.complete(Unit); job.join()
            assertTrue(job.isCancelled)
            assertFalse(published)
        }
    }

    @Test fun recoveryUsesTheSameUserAgentAndOneBudgetAcrossHttpAndEnvelopeFailures() = runTest {
        val session = DashboardSession()
        val transport = DashboardTransport { _, count -> if (count == 1) envelope("{}", 401) else data("[]") }
        assertEquals(PersonalDataDashboardData.OceanFishing(emptyList()),
            service(transport, session).fetchDashboardSection(PersonalDataDashboardSectionKind.OceanFishing))
        assertEquals(1, session.refreshes)
        assertEquals(listOf("Fixture browser UA", "Fixture browser UA"), transport.requests.map { it.headers["User-Agent"] })
        val exhausted = DashboardSession()
        val retry = DashboardTransport { _, count -> if (count == 1) envelope("{}", 401) else envelope("""{"code":10001}""") }
        assertAuthentication { service(retry, exhausted).fetchDashboardSection(PersonalDataDashboardSectionKind.SavageSummary) }
        assertEquals(1, exhausted.refreshes)
        assertEquals(2, retry.requests.size)
    }

    @Test fun acceptedCodeRetainsPayloadValidationAndBusinessErrorsStaySanitized() = runTest {
        val session = DashboardSession()
        val transport = DashboardTransport { _, _ -> envelope("""{"code":10002,"msg":"未登录","data":[]}""") }
        for (section in endpoints.keys) assertEquals(emptySections[section], service(transport, session).fetchDashboardSection(section))
        assertEquals(0, session.refreshes)
        assertMissing { service(DashboardTransport { _, _ -> envelope("""{"code":10002}""") }).fetchDashboardSection(PersonalDataDashboardSectionKind.OceanFishing) }
        try {
            service(DashboardTransport { _, _ -> envelope("""{"code":12345,"msg":"PRIVATE_RESPONSE"}""") })
                .fetchDashboardSection(PersonalDataDashboardSectionKind.OceanFishing)
            fail("Expected business failure")
        } catch (failure: PersonalDataException.Business) {
            assertEquals(12345, failure.code)
            assertFalse(failure.toString().contains("PRIVATE_RESPONSE"))
        }
    }

    @Test fun unrelatedAndPrivateFieldsNeverEnterTheTypedModels() = runTest {
        val result = fetch(PersonalDataDashboardSectionKind.Vanity,
            """[{"Name":"Official name","character_id":"PRIVATE_RESPONSE","user_name":"PRIVATE_RESPONSE","unknown":{"nested":"PRIVATE_RESPONSE"}}]""")
        assertEquals(PersonalDataDashboardData.Vanity(listOf(PersonalDataVanityUsage(PersonalDataVanityPeriod.Unknown,
            null, null, "Official name", null, null))), result)
        assertFalse(result.toString().contains("PRIVATE_RESPONSE"))
    }

    @Test fun supplementaryCatalogsUseOptionalProviderAndNeverAuthorizePersonalData() = runTest {
        val session = DashboardSession().apply { enabled = false }
        val transport = DashboardTransport()
        val provider = DashboardCatalogs()
        val actual = service(transport, session, provider).fetchSupplementaryCatalogs()
        assertEquals(provider.expected, actual)
        assertEquals(1, provider.extraReads)
        assertEquals(0, provider.originalReads)
        assertEquals(PersonalDataSupplementaryCatalogs(), service(transport, session).fetchSupplementaryCatalogs())
        assertTrue(transport.requests.isEmpty())
        assertTrue(session.contexts.isEmpty())
    }

    @Test fun supplementaryCatalogCancellationIsPropagatedWithoutFallbackToEmpty() = runTest {
        val provider = DashboardCatalogs().apply { cancel = true }
        try {
            service(DashboardTransport(), DashboardSession(), provider).fetchSupplementaryCatalogs()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, provider.extraReads)
        assertEquals(0, provider.originalReads)
    }

    @Test fun dashboardImageBridgesArePublicResourcesWithoutAuthorizationOrDataReads() {
        val session = DashboardSession().apply { enabled = false }
        val transport = DashboardTransport()
        val service: PersonalDataDashboardService = service(transport, session)
        assertEquals(personalDataAchievementIconUrl(300), service.achievementIconUrl(300))
        assertEquals(personalDataRaidImageUrl(200), service.raidImageUrl(200))
        assertNotNull(service.achievementIconUrl(300))
        assertNotNull(service.raidImageUrl(200))
        assertNull(service.achievementIconUrl(0))
        assertNull(service.raidImageUrl(-1))
        assertTrue(transport.requests.isEmpty())
        assertTrue(session.contexts.isEmpty())
    }

    private suspend fun fetch(section: PersonalDataDashboardSectionKind, rows: String) =
        service(DashboardTransport { _, _ -> data(rows) }).fetchDashboardSection(section)
    private fun service(transport: DashboardTransport, session: DashboardSession = DashboardSession(),
        catalogs: PersonalDataCatalogProvider = EmptyPersonalDataCatalogProvider) = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session, catalogs,
        temporarySessionId = "dashboard-fixture")
    private suspend fun assertMissing(block: suspend () -> Any?) {
        try { block(); fail("Expected MissingPayload") } catch (_: PersonalDataException.MissingPayload) { }
    }
    private suspend fun assertAuthentication(block: suspend () -> Any?) {
        try { block(); fail("Expected AuthenticationRequired") } catch (_: PersonalDataException.AuthenticationRequired) { }
    }
}

private val endpoints = linkedMapOf(
    PersonalDataDashboardSectionKind.FishingSummary to "fishTotal1", PersonalDataDashboardSectionKind.FishRanking to "fishNum2",
    PersonalDataDashboardSectionKind.BaitRanking to "fishBait3", PersonalDataDashboardSectionKind.BigFish to "fishBig4",
    PersonalDataDashboardSectionKind.FishingAchievements to "fishAchieve5", PersonalDataDashboardSectionKind.OceanFishing to "fishSea6",
    PersonalDataDashboardSectionKind.GlamourSummary to "getDressTotal7", PersonalDataDashboardSectionKind.Races to "getDressRace1",
    PersonalDataDashboardSectionKind.Stains to "getDressColor2", PersonalDataDashboardSectionKind.Accessories to "getDressOrnament3",
    PersonalDataDashboardSectionKind.Vanity to "getDressVanity4", PersonalDataDashboardSectionKind.Sets to "getDressFullset5",
    PersonalDataDashboardSectionKind.SavageSummary to "getLingShiTotal", PersonalDataDashboardSectionKind.SavageRaids to "getLingShi",
)
private val emptySections = linkedMapOf(
    PersonalDataDashboardSectionKind.FishingSummary to PersonalDataDashboardData.FishingSummary(null),
    PersonalDataDashboardSectionKind.FishRanking to PersonalDataDashboardData.FishRanking(emptyList()),
    PersonalDataDashboardSectionKind.BaitRanking to PersonalDataDashboardData.BaitRanking(emptyList()),
    PersonalDataDashboardSectionKind.BigFish to PersonalDataDashboardData.BigFish(emptyList()),
    PersonalDataDashboardSectionKind.FishingAchievements to PersonalDataDashboardData.FishingAchievements(emptyList()),
    PersonalDataDashboardSectionKind.OceanFishing to PersonalDataDashboardData.OceanFishing(emptyList()),
    PersonalDataDashboardSectionKind.GlamourSummary to PersonalDataDashboardData.GlamourSummary(null),
    PersonalDataDashboardSectionKind.Races to PersonalDataDashboardData.Races(emptyList()),
    PersonalDataDashboardSectionKind.Stains to PersonalDataDashboardData.Stains(emptyList()),
    PersonalDataDashboardSectionKind.Accessories to PersonalDataDashboardData.Accessories(emptyList()),
    PersonalDataDashboardSectionKind.Vanity to PersonalDataDashboardData.Vanity(emptyList()),
    PersonalDataDashboardSectionKind.Sets to PersonalDataDashboardData.Sets(emptyList()),
    PersonalDataDashboardSectionKind.SavageSummary to PersonalDataDashboardData.SavageSummary(null),
    PersonalDataDashboardSectionKind.SavageRaids to PersonalDataDashboardData.SavageRaids(emptyList()),
)

private class DashboardSession : RisingStonesSessionProvider {
    var enabled = true
    override val capabilities get() = if (enabled) setOf(RisingStonesCapability.PersonalData) else emptySet()
    val contexts = mutableListOf<RisingStonesRequestContext>()
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, sink ->
        contexts += context
        sink.set("Authorization", "Fixture token")
        sink.set("User-Agent", "Fixture browser UA")
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return currentAuthorizer() }
}
private class DashboardTransport(
    private val respond: suspend (RisingStonesHttpRequest, Int) -> RisingStonesHttpResponse = { _, _ -> data("[]") },
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return respond(request, requests.size)
    }
}
private class DashboardCatalogs : PersonalDataCatalogProvider, PersonalDataSupplementaryCatalogProvider {
    var originalReads = 0
    var extraReads = 0
    var cancel = false
    val expected = PersonalDataSupplementaryCatalogs(oceanFish = listOf(PersonalDataOceanFishCatalogEntry(1, 2, "Ocean fish")))
    override suspend fun fetchCatalogs(): PersonalDataOfficialCatalogs { originalReads++; return PersonalDataOfficialCatalogs() }
    override suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs {
        extraReads++
        if (cancel) throw CancellationException("Fixture cancellation")
        return expected
    }
}
private fun data(rows: String) = envelope("""{"code":10000,"data":$rows}""")
private fun envelope(payload: String, status: Int = 200) = RisingStonesHttpResponse(status, emptyMap(), payload.encodeToByteArray())
