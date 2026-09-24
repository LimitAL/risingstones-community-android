package top.cxmeow.risingstones.feature.recruitment.data

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.*

class RecruitmentNativeContractsTest {
    @Test fun authorRichCommunityReadsReuseTheirOriginalPayloadAndEligibility() = runBlocking {
        val transport = NativeRecruitmentTransport().apply {
            responses["recruitNeList"] = envelope("""{"count":2,"rows":[{"id":11,"character_name":"Same","uuid":"publisher"},{"id":12,"character_name":"Same","uuid":12}]}""")
            responses["getNeDetail"] = envelope("""{"id":11,"character_name":"Same","uuid":"publisher"}""")
        }
        val service: RecruitmentAuthorService = transport.service()
        val list = service.fetchCommunityRecruitmentsWithAuthors(CommunityRecruitmentQuery(CommunityRecruitmentKind.Beginner))
        assertTrue(list.authorUuids.isEmpty())
        assertEquals(2, list.page.items.size)
        val detail = service.fetchCommunityDetailWithAuthor(11, CommunityRecruitmentKind.Beginner)
        assertEquals("publisher", detail.authorUuid)
        assertNull(detail.interaction.isCurrentUserAuthor)
        assertEquals(listOf("recruitNeList", "getNeDetail"), transport.paths())
        val writerSession = writer()
        val writerService = transport.service(writerSession)
        val known = writerService.fetchCommunityDetailWithAuthor(11, CommunityRecruitmentKind.Beginner)
        assertEquals(false, known.interaction.isCurrentUserAuthor)
        assertEquals("publisher", known.authorUuid)
        assertEquals(listOf("getNeDetail", "getCharacterBindInfo"), transport.paths().takeLast(2))
        assertEquals(RisingStonesCapability.AccountRead, writerSession.contexts.last().capability)
    }

    @Test fun reviewAndReplyAuthorsUseUuidRatherThanReplyTargetAndKeepExactPagination() = runBlocking {
        val transport = NativeRecruitmentTransport().apply {
            responses["recruitRpCommentDetail"] = envelope("""{"rows":[{"id":"root","character_name":"Same","uuid":"root-author","mask_content":"Review"}]}""")
            responses["recruitRpSubCommentDetail"] = envelope("""{"rows":[{"id":"child","character_name":"Same","uuid":"child-author","to_uuid":"wrong","mask_content":"Reply"}]}""")
        }
        val service = transport.service()
        val reviews = service.fetchRolePlayReviewsWithAuthors(RolePlayRecruitmentReviewQuery(14, 2, 1, RolePlayRecruitmentReviewOrder.ScoreDescending))
        assertEquals(mapOf("root" to "root-author"), reviews.authorUuids)
        assertTrue(reviews.previewAuthorUuids.isEmpty())
        assertEquals("scoreDesc", transport.requests.last().url.toHttpUrl().queryParameter("order"))
        assertEquals(2, reviews.page.page)
        assertTrue(reviews.page.hasMore)
        val replies = service.fetchRolePlaySubcommentsWithAuthors("root", 3, 1)
        assertEquals(mapOf("child" to "child-author"), replies.authorUuids)
        assertEquals("earliest", transport.requests.last().url.toHttpUrl().queryParameter("order"))
        assertEquals("root", transport.requests.last().url.toHttpUrl().queryParameter("root_parent"))
        assertEquals(3, replies.page.page)
        assertEquals(2, transport.requests.size)
    }

