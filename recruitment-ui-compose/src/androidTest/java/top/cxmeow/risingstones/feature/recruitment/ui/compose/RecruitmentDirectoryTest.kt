package top.cxmeow.risingstones.feature.recruitment.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

/** Directory fixtures are independent of account state and of the existing interaction test suite. */
class RecruitmentDirectoryTest {
    @get:Rule val compose = createAndroidComposeRule<RecruitmentDirectoryTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle { RecruitmentDirectoryFixture.configuration = null }
    }

    @Test fun memberNavigationAtCompact599() = memberNavigation(599)
    @Test fun memberNavigationAtMedium600() = memberNavigation(600)
    @Test fun memberNavigationAtUpperMedium839() = memberNavigation(839)
    @Test fun memberNavigationAtExpanded840() = memberNavigation(840)

    private fun memberNavigation(width: Int) {
        val service = DirectoryServiceFixture()
        val configuration = show(service, width)
        detailNode(hasTestTag("recruitment-open-members"))
        compose.onNodeWithTag("recruitment-detail-content").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 24f)
        }
        val parentOffset = parentScrollOffset()
        assertTrue("The parent must be scrolled before opening the directory", parentOffset > 0f)
        compose.onNodeWithTag("recruitment-open-members").performClick()
        waitForTag("roleplay-directory-list")
        directoryListNode(hasTestTag("roleplay-member-101")).performClick()
        waitForText(memberDescription(101))
        if (width < 600) compose.onNodeWithTag("roleplay-directory-list").assertDoesNotExist()
        else compose.onNodeWithTag("roleplay-directory-list").assertExists()
        compose.onNodeWithTag("roleplay-directory-detail").assertExists()
        val resizedWidth = if (width < 600) 600 else 599
        compose.runOnIdle { configuration.width = resizedWidth }
        compose.onNodeWithText(memberDescription(101)).assertExists()
        if (resizedWidth < 600) compose.onNodeWithTag("roleplay-directory-list").assertDoesNotExist()
        else compose.onNodeWithTag("roleplay-directory-list").assertExists()
        compose.runOnIdle { configuration.width = width }
        compose.onNodeWithTag("roleplay-directory-back").performClick()
        compose.onNodeWithTag("roleplay-directory-list").assertExists()
        compose.onNodeWithText(memberDescription(101)).assertDoesNotExist()
        compose.onNodeWithTag("roleplay-directory-back").performClick()
        waitForTagAbsent("roleplay-directory")
        assertEquals(parentOffset, parentScrollOffset(), 0.1f)
        compose.onNodeWithTag("recruitment-open-members").assertExists()
        assertEquals(listOf(101), service.memberDetailReads)
        assertEquals(1, service.memberQueries.size)
    }

    @Test fun workspaceDirectoryResizeKeepsParentDetailScrollOnReturn() {
        val service = DirectoryServiceFixture()
        val configuration = show(service, width = 599, workspace = true)
        compose.onNodeWithText("Synthetic directory host").performScrollTo().performClick()
        waitForTag("recruitment-detail-content")
        detailNode(hasTestTag("recruitment-open-members"))
        compose.onNodeWithTag("recruitment-detail-content").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 24f)
        }
        val parentOffset = parentScrollOffset()
        assertTrue("The workspace detail must be scrolled before opening its directory", parentOffset > 0f)
        compose.onNodeWithTag("recruitment-open-members").performClick()
        waitForTag("roleplay-directory-list")
        directoryListNode(hasTestTag("roleplay-member-101")).performClick()
        waitForText(memberDescription(101))

        compose.runOnIdle { configuration.width = 600 }
        compose.onNodeWithTag("roleplay-directory-list").assertExists()
        compose.onNodeWithText(memberDescription(101)).assertExists()
        compose.runOnIdle { configuration.width = 599 }
        compose.onNodeWithTag("roleplay-directory-list").assertDoesNotExist()
        compose.onNodeWithText(memberDescription(101)).assertExists()
        compose.onNodeWithTag("roleplay-directory-back").performClick()
        compose.onNodeWithTag("roleplay-directory-list").assertExists()
        compose.onNodeWithTag("roleplay-directory-back").performClick()
        waitForTagAbsent("roleplay-directory")

        assertEquals("Returning from the directory must retain the main screen's detail scroll",
            parentOffset, parentScrollOffset(), 0.1f)
        compose.onNodeWithTag("recruitment-open-members").assertExists()
        assertEquals(listOf(42), service.detailReads)
        assertEquals(listOf(101), service.memberDetailReads)
        assertEquals(1, service.memberQueries.size)
    }

    @Test fun memberContinuationUsesTheOriginalCursorAndPreservesTheFirstPage() {
        val service = DirectoryServiceFixture()
        show(service)
        openMembers()
        directoryListNode(hasTestTag("roleplay-directory-more")).performClick()
        compose.waitUntil { service.memberQueries.size == 2 }
        directoryListNode(hasTestTag("roleplay-member-102")).assertExists()
        directoryListNode(hasTestTag("roleplay-member-101")).assertExists()
        assertEquals(RolePlayMemberQuery(42, page = 1), service.memberQueries[0])
        assertEquals(RolePlayMemberQuery(42, page = 2, pageTime = "fixture-cursor-42"), service.memberQueries[1])
        compose.onNodeWithTag("roleplay-directory-more").assertDoesNotExist()
    }

    @Test fun activityDirectoryOpensTheSelectedActivityAndKeepsOfficialOrdering() {
        val service = DirectoryServiceFixture()
        show(service, width = 840)
        detailNode(hasTestTag("recruitment-open-activities")).performClick()
        waitForTag("roleplay-directory-list")
        val activityFirst = compose.onNodeWithTag("roleplay-activity-302").fetchSemanticsNode().positionInRoot.y
        val activitySecond = compose.onNodeWithTag("roleplay-activity-301").fetchSemanticsNode().positionInRoot.y
        assertTrue("The service order must remain unchanged", activityFirst < activitySecond)
        directoryListNode(hasTestTag("roleplay-activity-301")).performClick()
        waitForText("Activity description 301", substring = true)
        directoryDetailNode(hasText("After picture 301", substring = true)).assertExists()
        val before = compose.onNodeWithText("Activity description 301", substring = true).fetchSemanticsNode()
        val picture = compose.onNodeWithText(text(R.string.recruitment_image_failed)).fetchSemanticsNode()
        val after = compose.onNodeWithText("After picture 301", substring = true).fetchSemanticsNode()
        assertTrue(before.positionInRoot.y < picture.positionInRoot.y)
        assertTrue(picture.positionInRoot.y < after.positionInRoot.y)
        assertTrue(before.config[SemanticsProperties.Text].single().spanStyles.any {
            it.item.fontWeight == FontWeight.Bold
        })
        assertEquals(listOf(301), service.activityDetailReads)
        compose.onNodeWithTag("roleplay-directory-back").performClick()
        directoryListNode(hasTestTag("roleplay-activity-302")).assertExists()
        compose.onNodeWithTag("roleplay-directory-more").assertDoesNotExist()
        compose.onNodeWithTag("roleplay-directory-members").performClick()
        directoryListNode(hasTestTag("roleplay-member-101")).assertExists()
        compose.onNodeWithTag("roleplay-directory-activities").performClick()
        directoryListNode(hasTestTag("roleplay-activity-301")).assertExists()
        assertEquals(listOf(42), service.activityReads)
    }

    @Test fun failedMemberListAndDetailRefreshKeepConfirmedContent() {
        val service = DirectoryServiceFixture()
        show(service, width = 840)
        openMembers()
        directoryListNode(hasTestTag("roleplay-member-101")).performClick()
        waitForText(memberDescription(101))
        compose.runOnIdle { service.failNextMemberPage = true }
        compose.onNodeWithTag("roleplay-directory-refresh").performClick()
        compose.waitUntil { service.memberQueries.size == 2 }
        compose.onNodeWithTag("roleplay-directory-retry").assertExists()
        compose.onNodeWithTag("roleplay-member-101").assertExists()
        compose.onNodeWithText(memberDescription(101)).assertExists()
        compose.runOnIdle { service.failNextMemberDetail = true }
        directoryDetailNode(hasTestTag("roleplay-directory-detail-refresh")).performClick()
        compose.waitUntil { service.memberDetailReads.size == 2 }
        compose.onNodeWithTag("roleplay-directory-detail-retry").assertExists()
        compose.onNodeWithText(memberDescription(101)).assertExists()
        compose.onNodeWithTag("roleplay-directory-detail-retry").performClick()
        compose.waitUntil { service.memberDetailReads.size == 3 }
        compose.onNodeWithText(memberDescription(101)).assertExists()
    }

    @Test fun activityImageLinkRequiresAClickAndHandlesUnavailableOrUnsafeTargets() {
        val safeLink = "https://example.com/fixture-activity-picture"
        val service = DirectoryServiceFixture().apply { activityImageLink = safeLink }
        val configuration = show(service)
        detailNode(hasTestTag("recruitment-open-activities")).performClick()
        waitForTag("roleplay-directory-list")
        directoryListNode(hasTestTag("roleplay-activity-301")).performClick()
        waitForText("Activity description 301", substring = true)
        val linkLabel = text(R.string.recruitment_image_link)
        directoryDetailNode(hasText(linkLabel)).assertIsDisplayed()
        assertTrue("Rendering a linked image must not launch it", configuration.uriHandler.opened.isEmpty())

        compose.onNodeWithText(linkLabel).performClick()
        compose.runOnIdle { assertEquals(listOf(safeLink), configuration.uriHandler.opened) }
        compose.onNodeWithText(text(R.string.recruitment_link_unavailable)).assertDoesNotExist()

        compose.runOnIdle { configuration.uriHandler.fail = true }
        directoryDetailNode(hasText(linkLabel)).performClick()
        directoryDetailNode(hasText(text(R.string.recruitment_link_unavailable))).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(safeLink, safeLink), configuration.uriHandler.opened)
            listOf(null, "intent://fixture/image", "javascript:alert(1)",
                "https://fixture-user@example.com/image", "http://example.com/image",
                "https://example.com:8443/image").forEach { unsafeLink ->
                assertFalse(openRecruitmentContentLink(configuration.uriHandler, unsafeLink))
            }
            assertEquals("Rejected targets must never reach the platform handler",
                listOf(safeLink, safeLink), configuration.uriHandler.opened)
        }
    }

    @Test fun changingParentDropsTheLateDirectoryResponse() {
        val service = DirectoryServiceFixture().apply { firstMemberPageGate = CompletableDeferred() }
        val configuration = show(service)
        openMembers(waitForList = false)
        compose.waitUntil { service.memberQueries.size == 1 }
        compose.runOnIdle { configuration.parentId = 84 }
        waitForTagAbsent("roleplay-directory")
        waitForText("Directory host body 84")
        openMembers()
        directoryListNode(hasTestTag("roleplay-member-201")).assertExists()
        service.firstMemberPageGate!!.complete(Unit)
        compose.waitUntil { service.lateMemberReadsCompleted == 1 }
        compose.onNodeWithTag("roleplay-member-101").assertDoesNotExist()
        compose.onNodeWithTag("roleplay-member-201").assertExists()
        assertEquals(listOf(42, 84), service.memberQueries.map { it.recruitmentId })
    }

    @Test fun activityRecreationKeepsDirectoryPagesAndSelectedMemberWithoutRefetching() {
        val service = DirectoryServiceFixture()
        show(service)
        openMembers()
        directoryListNode(hasTestTag("roleplay-directory-more")).performClick()
        directoryListNode(hasTestTag("roleplay-member-102")).performClick()
        waitForText(memberDescription(102))
        compose.activityRule.scenario.recreate()
        waitForText(memberDescription(102))
        compose.onNodeWithTag("roleplay-directory").assertExists()
        assertEquals(2, service.memberQueries.size)
        assertEquals(listOf(102), service.memberDetailReads)
        assertEquals(listOf(42), service.detailReads)
        compose.onNodeWithTag("roleplay-directory-back").performClick()
        directoryListNode(hasTestTag("roleplay-member-101")).assertExists()
        directoryListNode(hasTestTag("roleplay-member-102")).assertExists()
        assertEquals(2, service.memberQueries.size)
    }

    @Test fun legacyServiceKeepsMemberPreviewWithoutShowingDirectoryEntrances() {
        val service = DirectoryLegacyService()
        show(service)
        detailNode(hasText("Legacy member preview")).assertExists()
        compose.onNodeWithTag("recruitment-open-members").assertDoesNotExist()
        compose.onNodeWithTag("recruitment-open-activities").assertDoesNotExist()
        assertEquals(listOf(42), service.detailReads)
    }

    private fun show(service: DirectoryLegacyService, width: Int = 599,
        workspace: Boolean = false): RecruitmentDirectoryConfiguration {
        val configuration = RecruitmentDirectoryConfiguration(service, width, workspace)
        compose.runOnIdle { RecruitmentDirectoryFixture.configuration = configuration }
        if (workspace) {
            compose.onNodeWithText(text(R.string.recruitment_board_roleplay)).performScrollTo().performClick()
            waitForText("Synthetic directory host")
        } else {
            compose.waitUntil { service.detailReads.isNotEmpty() }
            waitForTag("recruitment-detail-content")
        }
        compose.runOnIdle { assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.1f) }
        return configuration
    }

    private fun detailNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("recruitment-detail-content").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun openMembers(waitForList: Boolean = true) {
        detailNode(hasTestTag("recruitment-open-members")).performClick()
        waitForTag("roleplay-directory")
        if (waitForList) waitForTag("roleplay-directory-list")
    }

    private fun directoryListNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("roleplay-directory-list").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun directoryDetailNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("roleplay-directory-detail").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun parentScrollOffset() = compose.onNodeWithTag("recruitment-detail-content")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitForText(value: String, substring: Boolean = false) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForTagAbsent(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
}

