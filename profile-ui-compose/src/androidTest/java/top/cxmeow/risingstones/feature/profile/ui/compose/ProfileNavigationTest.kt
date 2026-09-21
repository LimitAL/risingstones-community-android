package top.cxmeow.risingstones.feature.profile.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.profile.presentation.ProfileLoadStatus
import top.cxmeow.risingstones.feature.profile.presentation.ProfileViewModel

/** Uses only synthetic profiles and an Activity-owned ViewModel, including across recreation. */
class ProfileNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ProfileNavigationTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle { ProfileNavigationFixture.configuration = null }
    }

    @Test fun externalRootAndPersonReturnAt599() = rootAndPersonReturn(599)
    @Test fun externalRootAndPersonReturnAt600() = rootAndPersonReturn(600)
    @Test fun externalRootAndPersonReturnAt839() = rootAndPersonReturn(839)
    @Test fun externalRootAndPersonReturnAt840() = rootAndPersonReturn(840)

    private fun rootAndPersonReturn(width: Int) {
        val configuration = show(width)
        selectSection(ProfileSection.Following)
        val target = "profile-person-fixture-person-12"
        contentNode(hasTestTag(target)).assertIsDisplayed()
        val parentAnchor = visibleContentAnchor()
        assertNotEquals("The relation list must be scrolled before opening a person",
            "profile-person-fixture-person-1", parentAnchor.tag)
        compose.onNodeWithTag(target).performClick()
        // Clicking does not scroll the parent; its retained state must restore the same visible row.
        waitForOwner("fixture-person-12")
        compose.onNodeWithTag("profile-back").performClick()
        waitForOwner("fixture-root")
        compose.onNodeWithTag(target).assertIsDisplayed()
        assertContentAnchor(parentAnchor)

        compose.runOnIdle { configuration.width = if (width < 600) 600 else 599 }
        compose.onNodeWithTag(target).assertExists()
        compose.runOnIdle { configuration.width = width }
        compose.onNodeWithTag(target).assertIsDisplayed()
        assertContentAnchor(parentAnchor)

        compose.onNodeWithTag(target).performClick()
        waitForOwner("fixture-person-12")
        compose.onNodeWithTag("profile-back").performClick()
        waitForOwner("fixture-root")
        assertContentAnchor(parentAnchor)
        compose.onNodeWithTag(target).assertIsDisplayed()
        assertEquals(1, configuration.service.reads.size)
        assertFalse(configuration.model!!.state.value.canNavigateBack)
        compose.onNodeWithTag("profile-back").performClick()
        compose.runOnIdle { assertEquals(1, configuration.backCalls) }
        assertFalse(configuration.service.profiles.contains(ProfileOwner.Self))
    }

    @Test fun sectionsRestoreTheirOwnScrollAndDataWithoutReadingFollowersAgain() {
        val configuration = show(840)
        selectSection(ProfileSection.Posts)
        contentNode(hasTestTag("profile-entry-Post:12")).assertIsDisplayed()
        val postsAnchor = visibleContentAnchor()
        selectSection(ProfileSection.Followers)
        contentNode(hasTestTag("profile-person-fixture-person-18")).assertIsDisplayed()
        val followersAnchor = visibleContentAnchor()
        assertNotEquals("profile-entry-Post:1", postsAnchor.tag)
        assertNotEquals("profile-person-fixture-person-1", followersAnchor.tag)

        selectSection(ProfileSection.Posts)
        compose.onNodeWithTag("profile-entry-Post:12").assertIsDisplayed()
        assertContentAnchor(postsAnchor)
        selectSection(ProfileSection.Followers)
        compose.onNodeWithTag("profile-person-fixture-person-18").assertIsDisplayed()
        assertContentAnchor(followersAnchor)
        assertEquals(listOf(ProfileSection.Posts, ProfileSection.Followers),
            configuration.service.reads.map { it.section })
    }

    @Test fun recreationKeepsNestedOwnerAndBothScrollsWithoutReReadingFollowers() {
        val configuration = show(599)
        selectSection(ProfileSection.Followers)
        val parentTag = "profile-person-fixture-person-12"
        contentNode(hasTestTag(parentTag)).assertIsDisplayed()
        val parentAnchor = visibleContentAnchor()
        compose.onNodeWithTag(parentTag).performClick()
        waitForOwner("fixture-person-12")
        selectSection(ProfileSection.Posts)
        contentNode(hasTestTag("profile-entry-Post:15")).assertIsDisplayed()
        val childAnchor = visibleContentAnchor()
        val originalModel = configuration.model

        compose.activityRule.scenario.recreate()
        waitForOwner("fixture-person-12")
        compose.onNodeWithTag("profile-entry-Post:15").assertIsDisplayed()
        assertContentAnchor(childAnchor)
        assertSame(originalModel, configuration.model)
        compose.onNodeWithTag("profile-back").performClick()
        waitForOwner("fixture-root")
        compose.onNodeWithTag(parentTag).assertIsDisplayed()
        assertContentAnchor(parentAnchor)
        assertEquals(listOf(ProfileSection.Followers, ProfileSection.Posts),
            configuration.service.reads.map { it.section })
        assertEquals(listOf(ProfileOwner.User("fixture-root"), ProfileOwner.User("fixture-person-12")),
            configuration.service.profiles)
    }

    private fun show(width: Int): ProfileNavigationConfiguration {
        val configuration = ProfileNavigationConfiguration(width)
        compose.runOnIdle { ProfileNavigationFixture.configuration = configuration }
        waitForOwner("fixture-root")
        return configuration
    }

    private fun waitForOwner(uuid: String) {
        compose.waitUntil(5_000) {
            ProfileNavigationFixture.configuration?.model?.state?.value?.let {
                it.owner == ProfileOwner.User(uuid) && it.profileStatus == ProfileLoadStatus.Loaded
            } == true
        }
        compose.waitForIdle()
    }

    private fun selectSection(section: ProfileSection) {
        val tag = "profile-section-${section.name}"
        val configuration = ProfileNavigationFixture.configuration!!
        if (configuration.width < 600) {
            compose.onNodeWithTag("profile-content").performScrollToNode(hasTestTag("profile-sections"))
            compose.onNodeWithTag("profile-sections").performScrollToNode(hasTestTag(tag))
        } else {
            compose.onNodeWithTag("profile-navigation").performScrollToNode(hasTestTag(tag))
        }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(5_000) { configuration.model!!.state.value.let {
            it.section == section && it.listStatus == ProfileLoadStatus.Loaded
        } }
        compose.waitForIdle()
    }

    private fun contentNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("profile-content").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun assertContentAnchor(expected: VisibleProfileAnchor) {
        val actual = visibleContentAnchor()
        assertEquals("The first visible profile item must be retained", expected.tag, actual.tag)
        assertEquals("The visible item must retain its offset within the list viewport",
            expected.offsetPixels, actual.offsetPixels, 1f)
    }

    private fun visibleContentAnchor(): VisibleProfileAnchor {
        val viewport = compose.onNodeWithTag("profile-content").fetchSemanticsNode().boundsInRoot
        val rows = SemanticsMatcher("profile business row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let {
                it.startsWith("profile-person-") || it.startsWith("profile-entry-")
            } == true
        }
        val first = compose.onAllNodes(rows).fetchSemanticsNodes()
            // Lazy precomposition can leave an attached node with stale, non-empty bounds.
            // isDisplayed also checks placement of the node and every ancestor before clipping.
            .filter { compose.onNodeWithTag(it.config[SemanticsProperties.TestTag]).isDisplayed() }
            .filter { it.boundsInRoot.height > 0f && it.boundsInRoot.bottom > viewport.top &&
                it.boundsInRoot.top < viewport.bottom }
            .minByOrNull { it.positionInRoot.y }
            ?: error("No profile row is visible in the list viewport")
        // ScrollAxisRange is an accessibility estimate (index * 500 + offset), not a pixel position.
        // Raw positionInRoot preserves a partially clipped item's real top instead of clamping to 0.
        return VisibleProfileAnchor(first.config[SemanticsProperties.TestTag], first.positionInRoot.y - viewport.top)
    }
}

