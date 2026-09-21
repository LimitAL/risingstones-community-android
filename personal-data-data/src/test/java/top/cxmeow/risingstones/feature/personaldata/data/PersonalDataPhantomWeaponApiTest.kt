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

class PersonalDataPhantomWeaponApiTest {
    @Test fun typedSnapshotUsesOneExistingEightGetBatchWithTheLoginUserAgent() = runTest {
        val transport = PhantomTransport().apply {
            payloads["getMKDTotal1"] = """[{"now_level":"60"}]"""
            payloads["getMKDItemGet4"] = """[{"catalog_id":"47869","catalog_type":"幻境武器","get_num":"01"}]"""
            payloads["getMKDLight8"] = """[{"color":"green","quest_point":"10000"}]"""
        }
        val session = PhantomSession()
        val service: PersonalDataPhantomWeaponService = service(transport, session)
        val snapshot = service.fetchPhantomWeaponExploration()
        assertEquals(ExplorationBoard.OccultCrescent, snapshot.overview.board)
        assertEquals("60", snapshot.overview.metrics.single().value)
        assertEquals("01", snapshot.overview.sections.first { it.kind == ExplorationSectionKind.AcquiredItems }
            .records.single().fields.first { it.kind == ExplorationFieldKind.Quantity }.value)
        assertEquals(1L, snapshot.items!!.records.single().quantity)
        assertEquals(listOf(PhantomWeaponAetherRecord("green", 10000)), snapshot.aether!!.records)
        assertEquals(phantomEndpoints, transport.requests.map { it.url.toHttpUrl().pathSegments.last() }.toSet())
        assertEquals(8, transport.requests.size)
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get && it.body == null &&
            it.url.toHttpUrl().queryParameterNames.isEmpty() &&
            it.url.toHttpUrl().encodedPath.startsWith("/api/home/dataCenter/") &&
            it.headers["User-Agent"] == "Fixture browser UA" })
        assertTrue(session.contexts.all { it.capability == RisingStonesCapability.PersonalData &&
            it.requirement == RisingStonesAuthenticationRequirement.Required })
        assertEquals(8, session.contexts.size)
    }

    @Test fun accepted10002ReadsKeepValidPayloadDespiteAnAuthenticationMessage() = runTest {
        val transport = PhantomTransport().apply {
            code = 10002; message = "未登录"
            payloads["getMKDItemGet4"] = """[{"catalog_type":"消幻晶","get_num":"100"}]"""
        }
        val session = PhantomSession()
        assertEquals(100L, service(transport, session).fetchPhantomWeaponExploration().items!!.records.single().quantity)
        assertEquals(8, transport.requests.size); assertEquals(0, session.refreshes)
    }

    @Test fun closedOverviewHasNullWeaponInputsAndDoesNotReadChildren() = runTest {
        val transport = PhantomTransport().apply { payloads["dataOpenStatus"] = """{"mkd":"0"}""" }
        val result = service(transport).fetchPhantomWeaponExploration()
        assertFalse(result.overview.available); assertNull(result.items); assertNull(result.aether)
        assertEquals(listOf("dataOpenStatus"), transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
    }

    @Test fun explicitEmptyArraysAreSuccessfulEmptyInputs() = runTest {
        val result = service(PhantomTransport()).fetchPhantomWeaponExploration()
        assertEquals(PhantomWeaponItemSection(), result.items)
        assertEquals(PhantomWeaponAetherSection(), result.aether)
    }

    @Test fun missingNullScalarAndObjectPayloadsCannotImplyNoMaterials() = runTest {
        for (payload in listOf(null, "null", "{}", "false", "12", "\"[]\"", "{\"rows\":[]}")) {
            val transport = PhantomTransport().apply {
                payloads["getMKDItemGet4"] = payload
                payloads["getMKDLight8"] = payload
            }
            val result = service(transport).fetchPhantomWeaponExploration()
            assertEquals(ExplorationFailure.InvalidResponse, result.items!!.failure)
            assertEquals(ExplorationFailure.InvalidResponse, result.aether!!.failure)
        }
    }

    @Test fun invalidArrayMembersFailTheSectionWithoutDroppingOnlyTheBadRow() = runTest {
        for (invalid in listOf("null", "1", "false", "[]", "\"row\"", "{}")) {
            val result = fetchItems("""[{"catalog_id":"47869","get_num":"1"},$invalid]""")
            assertEquals(ExplorationFailure.InvalidResponse, result.items!!.failure)
            assertTrue(result.items!!.records.isEmpty())
        }
    }

    @Test fun ordinarySectionFailuresRemainSanitizedAndDoNotEraseOtherInputs() = runTest {
        val transport = PhantomTransport().apply {
            codes["getMKDItemGet4"] = 12345
            payloads["getMKDLight8"] = """[{"color":"yellow","quest_point":"24"}]"""
        }
        val result = service(transport).fetchPhantomWeaponExploration()
        assertEquals(ExplorationFailure.Business, result.items!!.failure)
        assertEquals(listOf(PhantomWeaponAetherRecord("yellow", 24)), result.aether!!.records)
        assertNull(result.aether!!.failure)
        assertFalse(result.toString().contains("PRIVATE_RESPONSE"))
    }

    @Test fun transportFailuresRemainFailedSectionsRatherThanSuccessfulEmptyInputs() = runTest {
        val transport = PhantomTransport().apply { beforeResponse = { endpoint ->
            if (endpoint == "getMKDLight8") throw IOException("PRIVATE_RESPONSE")
        } }
        val result = service(transport).fetchPhantomWeaponExploration()
        assertEquals(ExplorationFailure.Network, result.aether!!.failure)
        assertEquals(PhantomWeaponItemSection(), result.items)
        assertFalse(result.toString().contains("PRIVATE_RESPONSE"))
    }

    @Test fun httpFailureCannotUseASuccessEnvelopeAsWeaponData() = runTest {
        val transport = PhantomTransport().apply {
            statuses["getMKDItemGet4"] = 500
            payloads["getMKDItemGet4"] = """[{"catalog_type":"半魂晶","get_num":"1"}]"""
        }
        val result = service(transport).fetchPhantomWeaponExploration()
        assertEquals(ExplorationFailure.Network, result.items!!.failure)
        assertTrue(result.items!!.records.isEmpty())
    }

    @Test fun acceptedCodesStillRequireTheAvailabilityFlag() = runTest {
        for (payload in listOf(null, "null", "[]", "{}", "{\"mkd\":\"bad\"}")) {
            val transport = PhantomTransport().apply { code = 10002; payloads["dataOpenStatus"] = payload }
            val session = PhantomSession()
            assertInvalid { service(transport, session).fetchPhantomWeaponExploration() }
            assertEquals(1, transport.requests.size); assertEquals(0, session.refreshes)
        }
    }

    @Test fun itemCountsAreExactNonnegativeLongWithoutRoundingOrTruncation() = runTest {
        val result = fetchItems("""[
            {"catalog_id":47744,"get_num":9007199254740993},
            {"catalog_id":47745,"get_num":"9223372036854775807"},
            {"catalog_id":47746,"get_num":"100.0"},
            {"catalog_id":47747,"get_num":"1e2"},
            {"catalog_id":47748,"get_num":0}]""")
        assertEquals(listOf(9007199254740993L, Long.MAX_VALUE, 100L, 100L, 0L), result.items!!.records.map { it.quantity })
    }

    @Test fun invalidOrMissingItemCountsAndAetherPointsRemainUnknown() = runTest {
        for (raw in listOf("null", "false", "{}", "[]", "\"\"", "\"NaN\"", "\"Infinity\"", "-1",
            "1.5", "9223372036854775808", "1e999", "\"-1e-999\"", "\"1e-999\"")) {
            val transport = PhantomTransport().apply {
                payloads["getMKDItemGet4"] = """[{"catalog_type":"半魂晶","get_num":$raw}]"""
                payloads["getMKDLight8"] = """[{"color":"green","quest_point":$raw}]"""
            }
            val result = service(transport).fetchPhantomWeaponExploration()
            assertNull(result.items!!.records.single().quantity)
            assertNull(result.aether!!.records.single().points)
            assertNull(result.items!!.failure); assertNull(result.aether!!.failure)
        }
        val missing = fetchItems("""[{"catalog_type":"水晶混合黏土"}]""")
        assertNull(missing.items!!.records.single().quantity)
    }

    @Test fun quantitiesAndAetherDoNotClampLegitimateValuesToPresentationTargets() = runTest {
        val transport = PhantomTransport().apply {
            payloads["getMKDItemGet4"] = """[{"catalog_type":"消幻晶","get_num":"1201"}]"""
            payloads["getMKDLight8"] = """[{"color":"red","quest_point":"10001"},{"color":"red","quest_point":"0.0"}]"""
        }
        val result = service(transport).fetchPhantomWeaponExploration()
        assertEquals(1201L, result.items!!.records.single().quantity)
        assertEquals(listOf(10001L, 0L), result.aether!!.records.map { it.points })
    }

    @Test fun sourceOrderDuplicatesAndUnknownCategoriesOrColorsArePreserved() = runTest {
        val transport = PhantomTransport().apply {
            payloads["getMKDItemGet4"] = """[
                {"catalog_id":999999,"catalog_type":"Future category","get_num":"3","catalog_name":"Literal & <name>"},
                {"catalog_id":47744,"catalog_type":"半魂晶","get_num":"1"},
                {"catalog_id":47744,"catalog_type":"半魂晶","get_num":"2"}]"""
            payloads["getMKDLight8"] = """[{"color":"future","quest_point":4},{"color":"green","quest_point":1},{"color":"green","quest_point":2}]"""
        }
        val result = service(transport).fetchPhantomWeaponExploration()
        assertEquals(listOf(999999, 47744, 47744), result.items!!.records.map { it.itemId })
        assertEquals(listOf(3L, 1L, 2L), result.items!!.records.map { it.quantity })
        assertEquals("Future category", result.items!!.records.first().category)
        assertEquals("Literal & <name>", result.items!!.records.first().name)
        assertEquals(listOf("future", "green", "green"), result.aether!!.records.map { it.color })
        assertEquals(listOf(4L, 1L, 2L), result.aether!!.records.map { it.points })
    }

    @Test fun missingOrInvalidIdentityDoesNotDiscardAnOtherwiseKnownRecord() = runTest {
        val result = fetchItems("""[
            {"catalog_type":"Future","get_num":2},
            {"catalog_id":"bad","catalog_type":"Future"},
            {"catalog_id":-1,"catalog_type":"Future"},
            {"catalog_id":0,"catalog_type":"Future"}]""")
        assertEquals(4, result.items!!.records.size)
        assertTrue(result.items!!.records.all { it.itemId == null })
        assertNull(result.items!!.failure)
    }

    @Test fun timestampsKeepCalendarLocalAndExplicitOffsetMeanings() = runTest {
        val result = fetchItems("""[
            {"catalog_id":47869,"first_time":"2024-02-29"},
            {"catalog_id":47869,"first_time":"2026-09-20 10:30:12"},
            {"catalog_id":47869,"first_time":"2026-09-20T10:30:12.123"},
            {"catalog_id":47869,"first_time":"2026-09-20T10:30:12+08:00"},
            {"catalog_id":47869,"first_time":"2026-09-20T02:30:12Z"}]""")
        assertEquals(listOf(
            PhantomWeaponRecordTime.CalendarDate(LocalDate.of(2024, 2, 29)),
            PhantomWeaponRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 10, 30, 12)),
            PhantomWeaponRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 10, 30, 12, 123000000)),
            PhantomWeaponRecordTime.OffsetTime(Instant.parse("2026-09-20T02:30:12Z")),
            PhantomWeaponRecordTime.OffsetTime(Instant.parse("2026-09-20T02:30:12Z")),
        ), result.items!!.records.map { it.firstAcquiredAt })
    }

    @Test fun invalidAndNumericTimestampsStayUnknownWithoutGuessingTimezonesOrUnixUnits() = runTest {
        for (raw in listOf("null", "\"\"", "\"2026-02-29\"", "\"2026-09-31 12:00:00\"", "\"2026-09-20 24:00:00\"",
            "\"2026/09/20\"", "\"2026年9月20日\"", "946684800000", "\"2026-09-20T10:30:12+99:00\"")) {
            assertNull(fetchItems("""[{"catalog_id":47869,"first_time":$raw}]""").items!!.records.single().firstAcquiredAt)
        }
    }

    @Test fun onlyReviewedFieldsReachTheSnapshot() = runTest {
        val result = fetchItems("""[{"catalog_id":47744,"get_num":2,"character_id":"PRIVATE_RESPONSE",
            "user_name":"PRIVATE_RESPONSE","unknown":"PRIVATE_RESPONSE"}]""")
        assertFalse(result.toString().contains("PRIVATE_RESPONSE"))
        assertEquals("青色半魂晶", result.items!!.records.single().name)
    }

    @Test fun missingSectionsAndExistingSectionFailuresStayDistinctFromConfirmedEmpty() {
        val missing = phantomWeaponSnapshot(ExplorationOverview(ExplorationBoard.OccultCrescent, true, emptyList(), emptyList()))
        assertEquals(ExplorationFailure.InvalidResponse, missing.items!!.failure)
        assertEquals(ExplorationFailure.InvalidResponse, missing.aether!!.failure)
        val overview = ExplorationOverview(ExplorationBoard.OccultCrescent, true, emptyList(), listOf(
            ExplorationSection(ExplorationSectionKind.AcquiredItems, listOf(ExplorationRecord("retained", "", listOf(
                ExplorationField(ExplorationFieldKind.Quantity, "2")), itemId = 47744)), ExplorationFailure.Network),
            ExplorationSection(ExplorationSectionKind.Aether, failure = ExplorationFailure.Business),
        ))
        val result = phantomWeaponSnapshot(overview)
        assertSame(overview, result.overview)
        assertEquals(2L, result.items!!.records.single().quantity)
        assertEquals(ExplorationFailure.Network, result.items!!.failure)
        assertEquals(ExplorationFailure.Business, result.aether!!.failure)
    }

    @Test fun anotherExplorationBoardCannotBeRelabeledAsPhantomWeapons() {
        try {
            phantomWeaponSnapshot(ExplorationOverview(ExplorationBoard.DeepDungeon, true, emptyList(), emptyList()))
            fail("Expected InvalidResponse")
        } catch (_: ExplorationException.InvalidResponse) { }
    }

    @Test fun missingCapabilityOrAuthorizerNeverCallsTransport() = runTest {
        for (missingCapability in listOf(true, false)) {
            val transport = PhantomTransport()
            val session = PhantomSession().apply {
                enabled = !missingCapability; missingAuthorizer = !missingCapability
            }
            if (missingCapability) assertUnavailable { service(transport, session).fetchPhantomWeaponExploration() }
            else assertAuthentication { service(transport, session).fetchPhantomWeaponExploration() }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun expiredAuthenticationHasOneRecoveryAndNeverReturnsASnapshot() = runTest {
        for (http in listOf(false, true)) {
            val transport = PhantomTransport().apply {
                if (http) statuses["dataOpenStatus"] = 401 else codes["dataOpenStatus"] = 10105
            }
            val session = PhantomSession()
            assertAuthentication { service(transport, session).fetchPhantomWeaponExploration() }
            assertEquals(1, session.refreshes); assertEquals(2, transport.requests.size)
            assertTrue(transport.requests.all { it.headers["User-Agent"] == "Fixture browser UA" })
        }
    }

    @Test fun authenticationFailureInAChildCannotBecomeAnOrdinarySectionFailure() = runTest {
        val transport = PhantomTransport().apply { codes["getMKDItemGet4"] = 10001 }
        val session = PhantomSession()
        assertAuthentication { service(transport, session).fetchPhantomWeaponExploration() }
        assertEquals(1, session.refreshes)
        assertEquals(2, transport.requests.count { it.url.toHttpUrl().pathSegments.last() == "getMKDItemGet4" })
    }

    @Test fun revocationDuringAuthorizationOrHeaderWritingPreventsTheFirstRequest() = runTest {
        for (duringHeaders in listOf(false, true)) {
            val transport = PhantomTransport()
            val session = PhantomSession().apply {
                if (duringHeaders) headerHook = { enabled = false } else authorizerHook = { enabled = false }
            }
            assertUnavailable { service(transport, session).fetchPhantomWeaponExploration() }
            assertTrue(transport.requests.isEmpty()); assertEquals(0, session.refreshes)
        }
    }

    @Test fun revocationDuringAnItemReadDiscardsTheWholeLateSnapshot() = runTest {
        val session = PhantomSession()
        val transport = PhantomTransport().apply { beforeResponse = { endpoint ->
            if (endpoint == "getMKDItemGet4") session.enabled = false
        } }
        assertUnavailable { service(transport, session).fetchPhantomWeaponExploration() }
        assertEquals(0, session.refreshes)
    }

    @Test fun revocationDuringRecoveryDoesNotReplayTheRead() = runTest {
        val session = PhantomSession().apply { refreshHook = { enabled = false } }
        val transport = PhantomTransport().apply { codes["dataOpenStatus"] = 10001 }
        assertUnavailable { service(transport, session).fetchPhantomWeaponExploration() }
        assertEquals(1, session.refreshes); assertEquals(1, transport.requests.size)
    }

    @Test fun authorizerAndRefreshCancellationPropagateWithoutAnotherRequest() = runTest {
        for (duringRefresh in listOf(false, true)) {
            val session = PhantomSession().apply {
                if (duringRefresh) refreshHook = { throw CancellationException("fixture") }
                else authorizerHook = { throw CancellationException("fixture") }
            }
            val transport = PhantomTransport().apply { codes["dataOpenStatus"] = 10001 }
            try { service(transport, session).fetchPhantomWeaponExploration(); fail("Expected cancellation") }
            catch (_: CancellationException) { }
            assertEquals(if (duringRefresh) 1 else 0, transport.requests.size)
        }
    }

    @Test fun lateNonCooperativeItemResponseCannotPublishAfterCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = PhantomTransport().apply { beforeResponse = { endpoint ->
            if (endpoint == "getMKDItemGet4") {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        } }
        var published = false
        var ordinaryFailure = false
        val job = launch {
            try { service(transport).fetchPhantomWeaponExploration(); published = true }
            catch (_: CancellationException) { }
            catch (_: Exception) { ordinaryFailure = true }
        }
        started.await(); job.cancel(); release.complete(Unit); job.join()
        assertFalse(published); assertFalse(ordinaryFailure)
    }

    @Test fun legacyHistoryAlsoDiscardsAResponseAfterCapabilityRevocation() = runTest {
        val session = PhantomSession()
        val transport = PhantomTransport().apply { beforeResponse = { session.enabled = false } }
        assertUnavailable { service(transport, session).fetchExplorationHistory(
            ExplorationBoard.OccultCrescent, ExplorationSectionKind.RelicHistory) }
        val request = transport.requests.single()
        assertEquals("getMKDIHistory6", request.url.toHttpUrl().pathSegments.last())
        assertEquals("半魂晶", request.url.toHttpUrl().queryParameter("catalog_type"))
    }

    @Test fun legacyOverviewStillKeepsDeepDungeonParametersAndSections() = runTest {
        val transport = PhantomTransport()
        val result = service(transport).fetchExplorationOverview(ExplorationBoard.DeepDungeon)
        assertTrue(result.available)
        assertTrue(result.sections.any { it.kind == ExplorationSectionKind.Challenges })
        assertTrue(result.sections.none { it.kind == ExplorationSectionKind.PhantomJobs })
        for (request in transport.requests.drop(1)) {
            val url = request.url.toHttpUrl()
            if (url.pathSegments.last() in listOf("getDDGaoNan2", "getDDFirstTeam7"))
                assertEquals("1311", url.queryParameter("territory_type"))
            else assertEquals("dd4", url.queryParameter("dd_type"))
        }
    }

    @Test fun publicCatalogAndImageBridgesDoNotReadProtectedDataOrGrantCapability() = runTest {
        val session = PhantomSession().apply { enabled = false }
        val transport = PhantomTransport()
        val service: PersonalDataPhantomWeaponService = service(transport, session)
        val catalog = service.fetchPhantomWeaponCatalog()
        assertEquals(110, catalog.weapons.size)
        assertTrue(catalog.weapons.any { it.itemId == 51000 && it.stage == PhantomWeaponStage.Occultum })
        assertEquals(phantomWeaponItemIconUrl(26025), service.phantomWeaponItemIconUrl(26025))
        assertNotNull(service.phantomWeaponItemIconUrl(26025))
        assertNull(service.phantomWeaponItemIconUrl(0))
        assertEquals(phantomWeaponElementIconUrl(PhantomWeaponElement.Green),
            service.phantomWeaponElementIconUrl(PhantomWeaponElement.Green))
        for (step in 1..5) {
            assertNotNull(service.phantomWeaponLensImageUrl(step))
            assertEquals(phantomWeaponLensImageUrl(step), service.phantomWeaponLensImageUrl(step))
        }
        assertNull(service.phantomWeaponLensImageUrl(0)); assertNull(service.phantomWeaponLensImageUrl(6))
        assertFalse(service.hasCommunityIdentity)
        assertTrue(transport.requests.isEmpty()); assertTrue(session.contexts.isEmpty())
    }

    private suspend fun fetchItems(rows: String) = service(PhantomTransport().apply {
        payloads["getMKDItemGet4"] = rows
    }).fetchPhantomWeaponExploration()

    private fun service(transport: PhantomTransport, session: PhantomSession = PhantomSession()) =
        PersonalDataApiService(RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session)

    private suspend fun assertInvalid(block: suspend () -> Any?) {
        try { block(); fail("Expected InvalidResponse") } catch (_: ExplorationException.InvalidResponse) { }
    }
    private suspend fun assertUnavailable(block: suspend () -> Any?) {
        try { block(); fail("Expected Unavailable") } catch (_: ExplorationException.Unavailable) { }
    }
    private suspend fun assertAuthentication(block: suspend () -> Any?) {
        try { block(); fail("Expected AuthenticationRequired") } catch (_: ExplorationException.AuthenticationRequired) { }
    }
}

private val phantomEndpoints = setOf("dataOpenStatus", "getMKDTotal1", "getMKDSupportJob2", "getMKDItemUse3",
    "getMKDItemGet4", "getMKDItemBox5", "getMKDAchieve7", "getMKDLight8")

private class PhantomSession : RisingStonesSessionProvider {
    var enabled = true
    var missingAuthorizer = false
    var refreshes = 0
    var authorizerHook: suspend () -> Unit = {}
    var headerHook: suspend () -> Unit = {}
    var refreshHook: suspend () -> Unit = {}
    val contexts = mutableListOf<RisingStonesRequestContext>()
    override val capabilities get() = if (enabled) setOf(RisingStonesCapability.PersonalData) else emptySet()
    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer? {
        authorizerHook()
        if (missingAuthorizer) return null
        return RisingStonesRequestAuthorizer { context, sink ->
            contexts += context
            headerHook()
            sink.set("Authorization", "Fixture token")
            sink.set("User-Agent", "Fixture browser UA")
        }
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? {
        refreshes++
        refreshHook()
        return currentAuthorizer()
    }
}

private class PhantomTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    val payloads = mutableMapOf<String, String?>("dataOpenStatus" to """{"mkd":"1","page_a":"1"}""")
    val codes = mutableMapOf<String, Int>()
    val statuses = mutableMapOf<String, Int>()
    var code = 10000
    var message = "PRIVATE_RESPONSE"
    var beforeResponse: suspend (String) -> Unit = {}
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val endpoint = request.url.toHttpUrl().pathSegments.last()
        beforeResponse(endpoint)
        val data = if (payloads.containsKey(endpoint)) payloads[endpoint] else "[]"
        val payload = """{"code":${codes[endpoint] ?: code},"msg":"$message"${data?.let { ",\"data\":$it" }.orEmpty()}}"""
        return RisingStonesHttpResponse(statuses[endpoint] ?: 200, emptyMap(), payload.encodeToByteArray())
    }
}