/** The Activity supplies dimensions; production ViewModel stores retain directory and selection state. */
class RecruitmentDirectoryTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecruitmentDirectoryFixture.configuration?.let { configuration ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = Density(constraints.maxWidth.toFloat() / configuration.width, 1f)
                    CompositionLocalProvider(LocalDensity provides density,
                        LocalUriHandler provides configuration.uriHandler) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().onSizeChanged {
                                configuration.measuredWidthDp = with(density) { it.width.toDp().value }
                            }) {
                                when {
                                    !configuration.showRoute -> Text("Directory route closed")
                                    configuration.workspace -> RisingStonesRecruitmentScreen(configuration.service,
                                        { configuration.showRoute = false })
                                    else -> RisingStonesRecruitmentDetailScreen(configuration.service,
                                        configuration.parentId, RecruitmentBoardKind.RolePlay,
                                        { configuration.showRoute = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private object RecruitmentDirectoryFixture {
    var configuration by mutableStateOf<RecruitmentDirectoryConfiguration?>(null)
}

private class RecruitmentDirectoryConfiguration(val service: DirectoryLegacyService, width: Int,
    val workspace: Boolean) {
    val uriHandler = DirectoryRecordingUriHandler()
    var width by mutableStateOf(width)
    var measuredWidthDp = 0f
    var parentId by mutableStateOf(42)
    var showRoute by mutableStateOf(true)
}

/** Records explicit link requests without launching an external Activity. */
private class DirectoryRecordingUriHandler : UriHandler {
    val opened = CopyOnWriteArrayList<String>()
    var fail = false

    override fun openUri(uri: String) {
        opened += uri
        if (fail) error("Synthetic link handler failure")
    }
}

/** A legacy reader intentionally lacks every optional directory capability. */
private open class DirectoryLegacyService : DutyRecruitmentService {
    override val hasCommunityIdentity = false
    val detailReads = CopyOnWriteArrayList<Int>()

    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery) =
        DutyRecruitmentListPage(emptyList(), 0, query.page)
    override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail = error("No duty in directory fixtures")
    override suspend fun fetchCatalogs() = DutyRecruitmentCatalogs()
    override suspend fun fetchCommunityRecruitments(query: CommunityRecruitmentQuery) = CommunityRecruitmentPage(
        listOf(summary(42), summary(84)), 2, query.page)
    override suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentDetail {
        detailReads += id
        return CommunityRecruitmentDetail(summary(id), emptyList(), listOf(
            CommunityRecruitmentContent(CommunityRecruitmentContentKind.Description, "Directory host body $id", ""),
            CommunityRecruitmentContent(CommunityRecruitmentContentKind.Profile, "Synthetic parent context\n".repeat(20), "")))
    }
    override suspend fun fetchCommunityFilterCatalog(kind: CommunityRecruitmentKind) = CommunityRecruitmentFilterCatalog()
    override suspend fun fetchRolePlayMembers(id: Int) = listOf(RolePlayRecruitmentMember(
        id, "Legacy member preview", null, null, null, emptyList()))
    override suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int) =
        RolePlayRecruitmentReviewPage(emptyList(), page, false)
    override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int) =
        RolePlayRecruitmentSubcommentPage(emptyList(), page, false)
    override suspend fun fetchRolePlayRating(id: Int) = RolePlayRecruitmentRating(emptyList())
    override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? = error("No fixture writes")
    override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String): String? = error("No fixture writes")
    override suspend fun likeRolePlayReview(id: String): Int = error("No fixture writes")

    private fun summary(id: Int) = CommunityRecruitmentSummary(id, CommunityRecruitmentKind.RolePlay,
        if (id == 42) "Synthetic directory host" else "Second directory host", "Fixture author", null,
        null, null, null, null)
}