private data class VisibleProfileAnchor(val tag: String, val offsetPixels: Float)

class ProfileNavigationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProfileNavigationFixture.configuration?.let { configuration ->
                val model: ProfileViewModel = viewModel(factory = remember(configuration.service) {
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ProfileViewModel(configuration.service).apply {
                                openRoot(ProfileOwner.User("fixture-root"))
                            } as T
                    }
                })
                SideEffect { configuration.model = model }
                LaunchedEffect(model) { model.openRoot(ProfileOwner.User("fixture-root")) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDensity provides
                        Density(constraints.maxWidth.toFloat() / configuration.width, 1f)) {
                        MaterialTheme {
                            RisingStonesProfileScreen(model, { configuration.backCalls++ })
                        }
                    }
                }
            }
        }
    }
}

private object ProfileNavigationFixture {
    var configuration by mutableStateOf<ProfileNavigationConfiguration?>(null)
}

private class ProfileNavigationConfiguration(width: Int) {
    var width by mutableStateOf(width)
    val service = ProfileNavigationService()
    var model: ProfileViewModel? = null
    var backCalls = 0
}

private class ProfileNavigationService : ProfileService {
    override val canRead = true
    val profiles = CopyOnWriteArrayList<ProfileOwner>()
    val reads = CopyOnWriteArrayList<ProfileListQuery>()

    override suspend fun fetchProfile(owner: ProfileOwner): CommunityProfile {
        profiles += owner
        val id = (owner as? ProfileOwner.User)?.uuid ?: "fixture-self"
        return CommunityProfile(id, "Synthetic $id", "Fixture area", "Fixture world", null,
            "Synthetic profile biography", owner == ProfileOwner.Self, 30, 30, 0)
    }

    override suspend fun readSection(query: ProfileListQuery): ProfilePage {
        reads += query
        val items = (1..30).map { index ->
            if (query.section in listOf(ProfileSection.Following, ProfileSection.Followers)) {
                ProfileListItem.Person("fixture-person-$index", "Synthetic person $index",
                    "Fixture area", "Fixture world", null, "Synthetic person biography $index")
            } else {
                ProfileListItem.Content(ProfileContentTarget(ProfileContentKind.Post, index),
                    "Synthetic post $index", "Synthetic body $index", emptyList(), null)
            }
        }
        return ProfilePage(items, query.page, false)
    }
}
