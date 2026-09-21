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

class PersonalDataUltimateApiTest {
    @Test fun sixReadsUseExactGetPathsAndOnlyTheirReviewedQueries() = runTest {
        val session = UltimateSession()
        val transport = UltimateTransport()
        val service: PersonalDataUltimateService = service(transport, session)
        service.fetchUltimateRecords()
        for (section in ultimateEndpoints.keys) service.fetchUltimateSection(9999, section)
        assertEquals(listOf("gaoNanFirst1") + ultimateEndpoints.values, transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
        for ((index, request) in transport.requests.withIndex()) {
            val url = request.url.toHttpUrl()
            assertEquals(RisingStonesHttpMethod.Get, request.method); assertNull(request.body)
            assertEquals("/api/home/dataCenter/", url.encodedPath.substringBeforeLast('/') + "/")
            assertEquals(if (index == 0) setOf("tempsuid") else setOf("tempsuid", "territory_type"), url.queryParameterNames)
            assertEquals(if (index == 0) null else "9999", url.queryParameter("territory_type"))
            assertEquals("ultimate-fixture", url.queryParameter("tempsuid"))
            assertEquals("Fixture browser UA", request.headers["User-Agent"])
        }
        assertEquals(6, session.contexts.size)
        assertTrue(session.contexts.all { it.capability == RisingStonesCapability.PersonalData &&
            it.requirement == RisingStonesAuthenticationRequirement.Required })
    }

    @Test fun recordsPreserveUnknownTerritoriesDuplicatesAndSourceOrderWithoutAggregation() = runTest {
        val result = records("""[
            {"territory_type":"9999","clear_times":1}, {"territory_type":733,"clear_times":2},
            {"territory_type":"733","clear_times":3}, {"territory_type":1363,"clear_times":4}]""")
        assertEquals(listOf(9999, 733, 733, 1363), result.map { it.territoryType })
        assertEquals(listOf(1L, 2L, 3L, 4L), result.map { it.clearCount })
    }

    @Test fun onlyTheFirstClearArrayAcceptsTheOfficialNullPlaceholders() = runTest {
        assertEquals(emptyList<PersonalDataUltimateRecord>(), records("[null,null]"))
        assertEquals(listOf(733, 9999), records("""[null,{"territory_type":733},null,{"territory_type":9999}]""").map { it.territoryType })
        for (section in ultimateEndpoints.keys) assertMissing { fetch(section, "[null]") }
    }

    @Test fun explicitEmptyArraysConfirmTypedEmptyResults() = runTest {
        assertEquals(emptyList<PersonalDataUltimateRecord>(), records("[]"))
        for ((section, expected) in ultimateEmptySections) assertEquals(expected, fetch(section, "[]"))
    }

    @Test fun missingNullScalarAndWrappedDataRootsNeverConfirmEmpty() = runTest {
        for (payload in listOf("""{"code":10000}""", """{"code":10000,"data":null}""",
            """{"code":10000,"data":{}}""", """{"code":10000,"data":{"rows":[]}}""",
            """{"code":10000,"data":false}""", """{"code":10000,"data":"[]"}""")) {
            val service = service(UltimateTransport { _, _ -> ultimateEnvelope(payload) })
            assertMissing { service.fetchUltimateRecords() }
            for (section in ultimateEndpoints.keys) assertMissing { service.fetchUltimateSection(733, section) }
        }
    }

    @Test fun invalidNonNullArrayMembersCannotSilentlyBecomePartialSuccess() = runTest {
        for (invalid in listOf("true", "12", "\"row\"", "[]")) {
            assertMissing { records("""[{"territory_type":733},$invalid]""") }
            for ((section, row) in ultimateValidRows) assertMissing { fetch(section, "[$row,$invalid]") }
        }
    }

    @Test fun recordsRequireExactPositiveTerritoryIdentityWithoutAliasesOrTruncation() = runTest {
        for (raw in listOf("null", "false", "{}", "[]", "0", "-1", "1.5", "2147483648", "\"bad\"", "\"\"")) {
            assertMissing { records("""[{"territory_type":$raw}]""") }
        }
        assertMissing { records("[{}]") }
        assertMissing { records("""[{"territoryType":733}]""") }
    }

    @Test fun nonpositiveRequestedTerritoriesAreRejectedBeforeAuthorizationOrTransport() = runTest {
        val session = UltimateSession()
        val transport = UltimateTransport()
        val service = service(transport, session)
        for (territory in listOf(0, -1, Int.MIN_VALUE)) for (section in ultimateEndpoints.keys) {
            try { service.fetchUltimateSection(territory, section); fail("Expected invalid territory") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    @Test fun recordsUseExactFirstClearFieldsAndKeepDurationInSeconds() = runTest {
        assertEquals(listOf(PersonalDataUltimateRecord(968, 21, 42, "Job & <literal>",
            UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 10, 30)), 7201, 100)), records("""[{
                "territory_type":"968","clear_times":"21","enter_before_clear":42,"job_name":"Job & <literal>",
                "log_time":"2026-09-20 10:30:00","elapsed_time":"7201","dead_times":100,
                "duration":999,"first_clear_job":"wrong","count":999}]"""))
    }

    @Test fun missingRecordNumbersAndInvalidAliasesRemainUnknownRatherThanZero() = runTest {
        assertEquals(listOf(PersonalDataUltimateRecord(733, null, null, null, null, null, null)),
            records("""[{"territory_type":733,"clearTimes":10,"enterBeforeClear":20,"jobName":"wrong","logTime":"2026-09-20",
                "elapsedTime":3600,"deadTimes":3}]"""))
    }

    @Test fun countParsingIsExactNonnegativeLongIncludingDurationWithoutParseIntTruncation() = runTest {
        val result = records("""[{
            "territory_type":733,"clear_times":9007199254740993,"enter_before_clear":"9223372036854775807",
            "elapsed_time":"7201.9","dead_times":9223372036854775808}]""").single()
        assertEquals(9007199254740993L, result.clearCount); assertEquals(Long.MAX_VALUE, result.entriesBeforeFirstClear)
        assertNull(result.firstClearDurationSeconds); assertNull(result.deathsBeforeFirstClear)
        val zeros = records("""[{"territory_type":733,"clear_times":0,"enter_before_clear":"0.0","elapsed_time":"1e1","dead_times":0}]""").single()
        assertEquals(0L, zeros.clearCount); assertEquals(0L, zeros.entriesBeforeFirstClear)
        assertEquals(10L, zeros.firstClearDurationSeconds); assertEquals(0L, zeros.deathsBeforeFirstClear)
    }

    @Test fun invalidCountsStayUnknownAcrossOverviewJobsAndPartners() = runTest {
        for (raw in listOf("null", "false", "{}", "[]", "\"\"", "\"NaN\"", "\"Infinity\"", "-1", "1e999", "1.5", "\"-1e-999\"")) {
            val result = records("""[{"territory_type":733,"clear_times":$raw,"enter_before_clear":$raw,"elapsed_time":$raw,"dead_times":$raw}]""").single()
            assertNull(result.clearCount); assertNull(result.entriesBeforeFirstClear); assertNull(result.firstClearDurationSeconds); assertNull(result.deathsBeforeFirstClear)
            val jobs = fetch(PersonalDataUltimateSection.Jobs, """[{"job_name":"Job","job_times":$raw}]""") as PersonalDataUltimateData.Jobs
            val partners = fetch(PersonalDataUltimateSection.Partners, """[{"team_chara_name":"Companion","friend_times":$raw}]""") as PersonalDataUltimateData.Partners
            assertNull(jobs.rows.single().times); assertNull(partners.rows.single().jointEntries)
        }
    }

    @Test fun partyUsesTheOfficialDoubleETypoAndPreservesMembersWithoutInventedIdentityIds() = runTest {
        assertEquals(PersonalDataUltimateData.Party(listOf(UltimatePartyMember("Literal & <member>", "Area", "Server", "Job"),
            UltimatePartyMember("Literal & <member>", null, null, null))), fetch(PersonalDataUltimateSection.Party, """[
                {"character_namee":"Literal & <member>","area_name":"Area","group_name":"Server","job_name":"Job","uuid":"PRIVATE_RESPONSE"},
                {"character_namee":"Literal & <member>"}]"""))
        assertMissing { fetch(PersonalDataUltimateSection.Party, """[{"character_name":"Wrong alias"}]""") }
    }

    @Test fun jobsKeepDuplicatesAndCountsWithoutSortingOrReadingTheOverallCounter() = runTest {
        assertEquals(PersonalDataUltimateData.Jobs(listOf(UltimateJobUsage("Job", 2), UltimateJobUsage("Unknown job", 9), UltimateJobUsage("Job", null))),
            fetch(PersonalDataUltimateSection.Jobs, """[
                {"job_name":"Job","job_times":"2","clear_times":999},{"job_name":"Unknown job","job_times":9},
                {"job_name":"Job","clear_times":999}]"""))
    }

    @Test fun partnersKeepExactNameServerAndJointEntryCounterIncludingDuplicateNames() = runTest {
        assertEquals(PersonalDataUltimateData.Partners(listOf(UltimateCompanion("Companion", "Area", "Server", 12),
            UltimateCompanion("Companion", null, null, null))), fetch(PersonalDataUltimateSection.Partners, """[
                {"team_chara_name":"Companion","area_name":"Area","group_name":"Server","friend_times":"12"},
                {"team_chara_name":"Companion","times":999}]"""))
        assertMissing { fetch(PersonalDataUltimateSection.Partners, """[{"character_name":"Wrong alias"}]""") }
    }

    @Test fun requiredTextIdentitiesDoNotAcceptBlankOrNonStringPayloads() = runTest {
        val fields = mapOf(PersonalDataUltimateSection.Party to "character_namee", PersonalDataUltimateSection.Jobs to "job_name",
            PersonalDataUltimateSection.Partners to "team_chara_name", PersonalDataUltimateSection.Phases to "phase")
        for ((section, field) in fields) {
            for (value in listOf("null", "false", "1", "{}", "[]", "\"\"", "\"  \"")) {
                assertMissing { fetch(section, "[{\"$field\":$value}]") }
            }
            assertMissing { fetch(section, "[{}]") }
        }
    }

    @Test fun phaseRecordsKeepRawPhaseIdentityAndOrderForLocalProjection() = runTest {
        assertEquals(PersonalDataUltimateData.Phases(listOf(UltimatePhaseRecord("finish", UltimateRecordTime.CalendarDate(LocalDate.of(2026, 9, 20))),
            UltimatePhaseRecord("p1", null), UltimatePhaseRecord("future-phase", null), UltimatePhaseRecord("p1", null))),
            fetch(PersonalDataUltimateSection.Phases, """[
                {"phase":"finish","log_time":"2026-09-20"},{"phase":"p1"},{"phase":"future-phase"},{"phase":"p1"}]"""))
    }

    @Test fun recordsAndPhasesPreserveCalendarLocalAndExplicitOffsetTime() = runTest {
        val inputs = listOf("2024-02-29", "2026-09-20T01:02:03", "2026-09-20 01:02:03.125", "2026-09-20 01:02",
            "2026-09-20T00:30:00+08:00", "2026-09-19T16:30:00Z")
        val expected = listOf(UltimateRecordTime.CalendarDate(LocalDate.of(2024, 2, 29)),
            UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2, 3)),
            UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2, 3, 125000000)),
            UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 2)),
            UltimateRecordTime.OffsetTime(Instant.parse("2026-09-19T16:30:00Z")),
            UltimateRecordTime.OffsetTime(Instant.parse("2026-09-19T16:30:00Z")))
        assertEquals(expected, records(inputs.joinToString(",", "[", "]") { """{"territory_type":733,"log_time":"$it"}""" }).map { it.firstClearAt })
        val phases = fetch(PersonalDataUltimateSection.Phases,
            inputs.joinToString(",", "[", "]") { """{"phase":"p1","log_time":"$it"}""" }) as PersonalDataUltimateData.Phases
        assertEquals(expected, phases.rows.map { it.reachedAt })
    }

    @Test fun invalidAndUnprovenTimesStayUnknownWithoutDroppingRecordsOrGuessingUnix() = runTest {
        for (raw in listOf("null", "false", "{}", "[]", "\"\"", "1710000000000", "\"1710000000\"",
            "\"2026-02-29\"", "\"2024-02-30 10:00:00\"", "\"2026-09-20T25:00:00\"", "\"future-date\"")) {
            assertNull(records("""[{"territory_type":733,"log_time":$raw}]""").single().firstClearAt)
            val phases = fetch(PersonalDataUltimateSection.Phases, """[{"phase":"p1","log_time":$raw}]""") as PersonalDataUltimateData.Phases
            assertNull(phases.rows.single().reachedAt)
        }
    }

    @Test fun deathCoordinatesRemainRawSignedFiniteNumbersForEveryTerritory() = runTest {
        val rows = """[{"point_x":"-12.5","point_y":103.75},{"point_x":0,"point_y":"-0.125"}]"""
        for (territory in listOf(733, 968, 9999)) {
            assertEquals(PersonalDataUltimateData.Deaths(listOf(UltimateDeathRecord(-12.5, 103.75), UltimateDeathRecord(0.0, -0.125))),
                service(UltimateTransport { _, _ -> ultimateData(rows) }).fetchUltimateSection(territory, PersonalDataUltimateSection.Deaths))
        }
    }

    @Test fun missingAndInvalidDeathCoordinatesRemainRowsWithIndependentUnknownFields() = runTest {
        assertEquals(PersonalDataUltimateData.Deaths(listOf(UltimateDeathRecord(null, null), UltimateDeathRecord(null, 2.0), UltimateDeathRecord(3.0, null))),
            fetch(PersonalDataUltimateSection.Deaths, """[{}, {"point_x":"bad","point_y":2},{"point_x":3,"point_y":"NaN"}]"""))
        for (raw in listOf("null", "false", "{}", "[]", "\"\"", "\"NaN\"", "\"Infinity\"", "1e999", "-1e999")) {
            val deaths = fetch(PersonalDataUltimateSection.Deaths, """[{"point_x":$raw,"point_y":$raw}]""") as PersonalDataUltimateData.Deaths
            assertEquals(UltimateDeathRecord(null, null), deaths.rows.single())
        }
    }

    @Test fun deathsDoNotGuessAliasesPeriodsTimestampsOrClientSideFiveHundredLimits() = runTest {
        val result = fetch(PersonalDataUltimateSection.Deaths, (1..501).joinToString(",", "[", "]") {
            """{"x":$it,"y":$it,"period":"PRIVATE_RESPONSE","dead_time":"PRIVATE_RESPONSE"}"""
        }) as PersonalDataUltimateData.Deaths
        assertEquals(501, result.rows.size)
        assertTrue(result.rows.all { it.x == null && it.y == null })
        assertFalse(result.toString().contains("PRIVATE_RESPONSE"))
    }

    @Test fun unrelatedPrivateAndUnreviewedFieldsNeverEnterTheTypedModels() = runTest {
        val result = records("""[{"territory_type":733,"uuid":"PRIVATE_RESPONSE","achievement":{"name":"PRIVATE_RESPONSE"}}]""")
        assertFalse(result.toString().contains("PRIVATE_RESPONSE"))
        for ((section, row) in ultimateValidRows) {
            val actual = fetch(section, "[${row.dropLast(1)},\"uuid\":\"PRIVATE_RESPONSE\",\"unknown\":{\"private\":\"PRIVATE_RESPONSE\"}}]")
            assertFalse(actual.toString().contains("PRIVATE_RESPONSE"))
        }
    }

    @Test fun allNewReadsRequireExistingPersonalDataCapabilityWithoutCallingLegacyEndpoints() = runTest {
        val session = UltimateSession().apply { enabled = false }
        val transport = UltimateTransport()
        val service = service(transport, session)
        for (section in ultimateReads) assertAuthentication { read(service, section) }
        assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    @Test fun revocationDuringAnyReadRejectsAnOtherwiseValidEmptyPayload() = runTest {
        for (section in ultimateReads) {
            val session = UltimateSession()
            val transport = UltimateTransport { _, _ -> session.enabled = false; ultimateData("[]") }
            assertAuthentication { read(service(transport, session), section) }
            assertEquals(1, transport.requests.size); assertEquals(0, session.refreshes)
        }
    }

    @Test fun cancelledNonCooperativeResponsesNeverPublishAResult() = runTest {
        for (section in ultimateReads) {
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val transport = UltimateTransport { _, _ -> withContext(NonCancellable) { entered.complete(Unit); finish.await() }; ultimateData("[]") }
            var published = false
            val job = launch { read(service(transport), section); published = true }
            entered.await(); job.cancel(); finish.complete(Unit); job.join()
            assertTrue(job.isCancelled); assertFalse(published)
        }
    }

    @Test fun recoveryPreservesUserAgentAndSharesOneBudgetAcrossHttpAndBusinessFailures() = runTest {
        val session = UltimateSession()
        val transport = UltimateTransport { _, count -> if (count == 1) ultimateEnvelope("{}", 401) else ultimateData("[]") }
        assertTrue(service(transport, session).fetchUltimateRecords().isEmpty())
        assertEquals(1, session.refreshes)
        assertEquals(listOf("Fixture browser UA", "Fixture browser UA"), transport.requests.map { it.headers["User-Agent"] })
        val exhausted = UltimateSession()
        val retry = UltimateTransport { _, count -> if (count == 1) ultimateEnvelope("{}", 401) else ultimateEnvelope("""{"code":10001}""") }
        assertAuthentication { service(retry, exhausted).fetchUltimateSection(733, PersonalDataUltimateSection.Jobs) }
        assertEquals(1, exhausted.refreshes); assertEquals(2, retry.requests.size)
    }

    @Test fun identityConflictUsesItsResolverWithinTheSameSingleRecoveryBudget() = runTest {
        val session = UltimateSession()
        val transport = UltimateTransport { _, count -> if (count == 1) ultimateEnvelope("""{"code":10105}""") else ultimateData("[]") }
        assertTrue(service(transport, session).fetchUltimateRecords().isEmpty())
        assertEquals(1, session.conflicts); assertEquals(0, session.refreshes)
        val exhausted = UltimateSession()
        val retry = UltimateTransport { _, count -> if (count == 1) ultimateEnvelope("{}", 401) else ultimateEnvelope("""{"code":10105}""") }
        assertAuthentication { service(retry, exhausted).fetchUltimateRecords() }
        assertEquals(1, exhausted.refreshes); assertEquals(0, exhausted.conflicts); assertEquals(2, retry.requests.size)
    }

    @Test fun accepted10002DoesNotRefreshButNeverBypassesPayloadValidation() = runTest {
        val session = UltimateSession()
        val transport = UltimateTransport { _, _ -> ultimateEnvelope("""{"code":10002,"msg":"未登录","data":[]}""") }
        val service = service(transport, session)
        for (section in ultimateReads) read(service, section)
        assertEquals(6, transport.requests.size); assertEquals(0, session.refreshes)
        val missing = service(UltimateTransport { _, _ -> ultimateEnvelope("""{"code":10002}""") })
        for (section in ultimateReads) assertMissing { read(missing, section) }
    }

    @Test fun businessAndHttpErrorsRemainSanitizedAndDoNotBecomeEmptySuccess() = runTest {
        try {
            service(UltimateTransport { _, _ -> ultimateEnvelope("""{"code":12345,"msg":"PRIVATE_RESPONSE"}""") }).fetchUltimateRecords()
            fail("Expected business failure")
        } catch (failure: PersonalDataException.Business) {
            assertEquals(12345, failure.code); assertFalse(failure.toString().contains("PRIVATE_RESPONSE"))
        }
        try {
            service(UltimateTransport { _, _ -> ultimateEnvelope("""{"code":10000,"data":[],"msg":"PRIVATE_RESPONSE"}""", 500) }).fetchUltimateRecords()
            fail("Expected HTTP failure")
        } catch (failure: IOException) { assertFalse(failure.toString().contains("PRIVATE_RESPONSE")) }
    }

    @Test fun authorizationAndRefreshCancellationDoNotIssueAnotherRead() = runTest {
        for (duringRefresh in listOf(false, true)) {
            val session = UltimateSession().apply { cancelAuthorization = !duringRefresh; cancelRefresh = duringRefresh }
            val transport = UltimateTransport { _, _ -> ultimateEnvelope("{}", 401) }
            try { service(transport, session).fetchUltimateRecords(); fail("Expected cancellation") }
            catch (_: CancellationException) { }
            assertEquals(if (duringRefresh) 1 else 0, transport.requests.size)
        }
    }

    @Test fun emptyAuthorizerCannotUsePublicTransportForProtectedReads() = runTest {
        val session = UltimateSession().apply { writeHeaders = false }
        val transport = UltimateTransport()
        assertAuthentication { service(transport, session).fetchUltimateRecords() }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun imageAndRoleOrderBridgesDoNotReadOrGrantCapabilities() {
        val session = UltimateSession().apply { enabled = false }
        val transport = UltimateTransport()
        val service: PersonalDataUltimateService = service(transport, session)
        assertEquals(personalDataUltimateCoverUrl(733), service.ultimateCoverUrl(733))
        assertNotNull(service.ultimateCoverUrl(733)); assertNull(service.ultimateCoverUrl(9999))
        assertEquals(personalDataUltimateMedalImageUrl(1363), service.ultimateMedalImageUrl(1363))
        assertNotNull(service.ultimateMedalImageUrl(1363)); assertNull(service.ultimateMedalImageUrl(9999))
        assertEquals(personalDataUltimateJobIconUrl("骑士"), service.ultimateJobIconUrl("骑士"))
        assertNotNull(service.ultimateJobIconUrl("骑士")); assertNull(service.ultimateJobIconUrl("Unknown job"))
        assertEquals(personalDataUltimateJobOrder("骑士"), service.ultimateJobOrder("骑士"))
        assertEquals(0, service.ultimateJobOrder("骑士")); assertNull(service.ultimateJobOrder("Unknown job"))
        assertFalse(service.hasCommunityIdentity); assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    private suspend fun records(rows: String) = service(UltimateTransport { _, _ -> ultimateData(rows) }).fetchUltimateRecords()
    private suspend fun fetch(section: PersonalDataUltimateSection, rows: String) =
        service(UltimateTransport { _, _ -> ultimateData(rows) }).fetchUltimateSection(733, section)
    private suspend fun read(service: PersonalDataUltimateService, section: PersonalDataUltimateSection?): Any =
        if (section == null) service.fetchUltimateRecords() else service.fetchUltimateSection(733, section)
    private fun service(transport: UltimateTransport, session: UltimateSession = UltimateSession()) = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session, temporarySessionId = "ultimate-fixture")
    private suspend fun assertMissing(block: suspend () -> Any?) {
        try { block(); fail("Expected MissingPayload") } catch (_: PersonalDataException.MissingPayload) { }
    }
    private suspend fun assertAuthentication(block: suspend () -> Any?) {
        try { block(); fail("Expected AuthenticationRequired") } catch (_: PersonalDataException.AuthenticationRequired) { }
    }
}

private val ultimateEndpoints = linkedMapOf(
    PersonalDataUltimateSection.Party to "gaoNanTeam2", PersonalDataUltimateSection.Jobs to "gaoNanJob3",
    PersonalDataUltimateSection.Partners to "gaoNanFriend4", PersonalDataUltimateSection.Phases to "gaoNanPhase6",
    PersonalDataUltimateSection.Deaths to "gaoNanDeadPoint5",
)
private val ultimateReads: List<PersonalDataUltimateSection?> = listOf(null) + ultimateEndpoints.keys
private val ultimateEmptySections = linkedMapOf(
    PersonalDataUltimateSection.Party to PersonalDataUltimateData.Party(emptyList()),
    PersonalDataUltimateSection.Jobs to PersonalDataUltimateData.Jobs(emptyList()),
    PersonalDataUltimateSection.Partners to PersonalDataUltimateData.Partners(emptyList()),
    PersonalDataUltimateSection.Phases to PersonalDataUltimateData.Phases(emptyList()),
    PersonalDataUltimateSection.Deaths to PersonalDataUltimateData.Deaths(emptyList()),
)
private val ultimateValidRows = linkedMapOf(
    PersonalDataUltimateSection.Party to """{"character_namee":"Member"}""",
    PersonalDataUltimateSection.Jobs to """{"job_name":"Job"}""",
    PersonalDataUltimateSection.Partners to """{"team_chara_name":"Companion"}""",
    PersonalDataUltimateSection.Phases to """{"phase":"p1"}""",
    PersonalDataUltimateSection.Deaths to """{"point_x":1,"point_y":2}""",
)
private class UltimateSession : RisingStonesSessionProvider, RisingStonesIdentityConflictResolver {
    var enabled = true
    var cancelAuthorization = false
    var cancelRefresh = false
    var writeHeaders = true
    override val capabilities get() = if (enabled) setOf(RisingStonesCapability.PersonalData) else emptySet()
    val contexts = mutableListOf<RisingStonesRequestContext>()
    var refreshes = 0
    var conflicts = 0
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
    override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer {
        conflicts++
        return currentAuthorizer()
    }
}
private class UltimateTransport(
    private val respond: suspend (RisingStonesHttpRequest, Int) -> RisingStonesHttpResponse = { _, _ -> ultimateData("[]") },
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return respond(request, requests.size)
    }
}
private fun ultimateData(rows: String) = ultimateEnvelope("""{"code":10000,"data":$rows}""")
private fun ultimateEnvelope(payload: String, status: Int = 200) = RisingStonesHttpResponse(status, emptyMap(), payload.encodeToByteArray())
