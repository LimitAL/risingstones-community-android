package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

/** All protected records and image files are synthetic; the real application runtime is never used. */
class PersonalDataReadingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PersonalDataReadingTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle { ReadingScreenFixture.configuration = null }
    }

    @Test fun setsNavigateAndKeepParentAt599() = setsNavigation(599)
    @Test fun setsNavigateAndKeepParentAt600() = setsNavigation(600)
    @Test fun setsNavigateAndKeepParentAt839() = setsNavigation(839)
    @Test fun setsNavigateAndKeepParentAt840() = setsNavigation(840)

    private fun setsNavigation(width: Int) {
        val service = ReadingScreenService().apply { parentEntries = 24 }
        val configuration = show(service, width)
        selectBoard(PersonalDataBoard.Glamour)
        parentNode("personal-data-open-Sets")
        compose.onNodeWithTag("personal-data-board-pane").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 16f) }
        val parentOffset = scrollOffset("personal-data-board-pane")
        assertTrue("Parent scrolling must be exercised", parentOffset > 0f)
        compose.onNodeWithTag("personal-data-open-Sets").performClick()
        waitForPage(PersonalDataReadingPage.Sets)
        selectRecord("complete")
        waitForTag("reading-set-details")
        if (width < 600) compose.onNodeWithTag("reading-list").assertDoesNotExist()
        else {
            compose.onNodeWithTag("reading-list").assertIsDisplayed()
            compose.onNodeWithTag(recordTag("complete")).assertIsSelected()
        }
        detailNode("reading-item-1001").assertIsDisplayed()
        assertEquals("complete", readingModel().state.value.selectedSet?.record?.key)
        assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.02f)
        captureSets(width)

        compose.runOnIdle { configuration.width = if (width < 600) 600 else 599 }
        compose.onNodeWithTag("reading-set-details").assertIsDisplayed()
        assertEquals("complete", readingModel().state.value.selectedSet?.record?.key)
        compose.runOnIdle { configuration.width = width }
        returnToParent()

        compose.onNodeWithTag("personal-data-board-pane").assertIsDisplayed()
        assertEquals(PersonalDataBoard.Glamour, parentModel().state.value.selectedBoard)
        assertEquals(parentOffset, scrollOffset("personal-data-board-pane"), 0.1f)
        assertEquals(1, service.setReads)
        assertEquals(listOf(PersonalDataBoard.Glamour), service.boardReads)
    }

    @Test fun fishFilteringMoreAndReopeningPreserveLocalStateWithoutAnotherRead() = rankingLocalState(PersonalDataReadingPage.Fish)
    @Test fun baitFilteringMoreAndReopeningPreserveLocalStateWithoutAnotherRead() = rankingLocalState(PersonalDataReadingPage.Baits)

    private fun rankingLocalState(page: PersonalDataReadingPage) {
        val service = ReadingScreenService()
        show(service, 599)
        openPage(page)
        listNode("reading-search").performTextReplacement("School")
        Espresso.closeSoftKeyboard()
        listNode("reading-category-0").performClick()
        listNode("reading-show-more").performClick()
        listNode("reading-rank-15").assertIsDisplayed()
        val offset = nodeTop("reading-rank-15")
        val model = readingModel()
        assertEquals("School", model.state.value.query)
        assertEquals("普通钓场", model.state.value.category)
        assertEquals(20, model.state.value.visibleLimit)
        val reads = service.rankingReads.toList()
        assertEquals(1, reads.size)

        returnToParent()
        parentNode("personal-data-open-${page.name}").performClick()
        waitForPage(page)

        compose.onNodeWithTag("reading-rank-15").assertIsDisplayed()
        assertEquals(offset, nodeTop("reading-rank-15"), 0.1f)
        assertEquals("School", model.state.value.query)
        assertEquals("普通钓场", model.state.value.category)
        assertEquals(20, model.state.value.visibleLimit)
        assertEquals(reads, service.rankingReads.toList())
        val countText = text(if (page == PersonalDataReadingPage.Fish) R.string.pdr_fish_count else R.string.pdr_bait_count,
            model.state.value.visibleFishingRows[15].count)
        within("reading-rank-15", countText).assertIsDisplayed()
    }

    @Test fun racesRenderFractionAsPercentAndKeepMissingDaysUnknown() {
        val service = ReadingScreenService()
        show(service, 600)
        openPage(PersonalDataReadingPage.Races)

        listNode("reading-race-0")
        within("reading-race-0", text(R.string.pdr_proportion, 12.3)).assertIsDisplayed()
        within("reading-race-0", text(R.string.pdr_days, 42L)).assertIsDisplayed()
        listNode("reading-race-1")
        compose.onAllNodes(hasText(text(R.string.pdr_unknown)) and hasAnyAncestor(hasTestTag("reading-race-1")),
            useUnmergedTree = true).assertCountEquals(2)
        assertEquals(1, service.raceReads)
    }

    @Test fun partialAndCompleteRecordsOfOneSetRemainSeparateAndRecordedFilterIncludesBoth() {
        val service = ReadingScreenService()
        show(service, 839)
        openPage(PersonalDataReadingPage.Sets)
        val partial = recordTag("partial")
        val complete = recordTag("complete")

        listNode(partial)
        within(partial, text(R.string.pdr_partial)).assertIsDisplayed()
        compose.onNodeWithTag(partial).performClick().assertIsSelected()
        listNode(complete)
        within(complete, text(R.string.pdr_complete)).assertIsDisplayed()
        compose.onNodeWithTag(complete).performClick().assertIsSelected()
        listNode(partial).assertIsNotSelected()
        listNode("reading-filter-Recorded").performClick()
        val state = readingModel().state.value
        assertEquals(2, state.totalFiltered)
        assertEquals(setOf("partial", "complete"), state.visibleSets.map { it.record?.key }.toSet())
        listNode("reading-sets-progress").assertTextEquals(text(R.string.pdr_sets_progress, 1, 2))
        listNode("reading-filter-Unrecorded").performClick()
        assertEquals(1, readingModel().state.value.totalFiltered)
        assertEquals(2, readingModel().state.value.visibleSets.single().setId)
        assertEquals(1, service.setReads)
    }

    @Test fun compactSelectionKeepsOldItemsAndOffersCatalogOrRecordRetriesIndependently() {
        val service = ReadingScreenService().apply { missingCatalog = true }
        show(service, 599)
        openPage(PersonalDataReadingPage.Sets)
        listNode("reading-catalog-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("reading-sets-progress").assertDoesNotExist()
        assertNull(readingModel().state.value.setsProgress)
        assertTrue(readingModel().state.value.allSets.all { it.completion == PersonalDataSetCompletion.Unknown })
        selectRecord("complete")
        compose.onNodeWithTag("reading-list").assertDoesNotExist()
        compose.onNodeWithTag("reading-catalog-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("reading-retry-catalogs").assertIsDisplayed()
        val initialCatalogReads = service.catalogReads
        val initialRecordReads = service.setReads

        compose.runOnIdle { service.missingCatalog = false }
        compose.onNodeWithTag("reading-retry-catalogs").performClick()
        compose.waitUntil(5_000) { readingModel().state.value.catalogStatus == PersonalDataReadingLoadStatus.Loaded }
        assertEquals(initialCatalogReads + 1, service.catalogReads)
        assertEquals(initialRecordReads, service.setReads)
        assertEquals(PersonalDataSetsProgress(1, 2), readingModel().state.value.setsProgress)
        assertEquals("complete", readingModel().state.value.selectedSet?.record?.key)
        detailNode("reading-item-1001").assertIsDisplayed()

        compose.runOnIdle { service.failSets = true }
        compose.onNodeWithTag("reading-refresh").performClick()
        compose.onNodeWithTag("reading-error").assertIsDisplayed()
        compose.onNodeWithTag("reading-retry").assertIsDisplayed()
        assertEquals(2, readingModel().state.value.currentPageState.setRecords.size)
        assertEquals("complete", readingModel().state.value.selectedSet?.record?.key)
        detailNode("reading-item-1001").assertIsDisplayed()
        compose.runOnIdle { service.failSets = false }
        compose.onNodeWithTag("reading-retry").performClick()
        compose.waitUntil(5_000) { readingModel().state.value.currentPageState.status == PersonalDataReadingLoadStatus.Loaded }
        assertEquals(initialRecordReads + 2, service.setReads)
        assertEquals(initialCatalogReads + 1, service.catalogReads)
    }

    @Test fun returningAtNewWidthKeepsTheVisibleParentAnchorAndItsListOffset() {
        val service = ReadingScreenService().apply { parentEntries = 24 }
        val configuration = show(service, 599)
        selectBoard(PersonalDataBoard.Glamour)
        val anchor = "personal-data-open-Sets"
        parentNode(anchor)
        val list = compose.onNodeWithTag("personal-data-board-pane")
        val distance = compose.onNodeWithTag(anchor).fetchSemanticsNode().boundsInRoot.top -
            list.fetchSemanticsNode().boundsInRoot.top
        // Make the entry itself the first visible item, independent of the compact-only header button.
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance + 12f) }
        compose.onNodeWithTag(anchor).assertIsDisplayed()
        val anchorOffset = relativeTop(anchor, "personal-data-board-pane")
        assertTrue("The anchor should begin at the top edge, with the header above the viewport", anchorOffset <= 1f)
        compose.onNodeWithTag(anchor).performClick()
        waitForPage(PersonalDataReadingPage.Sets)
        selectRecord("complete")

        compose.runOnIdle { configuration.width = 600 }
        returnToParent()

        compose.onNodeWithTag(anchor).assertIsDisplayed()
        assertEquals(anchorOffset, relativeTop(anchor, "personal-data-board-pane"), 1f)
        assertEquals(PersonalDataBoard.Glamour, parentModel().state.value.selectedBoard)
        assertEquals(1, service.setReads)
        assertEquals(listOf(PersonalDataBoard.Glamour), service.boardReads)
    }

    @Test fun replacingServiceInTheSameActivityClearsOldModelsBeforeReadingTheNewOwner() {
        val first = ReadingScreenService().apply { userLabel = "Service A" }
        val configuration = show(first, 600)
        openPage(PersonalDataReadingPage.Sets)
        selectRecord("complete")
        val firstReading = readingModel()
        val firstMain = parentModel()
        val firstReads = first.readCounts()
        val second = ReadingScreenService().apply { enabled = false; userLabel = "Service B" }

        compose.runOnIdle { configuration.service = second }
        compose.onNodeWithText(text(R.string.personal_data_identity_required)).assertIsDisplayed()
        compose.waitUntil(5_000) { firstReading.state.value.pages.isEmpty() && firstMain.state.value.identity == null }

        assertNull(firstReading.state.value.catalogs)
        assertNull(firstReading.state.value.selectedSetKey)
        assertFalse(firstReading.hasCommunityIdentity)
        assertFalse(firstMain.hasCommunityIdentity)
        assertEquals(PersonalDataOfficialCatalogs(), firstMain.state.value.catalogs)
        assertEquals(List(7) { 0 }, second.readCounts())
        assertEquals(firstReads, first.readCounts())
        assertNotSame(firstReading, readingModel())
        compose.onNodeWithTag("reading-set-details").assertDoesNotExist()

        val third = ReadingScreenService().apply { userLabel = "Service C"; iconPath = ReadingScreenFixture.iconPath }
        compose.runOnIdle { configuration.service = third }
        waitForTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { parentModel().state.value.identity?.characterName == "Service C" }
        assertNull(parentModel().state.value.selectedBoard)
        assertNull(readingModel().state.value.activePage)
        assertNotSame(firstMain, parentModel())
        openPage(PersonalDataReadingPage.Sets)
        selectRecord("complete")
        assertEquals("Service C Alpha set", readingModel().state.value.selectedSet?.catalog?.name)
        assertEquals(1, third.identityReads)
        assertEquals(1, third.setReads)
        assertEquals(listOf(PersonalDataBoard.Glamour), third.boardReads)
        assertEquals(firstReads, first.readCounts())
        assertEquals(List(7) { 0 }, second.readCounts())
    }

    @Test fun revocationClearsReadingSelectionRecordsCatalogAndParentContent() {
        val service = ReadingScreenService()
        show(service, 600)
        openPage(PersonalDataReadingPage.Sets)
        selectRecord("complete")
        val reading = readingModel()
        val parent = parentModel()

        compose.runOnIdle { service.enabled = false }
        compose.waitUntil(5_000) { reading.state.value.pages.isEmpty() && parent.state.value.identity == null }

        assertNull(reading.state.value.activePage)
        assertNull(reading.state.value.selectedSetKey)
        assertNull(reading.state.value.catalogs)
        assertNull(parent.state.value.selectedBoard)
        assertNull(parent.state.value.content)
        assertEquals(PersonalDataOfficialCatalogs(), parent.state.value.catalogs)
        compose.onNodeWithTag("reading-set-details").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.personal_data_identity_required)).assertIsDisplayed()
    }

    @Test fun explicitAuthenticationFailureClearsParentEvenWhenProviderBooleanStaysTrue() {
        val service = ReadingScreenService()
        show(service, 600)
        openPage(PersonalDataReadingPage.Sets)
        selectRecord("complete")
        val reading = readingModel()
        val parent = parentModel()
        val identityReads = service.identityReads

        compose.runOnIdle { service.authFailure = true }
        compose.onNodeWithTag("reading-refresh").performClick()
        waitForTag("reading-unavailable")
        compose.waitUntil(5_000) { parent.state.value.identity == null && parent.state.value.selectedBoard == null }

        assertTrue(service.hasCommunityIdentity)
        assertEquals(PersonalDataReadingLoadStatus.AuthRequired, reading.state.value.currentPageState.status)
        assertTrue(reading.state.value.currentPageState.setRecords.isEmpty())
        assertNull(reading.state.value.selectedSetKey)
        assertNull(reading.state.value.catalogs)
        assertEquals(PersonalDataOfficialCatalogs(), parent.state.value.catalogs)
        assertEquals(identityReads, service.identityReads)
        compose.onNodeWithTag("reading-set-details").assertDoesNotExist()
    }

    @Test fun recreationAndWidthChangesKeepSetSelectionItemScrollFiltersAndRequestCache() {
        val service = ReadingScreenService()
        val configuration = show(service, 599)
        openPage(PersonalDataReadingPage.Sets)
        listNode("reading-search").performTextReplacement("Alpha")
        Espresso.closeSoftKeyboard()
        listNode("reading-filter-Recorded").performClick()
        selectRecord("complete")
        detailNode("reading-item-1017").assertIsDisplayed()
        val beforeTop = nodeTop("reading-item-1017")
        val beforeReads = service.readCounts()
        val reading = readingModel()

        compose.runOnIdle { configuration.width = 600 }
        compose.onNodeWithTag("reading-item-1017").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForTag("reading-set-details")
        compose.onNodeWithTag("reading-item-1017").assertIsDisplayed()
        assertSame(reading, readingModel())
        compose.runOnIdle { configuration.width = 599 }

        compose.onNodeWithTag("reading-item-1017").assertIsDisplayed()
        assertEquals(beforeTop, nodeTop("reading-item-1017"), 0.1f)
        assertEquals(PersonalDataReadingPage.Sets, reading.state.value.activePage)
        assertEquals("Alpha", reading.state.value.query)
        assertEquals(PersonalDataSetFilter.Recorded, reading.state.value.setFilter)
        assertEquals("complete", reading.state.value.selectedSet?.record?.key)
        assertEquals(beforeReads, service.readCounts())
        returnToParent()
        assertEquals(PersonalDataBoard.Glamour, parentModel().state.value.selectedBoard)
    }

    @Test fun legacyServiceShowsNeitherFishingNorGlamourDetailEntrypoints() {
        val service = ReadingScreenService()
        show(service, 600, legacy = true)
        selectBoard(PersonalDataBoard.Glamour)
        compose.onNodeWithTag("personal-data-open-Sets").assertDoesNotExist()
        compose.onNodeWithTag("personal-data-open-Races").assertDoesNotExist()
        selectBoard(PersonalDataBoard.Fishing)
        compose.onNodeWithTag("personal-data-open-Fish").assertDoesNotExist()
        compose.onNodeWithTag("personal-data-open-Baits").assertDoesNotExist()
        assertFalse(readingModel().supportsReading)
        assertEquals(0, service.setReads)
        assertTrue(service.rankingReads.isEmpty())
    }

    private fun show(service: ReadingScreenService, width: Int, legacy: Boolean = false): ReadingScreenConfiguration {
        val configuration = ReadingScreenConfiguration(if (legacy) ReadingLegacyService(service) else service, service, width)
        compose.runOnIdle {
            service.iconPath = ReadingScreenFixture.iconPath
            ReadingScreenFixture.configuration = configuration
        }
        waitForTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { parentModel().state.value.identity != null && !parentModel().state.value.isLoadingCatalogs }
        return configuration
    }

    private fun selectBoard(board: PersonalDataBoard) {
        if (parentModel().state.value.selectedBoard == board) return
        compose.onNodeWithTag("personal-data-hub-pane").performScrollToNode(hasTestTag("personal-data-board-$board"))
        compose.onNodeWithTag("personal-data-board-$board").performScrollTo().performClick()
        waitForTag("personal-data-board-pane")
    }

    private fun openPage(page: PersonalDataReadingPage) {
        selectBoard(if (page in listOf(PersonalDataReadingPage.Fish, PersonalDataReadingPage.Baits)) PersonalDataBoard.Fishing else PersonalDataBoard.Glamour)
        parentNode("personal-data-open-${page.name}").performClick()
        waitForPage(page)
    }

    private fun waitForPage(page: PersonalDataReadingPage) {
        val model = readingModel()
        compose.waitUntil(5_000) { model.state.value.activePage == page && model.state.value.currentPageState.hasLoaded &&
            (page != PersonalDataReadingPage.Sets || model.state.value.catalogStatus != PersonalDataReadingLoadStatus.Loading) }
        waitForTag("reading-list")
    }

    private fun returnToParent() {
        repeat(2) { if (readingModel().state.value.activePage != null) { Espresso.pressBack(); compose.waitForIdle() } }
        assertNull(readingModel().state.value.activePage)
        waitForTag("personal-data-board-pane")
    }

    private fun recordTag(key: String) = "reading-set-${readingModel().state.value.allSets.single { it.record?.key == key }.key}"
    private fun selectRecord(key: String) { listNode(recordTag(key)).performClick(); waitForTag("reading-set-details") }
    private fun parentNode(tag: String) = scrollNode("personal-data-board-pane", tag)
    private fun listNode(tag: String) = scrollNode("reading-list", tag)
    private fun detailNode(tag: String) = scrollNode("reading-set-details", tag)
    private fun scrollNode(container: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun within(tag: String, value: String) = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    private fun nodeTop(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
    private fun relativeTop(tag: String, container: String) = nodeTop(tag) - nodeTop(container)
    private fun scrollOffset(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun readingModel() = compose.runOnIdle {
        requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java].currentModels).reading
    }
    private fun parentModel() = compose.runOnIdle {
        requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java].currentModels).main
    }
    private fun captureSets(width: Int) {
        compose.onNodeWithTag("reading-back").assertIsDisplayed()
        compose.onNodeWithTag("reading-refresh").assertIsDisplayed()
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            root.postOnAnimation { root.postOnAnimation { rendered.countDown() } }
        }
        assertTrue("Synthetic reading frame did not render", rendered.await(3, TimeUnit.SECONDS))
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val destination = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reading-sets-$width.png")
        destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

class PersonalDataReadingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val icon = File(cacheDir, "reading-synthetic-icon.png")
        if (!icon.exists()) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(75, 112, 178)) }
            icon.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        ReadingScreenFixture.iconPath = icon.toURI().toString()
        setContent {
            ReadingScreenFixture.configuration?.let { configuration ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = Density(constraints.maxWidth.toFloat() / configuration.width, 1f)
                    CompositionLocalProvider(LocalDensity provides density) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().onSizeChanged { configuration.measuredWidthDp = with(density) { it.width.toDp().value } }) {
                                RisingStonesPersonalDataScreen(configuration.service, {})
                            }
                        }
                    }
                }
            }
        }
    }
}

private object ReadingScreenFixture {
    var configuration by mutableStateOf<ReadingScreenConfiguration?>(null)
    var iconPath: String? = null
}

private class ReadingScreenConfiguration(service: PersonalDataService, val fixture: ReadingScreenService, width: Int) {
    var service by mutableStateOf(service)
    var width by mutableStateOf(width)
    var measuredWidthDp = 0f
}

private class ReadingLegacyService(delegate: PersonalDataService) : PersonalDataService by delegate

private class ReadingScreenService : PersonalDataReadingService {
    var enabled by mutableStateOf(true)
    override val hasCommunityIdentity get() = enabled
    var missingCatalog = false
    var failSets = false
    var authFailure = false
    var userLabel = ""
    var parentEntries = 1
    var iconPath: String? = null
    var identityReads = 0
    var availabilityReads = 0
    var catalogReads = 0
    var setReads = 0
    var raceReads = 0
    val rankingReads = CopyOnWriteArrayList<PersonalDataFishingRankingKind>()
    val boardReads = CopyOnWriteArrayList<PersonalDataBoard>()
    private val items = (1..24).map { GlamourCatalogSetItem(it, 1000 + it, "Synthetic item $it", 2000 + it) }
    override suspend fun fetchIdentity(): PersonalDataIdentity {
        identityReads++
        return PersonalDataIdentity(userLabel.ifBlank { "Synthetic reader" }, "Fixture area", "Fixture world", null)
    }
    override suspend fun fetchAvailability(): PersonalDataAvailability {
        availabilityReads++
        return PersonalDataAvailability(PersonalDataBoard.entries.flatMap { it.statusKeys }.associateWith { "1" })
    }
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent {
        boardReads += board
        val entries = List(parentEntries) { index ->
            PersonalDataEntry("parent-record-$index", "Synthetic parent record ${index + 1}", listOf(
                PersonalDataField("catalog_name", "Synthetic catalog entry ${index + 1}"),
                PersonalDataField("log_time", "2026-09-20"),
                PersonalDataField("vanity_times", (index + 1).toString()),
            ))
        }
        // The real cards initially show three rows each. Spread the long fixture over the five
        // official glamour sections so the parent remains scrollable without expanding a card.
        val sectionIds = if (parentEntries > 3 && board == PersonalDataBoard.Glamour)
            listOf("race", "color", "ornament", "vanity", "fullset") else listOf("fullset")
        val groups = entries.chunked((parentEntries + sectionIds.size - 1) / sectionIds.size)
        return PersonalDataBoardContent(board, listOf(PersonalDataMetric("vanity_times", "12")),
            groups.mapIndexed { index, rows -> PersonalDataSection(sectionIds[index], rows) })
    }
    override suspend fun fetchUltimateDashboard() = UltimateDashboard(emptyList())
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary) =
        UltimateEncounterDetail(summary, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs {
        catalogReads++
        if (missingCatalog) return PersonalDataOfficialCatalogs()
        return PersonalDataOfficialCatalogs(glamour = GlamourCatalogSummary(2, 0, 0,
            sets = listOf(GlamourCatalogSet(1, if (userLabel.isBlank()) "Alpha set" else "$userLabel Alpha set", 2500, items),
                GlamourCatalogSet(2, "Beta set", 2501, listOf(GlamourCatalogSetItem(1, 2001, "Beta item", 2502))))))
    }
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind): List<PersonalDataFishingRank> {
        rankingReads += kind
        val noun = if (kind == PersonalDataFishingRankingKind.Fish) "fish" else "bait"
        return (1..36).map { PersonalDataFishingRank("School $noun $it", (100 - it).toLong(), if (it <= 24) "普通钓场" else "出海垂钓") }
    }
    override suspend fun fetchRaceUsage(): List<PersonalDataRaceUsage> {
        raceReads++
        return listOf(PersonalDataRaceUsage("Synthetic race", "Female", 0.123, 42, true, false),
            PersonalDataRaceUsage("Unknown race", "Male", null, null, false, false))
    }
    override suspend fun fetchGlamourSetRecords(): List<PersonalDataGlamourSetRecord> {
        setReads++
        if (authFailure) throw PersonalDataException.AuthenticationRequired
        if (failSets) throw IOException("Synthetic failure")
        return listOf(PersonalDataGlamourSetRecord("partial", 1, setOf(1001, 1002, 1003), Instant.parse("2026-01-01T00:00:00Z")),
            PersonalDataGlamourSetRecord("complete", 1, items.map { it.itemId }.toSet(), Instant.parse("2026-02-01T00:00:00Z")))
    }
    override fun itemIconUrl(iconId: Int) = iconPath
    fun readCounts() = listOf(identityReads, availabilityReads, catalogReads, setReads, raceReads, rankingReads.size, boardReads.size)
}
