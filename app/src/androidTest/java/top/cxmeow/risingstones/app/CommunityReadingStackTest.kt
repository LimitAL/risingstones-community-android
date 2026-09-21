package top.cxmeow.risingstones.app

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicService
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.feature.forum.ui.compose.R as ForumR
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.guild.domain.*
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.message.presentation.MessageViewModel
import top.cxmeow.risingstones.feature.message.ui.compose.RisingStonesMessageScreen
import top.cxmeow.risingstones.feature.message.ui.compose.R as MessageR
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.profile.presentation.ProfileViewModel
import top.cxmeow.risingstones.feature.profile.ui.compose.R as ProfileR
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentService

/** These tests use an isolated Activity and synthetic services, never MainActivity's session container. */
class CommunityReadingStackTest {
    @get:Rule val compose = createAndroidComposeRule<SyntheticComposeTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle {
            CommunityReadingFixture.configuration?.navigation?.clear()
            CommunityReadingFixture.configuration = null
            SyntheticComposeTestContent.content = null
        }
    }

    @Test fun authorPostAuthorBackAt599() = authorPostAuthorBack(599)
    @Test fun authorPostAuthorBackAt600() = authorPostAuthorBack(600)
    @Test fun authorPostAuthorBackAt839() = authorPostAuthorBack(839)
    @Test fun authorPostAuthorBackAt840() = authorPostAuthorBack(840)

    @Test fun photoMessageAuthorBackAt599() = photoMessageAuthorBack(599)
    @Test fun photoMessageAuthorBackAt600() = photoMessageAuthorBack(600)
    @Test fun photoMessageAuthorBackAt839() = photoMessageAuthorBack(839)
    @Test fun photoMessageAuthorBackAt840() = photoMessageAuthorBack(840)

    private fun photoMessageAuthorBack(width: Int) {
        val configuration = show(width, guildMessages = true)
        displayedNode(hasText(compose.activity.getString(MessageR.string.message_comments))).performClick()
        waitForTag("message-content-list")
        scrollNode("message-content-list", hasText(compose.activity.getString(MessageR.string.message_source))).performClick()
        waitForDepth(1)
        waitForTag("guild-photo-content")
        val photoEntry = navigation().entries.value.single()
        scrollNode("guild-photo-content", hasTestTag("guild-author-fixture-photo-author")).performTouchInput { click(center) }
        waitForDepth(2)
        waitForText("Synthetic profile fixture-photo-author")
        if (width == 840) {
            compose.activityRule.scenario.recreate()
            waitForText("Synthetic profile fixture-photo-author")
        }
        systemBackToDepth(1)
        waitForTag("guild-photo-content")
        assertSame(photoEntry, navigation().entries.value.single())
        systemBackToDepth(0)
        waitForTag("message-content-list")
        assertEquals(1, configuration.message.reads.size)
        assertEquals(listOf(42), configuration.guild.photosRead)
        assertEquals(listOf(42), configuration.guild.commentsRead)
        assertEquals(listOf(ProfileOwner.User("fixture-photo-author")), configuration.profile.profiles)
    }

    private fun authorPostAuthorBack(width: Int) {
        val configuration = show(width)
        val snapshot = openAuthorPostAuthor(configuration)
        assertProfileLayout(width)
        val resizedWidth = if (width < 600) 600 else 599
        compose.runOnIdle { configuration.width = resizedWidth }
        displayedNode(hasText("Synthetic profile fixture-author-b")).assertIsDisplayed()
        assertProfileLayout(resizedWidth)
        compose.runOnIdle { configuration.width = width }
        assertProfileLayout(width)
        assertEquals(3, navigation().entries.value.size)
        returnThroughAllLayers(configuration, snapshot)
        assertEquals(listOf(ProfileOwner.User("fixture-author-a"), ProfileOwner.User("fixture-author-b")),
            configuration.profile.profiles)
        assertEquals(listOf(42), configuration.forum.details)
        assertEquals(1, configuration.profile.sections.size)
    }

    @Test fun recreatingTheWholeStackKeepsEntriesScrollAndCompletedReads() {
        val configuration = show(599)
        val snapshot = openAuthorPostAuthor(configuration)
        val originalNavigation = navigation()
        val entries = originalNavigation.entries.value
        compose.activityRule.scenario.recreate()
        waitForText("Synthetic profile fixture-author-b")
        assertSame(originalNavigation, navigation())
        assertEquals(entries, navigation().entries.value)
        assertEquals(2, configuration.profile.profiles.size)
        assertEquals(1, configuration.profile.sections.size)
        assertEquals(listOf(42), configuration.forum.details)
        returnThroughAllLayers(configuration, snapshot)
        assertEquals(2, configuration.profile.profiles.size)
        assertEquals(1, configuration.profile.sections.size)
        assertEquals(listOf(42), configuration.forum.details)
    }

    @Test fun revocationClearsEveryEntryStoreAndProtectedProfileContent() {
        val configuration = show(840)
        openAuthorPostAuthor(configuration)
        val entries = navigation().entries.value
        lateinit var profiles: List<ProfileViewModel>
        lateinit var probes: List<ReadingProbeModel>
        compose.runOnIdle {
            profiles = entries.filter { it.destination is CommunityDestination.Profile }.map {
                ViewModelProvider(it)[ProfileViewModel::class.java]
            }
            probes = entries.map { entry -> ReadingProbeModel().also { entry.viewModelStore.put("probe", it) } }
            assertTrue(profiles.all { it.state.value.profile != null })
        }
        compose.runOnIdle { configuration.access = CommunityReadingAccess() }
        waitForDepth(0)
        compose.onNodeWithTag("community-reading-stack").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(probes.all { it.cleared })
            profiles.forEach {
                assertNull(it.state.value.profile)
                assertTrue(it.state.value.items.isEmpty())
                assertFalse(it.navigateBack())
            }
        }
        compose.onNodeWithTag("base-author-12").assertIsDisplayed().assertIsSelected()
        assertEquals(2, configuration.profile.profiles.size)
        assertFalse(configuration.profile.profiles.contains(ProfileOwner.Self))
    }

    @Test fun authorFromRepliesSheetReturnsToTheSameSheetWithoutReadingRepliesAgain() {
        val configuration = show(599)
        compose.runOnIdle { configuration.forum.includeReplies = true }
        openAuthorPost(configuration)
        val viewReplies = compose.activity.getString(ForumR.string.forum_view_replies, 4)
        forumNode(hasText(viewReplies)).performClick()
        val sheetTitle = compose.activity.getString(ForumR.string.forum_replies)
        waitForText(sheetTitle)
        waitForTag("forum-replies-content")
        expandRepliesSheet()
        val author = hasTestTag("forum-author-fixture-reply-author-4")
        scrollNode("forum-replies-content", author).performTouchInput { click(center) }
        waitForDepth(3)
        waitForText("Synthetic profile fixture-reply-author-4")
        assertNoDisplayed(hasText(sheetTitle))

        // A physical tap must reach the new profile, not the covered sheet's separate Window.
        val refresh = compose.activity.getString(ProfileR.string.profile_refresh_profile)
        profileNode(hasText(refresh)).performTouchInput { click(center) }
        compose.waitUntil(5_000) { configuration.profile.profiles.size == 3 }
        assertEquals(listOf(ProfileOwner.User("fixture-author-a"),
            ProfileOwner.User("fixture-reply-author-4"), ProfileOwner.User("fixture-reply-author-4")),
            configuration.profile.profiles)
        val commentReads = configuration.forum.comments.toList()
        val replyReads = configuration.forum.replies.toList()
        assertEquals(listOf(3, 20), replyReads.map { it.limit })
        assertEquals(listOf(700, 700), replyReads.map { it.rootParentId })

        systemBackToDepth(2)
        waitForText(sheetTitle)
        waitForTag("forum-replies-content")
        expandRepliesSheet()
        scrollNode("forum-replies-content", hasText("Synthetic reply body 4")).assertIsDisplayed()
        assertEquals(commentReads, configuration.forum.comments)
        assertEquals(replyReads, configuration.forum.replies)
        displayedNode(hasText(compose.activity.getString(ForumR.string.forum_close)))
            .performTouchInput { click(center) }
        compose.waitUntil(5_000) { displayedNodeIds(hasText(sheetTitle)).isEmpty() }
        assertEquals(2, navigation().entries.value.size)
        displayedNode(hasTestTag("forum-detail-content")).assertIsDisplayed()
        assertEquals(listOf(42), configuration.forum.details)
        assertEquals(commentReads, configuration.forum.comments)
        assertEquals(replyReads, configuration.forum.replies)
    }

    @Test fun stoppedActivityRevocationClearsStoresBeforeReturningToResumed() {
        val configuration = show(840)
        openAuthorPostAuthor(configuration)
        lateinit var profiles: List<ProfileViewModel>
        lateinit var probes: List<ReadingProbeModel>
        compose.runOnIdle {
            val entries = navigation().entries.value
            profiles = entries.filter { it.destination is CommunityDestination.Profile }.map {
                ViewModelProvider(it)[ProfileViewModel::class.java]
            }
            probes = entries.map { entry -> ReadingProbeModel().also { entry.viewModelStore.put("background-probe", it) } }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
            // Simulates the runtime's continuously collected session callback, independent of UI lifecycle.
            configuration.access = CommunityReadingAccess()
            configuration.revision++
            configuration.navigation!!.synchronize(configuration.access, configuration.revision)
            assertTrue(configuration.navigation!!.entries.value.isEmpty())
            assertTrue(probes.all { it.cleared })
            profiles.forEach {
                assertNull(it.state.value.profile)
                assertTrue(it.state.value.items.isEmpty())
                assertFalse(it.navigateBack())
            }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("reading-base-list")
        compose.onNodeWithTag("community-reading-stack").assertDoesNotExist()
        compose.onNodeWithTag("base-author-12").assertIsDisplayed().assertIsSelected()
        assertTrue(navigation().entries.value.isEmpty())
        assertEquals(2, configuration.profile.profiles.size)
        assertEquals(listOf(42), configuration.forum.details)
        assertFalse(configuration.profile.profiles.contains(ProfileOwner.Self))
    }

    @Test fun explicitSelfRootReadsOnlySelfAndBackReturnsToTheSource() {
        val configuration = show(599)
        sourceNode(hasTestTag("base-self")).performClick()
        waitForText("Synthetic profile fixture-self")
        assertEquals(CommunityDestination.Profile(ProfileOwner.Self), navigation().entries.value.single().destination)
        assertEquals(listOf(ProfileOwner.Self), configuration.profile.profiles)
        assertTrue(configuration.profile.sections.isEmpty())
        systemBackToDepth(0)
        compose.onNodeWithTag("base-self").assertIsDisplayed()
        assertEquals(listOf(ProfileOwner.Self), configuration.profile.profiles)
    }

    @Test fun coveredLayersStopInputAndLifecycleWhileSystemBackClearsOnlyTheTopStore() {
        val configuration = show(599, generic = true)
        compose.onNodeWithTag("probe-push-base").performClick()
        waitForDepth(1)
        compose.onNodeWithTag("probe-push-layer-1").performClick()
        waitForDepth(2)
        compose.runOnIdle {
            assertEquals(Lifecycle.State.CREATED, configuration.owners.getValue("base").lifecycle.currentState)
            assertEquals(Lifecycle.State.CREATED, configuration.owners.getValue("layer-1").lifecycle.currentState)
            assertEquals(Lifecycle.State.RESUMED, configuration.owners.getValue("layer-2").lifecycle.currentState)
        }
        assertNoDisplayed(hasTestTag("probe-tap-base"))
        assertNoDisplayed(hasTestTag("probe-tap-layer-1"))
        compose.onNodeWithTag("probe-tap-layer-2").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(mapOf("layer-2" to 1), configuration.taps)
        }
        val first = configuration.probes.getValue("layer-1")
        val second = configuration.probes.getValue("layer-2")
        val secondLifecycle = configuration.owners.getValue("layer-2")
        systemBackToDepth(1)
        compose.runOnIdle {
            assertTrue(second.cleared)
            assertFalse(first.cleared)
            assertEquals(Lifecycle.State.DESTROYED, secondLifecycle.lifecycle.currentState)
            assertEquals(Lifecycle.State.RESUMED, configuration.owners.getValue("layer-1").lifecycle.currentState)
            assertEquals(0, configuration.backCalls["base"] ?: 0)
            assertEquals(0, configuration.backCalls["layer-1"] ?: 0)
        }
        systemBackToDepth(0)
        compose.runOnIdle {
            assertTrue(first.cleared)
            assertFalse(configuration.probes.getValue("base").cleared)
            assertEquals(Lifecycle.State.RESUMED, configuration.owners.getValue("base").lifecycle.currentState)
            assertEquals(0, configuration.backCalls["base"] ?: 0)
        }
        pressSystemBack()
        compose.runOnIdle { assertEquals(1, configuration.backCalls["base"]) }
    }

    private fun openAuthorPost(configuration: ReadingConfiguration): Pair<Float, Float> {
        // Make the retained-scroll assertion meaningful even on a tall tablet where row 12 fits.
        displayedNode(hasTestTag("reading-base-list")).performScrollToIndex(12)
        sourceNode(hasTestTag("base-author-12")).assertIsDisplayed()
        val sourceOffset = offset("reading-base-list")
        assertTrue(sourceOffset > 0f)
        compose.onNodeWithTag("base-author-12").performClick()
        waitForText("Synthetic profile fixture-author-a")
        selectProfilePosts(configuration)
        val postTag = "profile-entry-Post:42"
        profileNode(hasTestTag(postTag)).assertIsDisplayed()
        val open = hasText(compose.activity.getString(ProfileR.string.profile_open_content)) and
            hasAnyAncestor(hasTestTag(postTag))
        profileNode(open)
        val profileOffset = offset("profile-content")
        assertTrue(profileOffset > 0f)
        displayedNode(open).performClick()
        waitForDepth(2)
        waitForTag("forum-detail-content")
        assertEquals(12, configuration.sourceSelection)
        return sourceOffset to profileOffset
    }

    private fun openAuthorPostAuthor(configuration: ReadingConfiguration): ReadingSnapshot {
        val (sourceOffset, profileOffset) = openAuthorPost(configuration)
        forumNode(hasTestTag("forum-author-fixture-author-b")).assertIsDisplayed()
        val postOffset = offset("forum-detail-content")
        compose.onNodeWithTag("forum-author-fixture-author-b").performClick()
        waitForText("Synthetic profile fixture-author-b")
        waitForDepth(3)
        assertEquals(12, configuration.sourceSelection)
        return ReadingSnapshot(sourceOffset, profileOffset, postOffset)
    }

    private fun returnThroughAllLayers(configuration: ReadingConfiguration, snapshot: ReadingSnapshot) {
        systemBackToDepth(2)
        compose.onNodeWithTag("forum-author-fixture-author-b").assertIsDisplayed()
        assertEquals(snapshot.postOffset, offset("forum-detail-content"), 0.1f)
        systemBackToDepth(1)
        compose.onNodeWithTag("profile-entry-Post:42").assertIsDisplayed()
        assertEquals(snapshot.profileOffset, offset("profile-content"), 0.1f)
        compose.runOnIdle {
            val profile = ViewModelProvider(navigation().entries.value.single())[ProfileViewModel::class.java]
            assertEquals(ProfileSection.Posts, profile.state.value.section)
            assertFalse(profile.state.value.canNavigateBack)
        }
        systemBackToDepth(0)
        compose.onNodeWithTag("base-author-12").assertIsDisplayed().assertIsSelected()
        assertEquals(snapshot.sourceOffset, offset("reading-base-list"), 0.1f)
        assertEquals(12, configuration.sourceSelection)
    }

    private fun selectProfilePosts(configuration: ReadingConfiguration) {
        val section = hasTestTag("profile-section-Posts")
        if (configuration.width < 600) {
            profileNode(hasTestTag("profile-sections"))
            scrollNode("profile-sections", section).performClick()
        } else scrollNode("profile-navigation", section).performClick()
        compose.waitUntil(5_000) { configuration.profile.sections.isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertProfileLayout(width: Int) {
        if (width < 600) assertNoDisplayed(hasTestTag("profile-navigation"))
        else {
            val totalWidth = displayedNode(hasTestTag("community-reading-stack")).fetchSemanticsNode().boundsInRoot.width
            val sidebarWidth = displayedNode(hasTestTag("profile-navigation")).fetchSemanticsNode().boundsInRoot.width
            assertEquals((if (width < 840) 220f else 280f) * totalWidth / width, sidebarWidth, 1f)
        }
    }

    private fun show(width: Int, generic: Boolean = false, guildMessages: Boolean = false): ReadingConfiguration {
        val configuration = ReadingConfiguration(width, generic, guildMessages)
        compose.runOnIdle {
            CommunityReadingFixture.configuration = configuration
            SyntheticComposeTestContent.content = { ReadingTestContent() }
        }
        if (guildMessages) waitForText(compose.activity.getString(MessageR.string.message_comments))
        else waitForTag(if (generic) "probe-push-base" else "reading-base-list")
        return configuration
    }

    private fun sourceNode(matcher: SemanticsMatcher) = scrollNode("reading-base-list", matcher)
    private fun profileNode(matcher: SemanticsMatcher) = scrollNode("profile-content", matcher)
    private fun forumNode(matcher: SemanticsMatcher) = scrollNode("forum-detail-content", matcher)
    private fun expandRepliesSheet() {
        // Lazy-list semantic scrolling does not expand a partially expanded sheet. A real gesture
        // participates in nested scrolling and brings the complete list viewport onto the screen.
        displayedNode(hasTestTag("forum-replies-content")).performTouchInput { swipeUp() }
        compose.waitForIdle()
    }
    private fun scrollNode(tag: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val container = displayedNode(hasTestTag(tag))
        container.performScrollToNode(matcher)
        val containerId = container.fetchSemanticsNode().id
        // A child can still need a final scroll within its lazy item. Scope it to the displayed
        // container instead of selecting a same-tag child from a retained, unplaced reading layer.
        val target = compose.onNode(matcher and hasAnyAncestor(
            SemanticsMatcher("displayed scroll container $containerId") { it.id == containerId }))
        return target.performScrollTo().assertIsDisplayed()
    }
    private fun offset(tag: String) = displayedNode(hasTestTag(tag)).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun displayedNodeIds(matcher: SemanticsMatcher): List<Int> {
        return compose.onAllNodes(matcher).fetchSemanticsNodes().mapNotNull { node ->
            node.id.takeIf {
                compose.onNode(matcher and SemanticsMatcher("candidate node ${node.id}") { it.id == node.id })
                    .isDisplayed()
            }
        }
    }

    private fun displayedNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val ids = displayedNodeIds(matcher)
        assertEquals("Exactly one displayed node must match ${matcher.description}", 1, ids.size)
        val id = ids.single()
        return compose.onNode(matcher and SemanticsMatcher("displayed node $id") { it.id == id })
    }

    private fun assertNoDisplayed(matcher: SemanticsMatcher) {
        assertTrue("Covered content must not be displayed: ${matcher.description}", displayedNodeIds(matcher).isEmpty())
    }
    private fun navigation() = CommunityReadingFixture.configuration!!.navigation!!
    private fun waitForDepth(depth: Int) {
        compose.waitUntil(5_000) { navigation().entries.value.size == depth }
        compose.waitForIdle()
    }
    private fun systemBackToDepth(depth: Int) {
        compose.waitForIdle()
        pressSystemBack()
        waitForDepth(depth)
    }
    private fun pressSystemBack() {
        compose.waitForIdle()
        // Send a real system key to the foreground window instead of selecting a retained old root.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        displayedNodeIds(hasTestTag(tag)).isNotEmpty()
    }
    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        displayedNodeIds(hasText(text)).isNotEmpty()
    }
}

