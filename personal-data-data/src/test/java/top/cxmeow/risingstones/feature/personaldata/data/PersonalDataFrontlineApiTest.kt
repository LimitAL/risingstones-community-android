package top.cxmeow.risingstones.feature.personaldata.data

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

class PersonalDataFrontlineApiTest {
    @Test fun sevenSectionsUseFullGetArraysWithOnlyTheCommonQueryAndOriginalUserAgent() = runTest {
        val session = FrontlineSession()
        val transport = FrontlineTransport()
        val service: PersonalDataFrontlineService = service(transport, session)
        for (section in frontlineEndpoints.keys) service.fetchFrontlineSection(section)
        assertEquals(frontlineEndpoints.values.toList(), transport.requests.map { it.url.toHttpUrl().encodedPath })
        for (request in transport.requests) {
            assertEquals(RisingStonesHttpMethod.Get, request.method)
            assertNull(request.body)
            assertEquals(setOf("tempsuid"), request.url.toHttpUrl().queryParameterNames)
            assertEquals("frontline-fixture", request.url.toHttpUrl().queryParameter("tempsuid"))
            assertEquals("Fixture browser UA", request.headers["User-Agent"])
        }
        assertEquals(7, session.contexts.size)
        assertTrue(session.contexts.all { it.capability == RisingStonesCapability.PersonalData &&
            it.requirement == RisingStonesAuthenticationRequirement.Required })
    }

    @Test fun explicitEmptyArraysConfirmEachTypedEmptyResult() = runTest {
        for ((section, expected) in frontlineEmptySections) assertEquals(section.name, expected, fetch(section, "[]"))
    }

    @Test fun missingNullScalarAndWrappedRootsCannotConfirmEmptyData() = runTest {
        for (payload in listOf("""{"code":10000}""", """{"code":10000,"data":null}""",
            """{"code":10000,"data":{}}""", """{"code":10000,"data":{"rows":[]}}""",
            """{"code":10000,"data":false}""", """{"code":10000,"data":"[]"}""")) {
            for (section in frontlineEndpoints.keys) assertMissing {
                service(FrontlineTransport { _, _ -> frontlineEnvelope(payload) }).fetchFrontlineSection(section)
            }
        }
    }

    @Test fun invalidArrayMembersCannotBeSilentlyDroppedAfterAValidRow() = runTest {
        for ((section, row) in frontlineValidRows) {
            for (invalid in listOf("null", "false", "123", "\"row\"", "[]")) {
                assertMissing { fetch(section, "[$row,$invalid]") }
            }
        }
    }

    @Test fun identityFieldsRequireNonBlankStringsRatherThanNumberOrObjectCoercion() = runTest {
        val identities = listOf(
            Triple(PersonalDataFrontlineSection.Overview, "data_time", ""),
            Triple(PersonalDataFrontlineSection.Weekly, "part_date", ""),
            Triple(PersonalDataFrontlineSection.Jobs, "data_time", "\"job_name\":\"Job\","),
            Triple(PersonalDataFrontlineSection.Jobs, "job_name", "\"data_time\":\"total\","),
            Triple(PersonalDataFrontlineSection.Best, "best_type", ""),
            Triple(PersonalDataFrontlineSection.Maps, "territory_type", ""),
            Triple(PersonalDataFrontlineSection.MapJobs, "territory_type", "\"job_name\":\"Job\","),
            Triple(PersonalDataFrontlineSection.MapJobs, "job_name", "\"territory_type\":\"Map\","),
        )
        for ((section, key, other) in identities) {
            for (raw in listOf("null", "false", "1", "{}", "[]", "\"\"", "\"  \"")) {
                assertMissing { fetch(section, "[{$other\"$key\":$raw}]") }
            }
            assertMissing { fetch(section, "[{${other.removeSuffix(",")}}]") }
        }
    }

    @Test fun periodsAndDuplicateRowsArePreservedWithoutTotalFallbackOrAggregation() = runTest {
        val periods = listOf("v51", "future", "total", "30days", "total", " total ")
        val rows = periods.mapIndexed { index, period -> """{"data_time":"$period","fight_times":$index}""" }
        val result = fetch(PersonalDataFrontlineSection.Overview, rows.joinToString(",", "[", "]")) as PersonalDataFrontlineData.Overview
        assertEquals(listOf(FrontlinePeriodKind.Since51, null, FrontlinePeriodKind.Total, FrontlinePeriodKind.Last30Days,
            FrontlinePeriodKind.Total, null), result.rows.map { it.period })
        assertEquals((0L..5L).toList(), result.rows.map { it.battles })
    }

