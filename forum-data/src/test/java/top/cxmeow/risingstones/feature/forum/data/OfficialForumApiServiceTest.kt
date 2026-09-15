package top.cxmeow.risingstones.feature.forum.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictHandler
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictState
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumListQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchOrder
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSubCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteSelection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class OfficialForumApiServiceTest {
    @Test
    fun cosSignerMatchesIosKnownAnswer() {
        assertEquals(
            "q-sign-algorithm=sha1&q-ak=AKIDTEST&q-sign-time=1700000000;1700001800&" +
                "q-key-time=1700000000;1700001800&q-header-list=content-length&" +
                "q-url-param-list=&q-signature=b65fab15b01f7bb92c66dae6318aa4c59cdb2111",
            RisingStonesCos.authorization(
                url = "https://ff14risingstones.gcloud.com.cn/posts/20260722/10015973/test/" +
                    "1700000000123_abc1700000000123.png",
                contentLength = 5,
                secretId = "AKIDTEST",
                secretKey = "test-secret",
                startTime = 1_700_000_000,
                expiredTime = 1_700_001_800,
            ),
        )
    }

    @Test
    fun imageUploadFetchesTemporaryTokenThenPutsNormalizedBytes() = runBlocking {
        val transport = ImageUploadTransport()
        val url = service(transport, TestCredentialProvider).uploadCommentImage(
            OfficialForumCommentImageUpload(byteArrayOf(0, 1, 2, 3, 4), "image/png"),
        )

        assertEquals(2, transport.requests.size)
        assertEquals(RisingStonesHttpMethod.Get, transport.requests[0].method)
        assertEquals("posts", transport.requests[0].url.toHttpUrl().queryParameter("channel"))
        assertEquals(RisingStonesHttpMethod.Put, transport.requests[1].method)
        assertEquals("https://ff14risingstones.gcloud.com.cn", transport.requests[1].url.substringBefore("/posts/"))
        assertEquals("image/png", transport.requests[1].headers["Content-Type"])
        assertEquals("5", transport.requests[1].headers["Content-Length"])
        assertEquals("session-token", transport.requests[1].headers["x-cos-security-token"])
        assertEquals(byteArrayOf(0, 1, 2, 3, 4).toList(), transport.requests[1].body?.toList())
        assertTrue(transport.requests[1].headers["Authorization"].orEmpty().contains("q-sign-algorithm=sha1"))
        assertTrue(url.startsWith("https://ff14risingstones.gcloud.com.cn/posts/"))
    }

    @Test
    fun publicListsUseFrozenQueriesAndMapFlexibleRows() = runBlocking {
        val transport = OfficialForumTransport()
        val service = service(transport)

        val parts = service.fetchParts()
        val posts = service.fetchPosts(
            OfficialForumListQuery(OfficialForumContentKind.Post, page = 2, partIds = listOf(8, 3)),
        )
        val search = service.searchPosts(
            OfficialForumSearchQuery(
                OfficialForumContentKind.Guide,
                "  攻略  ",
                order = OfficialForumSearchOrder.Comment,
            ),
        )

        assertEquals(listOf(8, 3), parts.map { it.id })
        val listUrl = transport.requests[1].url.toHttpUrl()
        assertEquals("0", listUrl.queryParameter("is_top"))
        assertEquals("8,3", listUrl.queryParameter("part_id"))
        assertEquals("2", listUrl.queryParameter("page"))
        assertEquals(41, posts.total)
        assertEquals("Topic", posts.items.single().title)
        assertEquals(listOf("https://cdn.test/cover.jpg"), posts.items.single().coverImageUrls)
        val searchUrl = transport.requests[2].url.toHttpUrl()
        assertEquals("2", searchUrl.queryParameter("type"))
        assertEquals("  攻略  ", searchUrl.queryParameter("keywords"))
        assertEquals("comment", searchUrl.queryParameter("orderBy"))
        assertFalse(searchUrl.queryParameter("tempsuid").isNullOrBlank())
        assertEquals(1, search.items.size)
    }

    @Test
    fun detailCommentsAndSubCommentsPreserveRichContentAndVoteGroups() = runBlocking {
        val transport = OfficialForumTransport()
        val service = service(transport)

        val detail = service.fetchPostDetail(42)
        val comments = service.fetchComments(OfficialForumCommentQuery(42, onlyPostAuthor = true))
        val children = service.fetchSubComments(OfficialForumSubCommentQuery(9, limit = 3))

        assertEquals("Hello & world\n\nsite [emo2]", detail.bodyText)
        assertTrue(detail.bodySegments.any { it is OfficialForumRichTextSegment.Link })
        assertTrue(detail.bodySegments.any { it is OfficialForumRichTextSegment.Emoji })
        assertEquals(1, detail.votes.size)
        assertEquals(listOf(1, 2), detail.votes.single().options.map { it.optionId })
        assertTrue(detail.votes.single().hasParticipated)
        assertEquals(true, detail.isLiked)
        assertEquals(null, detail.isStarred)
        assertEquals(2, detail.contentImageUrls.size)
        assertEquals("1", transport.requests[1].url.toHttpUrl().queryParameter("onlyLandlord"))
        assertEquals("earliest", transport.requests[2].url.toHttpUrl().queryParameter("order"))
        assertEquals("Reply", comments.items.single().bodyText)
        assertEquals(3, comments.items.single().childCount)
        assertTrue(comments.items.single().isMine)
        assertEquals(1, children.items.size)
    }

    @Test
    fun currentCamelCaseDetailRelationsAreDecoded() = runBlocking {
        val detail = service(OfficialForumTransport(detailBody = CAMEL_DETAIL))
            .fetchPostDetail(8)

        assertEquals("Current response", detail.bodyText)
        assertEquals("Forum helper", detail.author.characterName)
        assertEquals("Moderation", detail.part.name)
        assertEquals("Official", detail.part.parentName)
        assertEquals("Current vote", detail.votes.single().title)
    }

    @Test
    fun writesRequireRisingStonesCredentialAndUseExactFormFields() = runBlocking {
        val unauthenticated = service(OfficialForumTransport())
        assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
            runBlocking { unauthenticated.likePost(42) }
        }

        val transport = OfficialForumTransport()
        val service = service(transport, TestCredentialProvider)
        assertEquals(1, service.likePost(42))
        assertEquals(-1, service.starPost(42))
        assertEquals(
            listOf(91),
            service.submitComment(OfficialForumCommentDraft(42, 9, 9, "<p>Hello</p>", "a.jpg")),
        )
        val vote = service.submitVote(
            OfficialForumVoteDraft(42, listOf(OfficialForumVoteSelection(2, "Option B"))),
        )

        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Post })
        assertTrue(transport.requests.all { it.headers["Authorization"] == "host token" })
        assertTrue(transport.requests.all {
            it.contentType == "application/x-www-form-urlencoded; charset=utf-8"
        })
        assertEquals("42", transport.requests[0].form()["id"])
        assertEquals("1", transport.requests[0].form()["type"])
        assertEquals("42", transport.requests[1].form()["posts_id"])
        assertEquals("<p>Hello</p>", transport.requests[2].form()["content"])
        assertEquals("9", transport.requests[2].form()["root_parent"])
        assertTrue(transport.requests[3].form().getValue("options").contains("option_id"))
        assertEquals(7, vote.voteTotalUser)
        assertEquals(5, vote.voteDetails[2])
    }

    @Test
    fun deleteCommentSendsJsonBodyOverDeleteWithCredential() = runBlocking {
        val unauthenticated = service(OfficialForumTransport())
        assertThrows(OfficialForumException.AuthenticationRequired::class.java) {
            runBlocking { unauthenticated.deleteComment(2139818) }
        }

        val transport = OfficialForumTransport()
        val service = service(transport, TestCredentialProvider)
        service.deleteComment(2139818)

        val request = transport.requests.single()
        assertEquals(RisingStonesHttpMethod.Delete, request.method)
        assertEquals("host token", request.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", request.contentType)
        assertEquals("{\"comment_id\":\"2139818\"}", requireNotNull(request.body).decodeToString())
    }

    @Test
    fun businessFailureIsNotPresentedAsEmptyContent() {
        val transport = OfficialForumTransport(failBusiness = true)
        val error = assertThrows(OfficialForumException.Business::class.java) {
            runBlocking { service(transport).fetchParts() }
        }
        assertEquals(10001, error.code)
        assertEquals("denied", error.detail)
    }

    @Test
    fun authenticatedIdentityIsAppliedToReadsAsWellAsWrites() = runBlocking {
        val transport = OfficialForumTransport()
        service(transport, TestCredentialProvider).fetchPostDetail(42)

        assertEquals("host token", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun expiredCredentialRefreshesOnceAndRetriesAuthenticatedRead() = runBlocking {
        val transport = RefreshingOfficialForumTransport()
        val provider = RefreshingCredentialProvider()

        val detail = service(transport, provider).fetchPostDetail(42)

        assertEquals(42, detail.id)
        assertEquals(1, provider.refreshCount)
        assertEquals(listOf("expired", "refreshed"), transport.authorizationHeaders)
    }

    @Test
    fun identityConflictIsExplicitAndDoesNotAutomaticallyTakeOver() {
        val provider = RefreshingCredentialProvider()
        val error = assertThrows(OfficialForumException.IdentityConflict::class.java) {
            runBlocking { service(IdentityConflictTransport(), provider).fetchPostDetail(42) }
        }

        assertEquals(OfficialForumException.IdentityConflict, error)
        assertEquals(0, provider.refreshCount)
    }

    @Test
    fun confirmedIdentityConflictReclaimsThenRetriesOriginalRequest() = runBlocking {
        val provider = CoordinatedConflictCredentialProvider()
        val transport = RefreshingOfficialForumTransport(conflictCode = 10105)
        val pending = async { service(transport, provider).fetchPostDetail(42) }

        while (!provider.isWaitingForConfirmation) yield()
        assertEquals(listOf("expired"), transport.authorizationHeaders)
        provider.reclaimIdentityConflict()

        assertEquals(42, pending.await().id)
        assertEquals(listOf("expired", "refreshed"), transport.authorizationHeaders)
        assertEquals(1, provider.reclaimCount)
    }

    private fun service(
        transport: RisingStonesHttpClient,
        sessionProvider: RisingStonesSessionProvider? = null,
    ) = OfficialForumApiService(
        RisingStonesPublicApiClient(transport, listOf("https://rising.test")),
        sessionProvider,
    )
}

private object TestCredentialProvider : RisingStonesSessionProvider {
    override val capabilities = RisingStonesCapability.entries.toSet()
    override suspend fun currentAuthorizer() = authorizer("Authorization" to "host token")
}

private class RefreshingCredentialProvider : RisingStonesSessionProvider {
    var refreshCount = 0
        private set

    override val capabilities = RisingStonesCapability.entries.toSet()
    override suspend fun currentAuthorizer() = authorizer("Authorization" to "expired")
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshCount += 1
        return authorizer("Authorization" to "refreshed")
    }
}

private class CoordinatedConflictCredentialProvider :
    RisingStonesSessionProvider,
    OfficialForumIdentityConflictHandler {
    private var continuation:
        kotlinx.coroutines.CompletableDeferred<RisingStonesRequestAuthorizer?>? = null
    var reclaimCount = 0
        private set
    val isWaitingForConfirmation: Boolean get() = continuation != null

    override val capabilities = RisingStonesCapability.entries.toSet()
    override val identityConflictState: StateFlow<OfficialForumIdentityConflictState> =
        MutableStateFlow(OfficialForumIdentityConflictState())
    override suspend fun currentAuthorizer() = authorizer("Authorization" to "expired")
    override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer? {
        val pending = kotlinx.coroutines.CompletableDeferred<RisingStonesRequestAuthorizer?>()
        continuation = pending
        return pending.await()
    }
    override suspend fun reclaimIdentityConflict() {
        reclaimCount += 1
        continuation?.complete(authorizer("Authorization" to "refreshed"))
        continuation = null
    }
    override suspend fun cancelIdentityConflict() {
        continuation?.complete(null)
        continuation = null
    }
}

private fun authorizer(
    vararg headers: Pair<String, String>,
): RisingStonesRequestAuthorizer = RisingStonesRequestAuthorizer { _, sink: RisingStonesHeaderSink ->
    headers.forEach { (name, value) -> sink.set(name, value) }
}

private class RefreshingOfficialForumTransport(private val conflictCode: Int = 10002) : RisingStonesHttpClient {
    val authorizationHeaders = mutableListOf<String?>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        authorizationHeaders += request.headers["Authorization"]
        val body = if (request.headers["Authorization"] == "expired") {
            """{"code":$conflictCode,"msg":"登录失效"}"""
        } else {
            DETAIL
        }
        return RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
    }
}