    @Test fun malformedUnknownAndDuplicateAuthorsCannotAttachToAnotherDisplayedRow() = runBlocking {
        val transport = NativeRecruitmentTransport().apply {
            responses["recruitRpCommentDetail"] = envelope("""{"rows":[
                {"id":"a","uuid":"invalid-row"},
                {"id":"a","character_name":"Same","mask_content":"First"},
                {"id":"a","character_name":"Same","mask_content":"Second","uuid":"wrong-duplicate"},
                {"id":"b","character_name":"Same","mask_content":"Other","uuid":"valid-author"},
                {"id":"c","character_name":"Same","mask_content":"Numeric","uuid":123},
                {"id":"d","character_name":"Same","mask_content":"Blank","uuid":"  "}]}""")
        }
        val rich = transport.service().fetchRolePlayReviewsWithAuthors(RolePlayRecruitmentReviewQuery(14))
        assertEquals(mapOf("b" to "valid-author"), rich.authorUuids)
        assertEquals(5, rich.page.items.size)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun fullDutyFilterUsesExactTeamAreaLabelAndMultiplePositionParameters() = runBlocking {
        val transport = NativeRecruitmentTransport()
        val service: RecruitmentBrowsingService = transport.service()
        service.fetchDutyRecruitments(DutyRecruitmentBrowseQuery(
            list = DutyRecruitmentListQuery(2, 10, "A & B", "诛灭战"),
            positions = listOf(DutyRecruitmentPosition.MainTank, DutyRecruitmentPosition.Healer1),
            teamComposition = "团队", targetAreaId = "-1", labelIds = listOf("2", "8"), allianceTeamKey = "B",
        ))
        val request = transport.requests.single()
        val url = request.url.toHttpUrl()
        assertEquals(RisingStonesHttpMethod.Get, request.method)
        assertEquals("A & B", url.queryParameter("fb_name"))
        assertEquals("诛灭战", url.queryParameter("fb_type"))
        assertEquals("团队", url.queryParameter("team_composition"))
        assertEquals("-1", url.queryParameter("target_area_id"))
        assertEquals("2,8", url.queryParameter("label"))
        assertEquals("MT,H1", url.queryParameter("position"))
        assertEquals("MT,H1", url.queryParameter("son_team_position"))
        assertEquals("B", url.queryParameter("son_team_key"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("10", url.queryParameter("limit"))
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun ordinaryAndLegacyDutyFiltersDoNotLeakAllianceOrAllAreaSentinels() = runBlocking {
        val transport = NativeRecruitmentTransport()
        val service = transport.service()
        service.fetchDutyRecruitments(DutyRecruitmentBrowseQuery(
            positions = listOf(DutyRecruitmentPosition.Tank), teamComposition = "轻锐小队",
            targetAreaId = "0", allianceTeamKey = "C",
        ))
        val light = transport.requests.single().url.toHttpUrl()
        assertNull(light.queryParameter("son_team_key"))
        assertNull(light.queryParameter("son_team_position"))
        assertNull(light.queryParameter("target_area_id"))
        service.fetchDutyRecruitments(DutyRecruitmentListQuery(page = 0, limit = 0, position = DutyRecruitmentPosition.Healer2))
        val legacy = transport.requests.last().url.toHttpUrl()
        assertEquals("H2", legacy.queryParameter("position"))
        assertNull(legacy.queryParameter("team_composition"))
        assertNull(legacy.queryParameter("label"))
        assertEquals("1", legacy.queryParameter("page"))
        assertEquals("1", legacy.queryParameter("limit"))
    }

    @Test
    fun dutyFilterCatalogReadsOnlyTheFourOfficialDirectories() = runBlocking {
        val transport = NativeRecruitmentTransport()
        val catalog = transport.service().fetchDutyFilterCatalog()
        assertEquals(listOf("getJobConfigList", "getFbConfigList", "fbLabelList", "getAreaAndGroupList"), transport.paths())
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get && it.body == null })
        assertEquals("Fixture job", catalog.catalogs.jobs.getValue("19").name)
        assertEquals("团队", catalog.catalogs.duties.single().teamComposition)
        assertEquals(DutyRecruitmentLabel("2", "Fixture label", 9), catalog.labels.single())
        assertEquals("Fixture server", catalog.areas.single().servers.single().name)
    }

    @Test
    fun reviewOrderIsExplicitWhileLegacyCallsKeepTheirPreviousOrder() = runBlocking {
        val transport = NativeRecruitmentTransport()
        val service = transport.service()
        for (order in RolePlayRecruitmentReviewOrder.entries) {
            service.fetchRolePlayReviews(RolePlayRecruitmentReviewQuery(42, 2, 10, order))
            val url = transport.requests.last().url.toHttpUrl()
            assertEquals(order.wireValue, url.queryParameter("order"))
            assertEquals("42", url.queryParameter("id"))
            assertEquals("2", url.queryParameter("page"))
        }
        service.fetchRolePlayReviews(42, 1, 10)
        assertEquals("hottest", transport.requests.last().url.toHttpUrl().queryParameter("order"))
    }

    @Test
    fun skippedMalformedRowsDoNotEndReviewOrSubcommentPaginationEarly() = runBlocking {
        val transport = NativeRecruitmentTransport().apply {
            responses["recruitRpCommentDetail"] = envelope("""{"rows":[{"id":1,"character_name":"Fixture","mask_content":"<p>Review</p>"},{}]}""")
            responses["recruitRpSubCommentDetail"] = responses.getValue("recruitRpCommentDetail")
        }
        val reviews = transport.service().fetchRolePlayReviews(RolePlayRecruitmentReviewQuery(42, limit = 2))
        assertEquals(1, reviews.items.size)
        assertTrue(reviews.hasMore)
        val children = transport.service().fetchRolePlaySubcomments("1", 3, 2)
        assertEquals(1, children.items.size)
        assertTrue(children.hasMore)
        val url = transport.requests.last().url.toHttpUrl()
        assertEquals("1", url.queryParameter("root_parent"))
        assertEquals("earliest", url.queryParameter("order"))
        assertEquals("3", url.queryParameter("page"))
    }

    @Test
    fun missingContainersAreErrorsWhileExplicitEmptyArraysRemainEmptyResults() = runBlocking {
        val actions: Map<String, suspend (DutyRecruitmentApiService) -> Unit> = mapOf(
            "getJobConfigList" to { it.fetchCatalogs() },
            "getFbConfigList" to { it.fetchCatalogs() },
            "fbLabelList" to { it.fetchDutyFilterCatalog() },
            "getAreaAndGroupList" to { it.fetchDutyFilterCatalog() },
            "styleConfigList" to { it.fetchCommunityFilterCatalog(CommunityRecruitmentKind.Beginner) },
            "recruitFbList" to { it.fetchDutyRecruitments(DutyRecruitmentListQuery()) },
            "recruitRpCommentDetail" to { it.fetchRolePlayReviews(42, 1, 10) },
            "recruitRpSubCommentDetail" to { it.fetchRolePlaySubcomments("1", 1, 10) },
            "getRecruitRpScoreListByRpId" to { it.fetchRolePlayRating(42) },
        )
        for ((path, action) in actions) {
            val broken = NativeRecruitmentTransport().apply { responses[path] = """{"code":10002,"data":null}""" }
            assertThrows(DutyRecruitmentException.MissingPayload::class.java) { runBlocking { action(broken.service()) } }
        }
        for (rows in listOf("{}", "{\"rows\":null}", "{\"rows\":{}}")) {
            val broken = NativeRecruitmentTransport().apply { responses["recruitRpCommentDetail"] = envelope(rows) }
            assertThrows(DutyRecruitmentException.MissingPayload::class.java) {
                runBlocking { broken.service().fetchRolePlayReviews(42, 1, 10) }
            }
        }
        val empty = NativeRecruitmentTransport()
        assertFalse(empty.service().fetchRolePlayReviews(42, 1, 10).hasMore)
        assertFalse(empty.service().fetchRolePlaySubcomments("1", 1, 10).hasMore)
        empty.responses["getRecruitRpScoreListByRpId"] = envelope("[]")
        assertEquals(List(5) { 0 }, empty.service().fetchRolePlayRating(42).counts)
    }

    @Test
    fun responseContactsAreOptionalInsideTheRequiredServerDataObject() = runBlocking {
        val contact = "QQ & + 中文=?"
        val missing = NativeRecruitmentTransport().apply {
            responses["responseRecruitFb"] = envelope("null", 10002)
        }
        assertThrows(DutyRecruitmentException.MissingPayload::class.java) {
            runBlocking { missing.service(writer()).respondToDutyRecruitment(42, contact) }
        }
        for (payload in listOf("{}", "{\"recruit_contact_info\":null}", "{\"recruit_contact_info\":7}")) {
            val transport = NativeRecruitmentTransport().apply {
                responses["responseRecruitFb"] = envelope(payload, 10002)
                responses["responseNoviceEntertain"] = envelope(payload, 10002)
            }
            val session = NativeRecruitmentSession(setOf(RisingStonesCapability.RecruitmentWrite))
            val service = transport.service(session)
            assertNull(service.respondToDutyRecruitment(42, contact))
            assertNull(service.respondToBeginnerRecruitment(11, contact))
            assertEquals(2, transport.requests.size)
            assertEquals(0, session.refreshes)
            for (request in transport.requests) {
                assertEquals(RisingStonesHttpMethod.Post, request.method)
                assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
                assertEquals(contact, request.form()["contact_info"])
            }
        }
        for (key in listOf("recruit_contact_info", "recruitContactInfo")) {
            val transport = NativeRecruitmentTransport().apply { responses["responseRecruitFb"] = envelope("""{"$key":"Server contact"}""") }
            assertEquals("Server contact", transport.service(writer()).respondToDutyRecruitment(42, contact))
        }
    }

    @Test
    fun reviewLikeSendsTypeTwoAndReturnsTheSignedDeltaWithoutReplaying() = runBlocking {
        for (delta in listOf(1, -1)) {
            val transport = NativeRecruitmentTransport().apply { responses["rpCommentlike"] = envelope("\"$delta\"", 10002) }
            val session = writer()
            assertEquals(delta, transport.service(session).likeRolePlayReview("9"))
            val request = transport.requests.single()
            assertEquals(RisingStonesHttpMethod.Post, request.method)
            assertEquals(mapOf("id" to "9", "type" to "2", "tempsuid" to "fixture-session"), request.form())
            assertEquals(0, session.refreshes)
        }
    }

    @Test
    fun optionalWriteCapabilityTracksRevocationWithoutEquatingReadIdentityWithWritePermission() {
        val transport = NativeRecruitmentTransport()
        val session = NativeRecruitmentSession(setOf(RisingStonesCapability.RecruitmentAuthenticated))
        val service: RecruitmentInteractionService = transport.service(session)
        assertTrue(service.hasCommunityIdentity)
        assertFalse(service.canPerformAuthenticatedWrites)
        assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
            runBlocking { service.respondToDutyRecruitment(42, "Fixture") }
        }
        assertTrue(transport.requests.isEmpty())
        session.capabilities = setOf(RisingStonesCapability.RecruitmentWrite)
        assertTrue(service.canPerformAuthenticatedWrites)
        assertFalse(service.hasCommunityIdentity)
        session.capabilities = emptySet()
        assertFalse(service.canPerformAuthenticatedWrites)
    }

    @Test fun retainedWriteCapabilityCannotReopenRevokedGuildReads() {
        val transport = NativeRecruitmentTransport()
        val session = NativeRecruitmentSession(setOf(
            RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead,
            RisingStonesCapability.RecruitmentWrite, RisingStonesCapability.RecruitmentAuthenticated,
        ))
        val service = transport.service(session)
        assertTrue(service.hasCommunityIdentity)
        session.capabilities = session.capabilities - RisingStonesCapability.RecruitmentAuthenticated
        assertTrue(service.canPerformAuthenticatedWrites)
        assertFalse(service.hasCommunityIdentity)
        assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
            runBlocking { service.fetchCommunityRecruitmentDetail(42, CommunityRecruitmentKind.Guild) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun authorEligibilityComparesOnlyVerifiedCharacterIdentityAndKeepsLegacyReadsSingleRequest() = runBlocking {
        for (current in listOf("fixture-author", "fixture-reader")) {
            val transport = NativeRecruitmentTransport().apply { responses["getCharacterBindInfo"] = envelope("""{"uuid":"$current","character_name":"Fixture"}""") }
            val writerSession = writer()
            val service = transport.service(writerSession)
            val duty = service.fetchDutyInteractionDetail(42)
            assertEquals(current == "fixture-author", duty.isCurrentUserAuthor)
            val beginner = service.fetchCommunityInteractionDetail(11, CommunityRecruitmentKind.Beginner)
            assertEquals(current == "fixture-author", beginner.isCurrentUserAuthor)
            assertEquals(listOf("getRecruitFbDetail", "getCharacterBindInfo", "getNeDetail", "getCharacterBindInfo"), transport.paths())
            for (request in transport.requests.filter { it.url.toHttpUrl().pathSegments.last() == "getCharacterBindInfo" }) {
                assertEquals(RisingStonesHttpMethod.Get, request.method)
                assertEquals("1", request.url.toHttpUrl().queryParameter("platform"))
                assertEquals("fixture-agent", request.headers["User-Agent"])
            }
            assertTrue(writerSession.contexts.filter { it.path.endsWith("getCharacterBindInfo") }.all {
                it.capability == RisingStonesCapability.AccountRead
            })
            transport.requests.clear()
            assertEquals(duty.detail, service.fetchDutyRecruitmentDetail(42))
            assertEquals(beginner.detail, service.fetchCommunityRecruitmentDetail(11, CommunityRecruitmentKind.Beginner))
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun firstWriteEligibilityUsesAccountReadForAuthorComparisonWithoutOpeningGuildReads() = runBlocking {
        val transport = NativeRecruitmentTransport().apply {
            responses["getCharacterBindInfo"] = envelope("""{"uuid":"fixture-author","character_name":"Fixture"}""")
        }
        val session = FirstWriteEligibleSession()
        val service = transport.service(session)

        assertTrue(service.canAttemptAuthenticatedWrites)
        assertFalse(service.canPerformAuthenticatedWrites)
        assertFalse(service.hasCommunityIdentity)
        assertEquals(true, service.fetchDutyInteractionDetail(42).isCurrentUserAuthor)
        assertEquals(listOf("getRecruitFbDetail", "getCharacterBindInfo"), transport.paths())
        assertEquals(RisingStonesCapability.AccountRead, session.contexts.single().capability)
        assertEquals(0, session.attempts)

        assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
            runBlocking {
                service.fetchCommunityRecruitments(CommunityRecruitmentQuery(CommunityRecruitmentKind.Guild))
            }
        }
        assertEquals(2, transport.requests.size)
    }

    @Test fun verifiedLegacyWriteStillAuthorizesItsExistingAuthorComparisonWithoutAccountRead() = runBlocking {
        val transport = NativeRecruitmentTransport().apply {
            responses["getCharacterBindInfo"] = envelope("""{"uuid":"fixture-reader","character_name":"Fixture"}""")
        }
        val session = NativeRecruitmentSession(setOf(RisingStonesCapability.RecruitmentWrite))
        val service = transport.service(session)

        assertEquals(false, service.fetchDutyInteractionDetail(42).isCurrentUserAuthor)
        assertEquals(listOf("getRecruitFbDetail", "getCharacterBindInfo"), transport.paths())
        assertEquals(RisingStonesCapability.RecruitmentWrite, session.contexts.last().capability)
        assertTrue(service.canPerformAuthenticatedWrites)
    }

    @Test
    fun unknownIdentityOrMissingWriteCapabilityPreservesThePublicDetailWithoutGrantingEligibility() = runBlocking {
        for (payload in listOf("null", "{}", "{\"uuid\":\"fixture-author\"}", "{\"uuid\":\"\",\"character_name\":\"Fixture\"}", "{\"uuid\":\"fixture-author\",\"character_name\":\"\"}")) {
            val transport = NativeRecruitmentTransport().apply { responses["getCharacterBindInfo"] = envelope(payload) }
            val session = writer()
            val result = transport.service(session).fetchDutyInteractionDetail(42)
            assertEquals(42, result.detail.summary.id)
            assertNull(result.isCurrentUserAuthor)
            assertEquals(setOf(RisingStonesCapability.RecruitmentWrite, RisingStonesCapability.AccountRead), session.capabilities)
        }
        val anonymous = NativeRecruitmentTransport()
        assertNull(anonymous.service().fetchDutyInteractionDetail(42).isCurrentUserAuthor)
        assertEquals(listOf("getRecruitFbDetail"), anonymous.paths())
        val missingAuthor = NativeRecruitmentTransport().apply { responses["getRecruitFbDetail"] = envelope("{\"id\":42}") }
        assertNull(missingAuthor.service(writer()).fetchDutyInteractionDetail(42).isCurrentUserAuthor)
        assertEquals(listOf("getRecruitFbDetail"), missingAuthor.paths())
    }

    @Test
    fun ordinaryIdentityFailureIsUnknownButExpiryAndCancellationStillPropagate() = runBlocking {
        val transport = NativeRecruitmentTransport().apply { failures["getCharacterBindInfo"] = IllegalStateException("fixture transport") }
        assertNull(transport.service(writer()).fetchDutyInteractionDetail(42).isCurrentUserAuthor)
        transport.failures.clear()
        transport.responses["getCharacterBindInfo"] = """{"code":401,"msg":"登录失效"}"""
        assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
            runBlocking { transport.service(writer()).fetchDutyInteractionDetail(42) }
        }
        val cancellation = CancellationException("fixture cancelled")
        transport.failures["getCharacterBindInfo"] = cancellation
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { transport.service(writer()).fetchDutyInteractionDetail(42) }
        }
        assertSame(cancellation, error)
    }

    @Test
    fun finalJsonAndHttpExpiryUseDomainAuthenticationWithoutAdditionalRefreshOrReplay() {
        val operations: List<suspend (DutyRecruitmentApiService) -> Unit> = listOf(
            { it.fetchDutyRecruitmentDetail(42) }, { it.respondToDutyRecruitment(42, "Fixture") },
            { it.respondToBeginnerRecruitment(11, "Fixture") }, { it.likeRolePlayReview("9") },
        )
        for (http in listOf(false, true)) for (canRefresh in listOf(false, true)) for (operation in operations) {
            val transport = NativeRecruitmentTransport().apply {
                if (http) allFailure = RisingStonesHttpException.ServerResponse(403, byteArrayOf())
                else allResponse = """{"code":401,"msg":"登录失效"}"""
            }
            val session = writer().apply { refreshAvailable = canRefresh }
            assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
                runBlocking { operation(transport.service(session)) }
            }
            assertEquals(1, session.refreshes)
            val isRead = operation === operations.first()
            assertEquals(if (isRead && canRefresh) 2 else 1, transport.requests.size)
        }
    }

    @Test
    fun httpThenJsonExpiryCannotRefreshAndSubmitAThirdTime() {
        for (path in listOf("responseRecruitFb", "responseNoviceEntertain", "rpCommentlike")) {
            val requests = mutableListOf<RisingStonesHttpRequest>()
            val transport = object : RisingStonesHttpClient {
                override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
                    requests += request
                    if (requests.size == 1) throw RisingStonesHttpException.ServerResponse(401, byteArrayOf())
                    return RisingStonesHttpResponse(200, emptyMap(), """{"code":401,"msg":"登录失效"}""".encodeToByteArray())
                }
            }
            val session = writer().apply { refreshAvailable = true }
            val service = DutyRecruitmentApiService(RisingStonesPublicApiClient(transport), session)
            assertThrows(DutyRecruitmentException.AuthenticationRequired::class.java) {
                runBlocking {
                    when (path) {
                        "responseRecruitFb" -> service.respondToDutyRecruitment(42, "Fixture")
                        "responseNoviceEntertain" -> service.respondToBeginnerRecruitment(11, "Fixture")
                        else -> service.likeRolePlayReview("9")
                    }
                }
            }
            assertEquals(1, session.refreshes)
            assertEquals(1, requests.size)
        }
    }

