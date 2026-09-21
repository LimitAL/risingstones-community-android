package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataDashboardSectionKind as Section
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataDashboardData as Data

/** Uses only synthetic records in a separate test application, without a network or account session. */
class PersonalDataDashboardScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PersonalDataDashboardTestActivity>()
    @After fun clear() { compose.runOnIdle { DashboardFixture.configuration = null } }

    @Test fun nativeNavigationAt599() = navigation(599)
    @Test fun nativeNavigationAt600() = navigation(600)
    @Test fun nativeNavigationAt839() = navigation(839)
    @Test fun nativeNavigationAt840() = navigation(840)

    private fun navigation(width: Int) {
        val service = DashboardService()
        val config = show(service, width)
        selectBoard(PersonalDataBoard.Glamour)
        section(Section.Sets)
        node("dashboard-set-0").assertIsDisplayed()
        node("dashboard-open-Sets").performClick()
        waitTag("reading-list")
        compose.waitUntil(5_000) { models().reading.state.value.currentPageState.hasLoaded }
        Espresso.pressBack()
        waitTag("dashboard-list")
        assertEquals(Section.Sets, model().state.value.selectedSection)
        assertEquals(6, service.reads.size)
        assertEquals(0, service.legacyReads)
        assertEquals(1, service.setDetailReads)
        compose.runOnIdle { config.width = if (width < 600) 600 else 599 }
        node("dashboard-set-0").assertIsDisplayed()
        compose.runOnIdle { config.width = width }
        compose.activityRule.scenario.recreate()
        waitTag("dashboard-list")
        assertEquals(Section.Sets, model().state.value.selectedSection)
        compose.onNodeWithTag("dashboard-section-Sets").assertIsDisplayed().assertIsSelected()
        assertEquals(6, service.reads.size)
        if (width < 600) compose.onNodeWithTag("personal-data-hub-pane").assertDoesNotExist()
        else compose.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
        capture(width)
    }

    @Test fun everyTypedSectionRendersAndNeverCallsTheLegacyBoardReader() {
        val service = DashboardService()
        show(service, 840)
        val tags = mapOf(Section.FishRanking to "dashboard-fish-0", Section.BaitRanking to "dashboard-bait-0",
            Section.BigFish to "dashboard-big-fish-0", Section.FishingAchievements to "dashboard-achievement-0",
            Section.OceanFishing to "dashboard-ocean-0", Section.Races to "dashboard-race-0",
            Section.Stains to "dashboard-stain-0", Section.Accessories to "dashboard-accessory-0",
            Section.Vanity to "dashboard-vanity-0", Section.Sets to "dashboard-set-0", Section.SavageRaids to "dashboard-savage-series-0")
        listOf(PersonalDataBoard.Fishing, PersonalDataBoard.Glamour, PersonalDataBoard.Savage).forEach { board ->
            selectBoard(board)
            Section.forBoard(board).forEach { kind ->
                section(kind)
                tags[kind]?.let { node(it).assertIsDisplayed() }
            }
        }
        assertEquals(Section.entries.toSet(), service.reads.toSet())
        assertEquals(14, service.reads.size)
        assertEquals(0, service.legacyReads)
    }

    @Test fun percentageUnitsAndOceanTerritoriesAreSemantic() {
        show(DashboardService(), 600)
        selectBoard(PersonalDataBoard.Fishing)
        compose.onNodeWithText(text(R.string.pdd_percent, 12.3)).assertIsDisplayed()
        section(Section.OceanFishing)
        node("dashboard-ocean-0")
        within("dashboard-ocean-0", text(R.string.pdd_offshore)).assertIsDisplayed()
        node("dashboard-ocean-1")
        within("dashboard-ocean-1", text(R.string.pdd_nearshore)).assertIsDisplayed()
        selectBoard(PersonalDataBoard.Savage)
        compose.onNodeWithText(text(R.string.pdd_hours, 1.5)).assertIsDisplayed()
    }

    @Test fun filtersAndMoreRemainLocalAndSurviveRecreationWithScroll() {
        val service = DashboardService()
        show(service, 599)
        selectBoard(PersonalDataBoard.Fishing)
        section(Section.FishRanking)
        node("dashboard-search").performTextReplacement("School")
        Espresso.closeSoftKeyboard()
        node("dashboard-category-0").performClick()
        node("dashboard-more").performClick()
        node("dashboard-fish-15").assertIsDisplayed()
        val offset = scrollOffset()
        val reads = service.reads.toList()
        compose.activityRule.scenario.recreate()
        waitTag("dashboard-list")
        compose.onNodeWithTag("dashboard-fish-15").assertIsDisplayed()
        assertEquals(offset, scrollOffset(), 0.1f)
        assertEquals("School", model().state.value.currentCriteria.query)
        assertEquals(20, model().state.value.currentCriteria.visibleLimit)
        assertEquals(reads, service.reads.toList())
    }

    @Test fun failedSectionRetriesAloneAndTopRefreshUsesOnlyTypedReads() {
        val service = DashboardService().apply { failed = Section.BigFish }
        show(service, 600)
        selectBoard(PersonalDataBoard.Fishing)
        section(Section.BigFish)
        node("dashboard-error").assertIsDisplayed()
        assertEquals(6, service.reads.size)
        compose.runOnIdle { service.failed = null }
        node("dashboard-retry").performClick()
        waitLoaded()
        assertEquals(7, service.reads.size)
        assertEquals(2, service.reads.count { it == Section.BigFish })
        val catalogReads = service.catalogReads
        compose.onNodeWithTag("personal-data-refresh").performClick()
        compose.waitUntil(5_000) { service.reads.size == 13 && model().state.value.sections.values.none { it.status == PersonalDataDashboardLoadStatus.Loading } }
        assertEquals(catalogReads, service.catalogReads)
        assertEquals(0, service.legacyReads)
    }

    @Test fun unavailableCatalogShowsUnknownRecordAndRetriesWithoutBusinessRequests() {
        val service = DashboardService().apply { missingCatalog = true }
        show(service, 600)
        selectBoard(PersonalDataBoard.Glamour)
        section(Section.Stains)
        node("dashboard-catalog-error").assertIsDisplayed()
        node("dashboard-stain-0")
        within("dashboard-stain-0", text(R.string.pdd_stain_number, 1)).assertIsDisplayed()
        val reads = service.reads.toList()
        compose.runOnIdle { service.missingCatalog = false }
        node("dashboard-catalog-retry").performClick()
        compose.waitUntil(5_000) { model().state.value.catalogStatus == PersonalDataDashboardLoadStatus.Loaded }
        node("dashboard-stain-0")
        within("dashboard-stain-0", "Synthetic blue").assertIsDisplayed()
        assertEquals(reads, service.reads.toList())
    }

    @Test fun authenticationFailureClearsSiblingCachesAndDoesNotRefetchOnRecreation() {
        val service = DashboardService()
        show(service, 600)
        selectBoard(PersonalDataBoard.Glamour)
        val old = models()
        compose.runOnIdle { old.reading.open(PersonalDataReadingPage.Sets) }
        waitTag("reading-list")
        compose.waitUntil(5_000) { old.reading.state.value.currentPageState.hasLoaded }
        Espresso.pressBack()
        waitTag("dashboard-list")
        compose.runOnIdle { service.authFailure = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        waitTag("personal-data-auth")
        assertTrue(service.hasCommunityIdentity)
        assertNull(old.main.state.value.identity)
        assertTrue(old.reading.state.value.pages.isEmpty())
        assertTrue(old.dashboard.state.value.sections.values.all { it.data == null })
        val reads = service.reads.size
        val identities = service.identityReads
        compose.activityRule.scenario.recreate()
        waitTag("personal-data-auth")
        assertEquals(reads, service.reads.size)
        assertEquals(identities, service.identityReads)
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
    }

    @Test fun capabilityRevocationAndServiceReplacementClearEveryOldModel() {
        val first = DashboardService()
        val config = show(first, 600)
        selectBoard(PersonalDataBoard.Glamour)
        val old = models()
        compose.runOnIdle { first.enabled = false }
        compose.waitUntil(5_000) { old.dashboard.state.value.sections.isEmpty() && old.main.state.value.identity == null }
        compose.onNodeWithTag("dashboard-list").assertDoesNotExist()
        val second = DashboardService()
        compose.runOnIdle { config.service = second }
        waitTag("personal-data-hub-pane")
        selectBoard(PersonalDataBoard.Fishing)
        assertNotSame(old, models())
        assertTrue(old.dashboard.state.value.sections.isEmpty())
        assertFalse(old.dashboard.hasCommunityIdentity)
        assertEquals(0, second.legacyReads)
    }

    @Test fun achievementOnlyRaidOmitsJobDurationAndCompleteBadge() {
        show(DashboardService(), 840)
        selectBoard(PersonalDataBoard.Savage)
        section(Section.SavageRaids)
        node("dashboard-savage-tier-3")
        within("dashboard-savage-tier-3", "Achievement unlock").assertIsDisplayed()
        compose.onAllNodes(hasText("Synthetic job") and hasAnyAncestor(hasTestTag("dashboard-savage-tier-3")), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText(text(R.string.pdd_seconds, 42.5)) and hasAnyAncestor(hasTestTag("dashboard-savage-tier-3")), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText(text(R.string.pdd_tier_complete)) and hasAnyAncestor(hasTestTag("dashboard-savage-tier-3")), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun show(service: DashboardService, width: Int): DashboardConfiguration {
        val config = DashboardConfiguration(service, width)
        compose.runOnIdle { DashboardFixture.configuration = config }
        waitTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { models().main.state.value.identity != null }
        return config
    }
    private fun selectBoard(board: PersonalDataBoard) {
        if (models().main.state.value.selectedBoard != null && compose.onAllNodesWithTag("personal-data-hub-pane").fetchSemanticsNodes().isEmpty()) {
            node("dashboard-back").performClick()
        }
        compose.onNodeWithTag("personal-data-hub-pane").performScrollToNode(hasTestTag("personal-data-board-$board"))
        compose.onNodeWithTag("personal-data-board-$board").performClick()
        waitTag("dashboard-list")
        compose.waitUntil(5_000) { model().state.value.activeBoard == board && Section.forBoard(board).all {
            model().state.value.sections[it]?.status in listOf(PersonalDataDashboardLoadStatus.Loaded, PersonalDataDashboardLoadStatus.Failed) } }
    }
    private fun section(kind: Section) {
        compose.onNodeWithTag("dashboard-list").performScrollToIndex(1)
        compose.onNodeWithTag("dashboard-section-$kind").performScrollTo().performClick()
        compose.waitUntil(5_000) { model().state.value.selectedSection == kind }
    }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun waitLoaded() = compose.waitUntil(5_000) { model().state.value.currentSection.status == PersonalDataDashboardLoadStatus.Loaded }
    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun models() = compose.runOnIdle { requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java].currentModels) }
    private fun model() = models().dashboard
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun within(tag: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    private fun scrollOffset() = compose.onNodeWithTag("dashboard-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun capture(width: Int) {
        node("dashboard-set-0")
        compose.onNodeWithTag("personal-data-refresh").assertIsDisplayed()
        val frame = CountDownLatch(1)
        compose.runOnIdle { val view = compose.activity.window.decorView; view.postOnAnimation { view.postOnAnimation { frame.countDown() } } }
        assertTrue(frame.await(3, TimeUnit.SECONDS))
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val destination = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "dashboard-$width.png")
        destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

class PersonalDataDashboardTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val icon = File(cacheDir, "dashboard-synthetic-icon.png")
        if (!icon.exists()) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(75, 112, 178)) }
            icon.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        DashboardFixture.iconPath = icon.toURI().toString()
        setContent { DashboardFixture.configuration?.let { config ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth.toFloat() / config.width, 1f)) {
                    MaterialTheme { RisingStonesPersonalDataScreen(config.service, {}) }
                }
            }
        } }
    }
}
private object DashboardFixture {
    var configuration by mutableStateOf<DashboardConfiguration?>(null)
    var iconPath: String? = null
}
private class DashboardConfiguration(service: PersonalDataService, width: Int) {
    var service by mutableStateOf(service)
    var width by mutableStateOf(width)
}
private class DashboardService : PersonalDataDashboardService {
    var enabled by mutableStateOf(true)
    override val hasCommunityIdentity get() = enabled
    var failed: Section? = null
    var authFailure = false
    var missingCatalog = false
    var legacyReads = 0
    var identityReads = 0
    var catalogReads = 0
    var setDetailReads = 0
    val reads = CopyOnWriteArrayList<Section>()
    private val date = Instant.parse("2026-01-01T00:00:00Z")
    private val setRecords = listOf(PersonalDataGlamourSetRecord("record", 1, setOf(1001), date))
    override fun itemIconUrl(iconId: Int) = DashboardFixture.iconPath
    override fun achievementIconUrl(iconId: Int) = DashboardFixture.iconPath
    override fun raidImageUrl(imageId: Int) = DashboardFixture.iconPath
    override suspend fun fetchIdentity(): PersonalDataIdentity { identityReads++; return PersonalDataIdentity("Synthetic reader", "Synthetic area", "Synthetic world", null) }
    override suspend fun fetchAvailability() = PersonalDataAvailability(PersonalDataBoard.entries.flatMap { it.statusKeys }.associateWith { "1" })
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent { legacyReads++; return PersonalDataBoardContent(board, emptyList(), emptyList()) }
    override suspend fun fetchUltimateDashboard() = UltimateDashboard(emptyList())
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary) = UltimateEncounterDetail(summary, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs {
        catalogReads++
        if (missingCatalog) throw IOException("Synthetic catalog unavailable")
        return PersonalDataOfficialCatalogs(
            fish = mapOf(1 to FishKingCatalogEntry(1, 1, "Synthetic king", "7")),
            glamour = GlamourCatalogSummary(1, 1, 1,
                sets = listOf(GlamourCatalogSet(1, "Synthetic set", 1, listOf(GlamourCatalogSetItem(1, 1001, "Synthetic item", 1)))),
                stains = listOf(GlamourCatalogStain(1, "Synthetic blue", 0x123456, false)),
                fashionAccessories = listOf(GlamourCatalogFashionAccessory(1, 1, "Synthetic parasol"))),
            savageSeries = listOf(SavageRaidCatalogSeries("Synthetic series", "S", listOf(
                SavageRaidCatalogTier("Normal tier", "普通阶段", false, null, listOf(SavageRaidCatalogEntry(1, "Synthetic raid", 1), SavageRaidCatalogEntry(2, "Uncleared raid", 2))),
                SavageRaidCatalogTier("Achievement tier", "成就阶段", true, "Achievement unlock", listOf(SavageRaidCatalogEntry(3, "Achievement raid", 3)))))))
    }
    override suspend fun fetchSupplementaryCatalogs() = PersonalDataSupplementaryCatalogs(
        oceanFish = listOf(PersonalDataOceanFishCatalogEntry(2, 2, "Synthetic ocean fish")),
        fishingAchievements = listOf(PersonalDataAchievementCatalogEntry(1, "Synthetic achievement", "Synthetic description", 1)),
        vanityCategories = listOf(PersonalDataVanityCategory(1, "Synthetic swords", null, 1, 1)))
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind) = (1..24).map { PersonalDataFishingRank("School $it", it.toLong(), "普通钓场") }
    override suspend fun fetchRaceUsage() = listOf(PersonalDataRaceUsage("Synthetic race", "Female", 0.123, null, true, false))
    override suspend fun fetchGlamourSetRecords(): List<PersonalDataGlamourSetRecord> { setDetailReads++; return setRecords }
    override suspend fun fetchDashboardSection(section: Section): Data {
        reads += section
        if (authFailure) throw PersonalDataException.AuthenticationRequired
        if (failed == section) throw IOException("Synthetic section failure")
        return when (section) {
            Section.FishingSummary -> Data.FishingSummary(FishingOverview(100, 0.123, 2, 500))
            Section.FishRanking -> Data.FishRanking(fetchFishingRanking(PersonalDataFishingRankingKind.Fish))
            Section.BaitRanking -> Data.BaitRanking(fetchFishingRanking(PersonalDataFishingRankingKind.Bait))
            Section.BigFish -> Data.BigFish(listOf(PersonalDataFishCatch("Synthetic king", date, 3)))
            Section.FishingAchievements -> Data.FishingAchievements(listOf(PersonalDataAchievementRecord(1, null, null, date)))
            Section.OceanFishing -> Data.OceanFishing(listOf(PersonalDataOceanRoute(900, 500, 2), PersonalDataOceanRoute(1163, 1000, 3)))
            Section.GlamourSummary -> Data.GlamourSummary(GlamourOverview(1, 2, 3))
            Section.Races -> Data.Races(fetchRaceUsage().map { PersonalDataRankedRace(it, 1) })
            Section.Stains -> Data.Stains(listOf(PersonalDataStainUsage(1, 5, 1)))
            Section.Accessories -> Data.Accessories(listOf(PersonalDataAccessoryUsage(1, 2, 1)))
            Section.Vanity -> Data.Vanity(listOf(PersonalDataVanityUsage(PersonalDataVanityPeriod.AllTime, 1, 1001, "Synthetic sword", null, 3)))
            Section.Sets -> Data.Sets(setRecords)
            Section.SavageSummary -> Data.SavageSummary(SavageOverview(2, 3, 4, 1.5))
            Section.SavageRaids -> Data.SavageRaids(listOf(1, 3).map { PersonalDataSavageClear(it, date, true, "Synthetic job", 42.5) })
        }
    }
}
