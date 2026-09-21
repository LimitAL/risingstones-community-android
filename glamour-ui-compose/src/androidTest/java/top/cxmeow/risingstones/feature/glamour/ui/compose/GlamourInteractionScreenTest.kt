package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.*

class GlamourInteractionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactCandidateSelectionUsesEquipmentId() = chooseEquipment(599)
    @Test fun mediumCandidateSelectionUsesEquipmentId() = chooseEquipment(600)
    @Test fun upperMediumCandidateSelectionUsesEquipmentId() = chooseEquipment(839)
    @Test fun expandedCandidateSelectionUsesEquipmentId() = chooseEquipment(840)

    private fun chooseEquipment(width: Int) {
        val service = InteractionUiFixture()
        show(service, width)
        waitFor("Glamour Test")
        compose.onNodeWithText("Find by item").performClick()
        assertTrue(service.candidateQueries.isEmpty())
        compose.onNodeWithText("Item name").performTextInput("  robe  ")
        compose.onNodeWithTag("glamour-candidate-submit").performClick()
        waitFor("Fixture robe")
        compose.onNodeWithText("Fixture robe").performClick()
        compose.waitUntil { service.requests.lastOrNull()?.search?.searchByEquipment == true }
        assertEquals("51", service.requests.last().search?.keywords)
        assertEquals("Fixture robe", service.requests.last().search?.displayTitle)
        assertEquals(listOf("robe"), service.candidateQueries)
        assertEquals(0, service.writes.size)
    }

    @Test fun facewearSelectionKeepsGroupAndUsesGlassesId() {
        val service = InteractionUiFixture()
        show(service, 599)
        waitFor("Glamour Test")
        compose.onNodeWithText("Find by item").performClick()
        compose.onNodeWithText("Facewear").performClick()
        compose.onNodeWithText("Item name").performTextInput("glasses")
        compose.onNodeWithTag("glamour-candidate-submit").performClick()
        waitFor("Fixture style")
        compose.onNodeWithText("Fixture glasses").performClick()
        compose.waitUntil { service.requests.lastOrNull()?.search?.searchByGlasses == true }
        assertEquals("52", service.requests.last().search?.keywords)
    }

    @Test fun cancelCandidateSearchLeavesTheListingQueryUnchanged() {
        val service = InteractionUiFixture()
        show(service, 599)
        waitFor("Glamour Test")
        val requestCount = service.requests.size
        compose.onNodeWithText("Find by item").performClick()
        compose.onNodeWithText("Item name").performTextInput("unused")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(requestCount, service.requests.size)
            assertTrue(service.candidateQueries.isEmpty())
        }
    }

    @Test fun favoritePickerRequiresExplicitFolderAndConfirmation() {
        val service = InteractionUiFixture()
        showDetail(service)
        compose.onNodeWithText("Favorite (1)").performScrollTo().performClick()
        waitFor("Choose a favorite folder")
        assertTrue(service.writes.isEmpty())
        compose.onNodeWithText("Save favorite").assertIsNotEnabled()
        compose.onNodeWithText("Wardrobe").performClick()
        assertTrue(service.writes.isEmpty())
        compose.onNodeWithText("Save favorite").performClick()
        waitFor("Remove favorite (2)")
        assertEquals(listOf("favorite:42:2"), service.writes)
    }

    @Test fun cancelFavoritePickerDoesNotWrite() {
        val service = InteractionUiFixture()
        showDetail(service)
        compose.onNodeWithText("Favorite (1)").performScrollTo().performClick()
        waitFor("Wardrobe")
        compose.onNodeWithText("Wardrobe").performClick()
        compose.onNodeWithText("Cancel").performClick()
        waitFor("Favorite (1)")
        assertTrue(service.writes.isEmpty())
    }

    @Test fun folderCreateEditAndDeleteRespectDefaultFolderAndConfirmation() {
        val service = InteractionUiFixture()
        show(service, 840)
        waitFor("Glamour Test")
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        waitFor("Manage folders")
        compose.onNodeWithText("Manage folders").performClick()
        waitFor("Default folder")
        assertEquals(1, compose.onAllNodesWithText("Delete folder").fetchSemanticsNodes().size)
        compose.onNodeWithText("New folder").performClick()
        compose.onNodeWithText("Folder name").performTextInput("  New looks  ")
        compose.onNodeWithTag("glamour-folder-public").performClick()
        compose.onNodeWithText("Save").performClick()
        waitFor("New looks")
        assertEquals("create:New looks:false", service.writes.single())
        compose.onNodeWithTag("glamour-folder-edit-1").performClick()
        compose.onNodeWithText("Folder name").performTextClearance()
        compose.onNodeWithText("Folder name").performTextInput("Renamed default")
        compose.onNodeWithText("Save").performClick()
        waitFor("Renamed default")
        assertEquals("update:1:Renamed default:true", service.writes.last())
        compose.onNodeWithTag("glamour-folder-delete-2").performClick()
        assertEquals(2, service.writes.size)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, service.writes.size)
        compose.onNodeWithTag("glamour-folder-delete-2").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil { service.writes.size == 3 }
        assertEquals("delete:2", service.writes.last())
    }

    @Test fun couponClearlyFollowsAndClaimsInOneExplicitRequest() {
        val service = InteractionUiFixture(coupon = true)
        showDetail(service)
        compose.onNodeWithText("Follow and claim").performScrollTo()
        assertTrue(service.writes.isEmpty())
        compose.onNodeWithText("Claiming this coupon also follows the author if needed.").assertExists()
        compose.onNodeWithText("Follow and claim").performClick()
        waitFor("Coupon claimed")
        assertEquals(listOf("claim:42"), service.writes)
        compose.onNodeWithText("Follow and claim").assertDoesNotExist()
    }

    private fun showDetail(service: GlamourService) {
        compose.setContent { MaterialTheme { Box(Modifier.width(599.dp).height(1000.dp)) {
            RisingStonesGlamourDetailScreen(service, 42, {})
        } } }
        waitFor("Detail description")
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

private class InteractionUiFixture(private val coupon: Boolean = false) :
    GlamourCollectionService, GlamourService by FakeGlamourService {
    val writes = mutableListOf<String>()
    val requests = mutableListOf<GlamourListRequest>()
    val candidateQueries = mutableListOf<String>()
    private var folders = listOf(GlamourFavoriteFolder(1, "Own default", true, true, 1), GlamourFavoriteFolder(2, "Wardrobe", false, false, 0))
    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        requests += request
        return FakeGlamourService.fetchGlamours(request)
    }
    override suspend fun fetchDetail(id: Int) = FakeGlamourService.fetchDetail(id).copy(
        isCouponEligible = coupon, couponInviteCode = if (coupon) "fixture-invite" else null)
    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> {
        candidateQueries += name
        return listOf(GlamourEquipmentSearchResult(51, "Fixture robe", "Fixture description", null, listOf("Fixture job")))
    }
    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> {
        candidateQueries += name
        return listOf(GlamourGlassesSearchGroup(5, "Fixture style", listOf(GlamourAccessorySearchResult(52, "Fixture glasses", "", null))))
    }
    override suspend fun fetchFavoriteFolders(authorId: String?) = folders
    override suspend fun favoriteInFolder(id: Int, folderId: Int) { writes += "favorite:$id:$folderId" }
    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) {
        writes += "create:$name:$isPublic"
        folders = folders + GlamourFavoriteFolder(3, name, false, isPublic, 0)
    }
    override suspend fun updateFavoriteFolder(id: Int, name: String, isPublic: Boolean) {
        writes += "update:$id:$name:$isPublic"
        folders = folders.map { if (it.id == id) it.copy(name = name, isPublic = isPublic) else it }
    }
    override suspend fun deleteFavoriteFolder(id: Int) { writes += "delete:$id"; folders = folders.filterNot { it.id == id } }
    override suspend fun claimCoupon(inviteCode: String, glamourId: Int) { writes += "claim:$glamourId" }
    override suspend fun followAuthor(authorId: String) { writes += "follow" }
}
