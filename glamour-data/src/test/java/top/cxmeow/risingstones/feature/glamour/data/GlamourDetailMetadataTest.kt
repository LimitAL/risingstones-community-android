package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourJob
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GlamourDetailMetadataTest {
    @Test
    fun mapsDetailMetadataAlongsideExistingSortedTagsEquipmentAndAccessories() = runBlocking {
        val detail = fetchDetail("""
            {
              "id":"42","title":"Fixture look","desc":"Fixture description",
              "job_ids":[{"id":24,"name":"白魔法师"},28],"gender_ids":[2],
              "race_ids":[{"id":6,"name":"敖龙族"}],
              "tags":[
                {"tag_id":"11","tag_name":"高贵","is_custom":0,
                 "category_id":"2","category_name":"气质","category_code":"temperament",
                 "category_sort":2,"tag_sort":1},
                {"tag_id":"2","tag_name":"深肤色","is_custom":0,
                 "category_id":"1","category_name":"肤色","category_code":"skin_tone",
                 "category_sort":1,"tag_sort":2}
              ],
              "equipments":[{"slot":"BODY","equipment_id":"100","name":"Fixture body",
                "icon_id":"200","dye_ids":[1],
                "dyes":[{"id":1,"name":"Fixture dye","color":"#eeeeee"}]}],
              "ort_info":{"glasses_id":5,"glasses_name":"Fixture glasses",
                "ornament_id":6,"ornament_name":"Fixture parasol"},
              "userInfo":{"uuid":"fixture-author","characterName":"Fixture author"},
              "future_metadata":{"ignored":true}
            }
        """.trimIndent())

        assertEquals(listOf(GlamourJob(24, "白魔法师")), detail.jobs)
        assertEquals(listOf(2), detail.genderIds)
        assertEquals(listOf("敖龙族"), detail.raceNames)
        assertEquals(listOf(2, 11), detail.tags.map { it.id })
        assertEquals(listOf("深肤色", "高贵"), detail.tags.map { it.name })
        assertEquals(listOf("肤色", "气质"), detail.tags.map { it.categoryName })
        assertEquals(listOf("skin_tone", "temperament"), detail.tags.map { it.categoryCode })
        assertEquals(listOf(1, 2), detail.tags.map { it.categorySort })
        assertEquals(listOf(2, 1), detail.tags.map { it.tagSort })
        assertEquals("Fixture author", detail.author.characterName)
        assertEquals("Fixture body", detail.equipments.single().name)
        assertEquals(100, detail.equipments.single().equipmentId)
        assertEquals("Fixture dye", detail.equipments.single().dye(0)?.name)
        assertEquals("Fixture glasses", detail.faceAccessory?.name)
        assertEquals("Fixture parasol", detail.fashionAccessory?.name)
    }

    @Test
    fun mixedJobRowsRequireAnIdAndNonEmptyStringNameWithoutTrimmingOrInferring() = runBlocking {
        val detail = fetchDetail("""
            {"id":42,"job_ids":[
              {"id":"24","name":"白魔法师"},28,"29",null,true,[],
              {"id":30},{"name":"Missing ID"},{"id":"invalid","name":"Invalid ID"},
              {"id":31,"name":null},{"id":32,"name":""},
              {"id":33,"name":123},{"id":34,"name":false},
              {"id":9001,"name":" Future job "},{"id":25,"name":"  "}
            ]}
        """.trimIndent())

        assertEquals(
            listOf(GlamourJob(24, "白魔法师"), GlamourJob(9001, " Future job "), GlamourJob(25, "  ")),
            detail.jobs,
        )
    }

    @Test
    fun absentNullEmptyAndLegacyResponsesKeepMetadataOptional() = runBlocking {
        for (data in listOf(
            """{"id":42}""",
            """{"id":42,"job_ids":null,"gender_ids":null,"tags":null}""",
            """{"id":42,"job_ids":[],"gender_ids":[],"tags":[]}""",
            """{"id":42,"job_ids":[24,"28",{"id":30}],"gender_ids":[]}""",
        )) {
            val detail = fetchDetail(data)
            assertTrue(detail.jobs.isEmpty())
            assertTrue(detail.genderIds.isEmpty())
            assertTrue(detail.tags.isEmpty())
            assertEquals("—", detail.author.characterName)
        }

        val legacyTags = fetchDetail("""
            {"id":42,"tags":[
              {"tag_id":1,"tag_name":"Legacy tag"},
              {"tag_id":2,"tag_name":"Null category","category_code":null}
            ]}
        """.trimIndent()).tags
        assertEquals(listOf("Legacy tag", "Null category"), legacyTags.map { it.name })
        assertTrue(legacyTags.all { it.categoryCode == null })
    }

    @Test
    fun camelCaseAndMixedAliasesPreserveUnknownIdsAndRawCategoryCodes() = runBlocking {
        for ((jobKey, genderKey, categoryKey) in listOf(
            Triple("job_ids", "gender_ids", "category_code"),
            Triple("jobIds", "genderIds", "categoryCode"),
            Triple("job_ids", "genderIds", "categoryCode"),
        )) {
            val detail = fetchDetail("""
                {"id":42,"$jobKey":[{"id":"9001","name":"Future job"}],
                 "$genderKey":[1,"2",99,-7,null,"invalid",true,{},[]],
                 "tags":[{"tagId":"8","tagName":"Future tag","isCustom":"0",
                   "categoryId":"9001","categoryName":"Future category",
                   "$categoryKey":" future_category ","categorySort":"1","tagSort":"2"}]}
            """.trimIndent())
            assertEquals(listOf(GlamourJob(9001, "Future job")), detail.jobs)
            assertEquals(listOf(1, 2, 99, -7), detail.genderIds)
            assertEquals(" future_category ", detail.tags.single().categoryCode)
            assertEquals(9001, detail.tags.single().categoryId)
        }
    }

    @Test
    fun snakeCaseWinsWhenPresentAndNullFallsBackToCamelCase() = runBlocking {
        val detail = fetchDetail("""
            {"id":42,
             "job_ids":[{"id":24,"name":"Snake job"}],"jobIds":[{"id":25,"name":"Camel job"}],
             "gender_ids":[2],"genderIds":[1],
             "tags":[{"tag_id":1,"tag_name":"Fixture tag",
               "category_code":"skin_tone","categoryCode":"future_category"}]}
        """.trimIndent())
        assertEquals(listOf(GlamourJob(24, "Snake job")), detail.jobs)
        assertEquals(listOf(2), detail.genderIds)
        assertEquals("skin_tone", detail.tags.single().categoryCode)

        val fallback = fetchDetail("""
            {"id":42,"job_ids":null,"jobIds":[{"id":"25","name":"Camel job"}],
             "gender_ids":null,"genderIds":["1"],
             "tags":[{"tag_id":1,"tag_name":"Fixture tag",
               "category_code":null,"categoryCode":"skin_tone"}]}
        """.trimIndent())
        assertEquals(listOf(GlamourJob(25, "Camel job")), fallback.jobs)
        assertEquals(listOf(1), fallback.genderIds)
        assertEquals("skin_tone", fallback.tags.single().categoryCode)
    }

    @Test
    fun malformedOptionalMetadataDoesNotDiscardTheDetailOrInventCategoryStrings() = runBlocking {
        val detail = fetchDetail("""
            {"id":42,"title":"Legacy title","job_ids":{},"gender_ids":"2",
             "tags":[
               {"tag_id":1,"tag_name":"Numeric code","category_code":123},
               {"tag_id":2,"tag_name":"Boolean code","category_code":true},
               {"tag_id":3,"tag_name":"Empty code","category_code":""}
             ]}
        """.trimIndent())
        assertEquals("Legacy title", detail.title)
        assertTrue(detail.jobs.isEmpty())
        assertTrue(detail.genderIds.isEmpty())
        assertEquals(3, detail.tags.size)
        assertNull(detail.tags[0].categoryCode)
        assertNull(detail.tags[1].categoryCode)
        assertEquals("", detail.tags[2].categoryCode)
    }

    private suspend fun fetchDetail(data: String): GlamourDetail {
        val transport = DetailMetadataTransport(data)
        val detail = GlamourApiService(
            RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
            DetailMetadataSession,
            temporarySessionId = "fixture-session",
        ).fetchDetail(42)
        val request = transport.requests.single()
        val url = request.url.toHttpUrl()
        assertEquals(RisingStonesHttpMethod.Get, request.method)
        assertTrue(url.encodedPath.endsWith("/api/home/glamour/glamourDetail"))
        assertEquals("42", url.queryParameter("id"))
        assertEquals("fixture-session", url.queryParameter("tempsuid"))
        return detail
    }
}

private class DetailMetadataTransport(private val data: String) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), """{"code":10000,"data":$data}""".encodeToByteArray())
    }
}

private object DetailMetadataSession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.GlamourAuthenticated)
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink ->
        sink.set("Authorization", "fixture-token")
    }
}
