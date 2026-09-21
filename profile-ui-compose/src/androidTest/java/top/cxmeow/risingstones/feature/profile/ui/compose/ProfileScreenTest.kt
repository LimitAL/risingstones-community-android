package top.cxmeow.risingstones.feature.profile.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.profile.presentation.ProfileViewModel

class ProfileScreenTest {
    @get:Rule val rule = createComposeRule()
    @Test fun compactProfileAndPersonNavigation() = atWidth(599)
    @Test fun mediumProfileAndPersonNavigation() = atWidth(600)
    @Test fun upperMediumProfileAndPersonNavigation() = atWidth(839)
    @Test fun expandedProfileAndPersonNavigation() = atWidth(840)

    @Test fun resizingRetainsOwnerAndSectionWithoutReReadingFollowers() {
        val width = mutableStateOf(599)
        val service = Service()
        val model = ProfileViewModel(service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(width.value.dp).height(1000.dp)) { RisingStonesProfileScreen(model, {}) }
        } }
        waitFor("Followers 1")
        rule.onNodeWithText("Followers 1").performClick()
        waitFor("Fixture related person")
        rule.runOnIdle { width.value = 840 }
        rule.onNodeWithText("Fixture related person").assertExists()
        assertEquals(ProfileSection.Followers, model.state.value.section)
        assertEquals(1, service.reads.size)
        assertEquals(1, service.profileReads)
    }

    @Test fun contentNavigatesThroughTypedCallback() {
        val service = Service()
        val model = ProfileViewModel(service)
        var target: ProfileContentTarget? = null
        rule.setContent { MaterialTheme {
            Box(Modifier.width(599.dp).height(1000.dp)) {
                RisingStonesProfileScreen(model, {}, onOpenContent = { target = it })
            }
        } }
        waitFor("Fixture self")
        rule.onNodeWithText("Posts").performClick()
        waitFor("Fixture post")
        rule.onNodeWithText("Open detail").performScrollTo().performClick()
        assertEquals(ProfileContentTarget(ProfileContentKind.Post, 42), target)
        assertEquals(1, service.reads.size)
    }

    @Test fun unverifiedContentTargetHasNoOpenAction() {
        val service = Service()
        val model = ProfileViewModel(service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(599.dp).height(1000.dp)) {
                RisingStonesProfileScreen(model, {}, onOpenContent = { error("Unavailable target") }, canOpenContent = { false })
            }
        } }
        waitFor("Fixture self")
        rule.onNodeWithText("Posts").performClick()
        waitFor("Fixture post")
        rule.onNodeWithText("Open detail").assertDoesNotExist()
    }

    private fun atWidth(width: Int) {
        val service = Service()
        val model = ProfileViewModel(service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(width.dp).height(1000.dp)) { RisingStonesProfileScreen(model, {}) }
        } }
        waitFor("Fixture self")
        assertTrue(service.reads.isEmpty())
        rule.onNodeWithText("Following 1").performClick()
        waitFor("Fixture related person")
        rule.onNodeWithText("Fixture related person").performScrollTo().performClick()
        waitFor("Fixture other")
        assertEquals(ProfileOwner.User("other"), model.state.value.owner)
        assertTrue(model.state.value.items.isEmpty())
        rule.onNodeWithText("Back").performClick()
        waitFor("Fixture related person")
        assertEquals(ProfileOwner.Self, model.state.value.owner)
        assertEquals(ProfileSection.Following, model.state.value.section)
        assertEquals(1, service.reads.size)
    }
    private fun waitFor(text: String) {
        rule.waitUntil(5000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

private class Service : ProfileService {
    override val canRead = true
    var profileReads = 0
    val reads = mutableListOf<ProfileListQuery>()
    override suspend fun fetchProfile(owner: ProfileOwner): CommunityProfile {
        profileReads++
        val id = (owner as? ProfileOwner.User)?.uuid ?: "self"
        return CommunityProfile(id, "Fixture $id", "Area", "World", null, "Fixture biography", owner == ProfileOwner.Self, 1, 1, 0)
    }
    override suspend fun readSection(query: ProfileListQuery): ProfilePage {
        reads += query
        val item = if (query.section in listOf(ProfileSection.Followers, ProfileSection.Following))
            ProfileListItem.Person("other", "Fixture related person", "Area", "World", null, "Bio")
        else ProfileListItem.Content(ProfileContentTarget(ProfileContentKind.Post, 42), "Fixture post", "Body", emptyList(), null)
        return ProfilePage(listOf(item), 1, false)
    }
}