    private fun writer() = NativeRecruitmentSession(
        setOf(RisingStonesCapability.RecruitmentWrite, RisingStonesCapability.AccountRead),
    )
}

private fun envelope(data: String, code: Int = 10000) = """{"code":$code,"msg":"${if (code == 10002) "未登录 session expired" else "Fixture"}","data":$data}"""

private class NativeRecruitmentSession(override var capabilities: Set<RisingStonesCapability> = emptySet()) : RisingStonesSessionProvider {
    var refreshes = 0
    var refreshAvailable = false
    val contexts = mutableListOf<RisingStonesRequestContext>()
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, sink ->
        contexts += context
        sink.set("User-Agent", "fixture-agent")
    }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer? { refreshes++; return if (refreshAvailable) currentAuthorizer() else null }
}

private class FirstWriteEligibleSession : RisingStonesSessionProvider, RisingStonesExplicitCapabilityProvider {
    override val capabilities = setOf(RisingStonesCapability.AccountRead)
    val contexts = mutableListOf<RisingStonesRequestContext>()
    var attempts = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { context, sink ->
        contexts += context
        sink.set("User-Agent", "fixture-first-write-agent")
    }
    override fun canAttemptCapability(capability: RisingStonesCapability) =
        capability == RisingStonesCapability.RecruitmentWrite
    override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext): RisingStonesCapabilityAttempt? {
        attempts++
        return null
    }
}