@Composable
private fun ReadingTestContent() {
    CommunityReadingFixture.configuration?.let { configuration ->
        val navigation: CommunityReadingNavigation = viewModel()
        SideEffect { configuration.navigation = navigation }
        LaunchedEffect(navigation, configuration.access, configuration.revision) {
            navigation.synchronize(configuration.access, configuration.revision)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth.toFloat() / configuration.width, 1f),
                LocalUriHandler provides configuration.uriHandler) {
                MaterialTheme {
                    if (configuration.generic) CommunityReadingStack(navigation,
                        { ReadingProbe("base", configuration, navigation) }) { entry ->
                        ReadingProbe("layer-${(entry.destination as CommunityDestination.Post).id}", configuration, navigation)
                    } else CommunityReadingHost(navigation, configuration.access, configuration.services) {
                        ReadingSource(configuration, navigation)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingSource(configuration: ReadingConfiguration, navigation: CommunityReadingNavigation) {
    if (configuration.guildMessages) {
        val model = viewModel<MessageViewModel>(factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MessageViewModel(configuration.message) as T
        })
        RisingStonesMessageScreen(model, {},
            onOpenTarget = { it.destination()?.let(navigation::open) },
            canOpenTarget = { it.destination()?.let(configuration.access::allows) == true })
        return
    }
    val scroll = rememberLazyListState()
    var selection by rememberSaveable { mutableStateOf(0) }
    val observedSelection = selection
    SideEffect { configuration.sourceSelection = observedSelection }
    LazyColumn(Modifier.fillMaxSize().testTag("reading-base-list"), state = scroll,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Button(onClick = { navigation.open(CommunityDestination.Profile(ProfileOwner.Self)) },
                modifier = Modifier.testTag("base-self")) { Text("Open synthetic self") }
        }
        items((1..30).toList()) { index ->
            Button(onClick = { selection = index; navigation.openAuthor("fixture-author-a") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("base-author-$index")
                    .semantics { selected = observedSelection == index }) {
                Text("Synthetic source row $index")
            }
        }
    }
}

@Composable
private fun ReadingProbe(tag: String, configuration: ReadingConfiguration, navigation: CommunityReadingNavigation) {
    val owner = LocalLifecycleOwner.current
    val model: ReadingProbeModel = viewModel(key = "probe-$tag", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadingProbeModel() as T
    })
    SideEffect { configuration.owners[tag] = owner; configuration.probes[tag] = model }
    BackHandler {
        configuration.backCalls[tag] = (configuration.backCalls[tag] ?: 0) + 1
        if (tag != "base") navigation.back()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { configuration.taps[tag] = (configuration.taps[tag] ?: 0) + 1 },
            modifier = Modifier.testTag("probe-tap-$tag")) { Text("Tap $tag") }
        Button(onClick = { navigation.open(CommunityDestination.Post(if (tag == "base") 1 else 2)) },
            modifier = Modifier.testTag("probe-push-$tag")) { Text("Push from $tag") }
    }
}

private object CommunityReadingFixture {
    var configuration by mutableStateOf<ReadingConfiguration?>(null)
}
private data class ReadingSnapshot(val sourceOffset: Float, val profileOffset: Float, val postOffset: Float)
private class ReadingConfiguration(width: Int, val generic: Boolean, val guildMessages: Boolean = false) {
    var width by mutableStateOf(width)
    var access by mutableStateOf(CommunityReadingAccess(profile = true, guild = guildMessages))
    var revision by mutableStateOf(0L)
    var navigation: CommunityReadingNavigation? = null
    var sourceSelection = 0
    val profile = ReadingProfileService()
    val forum = ReadingForumService()
    val guild = ReadingGuildService()
    val message = ReadingGuildMessageService()
    val services = CommunityReadingServices(profile, forum, unusedReadingService<DynamicService>(),
        unusedReadingService<GlamourService>(), unusedReadingService<DutyRecruitmentService>(), guild)
    val uriHandler = object : UriHandler {
        override fun openUri(uri: String) = error("No external URI is allowed in reading-stack fixtures")
    }
    val owners = mutableMapOf<String, LifecycleOwner>()
    val probes = mutableMapOf<String, ReadingProbeModel>()
    val taps = mutableMapOf<String, Int>()
    val backCalls = mutableMapOf<String, Int>()
}

private class ReadingGuildMessageService : MessageService {
    override val canRead = true
    val reads = CopyOnWriteArrayList<MessageQuery>()
    override suspend fun fetchUnreadSummary() = MessageUnreadSummary(0, 0, 1, 0, 0, emptyMap())
    override suspend fun readMessages(query: MessageQuery): MessagePage {
        reads += query
        return MessagePage(listOf(CommunityMessage("fixture-guild-photo", MessageCategory.Comments,
            "Synthetic commenter", "", "Synthetic photo message", "Synthetic comment", "",
            emptyList(), null, MessageTarget(MessageTargetKind.GuildPhoto, 42), null, null)), 1, false)
    }
}

private class ReadingGuildService : GuildService by unusedReadingService<GuildService>() {
    override val canRead = true
    val photosRead = CopyOnWriteArrayList<Int>()
    val commentsRead = CopyOnWriteArrayList<Int>()
    override suspend fun photo(id: Int): GuildPhotoDetail {
        photosRead += id
        return GuildPhotoDetail(id, GuildId("77"), "", "fixture-photo-author", "Synthetic photo author",
            "", "", null, 0, 0, 0, false)
    }
    override suspend fun comments(photoId: Int, page: Int, pageTime: String?): GuildPage<GuildPhotoComment> {
        commentsRead += photoId
        return GuildPage(emptyList(), page, false)
    }
}
private class ReadingProbeModel : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
}

