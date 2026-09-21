package top.cxmeow.risingstones.feature.glamour.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.glamour.domain.*

class GlamourConfigurationChangeTest {
    @get:Rule val compose = createAndroidComposeRule<GlamourConfigurationTestActivity>()

    @Test fun recreationRetainsSelectionsAndCompletesEachWriteOnceThenRouteExitClearsState() {
        val service = ConfigurationFixture.service
        waitFor("Detail description")
        compose.onNodeWithText("Favorite (1)").performScrollTo().performClick()
        waitFor("Wardrobe")
        compose.onNodeWithText("Wardrobe").performClick()
        compose.activityRule.scenario.recreate()
        waitFor("Choose a favorite folder")
        compose.onNodeWithText("Save favorite").assertIsEnabled()
        assertEquals(1, service.detailReads)
        compose.onNodeWithText("New folder").performClick()
        compose.onNodeWithText("Folder name").performTextInput("Rotation folder")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil { service.createCalls == 1 }
        compose.activityRule.scenario.recreate()
        waitFor("Folder name")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        service.createGate.complete(Unit)
        waitFor("Rotation folder")
        compose.onNodeWithText("Folder name").assertDoesNotExist()
        compose.onNodeWithText("Save favorite").assertIsNotEnabled()
        assertEquals(1, service.createCalls)
        compose.onNodeWithText("Rotation folder").performClick()
        compose.onNodeWithText("Save favorite").performClick()
        compose.waitUntil { service.favoriteCalls == 1 }
        compose.activityRule.scenario.recreate()
        waitFor("Choose a favorite folder")
        compose.onNodeWithText("Save favorite").assertIsNotEnabled()
        service.favoriteGate.complete(Unit)
        waitFor("Remove favorite (2)")
        assertEquals(1, service.favoriteCalls)
        compose.onNodeWithText("Follow and claim").performScrollTo().performClick()
        compose.waitUntil { service.claimCalls == 1 }
        compose.activityRule.scenario.recreate()
        waitFor("Follow and claim")
        compose.onNodeWithText("Follow and claim").assertIsNotEnabled()
        service.claimGate.complete(Unit)
        waitFor("Coupon claimed")
        assertEquals(1, service.claimCalls)
        assertEquals(1, service.detailReads)
        compose.onNodeWithText("Back").performClick()
        waitFor("Route closed")
        compose.runOnIdle { ConfigurationFixture.showDetail.value = true }
        waitFor("Detail description")
        assertEquals(2, service.detailReads)
    }

    private fun waitFor(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

class GlamourConfigurationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().widthIn(max = 599.dp)) {
                if (ConfigurationFixture.showDetail.value) {
                    RisingStonesGlamourDetailScreen(ConfigurationFixture.service, 42,
                        { ConfigurationFixture.showDetail.value = false })
                } else Text("Route closed")
            }
        } }
    }
}

private object ConfigurationFixture {
    val service = ConfigurationService()
    val showDetail = mutableStateOf(true)
}

private class ConfigurationService : GlamourCollectionService, GlamourService by FakeGlamourService {
    var detailReads = 0
    var createCalls = 0
    var favoriteCalls = 0
    var claimCalls = 0
    val createGate = CompletableDeferred<Unit>()
    val favoriteGate = CompletableDeferred<Unit>()
    val claimGate = CompletableDeferred<Unit>()
    private var created = false
    private var favorite = false
    private var claimed = false
    override suspend fun fetchDetail(id: Int): GlamourDetail {
        detailReads++
        return FakeGlamourService.fetchDetail(id).copy(isFavorite = favorite, favorites = if (favorite) 2 else 1,
            isCouponEligible = true, couponInviteCode = "fixture-invite", isCouponClaimed = claimed)
    }
    override suspend fun fetchFavoriteFolders(authorId: String?) =
        listOf(GlamourFavoriteFolder(1, "Wardrobe", true, false, 1)) +
            if (created) listOf(GlamourFavoriteFolder(2, "Rotation folder", false, true, 0)) else emptyList()
    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) {
        createCalls++; createGate.await(); created = true
    }
    override suspend fun favoriteInFolder(id: Int, folderId: Int) {
        favoriteCalls++; favoriteGate.await(); favorite = true
    }
    override suspend fun claimCoupon(inviteCode: String, glamourId: Int) {
        claimCalls++; claimGate.await(); claimed = true
    }
    override suspend fun updateFavoriteFolder(id: Int, name: String, isPublic: Boolean) = error("unused")
}
