package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriodKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetric
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetricUnit
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class PersonalDataMetricSemanticsTest {
    @Test
    fun frontlineOverviewAndEachPeriodExposeIntegerPercentages() = runBlocking {
        val content = service(
            "frontline1TotalNew" to """[
                {"data_time":"v51","win_rate":"0.4"},
                {"data_time":"total","win_rate":0.845},
                {"data_time":"30days","win_rate":"0.995"}
            ]""",
        ).fetchBoardContent(PersonalDataBoard.Frontline)

        assertEquals(percent("win_rate", "85"), content.metrics.single())
        assertEquals(
            mapOf(
                FrontlinePeriodKind.Since51 to percent("win_rate", "40"),
                FrontlinePeriodKind.Total to percent("win_rate", "85"),
                FrontlinePeriodKind.Last30Days to percent("win_rate", "100"),
            ),
            content.frontlinePeriods.associate { it.kind to it.metrics.single() },
        )
    }

    @Test
    fun fishingUsesRatioConversionEvenWhenInputLooksLikeAnExistingPercentage() = runBlocking {
        for ((raw, expected) in listOf("0" to "0", "1" to "100", "0.845" to "85", "84.5" to "8450")) {
            val content = service("fishTotal1" to """[{"succ_rate":"$raw"}]""")
                .fetchBoardContent(PersonalDataBoard.Fishing)

            assertEquals("raw=$raw", percent("succ_rate", expected), content.metrics.single())
        }
    }

    @Test
    fun invalidAndNonFiniteRatiosNeverBecomeDisplayValues() = runBlocking {
        val invalidValues = listOf(
            "null", "true", "{}", "[]", "\"\"", "\"   \"", "\"unknown\"", "\"NaN\"",
            "\"Infinity\"", "\"-Infinity\"", "\"1e309\"", "\"1e308\"", "\"0.5 trailing\"",
        )
        for (raw in invalidValues) {
            val fishing = service("fishTotal1" to """[{"succ_rate":$raw}]""")
                .fetchBoardContent(PersonalDataBoard.Fishing)
            val frontline = service("frontline1TotalNew" to """[{"data_time":"total","win_rate":$raw}]""")
                .fetchBoardContent(PersonalDataBoard.Frontline)

            assertTrue("Fishing must omit $raw", fishing.metrics.isEmpty())
            assertTrue("Frontline must omit $raw", frontline.metrics.isEmpty())
            assertTrue(frontline.frontlinePeriods.single().metrics.isEmpty())
        }
    }

    @Test
    fun kdaUsesTwoDecimalPlacesAndTheOfficialBinaryNumberRounding() = runBlocking {
        for ((raw, expected) in listOf("0" to "0.00", "2" to "2.00", "1.005" to "1.00", "2.675" to "2.67", "1.375" to "1.38")) {
            val content = service("frontline1TotalNew" to """[{"data_time":"total","kda":"$raw"}]""")
                .fetchBoardContent(PersonalDataBoard.Frontline)

            assertEquals("raw=$raw", PersonalDataMetric("kda", expected), content.metrics.single())
            assertEquals(content.metrics, content.frontlinePeriods.single().metrics)
        }
    }

    @Test
    fun frontlineHoursAndRankValuesKeepTheirExistingScaleAndCompanyKeepsItsName() = runBlocking {
        val content = service(
            "frontline1TotalNew" to """[{"data_time":"total","clear_time":"19.5",
                "gc_id":"恒辉队","kill_rank":"86","heal_rank":52,"pvp_rank":"30"}]""",
        ).fetchBoardContent(PersonalDataBoard.Frontline)

        assertEquals(
            setOf(
                PersonalDataMetric("clear_time", "19.5", PersonalDataMetricUnit.Hours),
                PersonalDataMetric("gc_id", "恒辉队"),
                PersonalDataMetric("kill_rank", "86"),
                PersonalDataMetric("heal_rank", "52"),
                PersonalDataMetric("pvp_rank", "30", PersonalDataMetricUnit.Levels),
            ),
            content.metrics.toSet(),
        )
    }

    @Test
    fun missingAndInvalidNumericMetricsStayAbsentWithoutAZeroFallback() = runBlocking {
        val content = service(
            "frontline1TotalNew" to """[{"data_time":"total","kda":"NaN","clear_time":"Infinity",
                "fight_times":"unknown","kill_rank":false,"heal_rank":null}]""",
        ).fetchBoardContent(PersonalDataBoard.Frontline)

        assertTrue(content.metrics.isEmpty())
        assertTrue(content.frontlinePeriods.single().metrics.isEmpty())
        assertTrue(service("fishTotal1" to "[{}]").fetchBoardContent(PersonalDataBoard.Fishing).metrics.isEmpty())
    }

    @Test
    fun fishingSavageAndGlamourUseTheirFirstSummaryRowWithoutAggregating() = runBlocking {
        val service = service(
            "fishTotal1" to """[{"total_times":"12","succ_rate":"0.845"},{"total_times":"50","succ_rate":"0.1"}]""",
            "getLingShiTotal" to """[{"territory_num":"12","elapsed_time":"19.5"},{"territory_num":"50","elapsed_time":"300"}]""",
            "getDressTotal7" to """[{"washing_num":"4","vanity_times":"302"},{"washing_num":"6","vanity_times":"900"}]""",
        )

        assertEquals(
            listOf(PersonalDataMetric("total_times", "12", PersonalDataMetricUnit.Times), percent("succ_rate", "85")),
            service.fetchBoardContent(PersonalDataBoard.Fishing).metrics,
        )
        assertEquals(
            listOf(
                PersonalDataMetric("territory_num", "12", PersonalDataMetricUnit.Pieces),
                PersonalDataMetric("elapsed_time", "19.5", PersonalDataMetricUnit.Hours),
            ),
            service.fetchBoardContent(PersonalDataBoard.Savage).metrics,
        )
        assertEquals(
            listOf(
                PersonalDataMetric("washing_num", "4", PersonalDataMetricUnit.Times),
                PersonalDataMetric("vanity_times", "302", PersonalDataMetricUnit.Times),
            ),
            service.fetchBoardContent(PersonalDataBoard.Glamour).metrics,
        )
    }

    @Test
    fun emptyFirstSummaryRowDoesNotBorrowMetricsFromLaterRows() = runBlocking {
        val service = service(
            "fishTotal1" to """[{}, {"total_times":"50","succ_rate":"0.1"}]""",
            "getLingShiTotal" to """[{}, {"territory_num":"50","elapsed_time":"300"}]""",
            "getDressTotal7" to """[{}, {"washing_num":"6","vanity_times":"900"}]""",
        )

        for (board in listOf(PersonalDataBoard.Fishing, PersonalDataBoard.Savage, PersonalDataBoard.Glamour)) {
            assertTrue("$board", service.fetchBoardContent(board).metrics.isEmpty())
        }
    }

    @Test
    fun frontlineWithoutTotalDoesNotPromoteAnotherPeriodToTheOverview() = runBlocking {
        val content = service(
            "frontline1TotalNew" to """[
                {"data_time":"v51","win_rate":"0.4","fight_times":"40"},
                {"data_time":"30days","win_rate":"0.5","fight_times":"20"},
                {"data_time":"unknown","win_rate":"1","fight_times":"100"},
                {"fight_times":"200"}
            ]""",
        ).fetchBoardContent(PersonalDataBoard.Frontline)

        assertTrue(content.metrics.isEmpty())
        assertEquals(listOf(FrontlinePeriodKind.Since51, FrontlinePeriodKind.Last30Days), content.frontlinePeriods.map { it.kind })
        assertEquals(listOf("40", "50"), content.frontlinePeriods.map { period -> period.metrics.single { it.id == "win_rate" }.value })
    }

    @Test
    fun emptyTotalAndEmptyDatasetsDoNotInventOverviewMetrics() = runBlocking {
        val frontline = service(
            "frontline1TotalNew" to """[{"data_time":"v51","win_rate":"0.4"},{"data_time":"total"}]""",
        ).fetchBoardContent(PersonalDataBoard.Frontline)

        assertTrue(frontline.metrics.isEmpty())
        for (board in PersonalDataBoard.entries.filterNot { it == PersonalDataBoard.Ultimate }) {
            assertTrue("$board", service().fetchBoardContent(board).metrics.isEmpty())
        }
    }

    @Test
    fun confirmedCatalogNameAndCapitalNameFieldsSupplyTitlesWithoutReplacingExistingNames() = runBlocking {
        val content = service(
            "getDressFullset5" to """[
                {"catalog_name":"套装 & <字面值>","log_time":"2026-01-01"},
                {"Name":"Stain literal name","log_time":"2026-01-02"},
                {"name":"Existing title","catalog_name":"Other title","Name":"Third title"},
                {"name":"   ","Name":"Fallback name"}
            ]""",
        ).fetchBoardContent(PersonalDataBoard.Glamour)

        assertEquals(
            listOf("套装 & <字面值>", "Stain literal name", "Existing title", "Fallback name"),
            content.sections.single { it.id == "fullset" }.entries.map { it.title },
        )
    }

    @Test
    fun missingNullScalarAndMalformedSummaryPayloadsAreNotConfirmedEmptyData() = runBlocking {
        val malformed = listOf(
            """{"code":10000}""",
            """{"code":10000,"data":null}""",
            """{"code":10000,"data":false}""",
            """{"code":10000,"data":"unavailable"}""",
            """{"code":10000,"data":[null]}""",
            """{"code":10000,"data":[{"total_times":10},false]}""",
            """{"code":10000,"data":{"rows":null}}""",
            """{"code":10000,"data":{"list":[42]}}""",
        )
        for (payload in malformed) {
            assertMissingPayload {
                serviceWithResponse("fishTotal1", payload).fetchBoardContent(PersonalDataBoard.Fishing)
            }
        }
        assertTrue(service("fishTotal1" to "[]").fetchBoardContent(PersonalDataBoard.Fishing).metrics.isEmpty())
    }

    @Test
    fun legitimateObjectAndArrayWrappersKeepTheirExistingSupport() = runBlocking {
        for (data in listOf(
            """{"succ_rate":"0.845"}""",
            """[{"succ_rate":"0.845"}]""",
            """{"rows":[{"succ_rate":"0.845"}]}""",
            """{"list":[{"succ_rate":"0.845"}]}""",
            """{"data":[{"succ_rate":"0.845"}]}""",
        )) {
            assertEquals(
                listOf(percent("succ_rate", "85")),
                service("fishTotal1" to data).fetchBoardContent(PersonalDataBoard.Fishing).metrics,
            )
        }
        for (wrapper in listOf("rows", "list", "data")) {
            assertTrue(
                service("fishTotal1" to """{"$wrapper":[]}""")
                    .fetchBoardContent(PersonalDataBoard.Fishing).metrics.isEmpty(),
            )
        }
    }

    @Test
    fun malformedSectionReportsPartialFailureWhileExplicitEmptySectionSucceeds() = runBlocking {
        for (payload in listOf(
            """{"code":10000}""",
            """{"code":10000,"data":null}""",
            """{"code":10000,"data":[{"name":"Valid row"},"invalid row"]}""",
        )) {
            val content = serviceWithResponse("fishNum2", payload).fetchBoardContent(PersonalDataBoard.Fishing)
            val section = content.sections.single { it.id == "fish" }

            assertEquals("load_failed", section.error)
            assertTrue(section.entries.isEmpty())
            assertTrue(content.sections.filterNot { it.id == "fish" }.all { it.error == null })
        }
        assertTrue(service().fetchBoardContent(PersonalDataBoard.Fishing).sections.all { it.error == null && it.entries.isEmpty() })
    }

    @Test
    fun ultimateDashboardRejectsMalformedDataButAcceptsExplicitEmptyArray() = runBlocking {
        for (payload in listOf(
            """{"code":10000}""",
            """{"code":10000,"data":null}""",
            """{"code":10000,"data":1}""",
            """{"code":10000,"data":[{"territory_type":968},null]}""",
        )) {
            assertMissingPayload { serviceWithResponse("gaoNanFirst1", payload).fetchUltimateDashboard() }
        }
        assertTrue(service().fetchUltimateDashboard().summaries.isEmpty())
    }

    @Test
    fun ultimateDetailMissingDatasetIsAPartialFailureWithoutInventingTeammates() = runBlocking {
        val transport = MetricSemanticsTransport(
            mapOf("gaoNanFirst1" to """[{"territory_type":968,"clear_times":1}]"""),
            mapOf("gaoNanTeam2" to """{"code":10000}"""),
        )
        val service = service(transport)
        val summary = requireNotNull(service.fetchUltimateDashboard().summary(968))

        val detail = service.fetchUltimateEncounterDetail(summary)

        assertTrue(detail.teammates.isEmpty())
        assertEquals(mapOf("team" to "load_failed"), detail.sectionErrors)
    }

    private fun percent(id: String, value: String) = PersonalDataMetric(id, value, PersonalDataMetricUnit.Percent)

    private suspend fun assertMissingPayload(block: suspend () -> Any?) {
        try {
            block()
            fail("Expected missing or malformed data to be rejected")
        } catch (_: PersonalDataException.MissingPayload) {
            // The empty-data state requires an explicit valid payload.
        }
    }

    private fun service(vararg data: Pair<String, String>) = service(MetricSemanticsTransport(data.toMap()))

    private fun serviceWithResponse(endpoint: String, payload: String) =
        service(MetricSemanticsTransport(emptyMap(), mapOf(endpoint to payload)))

    private fun service(transport: RisingStonesHttpClient): PersonalDataApiService = PersonalDataApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        MetricSemanticsSession,
        temporarySessionId = "metric-fixture",
    )
}

private object MetricSemanticsSession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.PersonalData)
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("Authorization", "Fixture token")
    }
}

private class MetricSemanticsTransport(
    private val data: Map<String, String>,
    private val responses: Map<String, String> = emptyMap(),
) : RisingStonesHttpClient {
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        val endpoint = request.url.toHttpUrl().pathSegments.last()
        val payload = responses[endpoint] ?: """{"code":10000,"data":${data[endpoint] ?: "[]"}}"""
        return RisingStonesHttpResponse(200, emptyMap(), payload.encodeToByteArray())
    }
}
