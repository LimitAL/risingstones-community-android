package top.cxmeow.risingstones.app

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

/** Synthetic host coverage only; every service is local and no official endpoint is contacted. */
class DynamicPublishingCommunityHostTest {
    @get:Rule val compose = createAndroidComposeRule<SyntheticComposeTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle {
            PublishingHostFixture.current?.navigation?.clear()
            PublishingHostFixture.current = null
            SyntheticComposeTestContent.content = null
        }
    }

    @Test fun forumRelaySuccessPopsOnlyComposerAndRefreshesDynamicOnce() {
        val fixture = show()
        openRelayComposer()

        compose.onNodeWithTag("dynamic-publish-submit").performClick()
        compose.waitUntil(5_000) {
            fixture.dynamic.relayed.size == 1 && fixture.refreshes == 1 &&
                fixture.navigation?.entries?.value?.size == 1
        }
        compose.waitForIdle()

        assertEquals(listOf(42), fixture.dynamic.relayed.map(DynamicPostRelayDraft::postId))
        assertEquals(1, fixture.refreshes)
        assertEquals(CommunityDestination.Post(42), fixture.navigation!!.entries.value.single().destination)
        compose.onNodeWithTag("dynamic-publishing-screen").assertDoesNotExist()
        compose.onNodeWithTag("forum-detail-content").assertIsDisplayed()
        compose.onNodeWithTag("forum-relay-post").assertIsDisplayed()
    }

    @Test fun credentialRevisionClearsComposerStoreSourceAndDraftBeforeReopen() {
        val fixture = show()
        openRelayComposer()
        compose.onNode(
            hasText("First source") and hasAnyAncestor(hasTestTag("dynamic-publish-source")),
        ).assertIsDisplayed()
        compose.onNodeWithTag("dynamic-comment-input").performTextInput("protected draft")

        lateinit var entryProbes: List<ClearedPublishingProbe>
        var oldEntryKey = 0L
        compose.runOnIdle {
            val entries = fixture.navigation!!.entries.value
            val composer = entries.last()
            oldEntryKey = composer.key
            entryProbes = entries.map { entry ->
                ClearedPublishingProbe().also { entry.viewModelStore.put("credential-probe", it) }
            }
            fixture.forum.title = "Second source"
            fixture.revision++
        }
        compose.waitUntil(5_000) { fixture.navigation?.entries?.value?.isEmpty() == true }
        compose.waitForIdle()

        assertTrue(entryProbes.all(ClearedPublishingProbe::cleared))
        assertTrue(fixture.dynamic.scopes.single().closed)
        compose.onNodeWithTag("dynamic-publishing-screen").assertDoesNotExist()
        compose.onNodeWithText("First source").assertDoesNotExist()
        compose.onNodeWithText("protected draft").assertDoesNotExist()

        openRelayComposer()
        compose.runOnIdle { assertNotEquals(oldEntryKey, fixture.navigation!!.entries.value.last().key) }
        compose.onNode(
            hasText("Second source") and hasAnyAncestor(hasTestTag("dynamic-publish-source")),
        ).assertIsDisplayed()
        compose.onNodeWithText("First source").assertDoesNotExist()
        compose.onNodeWithTag("dynamic-comment-input").assertTextEquals("")
        assertEquals(0, fixture.refreshes)
        assertTrue(fixture.dynamic.relayed.isEmpty())
    }

    @Test fun recruitmentDetailRelayMapsSourceAndSuccessReturnsOneLayerWithOneRefresh() {
        val fixture = show()
        compose.onNodeWithTag("publishing-host-open-recruitment").performClick()
        compose.waitUntil(5_000) {
            fixture.navigation?.entries?.value?.size == 1 &&
                compose.onAllNodesWithTag("recruitment-relay-dynamic").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("recruitment-relay-dynamic").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            fixture.navigation?.entries?.value?.size == 2 &&
                compose.onAllNodesWithTag("dynamic-recruitment-relay-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(
                CommunityDestination.DynamicRecruitmentRelay(
                    77,
                    DynamicOrigin.OtherRecruitment,
                    "App other source",
                ),
                fixture.navigation!!.entries.value.last().destination,
            )
        }

        compose.onNodeWithTag("dynamic-recruitment-relay-submit").performClick()
        compose.waitUntil(5_000) {
            fixture.dynamic.relayedRecruitments.size == 1 && fixture.refreshes == 1 &&
                fixture.navigation?.entries?.value?.size == 1
        }
        assertEquals(
            DynamicRecruitmentRelayDraft(77, DynamicOrigin.OtherRecruitment),
            fixture.dynamic.relayedRecruitments.single(),
        )
        assertEquals(
            CommunityDestination.Recruitment(77, RecruitmentBoardKind.Other),
            fixture.navigation!!.entries.value.single().destination,
        )
        compose.onNodeWithTag("dynamic-recruitment-relay-screen").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-detail-content").assertIsDisplayed()
        assertEquals(1, fixture.refreshes)
    }

    private fun show(): PublishingHostConfiguration {
        val fixture = PublishingHostConfiguration()
        compose.runOnIdle {
            PublishingHostFixture.current = fixture
            SyntheticComposeTestContent.content = { PublishingHostContent() }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("publishing-host-open-post").fetchSemanticsNodes().isNotEmpty()
        }
        return fixture
    }

    private fun openRelayComposer() {
        compose.onNodeWithTag("publishing-host-open-post").performClick()
        compose.waitUntil(5_000) {
            PublishingHostFixture.current?.navigation?.entries?.value?.size == 1 &&
                compose.onAllNodesWithTag("forum-relay-post").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("forum-relay-post").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            PublishingHostFixture.current?.navigation?.entries?.value?.size == 2 &&
                compose.onAllNodesWithTag("dynamic-publishing-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
}

private object PublishingHostFixture {
    var current by mutableStateOf<PublishingHostConfiguration?>(null)
}

private class PublishingHostConfiguration {
    var revision by mutableLongStateOf(0L)
    var navigation: CommunityReadingNavigation? = null
    var refreshes = 0
    val forum = PublishingHostForumService()
    val dynamic = PublishingHostDynamicService()
    val recruitment = object : DutyRecruitmentService by unusedPublishingHostService<DutyRecruitmentService>() {
        override val hasCommunityIdentity = false
        override suspend fun fetchCatalogs() = DutyRecruitmentCatalogs()
        override suspend fun fetchCommunityFilterCatalog(
            kind: CommunityRecruitmentKind,
        ) = CommunityRecruitmentFilterCatalog()

        override suspend fun fetchCommunityRecruitmentDetail(
            id: Int,
            kind: CommunityRecruitmentKind,
        ) = CommunityRecruitmentDetail(
            CommunityRecruitmentSummary(
                id = id,
                kind = kind,
                title = "App other source",
                authorName = "Synthetic recruiter",
                avatarUrl = null,
                sourceLocation = null,
                targetLocation = null,
                summary = null,
                coverUrl = null,
            ),
            emptyList(),
            listOf(CommunityRecruitmentContent(
                CommunityRecruitmentContentKind.Description,
                "Synthetic recruitment body",
                "",
            )),
        )
    }
    val access = CommunityReadingAccess(dynamic = true)
    val services = CommunityReadingServices(
        profile = unusedPublishingHostService(),
        forum = forum,
        dynamic = dynamic,
        glamour = unusedPublishingHostService(),
        recruitment = recruitment,
        guild = unusedPublishingHostService(),
        onDynamicPublished = { refreshes++ },
    )
}

@Composable
private fun PublishingHostContent() {
    val fixture = PublishingHostFixture.current ?: return
    val navigation: CommunityReadingNavigation = viewModel()
    SideEffect { fixture.navigation = navigation }
    LaunchedEffect(navigation, fixture.revision) {
        navigation.synchronize(fixture.access, fixture.revision)
    }
    MaterialTheme {
        CommunityReadingHost(navigation, fixture.access, fixture.services) {
            Column {
                Button(
                    onClick = { navigation.open(CommunityDestination.Post(42)) },
                    modifier = Modifier.testTag("publishing-host-open-post"),
                ) {
                    Text("Open synthetic post")
                }
                Button(
                    onClick = {
                        navigation.open(CommunityDestination.Recruitment(77, RecruitmentBoardKind.Other))
                    },
                    modifier = Modifier.testTag("publishing-host-open-recruitment"),
                ) {
                    Text("Open synthetic recruitment")
                }
            }
        }
    }
}

private class ClearedPublishingProbe : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
}

private class PublishingHostScope : DynamicActionScope {
    var closed = false
    override suspend fun isCurrent() = !closed
    override fun close() { closed = true }
}

private class PublishingHostDynamicService : DynamicService, DynamicActionService, DynamicPublishingService,
    DynamicRecruitmentRelayService {
    override val canRead = true
    override val canPerformAuthenticatedWrites = false
    override val canAttemptAuthenticatedWrites = true
    val scopes = CopyOnWriteArrayList<PublishingHostScope>()
    val relayed = CopyOnWriteArrayList<DynamicPostRelayDraft>()
    val relayedRecruitments = CopyOnWriteArrayList<DynamicRecruitmentRelayDraft>()

    override suspend fun beginActionScope() = PublishingHostScope().also(scopes::add)
    override suspend fun relayPost(scope: DynamicActionScope, draft: DynamicPostRelayDraft) {
        check(scope.isCurrent())
        relayed += draft
    }
    override suspend fun publish(scope: DynamicActionScope, draft: DynamicPublishDraft) = error("Unused publish")
    override suspend fun relayRecruitment(scope: DynamicActionScope, draft: DynamicRecruitmentRelayDraft) {
        check(scope.isCurrent())
        relayedRecruitments += draft
    }
    override suspend fun fetchFeed(query: DynamicListQuery) = DynamicPage<DynamicEntry>(emptyList(), query.page, false)
    override suspend fun fetchDetail(id: Int) = error("Unused dynamic detail")
    override suspend fun fetchComments(id: Int, query: DynamicListQuery) =
        DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery) =
        DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun entryEligibility(scope: DynamicActionScope, dynamicId: Int) = error("Unused eligibility")
    override suspend fun commentEligibility(scope: DynamicActionScope, commentId: Int) = error("Unused eligibility")
    override suspend fun fetchMentionCandidates(scope: DynamicActionScope, query: DynamicListQuery) =
        DynamicPage<DynamicCommentMention>(emptyList(), query.page, false)
    override suspend fun fetchComments(scope: DynamicActionScope, dynamicId: Int, query: DynamicListQuery) =
        DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun fetchReplies(scope: DynamicActionScope, rootParentId: Int, query: DynamicListQuery) =
        DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int) = error("Unused like")
    override suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft) = error("Unused comment")
    override suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int) = error("Unused delete")
    override suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int) = error("Unused delete")
}