private class IdentityConflictTransport : RisingStonesHttpClient {
    override suspend fun execute(request: RisingStonesHttpRequest) = RisingStonesHttpResponse(
        200,
        emptyMap(),
        """{"code":10105,"msg":"账号在其他设备登录"}""".encodeToByteArray(),
    )
}

private class OfficialForumTransport(
    private val failBusiness: Boolean = false,
    private val detailBody: String = DETAIL,
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        if (failBusiness) return response("""{"code":10001,"msg":"denied"}""")
        val path = request.url.toHttpUrl().encodedPath
        val body = when {
            path.endsWith("/partList") -> PARTS
            path.endsWith("/postsList") || path.endsWith("/search") -> POSTS
            path.endsWith("/postsDetail") -> detailBody
            path.endsWith("/postsCommentDetail") || path.endsWith("/postsSubCommentDetail") -> COMMENTS
            path.endsWith("/like") -> """{"code":10000,"data":1}"""
            path.endsWith("/star") -> """{"code":10000,"data":-1}"""
            path.endsWith("/comment") -> """{"code":10000,"data":[91]}"""
            path.endsWith("/deleteComment") -> """{"code":10000,"msg":"操作成功","data":null}"""
            path.endsWith("/vote") -> """{"code":10000,"data":{"vote_total_user":"7","vote_details":[{"option_id":"2","total_vote_num":5}]}}"""
            else -> error("Unexpected request ${request.method} ${request.url}")
        }
        return response(body)
    }

    private fun response(body: String) = RisingStonesHttpResponse(200, emptyMap(), body.encodeToByteArray())
}

