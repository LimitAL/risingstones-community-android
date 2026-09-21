package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GlamourCandidateApiTest {
    @Test
    fun detailOmitsOnlyUnequippedSentinelsAndRetainsUnknownPositiveEquipment() = runBlocking {
        val transport = CandidateTransport().apply {
            payload = """{"id":1,"equipments":[
                {"slot":"HEAD","equipment_id":-1,"name":"Unequipped"},
                {"slot":"BODY","equipmentId":"-1"},
                {"slot":"HANDS","equipment_id":42,"name":null},
                {"slot":"LEGS","equipmentId":"43","name":"Fixture equipment",
                 "iconId":"123","dyeIds":["7"],"dyes":[{"id":7,"name":"Fixture dye"}]}
            ]}"""
        }
        val equipment = service(transport).fetchDetail(1).equipments
        assertEquals(listOf(42, 43), equipment.map { it.equipmentId })
        assertNull(equipment.first().name)
        assertEquals("Fixture equipment", equipment.last().name)
        assertEquals("123", equipment.last().iconId)
        assertEquals("Fixture dye", equipment.last().dye(0)?.name)
    }

    @Test
    fun detailOmitsUnequippedAccessoriesForBothFieldConventions() = runBlocking {
        val transport = CandidateTransport()
        val service = service(transport)
        for (ornament in listOf(
            """"ort_info":{"glasses_id":-1,"glasses_name":"Unused glasses","ornament_id":"-1","ornament_name":"Unused ornament"}""",
            """"ortInfo":{"glassesId":"-1","glassesName":"Unused glasses","ornamentId":-1,"ornamentName":"Unused ornament"}""",
        )) {
            transport.payload = """{"id":1,$ornament}"""
            val detail = service.fetchDetail(1)
            assertNull(detail.faceAccessory)
            assertNull(detail.fashionAccessory)
        }
        transport.payload = """{"id":1,"ortInfo":{"glassesId":"12","glassesName":"Fixture glasses","glassesIcon":"21","ornamentId":13,"ornamentName":"Fixture ornament","ornamentIcon":"22"}}"""
        val detail = service.fetchDetail(1)
        assertEquals(12, detail.faceAccessory?.id)
        assertEquals("21", detail.faceAccessory?.iconId)
        assertEquals(13, detail.fashionAccessory?.id)
        assertEquals("22", detail.fashionAccessory?.iconId)
    }

    @Test
    fun equipmentCandidatesPreserveSnakeAndCamelFieldsAndReadOnlyQuery() = runBlocking {
        val transport = CandidateTransport().apply {
            payload = """{"rows":[
                {"id":"42","name":"Fixture equipment","desc":"Fixture description","icon_id":"123","class_jobs":[{"name":"Fixture job"}]},
                {"id":43,"name":"Camel equipment","des":"Alternate description","iconId":"124","classJobs":[{"name":"Camel job"}]},
                {"id":44,"name":"Minimal equipment","icon":"125"}
            ]}"""
        }
        val result = service(transport).searchEquipment("Fixture & name", 2)
        assertEquals(listOf(42, 43, 44), result.map { it.id })
        assertEquals(listOf("123", "124", "125"), result.map { it.iconId })
        assertEquals("Fixture description", result.first().description)
        assertEquals("Alternate description", result[1].description)
        assertEquals(listOf("Fixture job"), result.first().jobNames)
        assertEquals(listOf("Camel job"), result[1].jobNames)
        assertTrue(result.last().jobNames.isEmpty())
        val request = transport.requests.single()
        val url = request.url.toHttpUrl()
        assertTrue(url.encodedPath.endsWith("/gameData/searchEquip"))
        assertEquals("Fixture & name", url.queryParameter("name"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals(RisingStonesHttpMethod.Get, request.method)
        assertEquals("fixture-paired-agent", request.headers["User-Agent"])
    }

    @Test
    fun accessoriesPreserveGroupsAndEmptyGroupsWithBothFieldConventions() = runBlocking {
        val transport = CandidateTransport().apply {
            payload = """[
                {"style_id":"2","style_name":"Fixture style","list":[{"id":"5","name":"Fixture glasses","des":"Description","icon":"123"}]},
                {"styleId":3,"styleName":"Empty style","list":[]}
            ]"""
        }
        val service = service(transport)
        val groups = service.searchGlasses("Fixture name")
        assertEquals(listOf(2, 3), groups.map { it.id })
        assertEquals("Fixture style", groups.first().name)
        assertEquals(5, groups.first().accessories.single().id)
        assertEquals("123", groups.first().accessories.single().iconId)
        assertEquals("Description", groups.first().accessories.single().description)
        assertTrue(groups.last().accessories.isEmpty())
        transport.payload = """{"rows":[{"id":6,"name":"Fixture ornament","icon_id":"124"},{"id":7,"name":"Alternate ornament","desc":"Legacy description","iconId":"125"}]}"""
        val ornaments = service.searchOrnaments("Fixture name")
        val ornament = ornaments.first()
        assertEquals(6, ornament.id)
        assertEquals("124", ornament.iconId)
        assertEquals("", ornament.description)
        assertEquals("125", ornaments.last().iconId)
        assertEquals("Legacy description", ornaments.last().description)
        assertEquals(listOf("getGlassesList", "getOrnamentList"), transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
        assertTrue(transport.requests.all { it.url.toHttpUrl().queryParameter("name") == "Fixture name" })
    }

    @Test
    fun explicitEmptyCandidateCollectionsRemainSuccessfulEmptyResults() = runBlocking {
        val transport = CandidateTransport().apply { payload = """{"rows":[]}""" }
        val service = service(transport)
        assertTrue(service.searchEquipment("Fixture", 1).isEmpty())
        assertTrue(service.searchOrnaments("Fixture").isEmpty())
        transport.payload = "[]"
        assertTrue(service.searchGlasses("Fixture").isEmpty())
    }

    @Test
    fun missingAndWrongCandidateContainersCannotMasqueradeAsEmptyResults() = runBlocking {
        val transport = CandidateTransport()
        val service = service(transport)
        for (payload in listOf("null", "{}", "1", "\"fixture\"", """{"rows":null}""", """{"rows":{}}""")) {
            transport.payload = payload
            expectMissingPayload { service.searchEquipment("Fixture", 1) }
            expectMissingPayload { service.searchGlasses("Fixture") }
            expectMissingPayload { service.searchOrnaments("Fixture") }
        }
        transport.includePayload = false
        expectMissingPayload { service.searchEquipment("Fixture", 1) }
        expectMissingPayload { service.searchGlasses("Fixture") }
        expectMissingPayload { service.searchOrnaments("Fixture") }
    }

    @Test
    fun malformedCandidateRowsRejectTheWholeResponse() = runBlocking {
        val transport = CandidateTransport()
        val service = service(transport)
        for (row in listOf("null", "1", "{}", """{"id":1}""", """{"name":"Missing identity"}""")) {
            transport.payload = """{"rows":[{"id":42,"name":"Valid"},$row]}"""
            expectMissingPayload { service.searchEquipment("Fixture", 1) }
            expectMissingPayload { service.searchOrnaments("Fixture") }
            transport.payload = """[{"style_id":2,"style_name":"Style","list":[{"id":42,"name":"Valid"},$row]}]"""
            expectMissingPayload { service.searchGlasses("Fixture") }
        }
    }

    @Test
    fun malformedGlassesGroupsCannotBecomeEmptyOrPartialResults() = runBlocking {
        val transport = CandidateTransport()
        val service = service(transport)
        for (group in listOf("null", "1", "{}", """{"style_id":1,"style_name":"Style"}""", """{"style_id":1,"style_name":"Style","list":null}""", """{"style_id":1,"list":[]}""")) {
            transport.payload = "[$group]"
            expectMissingPayload { service.searchGlasses("Fixture") }
        }
    }

    @Test
    fun malformedEquipmentJobMetadataIsRejected() = runBlocking {
        val transport = CandidateTransport()
        val service = service(transport)
        for (jobs in listOf("{}", "1", "[null]", "[{}]")) {
            transport.payload = """{"rows":[{"id":42,"name":"Fixture equipment","class_jobs":$jobs}]}"""
            expectMissingPayload { service.searchEquipment("Fixture", 1) }
        }
    }

    private suspend fun expectMissingPayload(block: suspend () -> Any?) {
        try {
            block()
            fail("Malformed candidate payload must be rejected")
        } catch (_: GlamourException.MissingPayload) { }
    }

    private fun service(transport: CandidateTransport) = GlamourApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        CandidateSession,
    )
}

private object CandidateSession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.GlamourAuthenticated)
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("User-Agent", "fixture-paired-agent")
    }
}

private class CandidateTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var payload = "null"
    var includePayload = true

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val body = if (includePayload) """{"code":10000,"data":$payload}""" else """{"code":10000}"""
        return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
    }
}
