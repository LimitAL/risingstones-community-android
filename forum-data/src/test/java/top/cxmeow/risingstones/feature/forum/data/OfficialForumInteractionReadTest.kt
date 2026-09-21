package top.cxmeow.risingstones.feature.forum.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumInteractionService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class OfficialForumInteractionReadTest {
    @Test
    fun zeroStringZeroAndExplicitNullExposeResultsWithoutImplyingParticipation() = runBlocking {
        for (count in listOf("0", "\"0\"", "null")) {
            val transport = DetailTransport(detail("voteInfo", listOf(option(1, count), option(2, count))))
            val result = transport.service().fetchPostInteraction(42)
            assertEquals(setOf(result.detail.votes.single().id), result.voteResultsAvailable)
            assertEquals(listOf(0, 0), result.detail.votes.single().options.map { it.totalVoteCount })
            assertFalse(result.detail.votes.single().hasParticipated)
            assertEquals(1, transport.requests.size)
            assertEquals(RisingStonesHttpMethod.Get, transport.requests.single().method)
        }
    }

    @Test
    fun oneMissingCountSuppressesResultsForAllGroupsEvenWithOtherPositiveCounts() = runBlocking {
        val transport = DetailTransport(detail("voteInfo", listOf(
            option(1, "9"), option(2, null, title = "Other vote"),
        )))
        val result = transport.service().fetchPostInteraction(42)
        assertEquals(2, result.detail.votes.size)
        assertTrue(result.voteResultsAvailable.isEmpty())
    }

    @Test
    fun camelAndLegacyAliasesUseTheSameMetadataRulesAsTheDetailMapping() = runBlocking {
        for (key in listOf("voteInfo", "vote_info")) {
            val result = DetailTransport(detail(key, listOf(option(1, "0"))))
                .service().fetchPostInteraction(42)
            assertEquals(setOf(result.detail.votes.single().id), result.voteResultsAvailable)
        }
        val legacy = "\"vote_info\":[${option(2, "0", title = "Legacy")} ]"
        val payload = """{"code":10000,"data":{"id":42,"voteInfo":[${option(1, null)}],$legacy}}"""
        val result = DetailTransport(payload).service().fetchPostInteraction(42)
        assertEquals("Fixture", result.detail.votes.single().title)
        assertTrue(result.voteResultsAvailable.isEmpty())
        val fallback = DetailTransport(payload.replace("[${option(1, null)}]", "null"))
            .service().fetchPostInteraction(42)
        assertEquals("Legacy", fallback.detail.votes.single().title)
        assertEquals(setOf(fallback.detail.votes.single().id), fallback.voteResultsAvailable)
    }

    @Test
    fun legacyDetailCallRemainsAReadOnlySingleRequestWithTheSameMapping() = runBlocking {
        val payload = detail("vote_info", listOf(option(1, "0")))
        val oldTransport = DetailTransport(payload)
        val legacy: OfficialForumService = oldTransport.service()
        val oldDetail = legacy.fetchPostDetail(42)
        val richTransport = DetailTransport(payload)
        val rich: OfficialForumInteractionService = richTransport.service()
        assertEquals(oldDetail, rich.fetchPostInteraction(42).detail)
        assertEquals(1, oldTransport.requests.size)
        assertEquals(1, richTransport.requests.size)
        assertFalse(rich.canPerformAuthenticatedWrites)
        assertFalse(rich.canUploadCommentImages)
    }

    @Test
    fun absentOrEmptyVoteListProvidesAnEmptyResultSet() = runBlocking {
        for (payload in listOf("""{"code":10000,"data":{"id":42}}""", detail("voteInfo", emptyList()))) {
            val result = DetailTransport(payload).service().fetchPostInteraction(42)
            assertTrue(result.detail.votes.isEmpty())
            assertTrue(result.voteResultsAvailable.isEmpty())
        }
    }

    @Test
    fun acceptedCodesStillRequireTheExistingDetailPayloadAndIdentifier() {
        for (code in listOf(10000, 10002)) {
            for (payload in listOf("null", "{}")) {
                assertThrows(OfficialForumException.MissingPayload::class.java) {
                    runBlocking {
                        DetailTransport("""{"code":$code,"data":$payload}""")
                            .service().fetchPostInteraction(42)
                    }
                }
            }
        }
    }

    private fun detail(key: String, options: List<String>) =
        """{"code":10000,"data":{"id":42,"$key":[${options.joinToString(",")}]}}"""

    private fun option(id: Int, count: String?, title: String = "Fixture"): String =
        """{"id":$id,"posts_id":42,"vote_type":1,"option_type":1,"vote_title":"$title","option_id":$id,"option":"Option $id","is_participant":0${count?.let { ",\"total_vote_num\":$it" }.orEmpty()}}"""

    private class DetailTransport(private val body: String) : RisingStonesHttpClient {
        val requests = mutableListOf<RisingStonesHttpRequest>()
        fun service() = OfficialForumApiService(RisingStonesPublicApiClient(this))
        override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
            requests += request
            return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
        }
    }
}