private class PublishingHostForumService : OfficialForumService {
    override val canPerformAuthenticatedWrites = false
    var title = "First source"
    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail {
        val body = "Synthetic relay body"
        return OfficialForumPostDetail(
            id, title, "", body,
            listOf(OfficialForumRichTextSegment.Text(body)),
            listOf(OfficialForumPostBodyBlock.Paragraph(listOf(OfficialForumRichTextSegment.Text(body)))),
            emptyList(), emptyList(),
            OfficialForumAuthor("fixture-author", "Synthetic author", "", "", null, 0),
            OfficialForumPart(1, "Synthetic category", ""), Instant.EPOCH, Instant.EPOCH, null,
            0, 0, 0, null, null, 0, null, false, false,
        )
    }
    override suspend fun fetchComments(query: OfficialForumCommentQuery) =
        OfficialForumPage<OfficialForumComment>(emptyList(), 0, query.page)
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery) =
        OfficialForumPage<OfficialForumComment>(emptyList(), 0, query.page)
    override suspend fun fetchParts() = error("Unused parts")
    override suspend fun fetchPosts(query: OfficialForumListQuery) = error("Unused posts")
    override suspend fun searchPosts(query: OfficialForumSearchQuery) = error("Unused search")
    override suspend fun likePost(id: Int) = error("No fixture writes")
    override suspend fun likeComment(id: Int) = error("No fixture writes")
    override suspend fun starPost(id: Int) = error("No fixture writes")
    override suspend fun submitComment(draft: OfficialForumCommentDraft) = error("No fixture writes")
    override suspend fun deleteComment(id: Int) = error("No fixture writes")
    override suspend fun submitVote(draft: OfficialForumVoteDraft) = error("No fixture writes")
}

@Suppress("UNCHECKED_CAST")
private inline fun <reified T : Any> unusedPublishingHostService(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { proxy, method, arguments -> when (method.name) {
    "equals" -> proxy === arguments?.firstOrNull()
    "hashCode" -> System.identityHashCode(proxy)
    "toString" -> "Unused publishing host service"
    else -> error("Unexpected publishing host service call: ${method.name}")
} } as T