private class DirectoryServiceFixture : DirectoryLegacyService(), RolePlayDirectoryService {
    val memberQueries = CopyOnWriteArrayList<RolePlayMemberQuery>()
    val memberDetailReads = CopyOnWriteArrayList<Int>()
    val activityReads = CopyOnWriteArrayList<Int>()
    val activityDetailReads = CopyOnWriteArrayList<Int>()
    var firstMemberPageGate: CompletableDeferred<Unit>? = null
    @Volatile var lateMemberReadsCompleted = 0
    var failNextMemberPage = false
    var failNextMemberDetail = false
    var activityImageLink: String? = null

    override suspend fun fetchRolePlayMemberPage(query: RolePlayMemberQuery): RolePlayMemberPage {
        memberQueries += query
        if (query.recruitmentId == 42 && memberQueries.size == 1 && firstMemberPageGate != null) {
            withContext(NonCancellable) { firstMemberPageGate!!.await(); lateMemberReadsCompleted++ }
        }
        if (failNextMemberPage) {
            failNextMemberPage = false
            throw DutyRecruitmentException.Business(12345, "Synthetic directory read failure")
        }
        val id = (if (query.recruitmentId == 42) 100 else 200) + query.page
        return RolePlayMemberPage(listOf(RolePlayRecruitmentMember(id, "Directory member $id", null,
            null, "Member summary $id", emptyList())), query.page, query.page == 1,
            if (query.page == 1) "fixture-cursor-${query.recruitmentId}" else "fixture-unused-next-cursor")
    }