private class NativeRecruitmentTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    val failures = mutableMapOf<String, Exception>()
    var allFailure: Exception? = null
    var allResponse: String? = null
    val responses = mutableMapOf(
        "recruitFbList" to envelope("""{"count":0,"rows":[]}"""),
        "getRecruitFbDetail" to envelope("""{"id":42,"uuid":"fixture-author"}"""),
        "getNeDetail" to envelope("""{"id":11,"uuid":"fixture-author","character_name":"Fixture","title":"Recruitment"}"""),
        "getJobConfigList" to envelope("""{"tank":[{"id":19,"value":"Fixture job"}]}"""),
        "getFbConfigList" to envelope("""[{"id":1,"fb_type":"诛灭战","fb_name":"Fixture duty","team_composition":"团队","weight":5}]"""),
        "fbLabelList" to envelope("""[{"id":2,"name":"Fixture label","weight":9}]"""),
        "getAreaAndGroupList" to envelope("""[{"AreaID":1,"AreaName":"Fixture area","vGroup":[{"GroupID":2,"GroupName":"Fixture server"}]}]"""),
        "styleConfigList" to envelope("[]"),
        "recruitRpCommentDetail" to envelope("""{"rows":[]}"""),
        "recruitRpSubCommentDetail" to envelope("""{"rows":[]}"""),
        "getRecruitRpScoreListByRpId" to envelope("[1,2,3,4,5]"),
        "getCharacterBindInfo" to envelope("""{"uuid":"fixture-reader","character_name":"Fixture"}"""),
    )
    fun service(session: RisingStonesSessionProvider = NativeRecruitmentSession()) = DutyRecruitmentApiService(
        RisingStonesPublicApiClient(this, listOf("https://rising.test")), session, temporarySessionId = "fixture-session",
    )
    fun paths() = requests.map { it.url.toHttpUrl().pathSegments.last() }
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        val path = request.url.toHttpUrl().pathSegments.last()
        (allFailure ?: failures[path])?.let { throw it }
        return RisingStonesHttpResponse(200, emptyMap(), (allResponse ?: responses.getValue(path)).encodeToByteArray())
    }
}

private fun RisingStonesHttpRequest.form(): Map<String, String> = requireNotNull(body).decodeToString().split('&').associate {
    val pair = it.split('=', limit = 2)
    pair[0] to URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name())
}
