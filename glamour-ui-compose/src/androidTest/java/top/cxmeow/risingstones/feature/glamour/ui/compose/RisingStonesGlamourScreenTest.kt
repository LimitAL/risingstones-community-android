package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessorySearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthorProfile
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourGlassesSearchGroup
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService

class RisingStonesGlamourScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactSelectionReplacesTheListWithDetail() {
        showAtWidth(400.dp)
        waitFor("Glamour Test")

        composeRule.onNodeWithText("Glamour Test").performClick()
        waitFor("Detail description")

        composeRule.onNodeWithText("Detail description").assertExists()
        composeRule.onNodeWithText("List excerpt", substring = true).assertDoesNotExist()
    }

    @Test
    fun mediumSelectionKeepsTheListBesideDetail() {
        showAtWidth(700.dp)
        waitFor("Glamour Test")

        composeRule.onNodeWithText("Glamour Test").performClick()
        waitFor("Detail description")

        composeRule.onNodeWithText("List excerpt", substring = true).assertExists()
        composeRule.onNodeWithText("Detail description").assertExists()
    }

    @Test
    fun expandedSelectionKeepsTheListBesideDetail() {
        showAtWidth(900.dp)
        waitFor("Glamour Test")

        composeRule.onNodeWithText("Glamour Test").performClick()
        waitFor("Detail description")

        composeRule.onNodeWithText("List excerpt", substring = true).assertExists()
        composeRule.onNodeWithText("Detail description").assertExists()
    }

    private fun showAtWidth(width: Dp) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .width(width)
                        .height(1_000.dp),
                ) {
                    RisingStonesGlamourScreen(
                        service = FakeGlamourService,
                        onNavigateBack = {},
                    )
                }
            }
        }
    }

    private fun waitFor(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

private object FakeGlamourService : GlamourService {
    override val hasCommunityIdentity = true

    override suspend fun fetchGlamours(request: GlamourListRequest) =
        GlamourListPage(listOf(TestGlamour), 1, false)

    override suspend fun fetchFavoriteFolders(authorId: String?): List<GlamourFavoriteFolder> =
        listOf(GlamourFavoriteFolder(1, "Default", true, false, 1))

    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) = error("unused")
    override suspend fun deleteFavoriteFolder(id: Int) = error("unused")
    override suspend fun fetchProfileStatistics(authorId: String?) =
        GlamourProfileStatistics(1, 2, 3)
    override suspend fun fetchAuthorProfile(authorId: String) = GlamourAuthorProfile(
        author = TestAuthor,
        profile = null,
        followingCount = 0,
        followerCount = 0,
        relation = 0,
    )
    override suspend fun followAuthor(authorId: String) = Unit
    override suspend fun cancelFollowAuthor(authorId: String) = Unit

    override suspend fun fetchRaces(): List<GlamourRace> = emptyList()
    override suspend fun searchEquipment(name: String, page: Int):
        List<GlamourEquipmentSearchResult> = emptyList()

    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> = emptyList()
    override suspend fun searchOrnaments(name: String):
        List<GlamourAccessorySearchResult> = emptyList()

    override suspend fun fetchDetail(id: Int) = GlamourDetail(
        id = id,
        title = "Glamour Test",
        description = "Detail description",
        imageUrls = emptyList(),
        author = TestAuthor,
        likes = 2,
        favorites = 1,
        isLiked = false,
        isFavorite = false,
        createdAt = Instant.parse("2026-07-27T12:00:00Z"),
        raceNames = listOf("Hyur"),
        equipments = emptyList(),
        faceAccessory = null,
        fashionAccessory = null,
    )

    override suspend fun favorite(id: Int) = Unit
    override suspend fun cancelFavorite(id: Int) = Unit
    override suspend fun toggleLike(id: Int) = true
}

private val TestAuthor = GlamourAuthor(
    id = "author",
    characterName = "Hero",
    areaName = "Area",
    groupName = "World",
    avatarUrl = null,
)

private val TestGlamour = GlamourListingSummary(
    id = 42,
    title = "Glamour Test",
    description = "List excerpt",
    imageUrls = emptyList(),
    author = TestAuthor,
    likes = 2,
    favorites = 1,
    isLiked = false,
    isFavorite = false,
    createdAt = Instant.parse("2026-07-27T12:00:00Z"),
    jobIds = emptyList(),
    raceIds = emptyList(),
    genderIds = emptyList(),
)
