package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.*

class GlamourAuthorNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun communityProfileIsIndependentOfBodyAndExistingAuthorWorks() {
        val fixture = AuthorGlamourFixture()
        val authors = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesGlamourAuthorNavigation({ authors += it }) { RisingStonesGlamourScreen(fixture, {}) }
        } } }
        waitFor("Glamour 1")
        author().performScrollTo().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("glamour-uuid"), authors)
            assertEquals(0, fixture.detailReads)
            assertTrue(fixture.requests.all { it.authorId == null })
            assertEquals(0, fixture.writes)
        }
        compose.onNodeWithText("Hero · Area · World").performScrollTo().performClick()
        waitFor("Author’s glamours")
        compose.runOnIdle { assertEquals("glamour-uuid", fixture.requests.last().authorId) }
        compose.onNodeWithText("Back").performClick()
        waitFor("Glamour 1")
        compose.onNodeWithText("Glamour 1").performScrollTo().performClick()
        waitFor("Detail description")
        author().performClick()
        compose.onNodeWithText("Detail description").assertHasNoClickAction()
        compose.runOnIdle { assertEquals(listOf("glamour-uuid", "glamour-uuid"), authors); assertEquals(0, fixture.writes) }
    }

    @Test fun standaloneDetailOffersCommunityProfileWithoutReadingList() {
        val fixture = AuthorGlamourFixture()
        var opened: String? = null
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesGlamourAuthorNavigation({ opened = it }) { RisingStonesGlamourDetailScreen(fixture, 73, {}) }
        } } }
        waitFor("Detail description")
        author().performClick()
        compose.runOnIdle {
            assertEquals("glamour-uuid", opened)
            assertEquals(1, fixture.detailReads)
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.writes)
        }
        compose.onNodeWithText("Hero · Area · World").assertHasClickAction()
    }

    @Test fun absentCallbackKeepsExistingWorksButHidesCommunityNavigation() {
        compose.setContent { MaterialTheme {
            RisingStonesGlamourAuthorNavigation(null) { RisingStonesGlamourDetailScreen(AuthorGlamourFixture(), 73, {}) }
        } }
        waitFor("Detail description")
        compose.onNodeWithText("Community profile").assertDoesNotExist()
        compose.onNodeWithText("Hero · Area · World").assertHasClickAction()
    }

    @Test fun blankCommunityIdentityNeverOffersProfileNavigation() {
        compose.setContent { MaterialTheme {
            RisingStonesGlamourAuthorNavigation({ error("blank identity") }) {
                RisingStonesGlamourDetailScreen(AuthorGlamourFixture(uuid = ""), 73, {})
            }
        } }
        waitFor("Detail description")
        compose.onNodeWithText("Community profile").assertDoesNotExist()
    }

    @Test fun listScrollSurvivesAllWidthBoundaries() = preservesScroll(detail = false)
    @Test fun detailScrollSurvivesAllWidthBoundaries() = preservesScroll(detail = true)

    private fun preservesScroll(detail: Boolean) {
        val width = mutableIntStateOf(599)
        val fixture = AuthorGlamourFixture(count = 40)
        compose.setContent { MaterialTheme { Box(Modifier.width(width.intValue.dp).height(1000.dp)) {
            RisingStonesGlamourAuthorNavigation({}) { RisingStonesGlamourScreen(fixture, {}) }
        } } }
        waitFor("Glamour 1")
        if (detail) { compose.onNodeWithText("Glamour 1").performClick(); waitFor("Detail description") }
        val tag = if (detail) "glamour-detail-content" else "glamour-list-content"
        compose.onNodeWithTag(tag).performScrollToIndex(12)
        val first = scrollIndex(tag)
        assertTrue(first >= 12)
        listOf(600, 839, 840, 599).forEach { breakpoint ->
            compose.runOnIdle { width.intValue = breakpoint }
            assertEquals("width $breakpoint", first, scrollIndex(tag))
        }
        assertEquals(1, fixture.requests.size)
        assertEquals(if (detail) 1 else 0, fixture.detailReads)
    }

    private fun scrollIndex(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value().toInt()
    private fun author() = compose.onNodeWithTag("glamour-community-author-glamour-uuid")
    private fun waitFor(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

private class AuthorGlamourFixture(private val uuid: String = "glamour-uuid", private val count: Int = 1) :
    GlamourService by FakeGlamourService {
    val requests = mutableListOf<GlamourListRequest>()
    var detailReads = 0
    var writes = 0
    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        requests += request
        val source = FakeGlamourService.fetchGlamours(request).items.single()
        return GlamourListPage((1..count).map { source.copy(id = it, title = "Glamour $it", author = source.author.copy(id = uuid)) }, 1, false)
    }
    override suspend fun fetchDetail(id: Int): GlamourDetail {
        detailReads++
        val source = FakeGlamourService.fetchDetail(id)
        return source.copy(author = source.author.copy(id = uuid), equipments = if (count > 1) (1..40).map {
            GlamourEquipment("Slot $it", it, "Equipment $it", null, emptyList(), emptyList())
        } else emptyList())
    }
    override suspend fun fetchAuthorProfile(authorId: String) = FakeGlamourService.fetchAuthorProfile(authorId).let {
        it.copy(author = it.author.copy(id = uuid))
    }
    override suspend fun toggleLike(id: Int): Boolean { writes++; return true }
    override suspend fun favorite(id: Int) { writes++ }
}
