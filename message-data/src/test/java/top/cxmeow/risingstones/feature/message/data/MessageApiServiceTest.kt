package top.cxmeow.risingstones.feature.message.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.network.*

class MessageApiServiceTest {
    @Test fun authorMetadataUsesEachOfficialHeaderFieldWithoutAnotherMessageRead() = runTest {
        val transport = Transport("[]")
        val service: MessageAuthorService = service(transport)
        val row = """{"id":1,"character_name":"Same name","uuid":"recruit-or-mention","comment_uuid":"commenter","like_uuid":"liker","favorite_uuid":"collector","to_uuid":"reply-recipient","from_info":{"uuid":"content-author"},"like_from":9}"""
        val cases = listOf(
            MessageQuery(MessageCategory.System) to null,
            MessageQuery(MessageCategory.Mentions) to MessageAuthorTarget.Community("recruit-or-mention"),
            MessageQuery(MessageCategory.Comments) to MessageAuthorTarget.Community("commenter"),
            MessageQuery(MessageCategory.Comments, commentChannel = MessageCommentChannel.Sent) to MessageAuthorTarget.Self,
            MessageQuery(MessageCategory.Likes) to MessageAuthorTarget.Community("collector"),
            MessageQuery(MessageCategory.Recruitment) to MessageAuthorTarget.Community("recruit-or-mention"),
            MessageQuery(MessageCategory.Responses) to MessageAuthorTarget.Self,
        )
        for ((query, expected) in cases) {
            transport.data = when (query.category) {
                MessageCategory.System -> """{"data":[$row]}"""
                MessageCategory.Comments, MessageCategory.Likes -> "[$row]"
                else -> """{"rows":[$row]}"""
            }
            val before = transport.requests.size
            val result = service.readMessagesWithAuthors(query)
            assertEquals(expected, result.authorsByKey[result.page.items.single().key])
            assertEquals(before + 1, transport.requests.size)
        }
        transport.data = "[${row.replace("\"like_from\":9", "\"like_from\":8")}]"
        assertEquals(MessageAuthorTarget.Community("liker"), service.readMessagesWithAuthors(MessageQuery(MessageCategory.Likes)).authorsByKey.values.single())
        assertTrue(transport.requests.none { it.url.contains("userInfo") || it.url.contains("getTip") })
    }

    @Test fun unknownSenderNeverFallsBackToContentAuthorOrNumericRecordId() = runTest {
        val transport = Transport("[]")
        for (value in listOf("null", "123", "true", "\"   \"")) {
            transport.data = """[{"id":1,"comment_uuid":$value,"uuid":"wrong","to_uuid":"wrong","character_name":"Same name"}]"""
            assertTrue(service(transport).readMessagesWithAuthors(MessageQuery(MessageCategory.Comments)).authorsByKey.isEmpty())
        }
        transport.data = """[{"id":1,"like_from":9,"like_uuid":"wrong"}]"""
        assertTrue(service(transport).readMessagesWithAuthors(MessageQuery(MessageCategory.Likes)).authorsByKey.isEmpty())
    }

    @Test fun acceptedCodesReadCountsAndCategoriesWithoutRefreshingOnAuthenticationLookingMessages() = runTest {
        for (code in listOf(10000, 10002)) {
            val transport = Transport(Summary).apply { this.code = code; message = "未登录" }
            val provider = Provider()
            val service = MessageApiService(client(transport), provider)
            assertEquals(2, service.fetchUnreadSummary().mentions)
            for (category in MessageCategory.entries) {
                val row = """{"msg_id":"fixture","title":"Fixture"}"""
                transport.data = when (category) {
                    MessageCategory.System -> """{"data":[$row],"count":1}"""
                    MessageCategory.Mentions, MessageCategory.Recruitment, MessageCategory.Responses ->
                        """{"rows":[$row],"count":1}"""
                    MessageCategory.Comments, MessageCategory.Likes -> "[$row]"
                }
                assertEquals("Fixture", service.readMessages(MessageQuery(category)).items.single().title)
            }
            assertEquals(listOf("getTip", "getSysMsg", "atMyMsg", "commentMsg", "likeMyMsg", "myRecruit", "myRecruitResponse"),
                transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
            assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
            assertEquals(0, provider.refreshes)
        }
    }

    @Test fun alternateAcceptedCodePreservesEveryEmptyMessageShape() = runTest {
        val transport = Transport("{}").apply { code = 10002 }
        val provider = Provider()
        val service = MessageApiService(client(transport), provider)
        for (category in MessageCategory.entries) {
            transport.data = when (category) {
                MessageCategory.System -> """{"data":[],"count":"0"}"""
                MessageCategory.Mentions, MessageCategory.Recruitment, MessageCategory.Responses ->
                    """{"rows":[],"count":0}"""
                MessageCategory.Comments, MessageCategory.Likes -> "[]"
            }
            val page = service.readMessages(MessageQuery(category))
            assertTrue(page.items.isEmpty())
            assertFalse(page.hasMore)
        }
        assertEquals(MessageCategory.entries.size, transport.requests.size)
        assertEquals(0, provider.refreshes)
    }

    @Test fun alternateAcceptedCodeNeverTurnsMalformedPayloadsIntoEmptyMessages() = runTest {
        val transport = Transport("null").apply { code = 10002 }
        val provider = Provider()
        val service = MessageApiService(client(transport), provider)
        for (data in listOf("null", "{}", "1")) {
            transport.data = data
            for (category in MessageCategory.entries) {
                val previous = transport.requests.size
                try { service.readMessages(MessageQuery(category)); fail() }
                catch (_: MessageException.InvalidResponse) { }
                assertEquals(previous + 1, transport.requests.size)
            }
        }
        assertEquals(0, provider.refreshes)
    }

    @Test fun alternateAcceptedProbeCodeNeedsCompleteCountsAndNeverReadsMessageLists() = runTest {
        val transport = Transport(Summary).apply { code = 10002; message = "未登录" }
        val validator = RisingStonesMessageSessionValidator(
            RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10002,
                setOf(RisingStonesCapability.MessageRead, RisingStonesCapability.AccountRead)) }, client(transport))
        val validated = validator.validateSession(Authorizer)
        assertEquals(10002, validated.code)
        assertEquals(setOf(RisingStonesCapability.MessageRead, RisingStonesCapability.AccountRead), validated.capabilities)
        for (data in listOf("null", "[]", "{}", """{"sysNum":0}""")) {
            transport.data = data
            val previous = transport.requests.size
            assertEquals(setOf(RisingStonesCapability.AccountRead), validator.validateSession(Authorizer).capabilities)
            assertEquals(previous + 1, transport.requests.size)
        }
        transport.data = Summary
        transport.code = 10403
        assertEquals(setOf(RisingStonesCapability.AccountRead), validator.validateSession(Authorizer).capabilities)
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get &&
            it.url.toHttpUrl().encodedPath == "/api/home/sysMsg/getTip" && it.url.toHttpUrl().query == null })
    }

    @Test fun probeOnlyReadsCountsAndNeverAcknowledgesMessages() = runTest {
        val transport = Transport(Summary)
        val validator = RisingStonesMessageSessionValidator(
            RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000,
                setOf(RisingStonesCapability.MessageRead, RisingStonesCapability.AccountRead)) }, client(transport))
        assertTrue(RisingStonesCapability.MessageRead in validator.validateSession(Authorizer).capabilities)
        transport.data = "{}"
        assertEquals(setOf(RisingStonesCapability.AccountRead), validator.validateSession(Authorizer).capabilities)
        assertTrue(transport.requests.all { it.url.toHttpUrl().encodedPath == "/api/home/sysMsg/getTip" })
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get && it.url.toHttpUrl().query == null })
    }

    @Test fun mixedNumericUnreadCountsAreParsedWithoutGuessingMissingFields() = runTest {
        val transport = Transport(Summary)
        val summary = service(transport).fetchUnreadSummary()
        assertEquals(2, summary.mentions)
        assertEquals(3, summary.recruitmentByChannel[MessageRecruitmentChannel.Duty])
        transport.data = "{}"
        try { service(transport).fetchUnreadSummary(); fail() } catch (_: MessageException.InvalidResponse) { }
    }

    @Test fun systemAndMentionWrappersAndOfficialPageSizeArePreserved() = runTest {
        val transport = Transport("""{"count":"11","data":[{"msg_id":"notice","title":"Notice","content":"<p>Body</p>","link":"https://untrusted.test/","created_at":"2026-09-18 10:00:00"}]}""")
        val messages = service(transport)
        val system = messages.readMessages(MessageQuery(MessageCategory.System))
        assertTrue(system.hasMore)
        assertEquals("Notice", system.items.single().title)
        assertNull(system.items.single().officialLink)
        transport.data = """{"count":1,"rows":[{"from":4,"from_root_id":25,"posts_type":2,"content":"Mention","uuid":"fixture"}]}"""
        val mention = messages.readMessages(MessageQuery(MessageCategory.Mentions))
        assertEquals(MessageTarget(MessageTargetKind.Guide, 25), mention.items.single().target)
        assertTrue(transport.requests.all { it.url.toHttpUrl().queryParameter("limit") == "10" })
        assertTrue(transport.requests.all { it.url.toHttpUrl().queryParameter("channel") == null })
    }

    @Test fun sparseCommentsRemainPageableAndDoNotLoseChannel() = runTest {
        val transport = Transport("""[{"comment_from":"2","posts_id":"31","posts_comment_id":"9","mask_content":"Comment"}]""")
        val result = service(transport).readMessages(MessageQuery(MessageCategory.Comments, 2, MessageCommentChannel.Sent))
        assertTrue(result.hasMore)
        assertEquals(MessageTarget(MessageTargetKind.Post, 31), result.items.single().target)
        val url = transport.requests.single().url.toHttpUrl()
        assertEquals("2", url.queryParameter("page"))
        assertEquals("2", url.queryParameter("channel"))
        transport.data = "[]"
        assertFalse(service(transport).readMessages(MessageQuery(MessageCategory.Comments)).hasMore)
    }

    @Test fun likesAndRecruitmentSourcesKeepTheirOwnIdentifiers() = runTest {
        val transport = Transport("""[{"like_from":9,"glamour_id":42,"favorite_uuid":"fixture","title":"Outfit"}]""")
        val messages = service(transport)
        assertEquals(MessageTarget(MessageTargetKind.Glamour, 42),
            messages.readMessages(MessageQuery(MessageCategory.Likes)).items.single().target)
        transport.data = """{"count":"1","rows":[{"recruit_response_id":99,"recruit_ne_id":45,"title":"Recruit","contact_info_mask":"Fixture contact"}]}"""
        val result = messages.readMessages(MessageQuery(MessageCategory.Responses, recruitmentChannel = MessageRecruitmentChannel.Beginner))
        assertEquals(MessageTarget(MessageTargetKind.Recruitment, 45, MessageRecruitmentChannel.Beginner), result.items.single().target)
        assertEquals("1", transport.requests.last().url.toHttpUrl().queryParameter("channel"))
    }

    @Test fun categoryFailureDoesNotRefreshOrRevokeSessionAndDoesNotLeakServerMessage() = runTest {
        val transport = Transport("{}").apply { code = 10401 }
        val provider = Provider()
        try {
            MessageApiService(client(transport), provider).readMessages(MessageQuery(MessageCategory.Recruitment,
                recruitmentChannel = MessageRecruitmentChannel.Guild))
            fail()
        } catch (error: MessageException.Business) {
            assertEquals(10401, error.code)
            assertFalse(error.message.orEmpty().contains("private"))
        }
        assertEquals(0, provider.refreshes)
    }

    @Test fun authenticationRetriesOnceAndUnavailableCapabilitySendsNothing() = runTest {
        for (code in listOf(10001, 10403, 10105)) {
            val transport = Transport("{}").apply { this.code = code }
            val provider = Provider()
            try { MessageApiService(client(transport), provider).fetchUnreadSummary(); fail()
            } catch (_: MessageException.AuthenticationRequired) { }
            assertEquals(1, provider.refreshes)
            assertEquals(2, transport.requests.size)
            provider.capabilities = emptySet()
            try { MessageApiService(client(transport), provider).fetchUnreadSummary(); fail()
            } catch (_: MessageException.Unavailable) { }
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun cancellationAndMalformedSuccessfulPayloadAreNotEmptyMessages() = runTest {
        val transport = Transport("{}")
        try { service(transport).readMessages(MessageQuery(MessageCategory.System)); fail()
        } catch (_: MessageException.InvalidResponse) { }
        transport.cancel = true
        try { service(transport).fetchUnreadSummary(); fail() } catch (_: CancellationException) { }
    }

    private fun client(transport: Transport) = RisingStonesPublicApiClient(transport, listOf("https://rising.test"))
    private fun service(transport: Transport) = MessageApiService(client(transport), Provider())
}

private val Authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("X-Test-Authorization", "fixture") }
private class Provider : RisingStonesSessionProvider {
    override var capabilities = setOf(RisingStonesCapability.MessageRead)
    var refreshes = 0
    override suspend fun currentAuthorizer() = Authorizer
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return Authorizer }
}
private class Transport(var data: String) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var code = 10000
    var message = "private server content"
    var cancel = false
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), """{"code":$code,"msg":"$message","data":$data}""".encodeToByteArray())
    }
}
private val Summary = """{"sysNum":1,"atMsgNum":"2","commentMsgNum":0,"beLikedMsgNum":"0","recruitTip":3,"recruitNeTip":0,"recruitFbTip":"3","recruitGuildTip":0,"recruitOtherTip":0}"""
