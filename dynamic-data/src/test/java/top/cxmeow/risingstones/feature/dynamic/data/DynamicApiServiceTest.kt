package top.cxmeow.risingstones.feature.dynamic.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.network.*

class DynamicApiServiceTest {
    @Test
    fun feedMapsOriginalAndSharedContentAndCarriesOpaqueCursor() = runTest {
        val transport = Transport()
        val service = service(transport)
        val page = service.fetchFeed(DynamicListQuery(page = 2, limit = 2, pageTime = "cursor-before"))
        assertEquals(2, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("cursor-next", page.pageTime)
        assertNull(page.items[0].reference)
        assertEquals(DynamicOrigin.Glamour, page.items[1].reference?.origin)
        assertEquals(listOf("https://ff14risingstones.gcloud.com.cn/dynamic/sample.png"), page.items[0].imageUrls)
        assertEquals("cursor-before", transport.requests.single().url.toHttpUrl().queryParameter("pageTime"))
        assertEquals(RisingStonesHttpMethod.Get, transport.requests.single().method)
        assertEquals("fixture", transport.requests.single().headers["X-Test-Authorization"])
    }

    @Test
    fun missingCapabilityDoesNotReachTransport() = runTest {
        val transport = Transport()
        val provider = Provider().apply { capabilities = emptySet() }
        try { service(transport, provider).fetchFeed(DynamicListQuery()); fail() }
        catch (_: DynamicException.Unavailable) { }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun authFailureRefreshesOnceAndNeverLeaksResponseText() = runTest {
        for (code in listOf(10001, 10403, 10105)) {
            val transport = Transport().apply { body = """{"code":$code,"msg":"private response"}""" }
            val provider = Provider()
            try { service(transport, provider).fetchFeed(DynamicListQuery()); fail() }
            catch (error: DynamicException.AuthenticationRequired) { assertFalse(error.toString().contains("private")) }
            assertEquals(1, provider.refreshes)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test
    fun acceptedCodesReadAllEndpointsWithoutRefreshingOnAuthenticationLookingMessages() = runTest {
        for (code in listOf(10000, 10002)) {
            val transport = Transport().apply {
                body = """{"code":$code,"msg":"未登录","data":{"rows":[$Original]}}"""
            }
            val provider = Provider()
            val service = service(transport, provider)
            assertEquals(1, service.fetchFeed(DynamicListQuery()).items.single().id)
            transport.body = """{"code":$code,"msg":"未登录","data":$Original}"""
            assertEquals(1, service.fetchDetail(1).id)
            transport.body = """{"code":$code,"msg":"未登录","data":{"rows":[{"id":3,"mask_content":"Fixture"}]}}"""
            assertEquals(3, service.fetchComments(1, DynamicListQuery()).items.single().id)
            assertEquals(3, service.fetchReplies(3, DynamicListQuery()).items.single().id)
            assertEquals(listOf("getFollowDynamicList", "dynamicDetail", "dynamicCommentDetail", "dynamicSubCommentDetail"),
                transport.requests.map { it.url.toHttpUrl().pathSegments.last() })
            assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
            assertEquals(0, provider.refreshes)
        }
    }

    @Test
    fun alternateAcceptedCodeKeepsEmptyAndMissingFeedPayloadsDistinct() = runTest {
        val transport = Transport().apply { body = """{"code":10002,"data":{"rows":[],"count":0}}""" }
        val provider = Provider()
        val service = service(transport, provider)
        val empty = service.fetchFeed(DynamicListQuery())
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
        for (body in listOf(
            """{"code":10002}""", """{"code":10002,"data":null}""",
            """{"code":10002,"data":{}}""", """{"code":10002,"data":{"rows":[{}]}}""",
        )) {
            transport.body = body
            val previous = transport.requests.size
            try { service.fetchFeed(DynamicListQuery()); fail() } catch (_: DynamicException.InvalidResponse) { }
            assertEquals(previous + 1, transport.requests.size)
        }
        assertEquals(0, provider.refreshes)
    }

    @Test
    fun alternateAcceptedProbeCodeNeedsFeedRowsAndPreservesOtherCapabilities() = runTest {
        val transport = Transport().apply {
            body = """{"code":10002,"msg":"未登录","data":{"rows":[],"count":0}}"""
        }
        val base = RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10002,
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead)) }
        val validator = RisingStonesDynamicSessionValidator(base, client(transport))
        val validated = validator.validateSession(Authorizer)
        assertEquals(10002, validated.code)
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead), validated.capabilities)
        for (body in listOf(
            """{"code":10002}""", """{"code":10002,"data":null}""",
            """{"code":10002,"data":{}}""", """{"code":10002,"data":{"rows":null}}""",
            """{"code":10403,"data":{"rows":[]}}""",
        )) {
            transport.body = body
            val previous = transport.requests.size
            assertEquals(setOf(RisingStonesCapability.AccountRead), validator.validateSession(Authorizer).capabilities)
            assertEquals(previous + 1, transport.requests.size)
        }
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get &&
            it.url.toHttpUrl().encodedPath == "/api/home/dynamic/getFollowDynamicList" &&
            it.url.toHttpUrl().queryParameter("limit") == "1" })
    }

    @Test
    fun emptyAndMalformedPayloadsRemainDistinct() = runTest {
        val transport = Transport().apply { body = """{"code":10000,"data":{"rows":[],"count":"0"}}""" }
        val page = service(transport).fetchFeed(DynamicListQuery())
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        transport.body = """{"code":10000,"data":{}}"""
        try { service(transport).fetchFeed(DynamicListQuery()); fail() }
        catch (_: DynamicException.InvalidResponse) { }
    }

    @Test
    fun detailCommentsAndRepliesUseDifferentReadEndpoints() = runTest {
        val transport = Transport().apply { body = """{"code":10000,"data":$Original}""" }
        assertEquals(1, service(transport).fetchDetail(1).id)
        transport.body = """{"code":10000,"data":{"count":"1","rows":[{"id":"3","mask_content":"Reply","children_count":2,"to_cname":"Member"}]}}"""
        val comments = service(transport).fetchComments(1, DynamicListQuery())
        assertEquals(2, comments.items.single().childCount)
        assertEquals("Member", comments.items.single().replyToName)
        service(transport).fetchReplies(3, DynamicListQuery())
        assertEquals("/api/home/dynamic/dynamicSubCommentDetail", transport.requests.last().url.toHttpUrl().encodedPath)
        assertEquals("3", transport.requests.last().url.toHttpUrl().queryParameter("root_parent"))
        assertEquals("earliest", transport.requests.last().url.toHttpUrl().queryParameter("order"))
    }

    @Test
    fun probeGrantsOnlyValidatedReadAndRevokesStaleCapabilityOnFailure() = runTest {
        val transport = Transport()
        val base = RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000,
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead)) }
        val validator = RisingStonesDynamicSessionValidator(base, client(transport))
        assertTrue(RisingStonesCapability.DynamicRead in validator.validateSession(Authorizer).capabilities)
        transport.body = """{"code":10403,"data":[]}"""
        val failed = validator.validateSession(Authorizer)
        assertEquals(setOf(RisingStonesCapability.AccountRead), failed.capabilities)
        assertTrue(transport.requests.all { it.method == RisingStonesHttpMethod.Get })
        assertTrue(transport.requests.all { it.url.toHttpUrl().queryParameter("limit") == "1" })
    }

    @Test
    fun cancellationPropagatesThroughProbeAndService() = runTest {
        val transport = Transport().apply { cancel = true }
        val validator = RisingStonesDynamicSessionValidator(
            RisingStonesSessionValidator { RisingStonesSessionValidation(null, 10000) }, client(transport))
        try { validator.validateSession(Authorizer); fail() } catch (_: CancellationException) { }
        try { service(transport).fetchFeed(DynamicListQuery()); fail() } catch (_: CancellationException) { }
    }

    @Test
    fun explicitLikeUsesDynamicEndpointAndCompletesOnlyForPlusOrMinusOne() = runTest {
        for ((data, expected) in listOf(1 to DynamicLikeResult.Liked, -1 to DynamicLikeResult.Unliked)) {
            val transport = Transport().apply { body = """{"code":10000,"data":$data}""" }
            val provider = Provider()
            val service = service(transport, provider)
            val scope = service.beginActionScope()
            assertEquals(
                setOf(RisingStonesCapability.DynamicWrite, RisingStonesCapability.DynamicImageUpload),
                provider.capturedCapabilities.single(),
            )
            assertEquals(expected, service.toggleDynamicLike(scope, 7))
            val request = transport.requests.single()
            assertEquals("/api/home/dynamic/like", request.url.toHttpUrl().encodedPath)
            assertEquals(RisingStonesHttpMethod.Post, request.method)
            assertEquals("id=7", requireNotNull(request.body).decodeToString())
            assertEquals(1, provider.completions)
        }
        val transport = Transport().apply { body = """{"code":10000,"data":0}""" }
        val provider = Provider()
        val service = service(transport, provider)
        try { service.toggleDynamicLike(service.beginActionScope(), 7); fail() }
        catch (_: DynamicException.InvalidResponse) { }
        assertEquals(0, provider.completions)
    }

    @Test
    fun malformedCommentPageCannotPartiallyAuthorizeDeletion() = runTest {
        val transport = Transport().apply {
            body = """{"code":10000,"data":{"rows":[{"id":3,"uuid":"member-1"},{"uuid":"member-2"}]}}"""
        }
        val service = service(transport)
        val scope = service.beginActionScope()
        assertTrue(runCatching { service.fetchComments(scope, 7, DynamicListQuery()) }
            .exceptionOrNull() is DynamicException.InvalidResponse)
        assertEquals(DynamicActionEligibility.Unknown, service.commentEligibility(scope, 3).deleteOwnComment)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun commentUsesExactFormAndStrictSuccessCodeWithoutReplay() = runTest {
        val transport = Transport().apply { body = """{"code":10000,"data":[31]}""" }
        val provider = Provider()
        val service = service(transport, provider)
        service.comment(
            service.beginActionScope(),
            DynamicCommentDraft(
                dynamicId = 7,
                contentHtml = "<p>Hello</p>",
                mentions = listOf(DynamicCommentMention("member-1", "Member")),
                parentId = 3,
                rootParentId = 3,
            ),
        )
        val request = transport.requests.single()
        assertEquals("/api/home/dynamic/comment", request.url.toHttpUrl().encodedPath)
        assertEquals(RisingStonesHttpMethod.Post, request.method)
        val body = requireNotNull(request.body).decodeToString()
        assertTrue(body.contains("dynamic_id=7"))
        assertTrue(body.contains("parent_id=3"))
        assertTrue(body.contains("root_parent=3"))
        assertTrue(body.contains("atInfo%5B0%5D%5Buuid%5D=member-1"))
        assertTrue(body.contains("comment_pic="))

        transport.body = """{"code":10002,"data":null}"""
        try { service.comment(service.beginActionScope(), DynamicCommentDraft(7, "x")); fail() }
        catch (error: DynamicException.Business) { assertEquals(10002, error.code) }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun observedScopeAuthorsGateDeletesAndIdentityConflictDoesNotComplete() = runTest {
        val transport = Transport()
        val provider = Provider()
        val service = service(transport, provider)
        val scope = service.beginActionScope()

        try { service.deleteOwnComment(scope, 9); fail() }
        catch (_: DynamicException.ActionNotEligible) { }
        assertTrue(transport.requests.isEmpty())

        transport.handler = { request -> when (request.url.toHttpUrl().encodedPath) {
            "/api/home/dynamic/dynamicCommentDetail" ->
                """{"code":10000,"data":{"rows":[{"id":9,"uuid":"fixture-user","mask_content":"Mine"}]}}"""
            "/api/home/groupAndRole/getCharacterBindInfo" ->
                """{"code":10000,"data":{"uuid":"fixture-user"}}"""
            "/api/home/dynamic/deleteComment" -> """{"code":10105,"data":null}"""
            else -> error("Unexpected ${request.url}")
        } }
        service.fetchComments(scope, 7, DynamicListQuery())
        try { service.deleteOwnComment(scope, 9); fail() }
        catch (_: DynamicException.IdentityConflict) { }
        assertEquals(0, provider.completions)
        assertEquals(1, transport.requests.count {
            it.url.toHttpUrl().encodedPath == "/api/home/dynamic/deleteComment"
        })
    }

    @Test
    fun foreignScopeForeignImageAndOtherOwnerFailClosed() = runTest {
        val firstProvider = Provider()
        val secondProvider = Provider()
        val firstTransport = Transport()
        val secondTransport = Transport()
        val first = service(firstTransport, firstProvider)
        val second = service(secondTransport, secondProvider)
        val firstScope = first.beginActionScope()
        try { second.toggleDynamicLike(firstScope, 7); fail() }
        catch (_: DynamicException.AuthenticationRequired) { }
        assertTrue(secondTransport.requests.isEmpty())

        val secondScope = second.beginActionScope()
        val foreignImage = object : BoundDynamicUploadedImage {
            override val actionScope = firstScope as OfficialDynamicActionScope
            override val url = "https://ff14risingstones.gcloud.com.cn/dynamic/fixture.png"
            override val purpose = DynamicImagePurpose.Comment
        }
        try { second.comment(secondScope, DynamicCommentDraft(7, "", commentImage = foreignImage)); fail() }
        catch (_: DynamicException.ActionNotEligible) { }
        assertTrue(secondTransport.requests.isEmpty())

        secondTransport.handler = { request -> when (request.url.toHttpUrl().encodedPath) {
            "/api/home/dynamic/dynamicCommentDetail" ->
                """{"code":10000,"data":{"rows":[{"id":9,"uuid":"another-user"}]}}"""
            "/api/home/groupAndRole/getCharacterBindInfo" ->
                """{"code":10000,"data":{"uuid":"fixture-user"}}"""
            else -> error("Unexpected ${request.url}")
        } }
        second.fetchComments(secondScope, 7, DynamicListQuery())
        try { second.deleteOwnComment(secondScope, 9); fail() }
        catch (_: DynamicException.ActionNotEligible) { }
        assertEquals(0, secondTransport.requests.count {
            it.url.toHttpUrl().encodedPath == "/api/home/dynamic/deleteComment"
        })
    }

    @Test
    fun publishUsesExactFormVisibilityAndImageOrder() = runTest {
        DynamicVisibility.entries.forEachIndexed { index, visibility ->
            val acceptedCode = if (index == 1) 10002 else 10000
            val transport = Transport().apply { body = """{"code":$acceptedCode,"data":null}""" }
            val provider = Provider()
            val service = service(transport, provider)
            val scope = service.beginActionScope() as OfficialDynamicActionScope
            val first = uploaded(scope, "https://ff14risingstones.gcloud.com.cn/dynamic/first.png")
            val second = uploaded(scope, "https://ff14risingstones.gcloud.com.cn/dynamic/second.webp")

            service.publish(
                scope,
                DynamicPublishDraft(
                    contentHtml = "<p>Hello world</p>",
                    visibility = visibility,
                    mentions = listOf(DynamicCommentMention("member-1", "Member One")),
                    images = listOf(second, first),
                ),
            )

            val request = transport.requests.single()
            assertEquals("/api/home/dynamic/create", request.url.toHttpUrl().encodedPath)
            assertEquals(RisingStonesHttpMethod.Post, request.method)
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
            assertEquals(
                listOf(
                    "atInfo[0][uuid]" to "member-1",
                    "atInfo[0][character_name]" to "Member One",
                    "content" to "<p>Hello world</p>",
                    "scope" to (index + 1).toString(),
                    "pic_url" to "${second.url},${first.url}",
                ),
                request.formFields(),
            )
            assertEquals(1, provider.completions)
        }
    }

    @Test
    fun relayUsesPostsEndpointDefaultContentAndNoInventedFields() = runTest {
        for (code in listOf(10000, 10002)) {
            val transport = Transport().apply { body = """{"code":$code,"data":null}""" }
            val provider = Provider()
            val service = service(transport, provider)
            service.relayPost(
                service.beginActionScope(),
                DynamicPostRelayDraft(
                    postId = 27,
                    contentHtml = "",
                    visibility = DynamicVisibility.OnlyMe,
                    mentions = listOf(DynamicCommentMention("member-2", "Member Two")),
                ),
            )

            val request = transport.requests.single()
            assertEquals("/api/home/posts/relay", request.url.toHttpUrl().encodedPath)
            assertEquals(RisingStonesHttpMethod.Post, request.method)
            assertEquals(
                listOf(
                    "atInfo[0][uuid]" to "member-2",
                    "atInfo[0][character_name]" to "Member Two",
                    "content" to "分享了内容：",
                    "scope" to "3",
                    "posts_id" to "27",
                ),
                request.formFields(),
            )
            assertFalse(request.formFields().any { it.first in setOf("pic_url", "type") })
            assertEquals(1, provider.completions)
        }
    }

    @Test
    fun recruitmentRelayUsesEveryConfirmedOriginAndVisibilityWithOnlyOfficialFields() = runTest {
        val origins = listOf(
            DynamicOrigin.BeginnerRecruitment,
            DynamicOrigin.DutyRecruitment,
            DynamicOrigin.GuildRecruitment,
            DynamicOrigin.RolePlayRecruitment,
            DynamicOrigin.OtherRecruitment,
        )
        origins.forEach { origin ->
            DynamicVisibility.entries.forEachIndexed { visibilityIndex, visibility ->
                val code = if ((origin.wireValue + visibilityIndex) % 2 == 0) 10000 else 10002
                val transport = Transport().apply { body = """{"code":$code,"data":null}""" }
                val provider = Provider()
                val service = service(transport, provider)

                service.relayRecruitment(
                    service.beginActionScope(),
                    DynamicRecruitmentRelayDraft(37, origin, visibility),
                )

                val request = transport.requests.single()
                assertEquals("/api/home/recruit/relay", request.url.toHttpUrl().encodedPath)
                assertEquals(RisingStonesHttpMethod.Post, request.method)
                assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
                assertEquals(
                    listOf(
                        "from" to origin.wireValue.toString(),
                        "scope" to (visibilityIndex + 1).toString(),
                        "recruid_id" to "37",
                    ),
                    request.formFields(),
                )
                assertEquals(1, provider.completions)
            }
        }
    }

    @Test
    fun recruitmentRelayDraftRejectsInvalidIdsAndUnconfirmedOrigins() {
        assertTrue(runCatching {
            DynamicRecruitmentRelayDraft(0, DynamicOrigin.BeginnerRecruitment)
        }.exceptionOrNull() is IllegalArgumentException)
        DynamicOrigin.entries.filter { it.wireValue !in 5..9 }.forEach { origin ->
            assertTrue(runCatching { DynamicRecruitmentRelayDraft(1, origin) }
                .exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun recruitmentRelayReusesScopeErrorCancellationAndNoReplayBoundaries() = runTest {
        val first = service(Transport(), Provider())
        val foreignScope = first.beginActionScope()
        val secondTransport = Transport()
        val second = service(secondTransport, Provider())
        assertTrue(runCatching {
            second.relayRecruitment(
                foreignScope,
                DynamicRecruitmentRelayDraft(9, DynamicOrigin.BeginnerRecruitment),
            )
        }.exceptionOrNull() is DynamicException.AuthenticationRequired)
        assertTrue(secondTransport.requests.isEmpty())

        val closedScope = second.beginActionScope().also { it.close() }
        assertTrue(runCatching {
            second.relayRecruitment(
                closedScope,
                DynamicRecruitmentRelayDraft(9, DynamicOrigin.DutyRecruitment),
            )
        }.exceptionOrNull() is DynamicException.AuthenticationRequired)
        assertTrue(secondTransport.requests.isEmpty())

        val businessTransport = Transport().apply { body = """{"code":12345,"data":null}""" }
        val businessProvider = Provider()
        val businessService = service(businessTransport, businessProvider)
        assertTrue(runCatching {
            businessService.relayRecruitment(
                businessService.beginActionScope(),
                DynamicRecruitmentRelayDraft(9, DynamicOrigin.GuildRecruitment),
            )
        }.exceptionOrNull() is DynamicException.Business)
        assertEquals(1, businessTransport.requests.size)
        assertEquals(0, businessProvider.completions)

        val failedTransport = Transport().apply { failure = java.io.IOException("synthetic failure") }
        val failedProvider = Provider()
        val failedService = service(failedTransport, failedProvider)
        assertTrue(runCatching {
            failedService.relayRecruitment(
                failedService.beginActionScope(),
                DynamicRecruitmentRelayDraft(9, DynamicOrigin.RolePlayRecruitment),
            )
        }.exceptionOrNull() is DynamicException.Network)
        assertEquals(1, failedTransport.requests.size)
        assertEquals(0, failedProvider.completions)

        val cancelledTransport = Transport().apply { handler = { throw CancellationException() } }
        val cancelledProvider = Provider()
        val cancelledService = service(cancelledTransport, cancelledProvider)
        assertTrue(runCatching {
            cancelledService.relayRecruitment(
                cancelledService.beginActionScope(),
                DynamicRecruitmentRelayDraft(9, DynamicOrigin.OtherRecruitment),
            )
        }.exceptionOrNull() is CancellationException)
        assertEquals(1, cancelledTransport.requests.size)
        assertEquals(0, cancelledProvider.completions)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lateTransport = Transport().apply {
            handler = {
                entered.complete(Unit)
                release.await()
                """{"code":10000,"data":null}"""
            }
        }
        val lateProvider = Provider()
        val lateService = service(lateTransport, lateProvider)
        val lateScope = lateService.beginActionScope()
        val result = async {
            runCatching {
                lateService.relayRecruitment(
                    lateScope,
                    DynamicRecruitmentRelayDraft(9, DynamicOrigin.OtherRecruitment),
                )
            }
        }
        entered.await()
        lateProvider.current = false
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is DynamicException.AuthenticationRequired)
        assertEquals(1, lateTransport.requests.size)
        assertEquals(0, lateProvider.completions)
    }

    @Test
    fun publishingDraftValidationAndImageBindingsFailBeforeNetwork() = runTest {
        val opaque = object : DynamicUploadedImage {}
        assertTrue(runCatching { DynamicPublishDraft("") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DynamicPublishDraft("x", images = List(10) { opaque }) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DynamicPostRelayDraft(0, "") }.exceptionOrNull() is IllegalArgumentException)

        val firstProvider = Provider()
        val first = service(Transport(), firstProvider)
        val firstScope = first.beginActionScope() as OfficialDynamicActionScope
        val secondTransport = Transport()
        val second = service(secondTransport, Provider())
        val secondScope = second.beginActionScope() as OfficialDynamicActionScope
        val publishing = uploaded(firstScope, "https://ff14risingstones.gcloud.com.cn/dynamic/publish.png")
        val comment = uploaded(secondScope, "https://ff14risingstones.gcloud.com.cn/dynamic/comment.png",
            DynamicImagePurpose.Comment)

        assertTrue(runCatching { second.publish(secondScope, DynamicPublishDraft("x", images = listOf(publishing))) }
            .exceptionOrNull() is DynamicException.ActionNotEligible)
        assertTrue(runCatching { second.publish(secondScope, DynamicPublishDraft("x", images = listOf(comment))) }
            .exceptionOrNull() is DynamicException.ActionNotEligible)
        assertTrue(runCatching { second.comment(secondScope, DynamicCommentDraft(7, "", commentImage = publishing)) }
            .exceptionOrNull() is DynamicException.ActionNotEligible)
        assertTrue(secondTransport.requests.isEmpty())
    }

    @Test
    fun malformedIdentityAndReadIdentityConflictDoNotGrantOrWrite() = runTest {
        for (identity in listOf("42", "true")) {
            val transport = Transport()
            val provider = Provider()
            val service = service(transport, provider)
            val scope = service.beginActionScope()
            transport.handler = { request -> when (request.url.toHttpUrl().encodedPath) {
                "/api/home/dynamic/dynamicDetail" ->
                    """{"code":10000,"data":{"id":7,"uuid":"fixture-user"}}"""
                "/api/home/groupAndRole/getCharacterBindInfo" ->
                    """{"code":10000,"data":{"uuid":$identity}}"""
                else -> error("Unexpected ${request.url}")
            } }
            try { service.deleteOwnDynamic(scope, 7); fail() }
            catch (_: DynamicException.InvalidResponse) { }
            assertEquals(0, provider.completions)
            assertEquals(0, transport.requests.count {
                it.url.toHttpUrl().encodedPath == "/api/home/dynamic/deleteDynamic"
            })
        }

        val transport = Transport().apply { body = """{"code":10105,"data":null}""" }
        val conflictProvider = Provider()
        val service = service(transport, conflictProvider)
        try { service.entryEligibility(service.beginActionScope(), 7); fail() }
        catch (_: DynamicException.IdentityConflict) { }
        assertEquals(0, conflictProvider.refreshes)
    }

    @Test
    fun writeHttpAuthenticationFailureRefreshesOnceWithoutReplayOrGrant() = runTest {
        for (failure in listOf<Throwable?>(
            null,
            IllegalStateException("outer", RisingStonesHttpException.ServerResponse(403, byteArrayOf())),
        )) {
            val transport = Transport().apply {
                statusCode = 401
                this.failure = failure
            }
            val provider = Provider()
            val service = service(transport, provider)
            try { service.toggleDynamicLike(service.beginActionScope(), 7); fail() }
            catch (_: DynamicException.AuthenticationRequired) { }
            assertEquals(1, provider.refreshes)
            assertEquals(1, transport.requests.size)
            assertEquals(0, provider.completions)
        }
    }

    @Test
    fun businessAndReadAuthenticationFailuresRefreshOnceWithoutReplay() = runTest {
        val writeTransport = Transport().apply { body = """{"code":10403,"data":null}""" }
        val writeProvider = Provider()
        val writeService = service(writeTransport, writeProvider)
        try { writeService.toggleDynamicLike(writeService.beginActionScope(), 7); fail() }
        catch (_: DynamicException.AuthenticationRequired) { }
        assertEquals(1, writeProvider.refreshes)
        assertEquals(1, writeTransport.requests.size)
        assertEquals(0, writeProvider.completions)

        for (failure in listOf<Throwable?>(null,
            IllegalStateException("outer", RisingStonesHttpException.ServerResponse(401, byteArrayOf())))) {
            val readTransport = Transport().apply {
                body = """{"code":10403,"data":null}"""
                this.failure = failure
            }
            val readProvider = Provider()
            val readService = service(readTransport, readProvider)
            try { readService.entryEligibility(readService.beginActionScope(), 7); fail() }
            catch (_: DynamicException.AuthenticationRequired) { }
            assertEquals(1, readProvider.refreshes)
            assertEquals(1, readTransport.requests.size)
            assertEquals(0, readProvider.completions)
        }
    }

    @Test
    fun cancelledAndLateWritesNeverCompleteCapability() = runTest {
        val cancelledTransport = Transport().apply { cancel = true }
        val cancelledProvider = Provider()
        val cancelledService = service(cancelledTransport, cancelledProvider)
        try { cancelledService.toggleDynamicLike(cancelledService.beginActionScope(), 7); fail() }
        catch (_: CancellationException) { }
        assertEquals(0, cancelledProvider.completions)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = Transport().apply {
            handler = {
                entered.complete(Unit)
                release.await()
                """{"code":10000,"data":1}"""
            }
        }
        val provider = Provider()
        val service = service(transport, provider)
        val scope = service.beginActionScope()
        val result = async { runCatching { service.toggleDynamicLike(scope, 7) } }
        entered.await()
        provider.current = false
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is DynamicException.AuthenticationRequired)
        assertEquals(1, transport.requests.size)
        assertEquals(0, provider.completions)
    }

    @Test
    fun readCapabilityAloneNeverExposesOrCapturesWrite() = runTest {
        val provider = Provider().apply {
            capabilities = setOf(RisingStonesCapability.DynamicRead)
            allowWrites = false
        }
        val service = service(Transport(), provider)
        assertFalse(service.canPerformAuthenticatedWrites)
        assertFalse(service.canAttemptAuthenticatedWrites)
        try { service.beginActionScope(); fail() }
        catch (_: DynamicException.AuthenticationRequired) { }
    }

    private fun service(transport: Transport, provider: Provider = Provider()) = DynamicApiService(client(transport), provider)
    private fun client(transport: Transport) = RisingStonesPublicApiClient(transport, listOf("https://rising.test"))

    private fun uploaded(
        scope: OfficialDynamicActionScope,
        url: String,
        purpose: DynamicImagePurpose = DynamicImagePurpose.Publishing,
    ) = object : BoundDynamicUploadedImage {
        override val actionScope = scope
        override val url = url
        override val purpose = purpose
    }
}

private fun RisingStonesHttpRequest.formFields(): List<Pair<String, String>> =
    requireNotNull(body).decodeToString().split('&').map { field ->
        val parts = field.split('=', limit = 2)
        java.net.URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
            java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
    }

private val Authorizer = RisingStonesRequestAuthorizer { _, sink -> sink.set("X-Test-Authorization", "fixture") }
private class Provider : RisingStonesSessionProvider, RisingStonesCapabilityScopeProvider,
    RisingStonesExplicitCapabilityProvider {
    override var capabilities = setOf(
        RisingStonesCapability.DynamicRead,
        RisingStonesCapability.DynamicWrite,
        RisingStonesCapability.DynamicImageUpload,
    )
    var refreshes = 0
    var completions = 0
    var current = true
    var allowWrites = true
    val capturedCapabilities = mutableListOf<Set<RisingStonesCapability>>()
    override suspend fun currentAuthorizer() = Authorizer
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer { refreshes++; return Authorizer }
    override fun canAttemptCapability(capability: RisingStonesCapability) = allowWrites && capability in setOf(
        RisingStonesCapability.DynamicWrite,
        RisingStonesCapability.DynamicImageUpload,
    )
    override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext) = attempt()
    override suspend fun captureCapabilityScope(capabilities: Set<RisingStonesCapability>): RisingStonesCapabilityScope? {
        capturedCapabilities += capabilities
        if (!allowWrites || capabilities != setOf(
                RisingStonesCapability.DynamicWrite,
                RisingStonesCapability.DynamicImageUpload,
            )
        ) return null
        return object : RisingStonesCapabilityScope {
        override val authorizer = Authorizer
        private var open = true
        override suspend fun isCurrent() = open && current
        override suspend fun beginCapabilityAttempt(context: RisingStonesRequestContext) = attempt()
        override fun close() { open = false }
        }
    }
    private fun attempt(): RisingStonesCapabilityAttempt = object : RisingStonesCapabilityAttempt,
        RisingStonesCapabilityAttemptGuard {
        override val authorizer = Authorizer
        override suspend fun isCurrent() = current
        override suspend fun complete(): Boolean {
            if (!current) return false
            completions++
            return true
        }
        override fun close() = Unit
    }
}
private class Transport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var cancel = false
    var statusCode = 200
    var failure: Throwable? = null
    var body = """{"code":10000,"data":{"rows":[$Original,$Shared],"pageTime":"cursor-next"}}"""
    var handler: (suspend (RisingStonesHttpRequest) -> String)? = null
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        failure?.let { throw it }
        return RisingStonesHttpResponse(statusCode, emptyMap(), (handler?.invoke(request) ?: body).encodeToByteArray())
    }
}
private val Original = """{"id":1,"from":1,"uuid":"fixture-author","character_name":"Member","mask_content":"<p>Activity</p>","pic_url":"https://ff14risingstones.gcloud.com.cn/dynamic/sample.png,http://untrusted.test/image.png,https://untrusted.test/image.png","created_at":"2026-09-18 10:00:00"}"""
private val Shared = """{"id":2,"from":10,"from_id":"23","from_info":{"title":"Outfit","desc":"Description","main_image":"https://ff14risingstones.gcloud.com.cn/glamour/sample.png"}}"""
