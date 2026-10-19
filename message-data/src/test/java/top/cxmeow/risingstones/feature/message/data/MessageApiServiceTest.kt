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
    @Test fun responseDetailsPreserveFourChannelsAndPublisherInOneRead() = runTest {
        for (channel in MessageRecruitmentChannel.entries) {
            val transport = Transport("""{"rows":[{"recruit_response_id":1,"id":42,"status":1,
                "uuid":" publisher ","character_name":" Publisher ","area_name":"Area","group_name":"World",
                "avatar":"https://static.web.sdo.com/avatar.png","identity":2,"fb_type":"Raid","progress":"Phase 2","strategy":"Plan","fb_time":"Evenings",
                "guild_tag":"TAG","weekday_time":"Weekday","weekend_time":"Weekend","category_name":"Event",
                "styleInfo":[{"style":"Casual"}],"labelInfo":[{"name":"Friendly"}],"target_area_name":"Target","target_group_name":"Server",
                "end_time":"1720000000.5","response_num":"0"}],"count":1}""")
            val result = service(transport).readResponseDetails(MessageQuery(MessageCategory.Responses, recruitmentChannel=channel))
            assertEquals(1,transport.requests.size)
            val metadata = result.metadataByKey.getValue(result.response.page.items.single().key)
            assertEquals(MessageResponsePublisher("publisher","Publisher","Area World","https://static.web.sdo.com/avatar.png"),metadata.publisher)
            assertEquals("Target Server",metadata.targetArea);assertEquals(0,metadata.responseCount)
            assertEquals(java.time.Instant.ofEpochMilli(1720000000500),metadata.endsAt)
            when(channel) {
                MessageRecruitmentChannel.Beginner -> { assertEquals(MessageResponseIdentity.LookingForMentor,metadata.identity);assertEquals(listOf("Casual"),metadata.tags);assertNull(metadata.badge) }
                MessageRecruitmentChannel.Duty -> {assertEquals("Raid",metadata.badge);assertEquals(listOf("Phase 2","Plan","Evenings"),metadata.details)}
                MessageRecruitmentChannel.Guild -> {assertEquals("TAG",metadata.badge);assertEquals(listOf("Weekday / Weekend"),metadata.details)}
                MessageRecruitmentChannel.Other -> {assertEquals("Event",metadata.badge);assertTrue(metadata.details.isEmpty())}
            }
            if(channel!=MessageRecruitmentChannel.Beginner)assertEquals(listOf("Friendly"),metadata.tags)
        }
    }
    @Test fun responseMalformedPublisherLabelsAndMetadataDoNotInventValuesOrDropCard() = runTest {
        for (invalid in listOf("NaN","Infinity","-1","1e100")) {
            val transport=Transport("""{"rows":[{"recruit_response_id":1,"uuid":7,"character_name":"Unknown","avatar":"https://unreviewed.invalid/avatar.png",
                "end_time":"$invalid","response_num":"2147483648","target_area_name":"  ","styleInfo":{},"labelInfo":[null,7,{"name":9},{"name":"Good"},{"name":"Good"}]}],"count":1}""")
            val result=service(transport).readResponseDetails(MessageQuery(MessageCategory.Responses))
            val row=result.response.page.items.single();val meta=result.metadataByKey.getValue(row.key)
            assertNull(row.target);assertFalse(result.response.statusesByKey.getValue(row.key).isRecruiting)
            assertNull(meta.publisher);assertNull(meta.endsAt);assertNull(meta.responseCount);assertNull(meta.targetArea);assertEquals(listOf("Good"),meta.tags)
            assertNull(service(transport).readMessagesWithAuthors(MessageQuery(MessageCategory.Responses)).authorsByKey[row.key])
        }
    }
    @Test fun responseDuplicateKeysKeepFirstCardAndItsMatchingMetadata() = runTest {
        val transport=Transport("""{"rows":[{"recruit_response_id":1,"status":0,"uuid":"first","character_name":"First"},{"recruit_response_id":1,"status":1,"uuid":"second","character_name":"Second"}]}""")
        // UUID participates in the stable key; same official row identity must remain coherent.
        transport.data="""{"rows":[{"recruit_response_id":1,"status":0,"uuid":"same","character_name":"First"},{"recruit_response_id":1,"status":1,"uuid":"same","character_name":"Second"}]}"""
        val result=service(transport).readResponseDetails(MessageQuery(MessageCategory.Responses))
        assertEquals(1,result.response.page.items.size);val row=result.response.page.items.single()
        assertEquals("First",result.metadataByKey.getValue(row.key).publisher?.name);assertFalse(result.response.statusesByKey.getValue(row.key).isRecruiting)
    }

    @Test fun responseInvalidFieldsCannotInventDestinationsOrDates() = runTest {
        val transport = Transport("[]")
        val query = MessageQuery(MessageCategory.Responses, recruitmentChannel = MessageRecruitmentChannel.Beginner)
        for (date in listOf("NaN", "Infinity", "-1", "1e100", "9223372036854776")) {
            transport.data = """{"rows":[{"recruit_response_id":1,"recruit_ne_id":"2147483648","recruit_fb_id":99,"status":1,"created_at":"$date"}],"count":1}"""
            val row = service(transport).readResponseMessages(query).page.items.single()
            assertNull(row.target); assertNull(row.createdAt)
        }
        transport.data = """{"rows":[{"recruit_response_id":1,"id":42,"title":"Missing status"}],"count":1}"""
        val result = service(transport).readResponseMessages(query)
        assertFalse(result.statusesByKey.values.single().isRecruiting)
        assertNull(result.page.items.single().target)
        transport.data = """{"rows":[{"recruit_response_id":2,"id":42,"status":1,"end_time":"1"}],"count":1}"""
        assertEquals(42, service(transport).readResponseMessages(query).page.items.single().target?.id)
    }

    @Test fun responsesKeepEndedContactButNeverEndedDestinations() = runTest {
        for (channel in MessageRecruitmentChannel.entries) {
            val field = when (channel) {
                MessageRecruitmentChannel.Beginner -> "recruit_ne_id"
                MessageRecruitmentChannel.Duty -> "recruit_fb_id"
                MessageRecruitmentChannel.Guild -> "recruit_guild_id"
                MessageRecruitmentChannel.Other -> "recruit_other_id"
            }
            val transport = Transport("""{"rows":[
                {"recruit_response_id":1,"$field":42,"status":1,"title":"Synthetic active","detail_mask":"<b>Summary</b>","contact_info_mask":"  synthetic-contact\nsecond-line  ","created_at":"1720000000.5"},
                {"recruit_response_id":2,"$field":43,"recruit_status":0,"status":0,"title":"Synthetic ended","contact_info_mask":"ended-contact","created_at":"Infinity"},
                {"recruit_response_id":3,"$field":0,"recruit_status":1,"title":"Missing destination","contact_info_mask":"   "},
                {"recruit_response_id":4,"$field":44,"recruit_status":1,"status":0,"title":"OR rule"}
            ],"count":4}""")
            val result = service(transport).readResponseMessages(MessageQuery(MessageCategory.Responses, recruitmentChannel = channel))
            assertEquals(1, transport.requests.size)
            assertEquals(4, result.page.items.size)
            val (active, ended, invalid, alternate) = result.page.items
            assertEquals(MessageTarget(MessageTargetKind.Recruitment, 42, channel), active.target)
            assertEquals("synthetic-contact\nsecond-line", active.contactInformation)
            assertEquals(java.time.Instant.ofEpochMilli(1720000000500), active.createdAt)
            assertNull(ended.target)
            assertEquals("ended-contact", ended.contactInformation)
            assertNull(ended.createdAt)
            assertFalse(result.statusesByKey.getValue(ended.key).isRecruiting)
            assertNull(invalid.target)
            assertNull(invalid.contactInformation)
            assertTrue(result.statusesByKey.getValue(alternate.key).isRecruiting)
        }
    }

    @Test fun systemMetadataPreservesKindsDefaultsReadAndFiltersInvisibleRows() = runTest {
        val transport = Transport("""{"data":[
            {"msg_id":1,"type":5,"is_read":0,"title":"Announcement","content":"<b>literal</b>","created_at":"2026-10-18T00:00:00Z","updated_at":"2026-10-19T00:00:00Z","pic_url":"https://static.web.sdo.com/first.png,https://static.web.sdo.com/second.png","cover_pic":"https://static.web.sdo.com/wrong.png"},
            {"msg_id":"2","type":"3","is_read":"1","title":"Badge"},
            {"msg_id":3,"type":99,"title":"Other"},
            {"msg_id":4,"title":"  ","content":"  "},{"title":"Missing identifier"}],"count":5}""")
        val page = service(transport).readMessages(MessageQuery(MessageCategory.System))
        assertEquals(3, page.items.size)
        assertEquals(MessageSystemMetadata(MessageSystemKind.Announcement, false), page.items[0].system)
        assertEquals(MessageSystemMetadata(MessageSystemKind.Badge, true), page.items[1].system)
        assertEquals(MessageSystemMetadata(MessageSystemKind.Other, true), page.items[2].system)
        assertEquals("<b>literal</b>", page.items[0].contentHtml)
        assertEquals(java.time.Instant.parse("2026-10-18T00:00:00Z"), page.items[0].createdAt)
        assertEquals(2, page.items[0].imageUrls.size)
        assertFalse(page.hasMore)
    }

    @Test fun commentMetadataSeparatesBodyQuoteSubjectAndReplyWithoutExtraRequests() = runTest {
        val base = "https://static.web.sdo.com/"
        val transport = Transport("""[{"comment_from":2,"posts_id":42,"posts_comment_id":7,"root_parent":0,
            "comment_uuid":"fixture-author","character_name":"Fixture","from_avatar":"${base}avatar.png",
            "mask_content":"Comment","comment_pic":"${base}comment.png","cover_pic":"${base}cover.png",
            "dynamic_content":"Wrong quote","parent_character_name":"Parent","parent_comment_pic":"${base}parent.png",
            "title":"Subject","part_name":"Forum","posts_character_name":"Publisher","to_cname":"Recipient"}]""")
        val service = service(transport)
        val message = service.readMessages(MessageQuery(MessageCategory.Comments)).items.single()
        assertEquals(listOf("${base}comment.png"), message.imageUrls)
        assertEquals("", message.contextHtml)
        val metadata = requireNotNull(message.comment)
        assertEquals("fixture-author", metadata.commenterUuid)
        assertEquals("${base}avatar.png", metadata.avatarUrl)
        assertEquals(MessageCommentQuote("Parent", "", true), metadata.quote)
        assertEquals(MessageCommentSubject("Subject", "Forum · Publisher", "${base}cover.png"), metadata.subject)
        assertEquals(MessageCommentReply(MessageTarget(MessageTargetKind.Post, 42), 7, 7), metadata.reply)
        assertEquals("Recipient", metadata.replyToName)
        val sent = service.readMessages(MessageQuery(MessageCategory.Comments, commentChannel = MessageCommentChannel.Sent)).items.single().comment!!
        assertNull(sent.commenterUuid)
        assertNull(sent.avatarUrl)
        assertEquals(2, transport.requests.size)
    }

    @Test fun dynamicSubjectDoesNotBecomeAQuoteAndKeepsRootReply() = runTest {
        val transport = Transport("""[{"comment_from":1,"dynamic_id":43,"dynamic_comment_id":8,"root_parent":6,
            "dynamic_content":"Dynamic subject","dynamic_character_name":"Publisher","comment_uuid":123,
            "to_uuid":true,"pic_url":"https://static.web.sdo.com/subject.png"}]""")
        val message = service(transport).readMessages(MessageQuery(MessageCategory.Comments)).items.single()
        assertEquals("", message.contextHtml)
        assertTrue(message.imageUrls.isEmpty())
        val metadata = requireNotNull(message.comment)
        assertNull(metadata.quote)
        assertNull(metadata.commenterUuid)
        assertNull(metadata.replyToUuid)
        assertEquals("Dynamic subject", metadata.subject.titleHtml)
        assertEquals(MessageCommentReply(MessageTarget(MessageTargetKind.Dynamic, 43), 8, 6), metadata.reply)
    }

    @Test fun malformedOrUnsupportedCommentsCannotCreateReplyDestinations() = runTest {
        val transport = Transport("[]")
        for ((source, id, root) in listOf(Triple(2, "0", "0"), Triple(2, "2147483648", "0"),
            Triple(2, "7", "-1"), Triple(2, "7", "2147483648"), Triple(4, "7", "0"), Triple(5, "7", "0"))) {
            transport.data = """[{"comment_from":$source,"posts_id":42,"posts_comment_id":$id,"root_parent":$root,
                "from_avatar":"https://example.invalid/avatar.png","cover_pic":"https://example.invalid/cover.png"}]"""
            val metadata = service(transport).readMessages(MessageQuery(MessageCategory.Comments)).items.single().comment!!
            assertNull(metadata.reply)
            assertNull(metadata.avatarUrl)
            assertNull(metadata.subject.coverUrl)
        }
    }

    @Test fun authorMetadataUsesEachOfficialHeaderFieldWithoutAnotherMessageRead() = runTest {
        val transport = Transport("[]")
        val service: MessageAuthorService = service(transport)
        val row = """{"id":1,"msg_id":1,"title":"Fixture","character_name":"Same name","uuid":"recruit-or-mention","comment_uuid":"commenter","like_uuid":"liker","favorite_uuid":"collector","to_uuid":"reply-recipient","from_info":{"uuid":"content-author"},"like_from":9}"""
        val cases = listOf(
            MessageQuery(MessageCategory.System) to null,
            MessageQuery(MessageCategory.Mentions) to MessageAuthorTarget.Community("recruit-or-mention"),
            MessageQuery(MessageCategory.Comments) to MessageAuthorTarget.Community("commenter"),
            MessageQuery(MessageCategory.Comments, commentChannel = MessageCommentChannel.Sent) to MessageAuthorTarget.Self,
            MessageQuery(MessageCategory.Likes) to MessageAuthorTarget.Community("collector"),
            MessageQuery(MessageCategory.Recruitment) to MessageAuthorTarget.Community("recruit-or-mention"),
            MessageQuery(MessageCategory.Responses) to MessageAuthorTarget.Community("recruit-or-mention"),
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
        transport.data = """{"count":"1","rows":[{"recruit_response_id":99,"recruit_ne_id":45,"status":1,"title":"Recruit","contact_info_mask":"Fixture contact"}]}"""
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