    @Test fun overviewMapsHoursFractionsAndNamedCompanyWithoutFieldOrUnitGuessing() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Overview, """[{
            "data_time":"total","fight_times":"30","win_times":12,"kill_times":"90","win_rate":"0.4","kda":"3.125",
            "gc_id":"恒辉队","pvp_rank":"50","series_level":25,"clear_time":"12.75","occupy_count":8,
            "avg_kill":"3.0","avg_assist":6.25,"avg_dead":"2.5","avg_damage":999,
            "kill_rank":80,"heal_rank":"50.5","damaged_rank":100,"damage_rank":0,"dead_rank":72,"assist_rank":99}]""") as PersonalDataFrontlineData.Overview
        assertEquals(FrontlineOverviewRecord(FrontlinePeriodKind.Total, 30, 12, 90, 0.4, 3.125, "恒辉队", 50, 25, 12.75, 8,
            FrontlineAverages(3.0, 6.25, 2.5), FrontlineRanks(80.0, 50.5, 100.0, 0.0, 72.0, 99.0)), result.rows.single())
    }

    @Test fun missingOverviewNumbersStayUnknownAndNumericCompanyIdsAreNotInventedNames() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Overview, """[{"data_time":"total","gc_id":1}]""") as PersonalDataFrontlineData.Overview
        assertEquals(FrontlineOverviewRecord(FrontlinePeriodKind.Total, null, null, null, null, null, null, null, null, null, null,
            FrontlineAverages(), FrontlineRanks(null, null, null, null, null, null)), result.rows.single())
    }

    @Test fun malformedNegativeAndNonFiniteNumbersRemainUnknown() = runTest {
        for (raw in listOf("null", "false", "{}", "[]", "\"\"", "\"NaN\"", "\"Infinity\"", "-1", "1e999")) {
            val result = fetch(PersonalDataFrontlineSection.Overview,
                """[{"data_time":"total","fight_times":$raw,"win_rate":$raw,"kda":$raw,"clear_time":$raw,"kill_rank":$raw}]""") as PersonalDataFrontlineData.Overview
            val row = result.rows.single()
            assertNull(row.battles); assertNull(row.winRate); assertNull(row.kda); assertNull(row.elapsedHours); assertNull(row.ranks.kills)
        }
    }

    @Test fun exactLongCountsDoNotTruncateFractionalValuesOverflowOrLoseDoublePrecision() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Overview, """[{
            "data_time":"total","fight_times":9007199254740993,"win_times":"9223372036854775807","kill_times":1.5,
            "pvp_rank":9223372036854775808,"series_level":"1e1","occupy_count":"2.000"}]""") as PersonalDataFrontlineData.Overview
        val row = result.rows.single()
        assertEquals(9007199254740993L, row.battles); assertEquals(Long.MAX_VALUE, row.wins)
        assertNull(row.kills); assertNull(row.pvpRank); assertEquals(10L, row.seriesLevel); assertEquals(2L, row.occupiedObjectives)
    }

    @Test fun decimalRangeIsValidatedBeforeDoubleRoundingCanConcealInvalidValues() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Overview, """[{
            "data_time":"total","clear_time":"-1e-999","kda":-1e-999,"avg_kill":"-1e-999",
            "win_rate":"1.0000000000000000000000001","kill_rank":"100.0000000000000000000001",
            "heal_rank":"-1e-999"}]""") as PersonalDataFrontlineData.Overview
        val row = result.rows.single()
        assertNull(row.elapsedHours); assertNull(row.kda); assertNull(row.averages.kills)
        assertNull(row.winRate); assertNull(row.ranks.kills); assertNull(row.ranks.healing)
        val job = fetch(PersonalDataFrontlineSection.Jobs, """[{
            "data_time":"total","job_name":"Job","use_rate":"-1e-999",
            "kda_rate":"1.0000000000000000000000001"}]""") as PersonalDataFrontlineData.Jobs
        assertNull(job.rows.single().useRate); assertNull(job.rows.single().kdaPercentile)
    }

    @Test fun nonnegativeTinyDecimalsRemainValidEvenWhenDoubleRepresentationUnderflows() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Overview, """[{
            "data_time":"total","clear_time":"1e-999","win_rate":"1e-999","kill_rank":"1e-999",
            "kda":"1e-300"}]""") as PersonalDataFrontlineData.Overview
        val row = result.rows.single()
        assertEquals(0.0, row.elapsedHours); assertEquals(0.0, row.winRate); assertEquals(0.0, row.ranks.kills)
        assertEquals(1e-300, row.kda)
    }

    @Test fun validZerosAndUpperFractionRadarBoundsRemainDistinctFromUnknown() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Overview, """[{
            "data_time":"total","fight_times":0,"win_rate":1,"kda":0,"clear_time":0,
            "kill_rank":100,"heal_rank":0,"damaged_rank":100.01,"damage_rank":-0.01}]""") as PersonalDataFrontlineData.Overview
        val row = result.rows.single()
        assertEquals(0L, row.battles); assertEquals(1.0, row.winRate); assertEquals(0.0, row.kda); assertEquals(0.0, row.elapsedHours)
        assertEquals(100.0, row.ranks.kills); assertEquals(0.0, row.ranks.healing)
        assertNull(row.ranks.damageTaken); assertNull(row.ranks.damage)
    }

    @Test fun weeklyMapsCountsWithoutReadingAnUnprovenWinRateOrAssistAlias() = runTest {
        assertEquals(PersonalDataFrontlineData.Weekly(listOf(FrontlineDayRecord(FrontlineDayStamp.CalendarDate(LocalDate.of(2024, 2, 29)),
            9, 3, 21, 0, 45))), fetch(PersonalDataFrontlineSection.Weekly, """[{
            "part_date":"2024-02-29","fight_times":"9","win_times":3,"kill_times":21,"dead_times":0,
            "assist_times":"45","assist":999,"win_rate":"PRIVATE_RESPONSE"}]"""))
    }

    @Test fun dayStampsSeparateCalendarLocalAndOffsetValuesWithoutApplyingAGuessedZone() = runTest {
        val inputs = listOf("2026-09-20", "2026-09-20T01:02:03", "2026-09-20 01:02:03.125", "2026-09-20 01:02",
            "2026-09-20T00:30:00+08:00", "2026-09-19T16:30:00Z")
        val result = fetch(PersonalDataFrontlineSection.Weekly,
            inputs.joinToString(",", "[", "]") { """{"part_date":"$it"}""" }) as PersonalDataFrontlineData.Weekly
        assertEquals(listOf(FrontlineDayStamp.CalendarDate(LocalDate.of(2026, 9, 20)),
            FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2, 3)),
            FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2, 3, 125000000)),
            FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2)),
            FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-19T16:30:00Z")),
            FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-19T16:30:00Z"))), result.rows.map { it.day })
    }

    @Test fun unrecognisedNonBlankDaysRemainRecordsWithUnknownDatesInsteadOfGuessedUnixOrCalendarValues() = runTest {
        for (date in listOf("1710000000000", "1710000000", "2026-02-29", "2024-02-30 10:00:00", "2026-13-01",
            "2026-09-20T25:00:00", "2026/09/20", "2026年9月20日", "future-date")) {
            val result = fetch(PersonalDataFrontlineSection.Weekly,
                """[{"part_date":"$date","fight_times":1}]""") as PersonalDataFrontlineData.Weekly
            assertNull(date, result.rows.single().day); assertEquals(1L, result.rows.single().battles)
        }
    }

    @Test fun jobsUseTheirOwnCountAndAverageFieldsAndKeepKdaSeparateFromPercentile() = runTest {
        assertEquals(PersonalDataFrontlineData.Jobs(listOf(FrontlineJobRecord(FrontlinePeriodKind.Last30Days, "Job & <literal>",
            40, 0.25, 80, 0.4, 2.125, 0.84, 8, FrontlineAverages(2.0, 3.0, 4.0, 12345.5, 4567.75, 7890.25)))),
            fetch(PersonalDataFrontlineSection.Jobs, """[{
                "data_time":"30days","job_name":"Job & <literal>","times":"40","fight_times":999,"use_rate":"0.25",
                "kill_times":80,"win_rate":0.4,"kda":"2.125","kda_rate":"0.84","lb_times":8,
                "avg_kill":2,"avg_assist":3,"avg_dead":4,"avg_damage":12345.5,"avg_heal":4567.75,"avg_damaged":7890.25}]"""))
    }

    @Test fun jobUnknownPeriodsAndOutOfRangeRatesDoNotAcquireTotalOrPercentageInterpretations() = runTest {
        val result = fetch(PersonalDataFrontlineSection.Jobs, """[{
            "data_time":"next","job_name":"Future job","times":1.5,"use_rate":25,"win_rate":84.5,"kda_rate":1.001,
            "avg_damage":-1,"avg_heal":"Infinity","avg_damaged":1e999}]""") as PersonalDataFrontlineData.Jobs
        assertEquals(FrontlineJobRecord(null, "Future job", null, null, null, null, null, null, null, FrontlineAverages()), result.rows.single())
    }

    @Test fun bestKindsAndDuplicatesKeepSourceOrderIncludingUnknownKinds() = runTest {
        val kinds = listOf("kill", "assist", "damage", "damaged", "heal", "future", "kill")
        val result = fetch(PersonalDataFrontlineSection.Best,
            kinds.joinToString(",", "[", "]") { """{"best_type":"$it"}""" }) as PersonalDataFrontlineData.Best
        assertEquals(listOf(FrontlineBestKind.Kills, FrontlineBestKind.Assists, FrontlineBestKind.Damage,
            FrontlineBestKind.DamageTaken, FrontlineBestKind.Healing, FrontlineBestKind.Unknown, FrontlineBestKind.Kills), result.rows.map { it.kind })
    }

    @Test fun bestUsesAssistAndCareerAndKeepsTeamNumbersInOfficialThreeTwoOneOrder() = runTest {
        assertEquals(PersonalDataFrontlineData.Best(listOf(FrontlineBestRecord(FrontlineBestKind.Assists, "Unknown map",
            FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 10, 30)), "Paladin", FrontlinePlacement.First, 10, 2, 38, 98765, 54321, 4567,
            listOf(FrontlineTeamScore(3, 300), FrontlineTeamScore(2, 200), FrontlineTeamScore(1, 100))))),
            fetch(PersonalDataFrontlineSection.Best, """[{
                "best_type":"assist","territory_type":"Unknown map","log_time":"2026-09-20 10:30:00","career":"Paladin",
                "job_name":"Wrong alias","result_rank":"0","kill_times":10,"dead_times":2,"assist":38,"assist_times":999,
                "total_damage":98765,"total_damaged":54321,"total_heal":4567,"team1_score":100,"team2_score":200,"team3_score":300}]"""))
    }

    @Test fun placementsOnlyAcceptZeroOneTwoAndScoresDoNotFillMissingTeamsWithZero() = runTest {
        val inputs = listOf("0", "\"1\"", "2", "3", "-1", "1.5", "true", "null")
        val result = fetch(PersonalDataFrontlineSection.Best,
            inputs.joinToString(",", "[", "]") { """{"best_type":"kill","result_rank":$it,"team1_score":0,"team2_score":-1}""" }) as PersonalDataFrontlineData.Best
        assertEquals(listOf(FrontlinePlacement.First, FrontlinePlacement.Second, FrontlinePlacement.Third, null, null, null, null, null), result.rows.map { it.placement })
        assertTrue(result.rows.all { it.scores == listOf(FrontlineTeamScore(3, null), FrontlineTeamScore(2, null), FrontlineTeamScore(1, 0)) })
    }

    @Test fun mapsKeepLiteralNamesAndDuplicatesWithoutTreatingTerritoryAsAnId() = runTest {
        assertEquals(PersonalDataFrontlineData.Maps(listOf(FrontlineMapRecord("Unknown & <map>", 10, 4, 25, 0.4),
            FrontlineMapRecord("Unknown & <map>", null, null, null, null))),
            fetch(PersonalDataFrontlineSection.Maps, """[
                {"territory_type":"Unknown & <map>","fight_times":10,"win_times":"4","kill_times":25,"win_rate":"0.4"},
                {"territory_type":"Unknown & <map>"}]"""))
    }

    @Test fun mapJobsUseOnlyTheJobPrefixedCountersAndRate() = runTest {
        assertEquals(PersonalDataFrontlineData.MapJobs(listOf(FrontlineMapJobRecord("Map", "Job", 7, 2, 15, 0.285))),
            fetch(PersonalDataFrontlineSection.MapJobs, """[{
                "territory_type":"Map","job_name":"Job","job_num":"7","job_win_times":2,"job_kill_times":15,"job_win_rate":0.285,
                "fight_times":999,"win_times":999,"kill_times":999,"win_rate":1}]"""))
    }

    @Test fun achievementsPreserveDuplicatesAndLiteralOfficialTextWithExplicitOffsetTime() = runTest {
        assertEquals(PersonalDataFrontlineData.Achievements(listOf(
            FrontlineAchievementRecord(123, "Title & <literal>", "Detail", FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-20T02:30:00Z"))),
            FrontlineAchievementRecord(123, null, null, null))), fetch(PersonalDataFrontlineSection.Achievements, """[
                {"achieve_id":"123","achieve_name":"Title & <literal>","achieve_detail":"Detail","log_time":"2026-09-20T10:30:00+08:00"},
                {"achieve_id":123,"log_time":"1710000000000"}]"""))
    }

    @Test fun bestAndAchievementTimesPreserveLocalCalendarAndOffsetSemantics() = runTest {
        val inputs = listOf("2024-02-29", "2026-09-20T01:02:03", "2026-09-20 01:02:03.125",
            "2026-09-20T00:30:00+08:00", "2026-09-19T16:30:00Z")
        val expected = listOf(FrontlineDayStamp.CalendarDate(LocalDate.of(2024, 2, 29)),
            FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2, 3)),
            FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2, 3, 125000000)),
            FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-19T16:30:00Z")),
            FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-19T16:30:00Z")))
        val best = fetch(PersonalDataFrontlineSection.Best,
            inputs.joinToString(",", "[", "]") { """{"best_type":"kill","log_time":"$it"}""" }) as PersonalDataFrontlineData.Best
        val achievements = fetch(PersonalDataFrontlineSection.Achievements,
            inputs.joinToString(",", "[", "]") { """{"achieve_id":1,"log_time":"$it"}""" }) as PersonalDataFrontlineData.Achievements
        assertEquals(expected, best.rows.map { it.recordedAt })
        assertEquals(expected, achievements.rows.map { it.obtainedAt })
    }

    @Test fun missingInvalidAndUnprovenBestAndAchievementTimesStayUnknownWithoutDroppingRecords() = runTest {
        val values = listOf("null", "false", "{}", "[]", "\"\"", "1710000000000", "\"1710000000\"",
            "\"2026-02-29\"", "\"2024-02-30 10:00:00\"", "\"future-date\"")
        val best = fetch(PersonalDataFrontlineSection.Best, (listOf("""{"best_type":"kill"}""") +
            values.map { """{"best_type":"kill","log_time":$it}""" }).joinToString(",", "[", "]")) as PersonalDataFrontlineData.Best
        val achievements = fetch(PersonalDataFrontlineSection.Achievements, (listOf("""{"achieve_id":1}""") +
            values.map { """{"achieve_id":1,"log_time":$it}""" }).joinToString(",", "[", "]")) as PersonalDataFrontlineData.Achievements
        assertEquals(values.size + 1, best.rows.size); assertTrue(best.rows.all { it.recordedAt == null })
        assertEquals(values.size + 1, achievements.rows.size); assertTrue(achievements.rows.all { it.obtainedAt == null })
    }

    @Test fun achievementsRequirePositiveIntegerIdsWithoutTruncation() = runTest {
        for (raw in listOf("null", "true", "{}", "0", "-1", "1.5", "2147483648", "\"bad\"")) {
            assertMissing { fetch(PersonalDataFrontlineSection.Achievements, """[{"achieve_id":$raw}]""") }
        }
        assertMissing { fetch(PersonalDataFrontlineSection.Achievements, "[{}]") }
    }

    @Test fun unrelatedFieldsDoNotLeakThroughAnyTypedSection() = runTest {
        for ((section, row) in frontlineValidRows) {
            val result = fetch(section, "[${row.dropLast(1)},\"character_id\":\"PRIVATE_RESPONSE\",\"unknown\":{\"nested\":\"PRIVATE_RESPONSE\"}}]")
            assertFalse(section.name, result.toString().contains("PRIVATE_RESPONSE"))
        }
    }

    @Test fun allSectionsRequireTheExistingPersonalDataCapabilityBeforeReading() = runTest {
        val session = FrontlineSession().apply { enabled = false }
        val transport = FrontlineTransport()
        for (section in frontlineEndpoints.keys) assertAuthentication { service(transport, session).fetchFrontlineSection(section) }
        assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    @Test fun capabilityRevocationWhileReadingDiscardsEvenValidEmptyResults() = runTest {
        for (section in frontlineEndpoints.keys) {
            val session = FrontlineSession()
            val transport = FrontlineTransport { _, _ -> session.enabled = false; frontlineData("[]") }
            assertAuthentication { service(transport, session).fetchFrontlineSection(section) }
            assertEquals(1, transport.requests.size); assertEquals(0, session.refreshes)
        }
    }

    @Test fun nonCooperativeCancelledReadsNeverPublishSuccess() = runTest {
        for (section in frontlineEndpoints.keys) {
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val transport = FrontlineTransport { _, _ -> withContext(NonCancellable) { entered.complete(Unit); finish.await() }; frontlineData("[]") }
            var published = false
            val job = launch { service(transport).fetchFrontlineSection(section); published = true }
            entered.await(); job.cancel(); finish.complete(Unit); job.join()
            assertTrue(job.isCancelled); assertFalse(published)
        }
    }

    @Test fun httpRecoveryRetainsTheOriginalUserAgentAndSharesOneEnvelopeRecoveryBudget() = runTest {
        val session = FrontlineSession()
        val transport = FrontlineTransport { _, count -> if (count == 1) frontlineEnvelope("{}", 401) else frontlineData("[]") }
        assertEquals(frontlineEmptySections[PersonalDataFrontlineSection.Overview], service(transport, session).fetchFrontlineSection(PersonalDataFrontlineSection.Overview))
        assertEquals(1, session.refreshes)
        assertEquals(listOf("Fixture browser UA", "Fixture browser UA"), transport.requests.map { it.headers["User-Agent"] })
        val exhausted = FrontlineSession()
        val retry = FrontlineTransport { _, count -> if (count == 1) frontlineEnvelope("{}", 401) else frontlineEnvelope("""{"code":10001}""") }
        assertAuthentication { service(retry, exhausted).fetchFrontlineSection(PersonalDataFrontlineSection.Jobs) }
        assertEquals(1, exhausted.refreshes); assertEquals(2, retry.requests.size)
    }

    @Test fun acceptedResponseCodeDoesNotRefreshButStillRequiresEachSectionsPayload() = runTest {
        val session = FrontlineSession()
        val transport = FrontlineTransport { _, _ -> frontlineEnvelope("""{"code":10002,"msg":"未登录","data":[]}""") }
        for ((section, expected) in frontlineEmptySections) assertEquals(expected, service(transport, session).fetchFrontlineSection(section))
        assertEquals(0, session.refreshes)
        assertMissing { service(FrontlineTransport { _, _ -> frontlineEnvelope("""{"code":10002}""") }).fetchFrontlineSection(PersonalDataFrontlineSection.Maps) }
    }

    @Test fun businessAndHttpFailuresNeverExposeRawResponseTextOrTreatHttpFailureAsSuccess() = runTest {
        try {
            service(FrontlineTransport { _, _ -> frontlineEnvelope("""{"code":12345,"msg":"PRIVATE_RESPONSE"}""") })
                .fetchFrontlineSection(PersonalDataFrontlineSection.Best)
            fail("Expected business failure")
        } catch (failure: PersonalDataException.Business) {
            assertEquals(12345, failure.code); assertFalse(failure.toString().contains("PRIVATE_RESPONSE"))
        }
        try {
            service(FrontlineTransport { _, _ -> frontlineEnvelope("""{"code":10000,"data":[],"msg":"PRIVATE_RESPONSE"}""", 500) })
                .fetchFrontlineSection(PersonalDataFrontlineSection.Weekly)
            fail("Expected HTTP failure")
        } catch (failure: IOException) { assertFalse(failure.toString().contains("PRIVATE_RESPONSE")) }
    }

    @Test fun authorizerAndRefreshCancellationPropagateWithoutAnotherRequest() = runTest {
        for (duringRefresh in listOf(false, true)) {
            val session = FrontlineSession().apply { cancelAuthorization = !duringRefresh; cancelRefresh = duringRefresh }
            val transport = FrontlineTransport { _, _ -> frontlineEnvelope("{}", 401) }
            try { service(transport, session).fetchFrontlineSection(PersonalDataFrontlineSection.Overview); fail("Expected cancellation") }
            catch (_: CancellationException) { }
            assertEquals(if (duringRefresh) 1 else 0, transport.requests.size)
        }
    }

    @Test fun emptyAuthorizerCannotUsePublicTransportToReadProtectedSections() = runTest {
        val session = FrontlineSession().apply { writeHeaders = false }
        val transport = FrontlineTransport()
        assertAuthentication { service(transport, session).fetchFrontlineSection(PersonalDataFrontlineSection.Overview) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun frontlineCatalogsUseOnlyTheInjectedSupplementaryProviderAndNeverAuthorizeTheAccount() = runTest {
        val session = FrontlineSession().apply { enabled = false }
        val transport = FrontlineTransport()
        val catalogs = FrontlineCatalogs()
        val result = service(transport, session, catalogs).fetchFrontlineCatalogs()
        assertEquals(personalDataFrontlineMapNames, result.mapNames)
        assertEquals(catalogs.frontline, result.achievements)
        assertEquals(1, catalogs.extraReads); assertEquals(0, catalogs.originalReads)
        assertEquals(PersonalDataFrontlineCatalogs(personalDataFrontlineMapNames), service(transport, session).fetchFrontlineCatalogs())
        assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    @Test fun catalogCancellationPropagatesInsteadOfBecomingConfirmedEmpty() = runTest {
        val catalogs = FrontlineCatalogs().apply { cancel = true }
        try { service(FrontlineTransport(), catalogs = catalogs).fetchFrontlineCatalogs(); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, catalogs.extraReads)
    }

    @Test fun imageBridgesExposeOnlyPublicResourcesWithoutReadsOrCapabilityGrant() {
        val session = FrontlineSession().apply { enabled = false }
        val transport = FrontlineTransport()
        val service: PersonalDataFrontlineService = service(transport, session)
        assertEquals(personalDataFrontlineJobIconUrl("骑士", true), service.frontlineJobIconUrl("骑士"))
        assertEquals(personalDataFrontlineJobIconUrl("骑士", false), service.frontlineJobIconUrl("骑士", false))
        assertNotNull(service.frontlineJobIconUrl("骑士")); assertNull(service.frontlineJobIconUrl("Unknown job"))
        assertEquals(personalDataFrontlineGrandCompanyImageUrl("恒辉队"), service.frontlineCompanyFlagUrl("恒辉队"))
        assertNotNull(service.frontlineCompanyFlagUrl("恒辉队")); assertNull(service.frontlineCompanyFlagUrl("1"))
        assertEquals(personalDataFrontlineAchievementImageUrl(), service.frontlineAchievementImageUrl())
        assertFalse(service.hasCommunityIdentity); assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    private suspend fun fetch(section: PersonalDataFrontlineSection, rows: String) =
        service(FrontlineTransport { _, _ -> frontlineData(rows) }).fetchFrontlineSection(section)
    private fun service(transport: FrontlineTransport, session: FrontlineSession = FrontlineSession(),
        catalogs: PersonalDataCatalogProvider = EmptyPersonalDataCatalogProvider) = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session, catalogs, temporarySessionId = "frontline-fixture")
    private suspend fun assertMissing(block: suspend () -> Any?) {
        try { block(); fail("Expected MissingPayload") } catch (_: PersonalDataException.MissingPayload) { }
    }
    private suspend fun assertAuthentication(block: suspend () -> Any?) {
        try { block(); fail("Expected AuthenticationRequired") } catch (_: PersonalDataException.AuthenticationRequired) { }
    }
}

private val frontlineEndpoints = linkedMapOf(
    PersonalDataFrontlineSection.Overview to "/api/home/dataCenter/frontline1TotalNew",
    PersonalDataFrontlineSection.Weekly to "/api/home/dataCenter/frontline2WeekNew",
    PersonalDataFrontlineSection.Jobs to "/api/home/dataCenter/frontline3JobNew",
    PersonalDataFrontlineSection.Best to "/api/home/dataCenter/frontline4Best",
    PersonalDataFrontlineSection.Maps to "/api/home/dataCenter/frontline5Map",
    PersonalDataFrontlineSection.MapJobs to "/api/home/dataCenter/frontline6MapJob",
    PersonalDataFrontlineSection.Achievements to "/api/home/dataCenter/frontlineActiveDetail",
)
private val frontlineEmptySections = linkedMapOf(
    PersonalDataFrontlineSection.Overview to PersonalDataFrontlineData.Overview(emptyList()),
    PersonalDataFrontlineSection.Weekly to PersonalDataFrontlineData.Weekly(emptyList()),
    PersonalDataFrontlineSection.Jobs to PersonalDataFrontlineData.Jobs(emptyList()),
    PersonalDataFrontlineSection.Best to PersonalDataFrontlineData.Best(emptyList()),
    PersonalDataFrontlineSection.Maps to PersonalDataFrontlineData.Maps(emptyList()),
    PersonalDataFrontlineSection.MapJobs to PersonalDataFrontlineData.MapJobs(emptyList()),
    PersonalDataFrontlineSection.Achievements to PersonalDataFrontlineData.Achievements(emptyList()),
)
private val frontlineValidRows = linkedMapOf(
    PersonalDataFrontlineSection.Overview to """{"data_time":"total"}""",
    PersonalDataFrontlineSection.Weekly to """{"part_date":"2026-09-20"}""",
    PersonalDataFrontlineSection.Jobs to """{"data_time":"total","job_name":"Job"}""",
    PersonalDataFrontlineSection.Best to """{"best_type":"kill"}""",
    PersonalDataFrontlineSection.Maps to """{"territory_type":"Map"}""",
    PersonalDataFrontlineSection.MapJobs to """{"territory_type":"Map","job_name":"Job"}""",
    PersonalDataFrontlineSection.Achievements to """{"achieve_id":1}""",
)

private class FrontlineSession : RisingStonesSessionProvider {
    var enabled = true
    var cancelAuthorization = false
    var cancelRefresh = false
    var writeHeaders = true
    override val capabilities get() = if (enabled) setOf(RisingStonesCapability.PersonalData) else emptySet()
    val contexts = mutableListOf<RisingStonesRequestContext>()
    var refreshes = 0
    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer {
        if (cancelAuthorization) throw CancellationException("Fixture cancellation")
        return RisingStonesRequestAuthorizer { context, sink ->
            contexts += context
            if (writeHeaders) {
                sink.set("Authorization", "Fixture token")
                sink.set("User-Agent", "Fixture browser UA")
            }
        }
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshes++
        if (cancelRefresh) throw CancellationException("Fixture cancellation")
        return currentAuthorizer()
    }
}
private class FrontlineTransport(
    private val respond: suspend (RisingStonesHttpRequest, Int) -> RisingStonesHttpResponse = { _, _ -> frontlineData("[]") },
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return respond(request, requests.size)
    }
}
private class FrontlineCatalogs : PersonalDataCatalogProvider, PersonalDataSupplementaryCatalogProvider {
    var originalReads = 0
    var extraReads = 0
    var cancel = false
    val frontline = listOf(PersonalDataAchievementCatalogEntry(12, "Frontline achievement", "Detail", 100))
    override suspend fun fetchCatalogs(): PersonalDataOfficialCatalogs { originalReads++; return PersonalDataOfficialCatalogs() }
    override suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs {
        extraReads++
        if (cancel) throw CancellationException("Fixture cancellation")
        return PersonalDataSupplementaryCatalogs(frontlineAchievements = frontline,
            fishingAchievements = listOf(PersonalDataAchievementCatalogEntry(13, "Unrelated", null, null)))
    }
}
private fun frontlineData(rows: String) = frontlineEnvelope("""{"code":10000,"data":$rows}""")
private fun frontlineEnvelope(payload: String, status: Int = 200) = RisingStonesHttpResponse(status, emptyMap(), payload.encodeToByteArray())