private class ImageUploadTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        if (request.method == RisingStonesHttpMethod.Put) return RisingStonesHttpResponse(200, emptyMap(), ByteArray(0))
        val now = Instant.now().epochSecond
        return RisingStonesHttpResponse(
            200,
            emptyMap(),
            """{"code":10002,"data":{"credentials":{"sessionToken":"session-token","tmpSecretId":"AKIDTEST","tmpSecretKey":"test-secret"},"startTime":${now - 5},"expiredTime":${now + 60},"keyDir":"posts/test"}}""".encodeToByteArray(),
        )
    }
}

private fun RisingStonesHttpRequest.form(): Map<String, String> {
    assertNotNull(body)
    return requireNotNull(body).decodeToString().split('&').associate { item ->
        val parts = item.split('=', limit = 2)
        parts.first() to URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
    }
}

private val PARTS = """
    {"code":10000,"data":[
      {"id":"3","name":"Low","status":1,"weight":"2"},
      {"id":8,"name":"High","status":"1","weight":9},
      {"id":1,"name":"Hidden","status":0,"weight":99}
    ]}
""".trimIndent()

private val POSTS = """
    {"code":10000,"data":{"count":"41","rows":[{
      "posts_id":"42","uuid":"user","character_name":"Hero","area_name":"World",
      "group_name":"DC","avatar":"https://cdn.test/avatar.jpg","admin_tag":"1",
      "part_id":"8","part_name":"News","part_parent_name":"Official","title":"Topic",
      "cover_pic":"https://cdn.test/cover.jpg","content_pre":"<p>Summary &amp; details</p>",
      "created_at":"2026-07-21 12:00:00","last_comment_time":"2026-07-21 13:00:00",
      "comment_count":"3","like_count":4,"star_count":"5","read_count":6,
      "is_top":"1","is_refine":1
    }]}}
""".trimIndent()