private class ReadingProfileService : ProfileService {
    override val canRead = true
    val profiles = CopyOnWriteArrayList<ProfileOwner>()
    val sections = CopyOnWriteArrayList<ProfileListQuery>()
    override suspend fun fetchProfile(owner: ProfileOwner): CommunityProfile {
        profiles += owner
        val id = (owner as? ProfileOwner.User)?.uuid ?: "fixture-self"
        return CommunityProfile(id, "Synthetic profile $id", "", "", null, "Synthetic biography",
            owner == ProfileOwner.Self, 0, 0, 0)
    }
    override suspend fun readSection(query: ProfileListQuery): ProfilePage {
        sections += query
        check(query.section == ProfileSection.Posts) { "No implicit relation reads are allowed" }
        return ProfilePage((31..60).map { id -> ProfileListItem.Content(
            ProfileContentTarget(ProfileContentKind.Post, id), "Synthetic profile post $id", "Synthetic summary", emptyList(), null)
        }, 1, false)
    }
}

private class ReadingForumService : OfficialForumService {
    override val canPerformAuthenticatedWrites = false
    val details = CopyOnWriteArrayList<Int>()
    val comments = CopyOnWriteArrayList<OfficialForumCommentQuery>()
    val replies = CopyOnWriteArrayList<OfficialForumSubCommentQuery>()
    var includeReplies = false
    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail {
        details += id
        val body = "Synthetic forum body ".repeat(80)
        return OfficialForumPostDetail(id, "Synthetic forum post $id", "", body,
            listOf(OfficialForumRichTextSegment.Text(body)),
            listOf(OfficialForumPostBodyBlock.Paragraph(listOf(OfficialForumRichTextSegment.Text(body)))),
            emptyList(), emptyList(), OfficialForumAuthor("fixture-author-b", "Synthetic forum author", "", "", null, 0),
            OfficialForumPart(1, "Synthetic category", ""), null, null, null,
            if (includeReplies) 1 else 0, 0, 0, null, null, 0, null, false, false)
    }
    override suspend fun fetchComments(query: OfficialForumCommentQuery): OfficialForumPage<OfficialForumComment> {
        comments += query
        return OfficialForumPage(if (includeReplies) listOf(comment(700, "fixture-root-comment", "Synthetic root comment", 4))
            else emptyList(), if (includeReplies) 1 else 0, query.page)
    }
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery): OfficialForumPage<OfficialForumComment> {
        check(includeReplies) { "Unused synthetic subcomments" }
        replies += query
        return OfficialForumPage((1..4).take(query.limit).map { index ->
            comment(700 + index, "fixture-reply-author-$index", "Synthetic reply body $index", 0)
        }, 4, query.page)
    }
    private fun comment(id: Int, author: String, body: String, childCount: Int) = OfficialForumComment(id,
        OfficialForumAuthor(author, "Synthetic $author", "", "", null, 0), null, body,
        listOf(OfficialForumRichTextSegment.Text(body)), emptyList(), null, null, 0, false, childCount,
        isPostAuthor = false, isMine = false)
    override suspend fun fetchParts() = error("Unused synthetic parts")
    override suspend fun fetchPosts(query: OfficialForumListQuery) = error("Unused synthetic posts list")
    override suspend fun searchPosts(query: OfficialForumSearchQuery) = error("Unused synthetic search")
    override suspend fun likePost(id: Int) = error("No fixture writes")
    override suspend fun likeComment(id: Int) = error("No fixture writes")
    override suspend fun starPost(id: Int) = error("No fixture writes")
    override suspend fun submitComment(draft: OfficialForumCommentDraft) = error("No fixture writes")
    override suspend fun deleteComment(id: Int) = error("No fixture writes")
    override suspend fun submitVote(draft: OfficialForumVoteDraft) = error("No fixture writes")
}

/** Unused destinations fail immediately if a regression attempts to invoke their services. */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T : Any> unusedReadingService(): T = Proxy.newProxyInstance(
    T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, arguments ->
    when (method.name) {
        "equals" -> proxy === arguments?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "Unused synthetic service"
        else -> error("Unexpected service call in reading-stack fixture: ${method.name}")
    }
} as T