    override suspend fun fetchRolePlayMemberDetail(id: Int): RolePlayMemberDetail {
        memberDetailReads += id
        if (failNextMemberDetail) {
            failNextMemberDetail = false
            throw DutyRecruitmentException.Business(12345, "Synthetic member detail failure")
        }
        return RolePlayMemberDetail(id, if (id < 200) 42 else 84, "Directory member $id", null,
            null, memberDescription(id), null)
    }

    override suspend fun fetchRolePlayActivities(recruitmentId: Int): List<RolePlayActivity> {
        activityReads += recruitmentId
        return listOf(activity(302, recruitmentId), activity(301, recruitmentId))
    }

    override suspend fun fetchRolePlayActivityDetail(id: Int): RolePlayActivityDetail {
        activityDetailReads += id
        val markup = "<p><strong>Activity description $id</strong></p>"
        return RolePlayActivityDetail(activity(id, 42), markup,
            listOf(RolePlayActivityBodyBlock.Html(markup),
                // A rejected local fixture URI exercises image placement without making a network request.
                RolePlayActivityBodyBlock.Image("fixture://directory-image", "Synthetic image", activityImageLink),
                RolePlayActivityBodyBlock.Html("<p>After picture $id</p>")), false)
    }

    private fun activity(id: Int, parent: Int) = RolePlayActivity(id, parent, "Directory activity $id", null,
        "2026-09-20 12:00:00", "2026-09-20 14:00:00", 1, 0)
}

private fun memberDescription(id: Int) = "Member biography $id <b>literal</b> &amp;"