private val DETAIL = """
    {"code":10000,"data":{"id":"42","title":"Topic","comment_count":3,
      "like_count":"4","star_count":5,"read_count":"6","is_like":"1","is_star":null,
      "is_top":1,"is_refine":"1","ip_location":"Shanghai",
      "created_at":"2026-07-21 12:00:00","content_info":{"content":"<p>Hello &amp; world</p><p><a href='https://example.test'>site</a> [emo2]</p><img src='https://cdn.test/a.jpg'><img src=\"https://cdn.test/b.jpg\">"},
      "user_info":{"uuid":"user","character_name":"Hero","area_name":"World","group_name":"DC","avatar":"https://cdn.test/avatar.jpg","admin_tag":1},
      "part_info":{"id":"8","name":"News","parent_part_name":"Official"},
      "vote_total_user":"7","vote_info":[
        {"id":"102","posts_id":42,"vote_type":2,"vote_title":"Choose","option_id":"2","option":"B","total_vote_num":"5","is_participant":"1","max_muti_choice":"2"},
        {"id":101,"posts_id":"42","vote_type":"2","vote_title":"Choose","option_id":1,"option":"A","total_vote_num":2,"is_participant":0,"max_muti_choice":2}
      ]}}
""".trimIndent()

private val CAMEL_DETAIL = """
    {"code":10000,"data":{"id":"8","title":"Agreement","comment_count":0,
      "created_at":"2026-07-27 12:00:00",
      "contentInfo":{"content":"<p>Current response</p>"},
      "userInfo":{"uuid":"helper","character_name":"Forum helper","area_name":"World",
        "group_name":"DC","avatar":"https://cdn.test/helper.jpg","admin_tag":1},
      "partInfo":{"id":"9","name":"Moderation","parent_part_name":"Official"},
      "vote_total_user":"1","voteInfo":[
        {"id":"201","posts_id":"8","vote_type":"1","vote_title":"Current vote",
          "option_id":"1","option":"Yes","total_vote_num":"1","is_participant":"0"}
      ]}}
""".trimIndent()

private val COMMENTS = """
    {"code":10000,"data":{"count":"1","rows":[{
      "id":"9","children_count":"3","mask_content":"<p>Reply</p>",
      "comment_pic":"https://cdn.test/reply.jpg","uuid":"commenter",
      "character_name":"Commenter","area_name":"World","group_name":"DC",
      "created_at":"2026-07-21 14:00:00","like_count":"2","is_posts_author":"1",
      "is_mine":"1"
    }]}}
""".trimIndent()
