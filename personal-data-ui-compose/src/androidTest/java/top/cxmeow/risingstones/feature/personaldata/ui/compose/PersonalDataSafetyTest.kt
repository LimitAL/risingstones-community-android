package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUiState

/** Responses and image failures are local fixtures; no network request is made. */
@OptIn(DelicateCoilApi::class)
class PersonalDataSafetyTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private lateinit var imageLoader: ImageLoader
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val failureText get() = context.getString(R.string.personal_data_load_failed)
    private val screenOwner get() = ViewModelProvider(owner, ViewModelProvider.NewInstanceFactory())["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java]
    private val model get() = checkNotNull(screenOwner.currentModels).main

    @Before fun syntheticImages() {
        imageLoader = ImageLoader.Builder(context).components {
            add(Interceptor { ErrorResult(null, it.request, IllegalStateException("Synthetic image unavailable")) })
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
    }
    @After fun release() {
        compose.runOnIdle { owner.viewModelStore.clear() }
        imageLoader.shutdown()
        SingletonImageLoader.reset()
    }

    @Test fun failedRefreshAt599RetainsContentAndUsesLocalizedErrors() = refreshFailure(599)
    @Test fun failedRefreshAt600RetainsContentAndUsesLocalizedErrors() = refreshFailure(600)
    @Test fun failedRefreshAt839RetainsContentAndUsesLocalizedErrors() = refreshFailure(839)
    @Test fun failedRefreshAt840RetainsContentAndUsesLocalizedErrors() = refreshFailure(840)

    private fun refreshFailure(width: Int) {
        val service = show(width)
        board(PersonalDataBoard.Frontline).performClick()
        inPane("personal-data-board-pane", hasText("Retained synthetic row")).assertIsDisplayed()
        compose.runOnIdle { service.rootFailure = IllegalStateException(PrivateReason); service.failBoard = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        inPane("personal-data-board-pane", hasText(failureText)).assertIsDisplayed()
        inPane("personal-data-board-pane", hasText("Retained synthetic row")).assertIsDisplayed()
        if (width >= 600) inPane("personal-data-hub-pane", hasText(failureText)).assertIsDisplayed()
        assertNoErrorLeak()
        compose.runOnIdle {
            assertEquals("Fixture identity 1", model.state.value.identity?.characterName)
            assertEquals("load_failed", model.state.value.rootError)
            assertEquals("load_failed", model.state.value.boardError)
        }
        saveRenderedScreenshot(width)
    }

    @Test fun initialRootFailureHasOneReadAndNeverRendersAnExceptionOrCategory() {
        val service = show(599, SafetyService().apply { rootFailure = IllegalStateException(PrivateReason) })
        inPane("personal-data-hub-pane", hasText(failureText)).assertIsDisplayed()
        assertNoErrorLeak()
        assertEquals(1, service.identityReads)
    }

    @Test fun authenticationFailureUsesLocalizedSignInMessageAndRemovesIdentity() {
        show(599, SafetyService().apply { rootFailure = PersonalDataException.AuthenticationRequired })
        compose.onNodeWithText(context.getString(R.string.personal_data_authentication_failed)).assertIsDisplayed()
        compose.onNodeWithTag("personal-data-hub-pane").assertDoesNotExist()
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
        compose.onNodeWithText("Fixture identity 1").assertDoesNotExist()
        assertNoErrorLeak()
    }

    @Test fun boardAndUltimatePartialErrorsAreLocalizedWhileOldRowsStayVisible() {
        val service = show(840)
        board(PersonalDataBoard.Frontline).performClick()
        inPane("personal-data-board-pane", hasText("Retained synthetic row")).assertIsDisplayed()
        compose.runOnIdle { service.partialBoard = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        inPane("personal-data-board-pane", hasText(failureText)).assertIsDisplayed()
        inPane("personal-data-board-pane", hasText("Retained synthetic row")).assertIsDisplayed()
        board(PersonalDataBoard.Ultimate).performClick()
        inPane("personal-data-board-pane", hasTestTag("personal-data-encounter-968")).performClick()
        inPane("personal-data-encounter-detail", hasText("Synthetic teammate")).assertIsDisplayed()
        compose.runOnIdle { service.partialDetail = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        inPane("personal-data-encounter-detail", hasText(failureText)).assertIsDisplayed()
        inPane("personal-data-encounter-detail", hasText("Synthetic teammate")).assertIsDisplayed()
        assertNoErrorLeak()
    }

    @Test fun capabilityRevocationInsideExplorationClearsMainAndAllVisitedExplorationCaches() {
        val service = show(599)
        openExploration(ExplorationBoard.OccultCrescent)
        compose.onNodeWithTag("exploration-record-overview").assertIsDisplayed()
        compose.onNodeWithTag("exploration-back").performClick()
        openExploration(ExplorationBoard.DeepDungeon)
        compose.onNodeWithTag("exploration-record-overview").assertIsDisplayed()
        compose.runOnIdle { service.hasCommunityIdentity = false }
        compose.onNodeWithText(context.getString(R.string.personal_data_identity_required)).assertIsDisplayed()
        compose.onNodeWithTag("exploration-record-overview").assertDoesNotExist()
        compose.onNodeWithTag("exploration-back").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(PersonalDataUiState(), model.state.value)
            service.hasCommunityIdentity = true
        }
        waitForTag("personal-data-hub-pane")
        openExploration(ExplorationBoard.DeepDungeon)
        compose.onNodeWithTag("exploration-record-overview").assertIsDisplayed()
        assertEquals(2, service.overviewReads[ExplorationBoard.DeepDungeon])
        compose.onNodeWithTag("exploration-back").performClick()
        openExploration(ExplorationBoard.OccultCrescent)
        assertEquals(2, service.overviewReads[ExplorationBoard.OccultCrescent])
        assertEquals(2, service.identityReads)
    }

    private fun show(width: Int, service: SafetyService = SafetyService()): SafetyService {
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth.toFloat() / width, 1f),
                    LocalViewModelStoreOwner provides owner) {
                    MaterialTheme { RisingStonesPersonalDataScreen(service, {}) }
                }
            }
        }
        waitForTag(if (service.rootFailure is PersonalDataException.AuthenticationRequired) "personal-data-auth" else "personal-data-hub-pane")
        return service
    }
    private fun board(board: PersonalDataBoard) = inPane("personal-data-hub-pane", hasTestTag("personal-data-board-$board"))
    private fun openExploration(board: ExplorationBoard) {
        inPane("personal-data-hub-pane", hasTestTag("personal-data-exploration-$board")).performClick()
        waitForTag("exploration-record-overview")
    }
    private fun inPane(pane: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag(pane).performScrollToNode(matcher)
        return compose.onNode(matcher and hasAnyAncestor(hasTestTag(pane)))
    }
    private fun waitForTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun assertNoErrorLeak() {
        listOf("load_failed", "authentication_required", PrivateReason).forEach { text ->
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
        }
    }
    private fun saveRenderedScreenshot(width: Int) {
        compose.onNodeWithTag("personal-data-board-pane").assertIsDisplayed()
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        onView(isRoot()).check { root, error ->
            if (error != null) throw error
            root.postOnAnimation { root.postOnAnimation { rendered.countDown() } }
        }
        check(rendered.await(3, TimeUnit.SECONDS)) { "Synthetic personal-data frame did not render" }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        context.filesDir.resolve("data-foundation-$width.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private companion object { const val PrivateReason = "Synthetic private exception detail" }
}

private class SafetyService : PersonalDataExplorationService {
    override var hasCommunityIdentity by mutableStateOf(true)
    var rootFailure: Exception? = null
    var failBoard = false
    var partialBoard = false
    var partialDetail = false
    var identityReads = 0
    val overviewReads = mutableMapOf<ExplorationBoard, Int>()
    override suspend fun fetchIdentity(): PersonalDataIdentity {
        identityReads++
        rootFailure?.let { throw it }
        return PersonalDataIdentity("Fixture identity $identityReads", "", "", null)
    }
    override suspend fun fetchAvailability() = PersonalDataAvailability(PersonalDataBoard.entries.flatMap { it.statusKeys }.associateWith { "1" })
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent {
        if (failBoard) throw IllegalStateException("Synthetic private exception detail")
        return PersonalDataBoardContent(board, listOf(PersonalDataMetric("fight_times", "12")), listOf(
            if (partialBoard) PersonalDataSection("weekly", error = "Synthetic private exception detail")
            else PersonalDataSection("weekly", listOf(PersonalDataEntry("retained", "Retained synthetic row", emptyList())))
        ))
    }
    override suspend fun fetchUltimateDashboard() = UltimateDashboard(listOf(UltimateEncounterSummary(968, 2, 10, "Paladin", null, 200, 3)))
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary) = UltimateEncounterDetail(summary,
        if (partialDetail) emptyList() else listOf(UltimateTeammate("Synthetic teammate", "", "", "Paladin")),
        emptyList(), emptyList(), emptyList(), emptyList(),
        if (partialDetail) mapOf("team" to "Synthetic private exception detail") else emptyMap())
    override suspend fun fetchExplorationOverview(board: ExplorationBoard): ExplorationOverview {
        overviewReads[board] = overviewReads.getOrDefault(board, 0) + 1
        return ExplorationOverview(board, true, emptyList(), listOf(ExplorationSection(board.sections().first(),
            listOf(ExplorationRecord("overview", "Synthetic exploration", listOf(ExplorationField(ExplorationFieldKind.GoldCoins, "10")))))))
    }
    override suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind) = ExplorationSection(section)
}
