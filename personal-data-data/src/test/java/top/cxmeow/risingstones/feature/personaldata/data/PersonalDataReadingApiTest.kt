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

class PersonalDataReadingApiTest {
    @Test fun fourRoutesUseOnlyFullGetAndTheCommonTemporarySessionQuery() = runTest {
        val session = ReadingSession()
        val transport = ReadingTransport()
        val service: PersonalDataReadingService = service(session, transport)

        repeat(4) { read(service, it) }

        assertEquals(listOf("fishNum2", "fishBait3", "getDressRace1", "getDressFullset5"),
            transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
        for (request in transport.requests) {
            assertEquals(RisingStonesHttpMethod.Get, request.method)
            assertNull(request.body)
            assertEquals(setOf("tempsuid"), request.url.toHttpUrl().queryParameterNames)
            assertEquals("reading-fixture", request.url.toHttpUrl().queryParameter("tempsuid"))
            assertEquals("Fixture browser UA", request.headers["User-Agent"])
        }
        assertTrue(session.contexts.all { it.capability == RisingStonesCapability.PersonalData &&
            it.requirement == RisingStonesAuthenticationRequirement.Required })
    }

    @Test fun fishAndBaitKeepEveryRowAndUseTheirOwnCountFields() = runTest {
        val service = service(ReadingSession(), ReadingTransport { request, _ ->
            val isFish = request.url.contains("fishNum2")
            response(if (isFish) """[{"catalog_name":"完整名称 & <字面值>","fish_num":"12","bait_num":999,"fish_type":"普通钓场"},
                {"catalog_name":"Legacy fish","fish_num":0}, {"catalog_name":"Other fish","fish_num":20,"fish_type":"Future category"}]"""
            else """[{"catalog_name":"Bait","bait_num":7,"fish_num":999,"fish_type":"出海垂钓"}]""")
        })

        assertEquals(listOf(
            PersonalDataFishingRank("完整名称 & <字面值>", 12, "普通钓场"),
            PersonalDataFishingRank("Legacy fish", 0, null),
            PersonalDataFishingRank("Other fish", 20, "Future category"),
        ), service.fetchFishingRanking(PersonalDataFishingRankingKind.Fish))
        assertEquals(listOf(PersonalDataFishingRank("Bait", 7, "出海垂钓")),
            service.fetchFishingRanking(PersonalDataFishingRankingKind.Bait))
    }

    @Test fun countsAreExactNonNegativeLongsWithoutDoubleRoundingOrTruncation() = runTest {
        val data = """[{"catalog_name":"Large numeric","fish_num":9007199254740993},
            {"catalog_name":"Maximum string","fish_num":"9223372036854775807"},
            {"catalog_name":"Integral decimal","fish_num":12.0},
            {"catalog_name":"Integral exponent","fish_num":"1e3"}]"""
        val result = service(data).fetchFishingRanking(PersonalDataFishingRankingKind.Fish)

        assertEquals(listOf(9007199254740993L, Long.MAX_VALUE, 12L, 1000L), result.map { it.count })
    }

    @Test fun invalidCountsRejectTheDatasetInsteadOfDroppingRowsOrInventingZero() = runTest {
        for (raw in listOf("null", "true", "{}", "[]", "-1", "1.5", "\"NaN\"", "\"Infinity\"",
            "9223372036854775808", "\"1e999\"", "\"\"")) {
            for (kind in PersonalDataFishingRankingKind.entries) {
                val field = if (kind == PersonalDataFishingRankingKind.Fish) "fish_num" else "bait_num"
                assertMissing { service("""[{"catalog_name":"Valid","$field":1},{"catalog_name":"Invalid","$field":$raw}]""")
                    .fetchFishingRanking(kind) }
            }
        }
        assertMissing { service("""[{"catalog_name":"Missing count"}]""").fetchFishingRanking(PersonalDataFishingRankingKind.Fish) }
    }

    @Test fun criticalNamesMustBeNonBlankText() = runTest {
        for (raw in listOf("null", "12", "true", "\"\"", "\"   \"", "{}")) {
            assertMissing { service("""[{"catalog_name":$raw,"fish_num":1}]""").fetchFishingRanking(PersonalDataFishingRankingKind.Fish) }
            assertMissing { service("""[{"race":$raw,"gender":"Female"}]""").fetchRaceUsage() }
            assertMissing { service("""[{"race":"Race","gender":$raw}]""").fetchRaceUsage() }
        }
        assertMissing { service("[{}]").fetchRaceUsage() }
    }

    @Test fun allFourRoutesRequireObjectArraysAndOnlyExplicitEmptyArrayMeansEmpty() = runTest {
        for (payload in listOf("""{"code":10000}""", """{"code":10000,"data":null}""",
            """{"code":10000,"data":{}}""", """{"code":10000,"data":{"rows":[]}}""",
            """{"code":10000,"data":0}""", """{"code":10000,"data":"[]"}""",
            """{"code":10000,"data":[null]}""", """{"code":10000,"data":[{},false]}""")) {
            repeat(4) { route ->
                assertMissing { read(service(ReadingSession(), ReadingTransport { _, _ -> envelope(payload) }), route) }
            }
        }
        repeat(4) { assertEquals(emptyList<Any>(), read(service("[]"), it)) }
    }

    @Test fun racesKeepFractionDaysAndCurrentOrMostUsedEvidenceWithoutSortingOrCropping() = runTest {
        val result = service("""[
            {"race":"Race B","gender":"Female","continue_rate":"0.845","continue_days":"9007199254740993","now_rn":"1","rate_rn":2},
            {"race":"Race A","gender":"Male","continue_rate":0,"continue_days":0,"now_rn":2,"rate_rn":"1"},
            {"race":"Race C","gender":"Female","continue_rate":"1","continue_days":"12.0"}
        ]""").fetchRaceUsage()

        assertEquals(listOf(
            PersonalDataRaceUsage("Race B", "Female", 0.845, 9007199254740993L, true, false),
            PersonalDataRaceUsage("Race A", "Male", 0.0, 0, false, true),
            PersonalDataRaceUsage("Race C", "Female", 1.0, 12, false, false),
        ), result)
    }

    @Test fun absentAndInvalidRaceNumbersRemainUnknownWithoutDiscardingTheNamedRow() = runTest {
        val missing = service("""[{"race":"Race","gender":"Female"}]""").fetchRaceUsage().single()
        assertNull(missing.proportion)
        assertNull(missing.days)
        for (raw in listOf("null", "false", "{}", "\"\"", "\"NaN\"", "\"Infinity\"", "\"0x1.0p-1\"", "-1", "1e309")) {
            val result = service("""[{"race":"Race","gender":"Female","continue_rate":$raw,"continue_days":$raw}]""")
                .fetchRaceUsage().single()
            assertNull("proportion $raw", result.proportion)
            assertNull("days $raw", result.days)
        }
        assertNull(service("""[{"race":"Race","gender":"Female","continue_rate":84.5,"continue_days":1.5}]""")
            .fetchRaceUsage().single().proportion)
        assertNull(service("""[{"race":"Race","gender":"Female","continue_days":9223372036854775808}]""")
            .fetchRaceUsage().single().days)
    }

    @Test fun setRecordsPreserveDuplicatesDeduplicateValidItemsAndMarkEveryInvalidToken() = runTest {
        val records = service("""[
            {"setitem":"42","partitem":"1,2,2, 3 ","log_time":"2026-09-20 10:30:00"},
            {"setitem":42,"partitem":"3,4"},
            {"setitem":"42","partitem":"1,2,2, 3 ","log_time":"2026-09-20 10:30:00"},
            {"setitem":"43","partitem":"1,,0,-1,1.5,1e2,no,2147483648,5"},
            {"setitem":"44","partitem":""}, {"setitem":"45"}, {"setitem":"46","partitem":12}
        ]""").fetchGlamourSetRecords()

        assertEquals(7, records.size)
        assertEquals(7, records.map { it.key }.distinct().size)
        assertEquals(listOf(42, 42, 42, 43, 44, 45, 46), records.map { it.setId })
        assertEquals(setOf(1, 2, 3), records[0].itemIds)
        assertEquals(setOf(3, 4), records[1].itemIds)
        assertEquals(setOf(1, 5), records[3].itemIds)
        assertTrue(records.take(3).none { it.hasInvalidItemIds })
        assertTrue(records.drop(3).all { it.hasInvalidItemIds })
        assertTrue(records.drop(4).all { it.itemIds.isEmpty() })
    }

    @Test fun invalidSetIdsRejectTheDatasetWithoutSilentlyLosingRecords() = runTest {
        for (raw in listOf("null", "true", "{}", "\"\"", "0", "-1", "1.5", "\"1e2\"", "2147483648")) {
            assertMissing { service("""[{"setitem":"1","partitem":"1"},{"setitem":$raw,"partitem":"2"}]""")
                .fetchGlamourSetRecords() }
        }
        assertMissing { service("""[{"partitem":"1"}]""").fetchGlamourSetRecords() }
    }

    @Test fun recordKeysSurviveRefreshReorderingAndIgnoreUnrelatedResponseFields() = runTest {
        val first = """{"setitem":"42","partitem":"1,2","log_time":"2026-09-20 10:30:00"}"""
        val other = """{"setitem":"42","partitem":"3","log_time":"2026-09-21 10:30:00"}"""
        val old = service("[$first,$other,$first]").fetchGlamourSetRecords()
        val refreshed = service("[$other,$first,$first]").fetchGlamourSetRecords()
        val extra = service("""[{"unrelated":"extra fixture","partitem":"1,2","log_time":"2026-09-20 10:30:00","setitem":42}]""")
            .fetchGlamourSetRecords().single()

        assertEquals(old[0].key, refreshed[1].key)
        assertEquals(old[1].key, refreshed[0].key)
        assertEquals(old[2].key, refreshed[2].key)
        assertEquals(old[0].key, extra.key)
        assertTrue(old.all { it.key.matches(Regex("[a-f0-9]{64}-[0-9]+")) })
    }

    @Test fun recordDatesUseConfirmedIsoOrOfficialLocalFormatsAndNeverGuessUnixUnits() = runTest {
        for (date in listOf("2026-09-20T02:30:00Z", "2026-09-20T10:30:00+08:00",
            "2026-09-20T10:30:00", "2026-09-20 10:30:00")) {
            assertEquals(Instant.parse("2026-09-20T02:30:00Z"),
                service("""[{"setitem":"1","partitem":"1","log_time":"$date"}]""").fetchGlamourSetRecords().single().recordedAt)
        }
        assertEquals(Instant.parse("2026-09-19T16:00:00Z"),
            service("""[{"setitem":"1","partitem":"1","log_time":"2026-09-20"}]""").fetchGlamourSetRecords().single().recordedAt)
        for (raw in listOf("null", "1710000000000", "\"1710000000\"", "\"2026-02-30 10:30:00\"", "\"not a date\"")) {
            assertNull(service("""[{"setitem":"1","partitem":"1","log_time":$raw}]""").fetchGlamourSetRecords().single().recordedAt)
        }
    }

    @Test fun newRoutesCannotBypassCapabilityOrAnEmptyAuthorizer() = runTest {
        for (emptyAuthorizer in listOf(false, true)) {
            val session = ReadingSession().apply {
                enabled = emptyAuthorizer
                if (emptyAuthorizer) authorizer = RisingStonesRequestAuthorizer { _, _ -> }
            }
            val transport = ReadingTransport()
            repeat(4) { route -> assertAuthentication { read(service(session, transport), route) } }
            assertTrue(transport.requests.isEmpty())
            assertEquals(0, session.refreshes)
        }
    }

    @Test fun accepted10002UsesPayloadValidationAndDoesNotRefreshBecauseOfMessageText() = runTest {
        val session = ReadingSession()
        val transport = ReadingTransport { _, _ -> envelope("""{"code":10002,"msg":"未登录","data":[]}""") }
        repeat(4) { assertEquals(emptyList<Any>(), read(service(session, transport), it)) }
        assertEquals(4, transport.requests.size)
        assertEquals(0, session.refreshes)
        assertMissing { service(ReadingSession(), ReadingTransport { _, _ -> envelope("""{"code":10002}""") }).fetchRaceUsage() }
    }

    @Test fun httpRecoveryRetainsOriginalUserAgentAndHasOneBudgetForEnvelopeFailure() = runTest {
        val session = ReadingSession()
        val transport = ReadingTransport { _, count -> if (count == 1) envelope("{}", 401) else response("[]") }
        assertTrue(service(session, transport).fetchRaceUsage().isEmpty())
        assertEquals(1, session.refreshes)
        assertEquals(listOf("Fixture browser UA", "Fixture browser UA"), transport.requests.map { it.headers["User-Agent"] })

        val exhaustedSession = ReadingSession()
        val exhausted = ReadingTransport { _, count -> if (count == 1) envelope("{}", 401) else envelope("""{"code":10001}""") }
        assertAuthentication { service(exhaustedSession, exhausted).fetchGlamourSetRecords() }
        assertEquals(1, exhaustedSession.refreshes)
        assertEquals(2, exhausted.requests.size)
    }

    @Test fun identityConflictUsesTheExistingResolverOnce() = runTest {
        val session = ReadingSession()
        val transport = ReadingTransport { _, count -> if (count == 1) envelope("""{"code":10105}""") else response("[]") }
        assertTrue(service(session, transport).fetchFishingRanking(PersonalDataFishingRankingKind.Bait).isEmpty())
        assertEquals(1, session.conflicts)
        assertEquals(0, session.refreshes)
        assertEquals(2, transport.requests.size)
    }

    @Test fun revokedCapabilityDuringTransportCannotPublishAnyOfTheNewDetailTypes() = runTest {
        repeat(4) { route ->
            val session = ReadingSession()
            val transport = ReadingTransport { _, _ -> session.enabled = false; response("[]") }
            assertAuthentication { read(service(session, transport), route) }
            assertEquals(1, transport.requests.size)
            assertEquals(0, session.refreshes)
        }
    }

    @Test fun cancelledNonCooperativeReadCannotPublishAnEmptySuccess() = runTest {
        repeat(4) { route ->
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val transport = ReadingTransport { _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); finish.await() }
                response("[]")
            }
            var published = false
            val job = launch { read(service(ReadingSession(), transport), route); published = true }
            entered.await()
            job.cancel()
            finish.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(published)
        }
    }

    @Test fun directCancellationAndSanitizedBusinessErrorsKeepTheirExistingMeaning() = runTest {
        val session = ReadingSession()
        try {
            service(session, ReadingTransport { _, _ -> throw CancellationException("Fixture cancellation") }).fetchRaceUsage()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, session.refreshes)
        try {
            service(session, ReadingTransport { _, _ -> envelope("""{"code":12345,"msg":"PRIVATE_RESPONSE"}""") }).fetchGlamourSetRecords()
            fail("Expected business error")
        } catch (failure: PersonalDataException.Business) {
            assertEquals(12345, failure.code)
            assertFalse(failure.toString().contains("PRIVATE_RESPONSE"))
        }
    }

    @Test fun optionalItemIconBridgeUsesOfficialHelperWithoutReadingProtectedData() {
        val session = ReadingSession().apply { enabled = false }
        val transport = ReadingTransport()
        val service: PersonalDataReadingService = service(session, transport)

        assertEquals(personalDataItemIconUrl(2510), service.itemIconUrl(2510))
        assertNotNull(service.itemIconUrl(2510))
        assertNull(service.itemIconUrl(0))
        assertTrue(transport.requests.isEmpty())
        assertTrue(session.contexts.isEmpty())
    }

    private suspend fun read(service: PersonalDataReadingService, route: Int): Any = when (route) {
        0 -> service.fetchFishingRanking(PersonalDataFishingRankingKind.Fish)
        1 -> service.fetchFishingRanking(PersonalDataFishingRankingKind.Bait)
        2 -> service.fetchRaceUsage()
        else -> service.fetchGlamourSetRecords()
    }
    private suspend fun assertMissing(block: suspend () -> Any?) {
        try { block(); fail("Expected MissingPayload") } catch (_: PersonalDataException.MissingPayload) { }
    }
    private suspend fun assertAuthentication(block: suspend () -> Any?) {
        try { block(); fail("Expected AuthenticationRequired") } catch (_: PersonalDataException.AuthenticationRequired) { }
    }
    private fun service(data: String) = service(ReadingSession(), ReadingTransport { _, _ -> response(data) })
    private fun service(session: ReadingSession, transport: ReadingTransport) = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session,
        temporarySessionId = "reading-fixture")
}

private class ReadingSession : RisingStonesSessionProvider, RisingStonesIdentityConflictResolver {
    var enabled = true
    override val capabilities get() = if (enabled) setOf(RisingStonesCapability.PersonalData) else emptySet()
    val contexts = mutableListOf<RisingStonesRequestContext>()
    var authorizer = RisingStonesRequestAuthorizer { context, sink ->
        contexts += context
        sink.set("Authorization", "Fixture token")
        sink.set("User-Agent", "Fixture browser UA")
    }
    var refreshes = 0
    var conflicts = 0
    override suspend fun currentAuthorizer() = authorizer
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return authorizer }
    override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer { conflicts++; return authorizer }
}

private class ReadingTransport(
    private val respond: suspend (RisingStonesHttpRequest, Int) -> RisingStonesHttpResponse = { _, _ -> response("[]") },
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return respond(request, requests.size)
    }
}

private fun response(data: String) = envelope("""{"code":10000,"data":$data}""")
private fun envelope(payload: String, status: Int = 200) =
    RisingStonesHttpResponse(status, emptyMap(), payload.encodeToByteArray())
