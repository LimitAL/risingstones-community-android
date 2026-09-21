package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.*

class GlamourBrowsingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactFollowingAndSelfWorks() = followingAndSelfWorks(599)
    @Test fun mediumFollowingAndSelfWorks() = followingAndSelfWorks(600)
    @Test fun upperMediumFollowingAndSelfWorks() = followingAndSelfWorks(839)
    @Test fun expandedFollowingAndSelfWorks() = followingAndSelfWorks(840)

    private fun followingAndSelfWorks(width: Int) {
        val service = BrowsingUiFixture()
        show(service, width)
        waitFor("Glamour Test")
        compose.onNodeWithText("Following").performClick()
        compose.waitUntil { service.requests.lastOrNull()?.following == true }
        waitFor("Followed glamour")
        compose.onNodeWithText("Search title").assertDoesNotExist()
        compose.onNodeWithText("Load more").performScrollTo().performClick()
        compose.waitUntil { service.requests.lastOrNull()?.listing?.page == 2 }
        assertEquals("next-fixture-page", service.requests.last().pageTime)
        compose.onNodeWithText("My works").performScrollTo().performClick()
        waitFor("No glamours were found.")
        compose.onNodeWithText("1 works · 2 likes · 3 favorites").assertExists()
        assertEquals(GlamourListSource.Profile, service.requests.last().listing.source)
        assertFalse(service.requests.last().following)
        compose.onNodeWithText("Community").performScrollTo().performClick()
        waitFor("Glamour Test")
        assertFalse(service.requests.last().following)
        assertNull(service.requests.last().pageTime)
    }

    @Test fun filtersUseOneTagPerCategoryAndMatchingTribe() {
        val service = BrowsingUiFixture()
        show(service, 599)
        waitFor("Glamour Test")
        compose.onNodeWithText("Filters").performScrollTo().performClick()
        waitFor("Fixture race")
        compose.onNodeWithText("Fixture race").performScrollTo().performClick()
        compose.onNodeWithText("Fixture tribe").performScrollTo().performClick()
        compose.onNodeWithText("Female").performScrollTo().performClick()
        compose.onNodeWithText("Last week").performScrollTo().performClick()
        compose.onNodeWithText("Fixture tag A").performScrollTo().performClick()
        compose.onNodeWithText("Fixture tag B").performScrollTo().performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil { service.requests.lastOrNull()?.tagIds == setOf(11) }
        val request = service.requests.last()
        assertEquals(1, request.listing.filter.raceId)
        assertEquals(2, request.tribeId)
        assertEquals(2, request.listing.filter.genderId)
        assertEquals("lastWeek", request.listing.filter.createTime)
        assertEquals(GlamourListOrder.Latest, request.listing.filter.order)
        val count = service.requests.size
        compose.onNodeWithText("Filters (5)").performScrollTo().performClick()
        compose.onNodeWithText("Reset filters").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(count, service.requests.size) }
    }

    @Test fun authorWorksAndFavoritesReturnToSelectedGlamour() {
        val service = BrowsingUiFixture()
        show(service, 599)
        waitFor("Glamour Test")
        compose.onNodeWithText("Glamour Test").performClick()
        waitFor("Detail description")
        compose.onNodeWithText("Hero · Area · World").performClick()
        waitFor("Author’s glamours")
        compose.waitUntil { service.requests.lastOrNull()?.listing?.authorId == "author" }
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.waitUntil { service.requests.lastOrNull()?.listing?.source == GlamourListSource.Favorites }
        assertEquals("author", service.requests.last().listing.authorId)
        compose.onNodeWithText("Back").performClick()
        waitFor("Detail description")
        assertEquals(0, service.writes)
    }

    @Test fun compactFailedDetailBackReturnsToListWithoutLeavingFeature() {
        var leftFeature = false
        val service = BrowsingUiFixture(failDetail = true)
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesGlamourScreen(service, { leftFeature = true })
        } } }
        waitFor("Glamour Test")
        compose.onNodeWithText("Glamour Test").performClick()
        waitFor("The glamour service is unavailable.")
        compose.onNodeWithText("Back").performClick()
        waitFor("Glamour Test")
        assertFalse(leftFeature)
    }

    @Test fun windowChangesKeepFollowingSelectionAndDetail() {
        val service = BrowsingUiFixture()
        val width = mutableIntStateOf(599)
        compose.setContent { MaterialTheme { Box(Modifier.width(width.intValue.dp).height(1000.dp)) {
            RisingStonesGlamourScreen(service, {})
        } } }
        waitFor("Glamour Test")
        compose.onNodeWithText("Following").performClick()
        waitFor("Followed glamour")
        compose.onNodeWithText("Followed glamour").performClick()
        waitFor("Detail description")
        compose.onNodeWithTag("glamour-list-pane").assertDoesNotExist()
        val count = service.requests.size
        listOf(600, 839, 840).forEach { breakpoint ->
            compose.runOnIdle { width.intValue = breakpoint }
            compose.onNodeWithTag("glamour-list-pane").assertExists()
            compose.onNodeWithText("Detail description").assertExists()
            compose.runOnIdle { assertEquals(count, service.requests.size) }
        }
        compose.runOnIdle { width.intValue = 599 }
        compose.onNodeWithTag("glamour-list-pane").assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        waitFor("Followed glamour")
        assertTrue(service.requests.last().following)
    }

    private fun show(service: GlamourService, width: Int) {
        compose.setContent { MaterialTheme { Box(Modifier.width(width.dp).height(1000.dp)) {
            RisingStonesGlamourScreen(service, {})
        } } }
    }

    private fun waitFor(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

private class BrowsingUiFixture(private val failDetail: Boolean = false) :
    GlamourBrowsingService, GlamourService by FakeGlamourService {
    val requests = mutableListOf<GlamourBrowseRequest>()
    var writes = 0
    override suspend fun fetchBrowsePage(request: GlamourBrowseRequest): GlamourBrowsePage {
        requests += request
        val page = FakeGlamourService.fetchGlamours(request.listing)
        return when {
            request.listing.page > 1 || request.listing.source == GlamourListSource.Profile ->
                GlamourBrowsePage(GlamourListPage(emptyList(), request.listing.page, false), null)
            request.following -> GlamourBrowsePage(page.copy(
                items = page.items.map { it.copy(title = "Followed glamour") }, hasNextPage = true), "next-fixture-page")
            else -> GlamourBrowsePage(page, null)
        }
    }
    override suspend fun fetchTagCategories() = listOf(
        GlamourTagCategory(1, "Fixture category", listOf(GlamourTag(10, "Fixture tag A"), GlamourTag(11, "Fixture tag B"))))
    override suspend fun fetchTribes() = listOf(GlamourTribe(2, 1, "Fixture tribe"))
    override suspend fun fetchRaces() = listOf(GlamourRace(1, "Fixture race"))
    override suspend fun fetchDetail(id: Int): GlamourDetail {
        if (failDetail) throw GlamourException.MissingPayload
        return FakeGlamourService.fetchDetail(id)
    }
    override suspend fun followAuthor(authorId: String) { writes++ }
    override suspend fun cancelFollowAuthor(authorId: String) { writes++ }
}
