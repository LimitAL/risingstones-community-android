package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.Bitmap
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineData as Data
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineSection as Section

/** The real root screen consumes only synthetic services and local PNGs, without a network session. */
class PersonalDataFrontlineScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PersonalDataFrontlineTestActivity>()

    @After fun clear() { compose.runOnIdle { FrontlineFixture.configuration = null } }

    @Test fun nativeNavigationAt599() = navigation(599)
    @Test fun nativeNavigationAt600() = navigation(600)
    @Test fun nativeNavigationAt839() = navigation(839)
    @Test fun nativeNavigationAt840() = navigation(840)

    private fun navigation(width: Int) {
        val service = FrontlineService()
        val configuration = show(service, width)
        openFrontline()
        assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.02f)
        section(Section.Maps)
        choose("frontline-map", 1)
        choose("frontline-map-job", 1)
        assertEquals("尘封秘岩", model().state.value.selectedMap)
        assertEquals("战士", model().state.value.selectedMapJob)

        // Exercise the actual compact back handler, including a transition from a two-pane window.
        if (width >= 600) compose.runOnIdle { configuration.width = 599 }
        compose.onNodeWithTag("personal-data-hub-pane").assertDoesNotExist()
        Espresso.pressBack()
        waitTag("personal-data-hub-pane")
        assertNull(models().main.state.value.selectedBoard)
        openFrontline()
        assertEquals(Section.Maps, model().state.value.selectedSection)
        assertEquals("尘封秘岩", model().state.value.selectedMap)
        assertEquals("战士", model().state.value.selectedMapJob)
        compose.runOnIdle { configuration.width = width }

        section(Section.Achievements)
        node("frontline-search").performTextReplacement("Entry")
        Espresso.closeSoftKeyboard()
        node("frontline-all-achievements").performClick()
        node("frontline-more").performClick()
        node("frontline-achievement-15").assertIsDisplayed()
        val top = nodeTop("frontline-achievement-15")
        val retainedModel = model()
        compose.activityRule.scenario.recreate()
        waitTag("frontline-list")
        assertSame(retainedModel, model())
        compose.onNodeWithTag("frontline-achievement-15").assertIsDisplayed()
        assertEquals(top, nodeTop("frontline-achievement-15"), 0.5f)
        assertEquals(Section.Achievements, model().state.value.selectedSection)
        assertEquals("Entry", model().state.value.query)
        assertTrue(model().state.value.includeUnobtained)
        assertEquals(20, model().state.value.visibleLimit)

        compose.runOnIdle { configuration.width = if (width < 600) 600 else 599 }
        compose.onNodeWithTag("frontline-list").assertIsDisplayed()
        assertEquals(Section.Achievements, model().state.value.selectedSection)
        assertEquals("Entry", model().state.value.query)
        assertEquals("尘封秘岩", model().state.value.selectedMap)
        assertEquals("战士", model().state.value.selectedMapJob)
        compose.runOnIdle { configuration.width = width }
        if (width < 600) compose.onNodeWithTag("personal-data-hub-pane").assertDoesNotExist()
        else compose.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()
        assertReadsOnce(service)
        section(Section.Overview)
        capture(width)
    }

    @Test fun sixVisiblePagesUseSevenTypedReadsOnceAndNeverReadTheLegacyBoard() {
        val service = FrontlineService()
        show(service, 840)
        openFrontline()
        val tags = linkedMapOf(
            Section.Overview to "frontline-radar",
            Section.Weekly to "frontline-weekly-chart",
            Section.Jobs to "frontline-job-details",
            Section.Best to "frontline-best-details",
            Section.Maps to "frontline-map-details",
            Section.Achievements to "frontline-achievement-0",
        )
        repeat(2) {
            tags.forEach { (kind, tag) -> section(kind); node(tag).assertIsDisplayed() }
        }
        compose.onNodeWithTag("frontline-section-MapJobs").assertDoesNotExist()
        assertReadsOnce(service)
        assertEquals(1, service.frontlineCatalogReads)
    }

    @Test fun overviewAndJobPeriodsAreIndependentAndSelectionsStayLocal() {
        val service = FrontlineService()
        show(service, 600)
        openFrontline()
        node("frontline-overview-period-Since51").performClick().assertIsSelected()
        assertEquals(51L, model().state.value.overall?.battles)
        assertEquals(100L, model().state.value.allTimeOverview?.battles)
        section(Section.Jobs)
        node("frontline-jobs-period-Total").assertIsSelected()
        node("frontline-jobs-period-Last30Days").performClick().assertIsSelected()
        choose("frontline-job", 1)
        node("frontline-job-details")
        within("frontline-job-details", "战士").assertIsDisplayed()
        assertEquals(FrontlinePeriodKind.Last30Days, model().state.value.jobPeriod)
        assertEquals(FrontlinePeriodKind.Since51, model().state.value.overallPeriod)
        captureImage("frontline-jobs.png")
        section(Section.Overview)
        node("frontline-overview-period-Since51").assertIsSelected()
        assertReadsOnce(service)
    }

    @Test fun selectingAnotherMapResetsItsJobWithoutReadingAgain() {
        val service = FrontlineService()
        show(service, 599)
        openFrontline()
        section(Section.Maps)
        choose("frontline-map-job", 1)
        assertEquals("骑士", model().state.value.selectedMapJob)
        assertEquals(7L, model().state.value.currentMapStats?.battles)
        choose("frontline-map", 1)
        assertNull(model().state.value.selectedMapJob)
        assertEquals("尘封秘岩", model().state.value.currentMapStats?.mapName)
        assertEquals(20L, model().state.value.currentMapStats?.battles)
        node("frontline-map-job").assertTextContains(text(R.string.pdr_all), substring = true)
        choose("frontline-map-job", 1)
        assertEquals("战士", model().state.value.selectedMapJob)
        assertEquals(8L, model().state.value.currentMapStats?.battles)
        assertReadsOnce(service)
    }

    @Test fun changingOverviewPeriodDoesNotResetTheAchievementScrollPosition() {
        val service = FrontlineService()
        show(service, 600)
        openFrontline()
        section(Section.Achievements)
        node("frontline-more").performClick()
        node("frontline-achievement-15").assertIsDisplayed()
        val top = nodeTop("frontline-achievement-15")
        val model = model()
        // Host selection avoids scrolling this list back to its tab strip before leaving it.
        compose.runOnIdle { model.selectSection(Section.Overview) }
        node("frontline-overview-period-Since51").performClick().assertIsSelected()
        compose.runOnIdle { model.selectSection(Section.Achievements) }
        compose.onNodeWithTag("frontline-achievement-15").assertIsDisplayed()
        assertEquals(top, nodeTop("frontline-achievement-15"), 0.5f)
        assertEquals(20, model.state.value.visibleLimit)
        assertEquals(FrontlinePeriodKind.Since51, model.state.value.overallPeriod)
        assertReadsOnce(service)
    }

    @Test fun mapJobFailureKeepsMapTotalsAndRetriesOnlyThatRead() {
        val service = FrontlineService().apply { failedSection = Section.MapJobs }
        show(service, 600)
        openFrontline()
        section(Section.Maps)
        node("frontline-error-MapJobs").assertIsDisplayed()
        node("frontline-map-details")
        within("frontline-map-details", "昂萨哈凯尔").assertIsDisplayed()
        assertEquals(10L, model().state.value.currentMapStats?.battles)
        assertEquals(PersonalDataFrontlineLoadStatus.Loaded, model().state.value.sections[Section.Maps]?.status)
        assertReadsOnce(service)
        compose.runOnIdle { service.failedSection = null }
        node("frontline-retry-MapJobs").performClick()
        compose.waitUntil(5_000) { model().state.value.sections[Section.MapJobs]?.status == PersonalDataFrontlineLoadStatus.Loaded }
        assertEquals(8, service.reads.size)
        Section.entries.forEach { assertEquals(if (it == Section.MapJobs) 2 else 1, service.reads.count { row -> row == it }) }
        choose("frontline-map-job", 1)
        assertEquals("骑士", model().state.value.selectedMapJob)
        assertEquals(7L, model().state.value.currentMapStats?.battles)
        assertEquals(0, service.legacyReads)
    }

    @Test fun lastSevenCompleteDaysDistinguishMissingDaysFromUnknownFields() {
        val service = FrontlineService()
        show(service, 840)
        openFrontline()
        section(Section.Weekly)
        val today = requireNotNull(model().state.value.today)
        val week = requireNotNull(model().state.value.weekly)
        assertEquals((7L downTo 1L).map(today::minusDays), week.days.map { it.date })
        assertTrue(week.days.first().isMissing)
        assertEquals(0L, week.days.first().battles)
        assertFalse(week.days[1].isMissing)
        assertNull(week.days[1].battles)
        assertNull(week.totals.battles)
        assertEquals(8L, week.days[2].battles)
        val missing = "frontline-day-${today.minusDays(7)}"
        val unknown = "frontline-day-${today.minusDays(6)}"
        node(missing)
        within(missing, number(0)).assertIsDisplayed()
        within(missing, text(R.string.pfl_missing_day)).assertIsDisplayed()
        node(unknown)
        within(unknown, text(R.string.pdr_unknown)).assertIsDisplayed()
        compose.onNodeWithTag("frontline-day-$today").assertDoesNotExist()
        node("frontline-unknown-day-0").assertIsDisplayed()
        choose("frontline-weekly-metric", PersonalDataFrontlineWeeklyMetric.WinRate.ordinal)
        node(unknown)
        within(unknown, text(R.string.pdr_unknown)).assertIsDisplayed()
        assertReadsOnce(service)
        captureImage("frontline-weekly.png")
    }

    @Test fun emptyAchievementCatalogIsRetriableWithoutRepeatingProtectedReads() {
        val service = FrontlineService().apply { missingAchievementCatalog = true }
        show(service, 600)
        openFrontline()
        section(Section.Achievements)
        node("frontline-catalog-error").assertIsDisplayed()
        node("frontline-achievement-0")
        within("frontline-achievement-0", text(R.string.pdd_achievement_number, 1)).assertIsDisplayed()
        assertFalse(model().state.value.hasAchievementCatalog)
        val reads = service.reads.toList()
        compose.runOnIdle { service.missingAchievementCatalog = false }
        node("frontline-catalog-retry").performClick()
        compose.waitUntil(5_000) { model().state.value.hasAchievementCatalog && service.frontlineCatalogReads == 2 }
        node("frontline-achievement-0")
        within("frontline-achievement-0", "Entry 01").assertIsDisplayed()
        assertEquals(reads, service.reads.toList())
        assertEquals(0, service.legacyReads)
    }

    @Test fun explicitRefreshRepeatsEachTypedReadOnceWithoutReloadingTheCatalog() {
        val service = FrontlineService()
        show(service, 600)
        openFrontline()
        compose.onNodeWithTag("personal-data-refresh").performClick()
        compose.waitUntil(5_000) { service.reads.size == 14 && model().state.value.sections.values.all { it.status == PersonalDataFrontlineLoadStatus.Loaded } }
        Section.entries.forEach { assertEquals(2, service.reads.count { row -> row == it }) }
        assertEquals(1, service.frontlineCatalogReads)
        assertEquals(0, service.legacyReads)
    }

    @Test fun authenticationFailureClearsAllModelsAndRecreationDoesNotReadAgain() {
        val service = FrontlineService()
        show(service, 600)
        openFrontline()
        val old = populateSiblingCaches(service)
        compose.runOnIdle { service.authenticationFailure = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        waitTag("personal-data-auth")
        assertTrue(service.hasCommunityIdentity)
        assertCleared(old)
        val reads = service.reads.toList()
        val identityReads = service.identityReads
        val catalogReads = service.frontlineCatalogReads
        compose.activityRule.scenario.recreate()
        waitTag("personal-data-auth")
        assertCleared(old)
        assertEquals(reads, service.reads.toList())
        assertEquals(identityReads, service.identityReads)
        assertEquals(catalogReads, service.frontlineCatalogReads)
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
    }

    @Test fun capabilityRevocationClearsEveryPopulatedModel() {
        val service = FrontlineService()
        show(service, 600)
        openFrontline()
        val old = populateSiblingCaches(service)
        val reads = service.reads.toList()
        compose.runOnIdle { service.enabled = false }
        assertCleared(old)
        compose.onNodeWithText(text(R.string.personal_data_identity_required)).assertIsDisplayed()
        compose.onNodeWithTag("frontline-list").assertDoesNotExist()
        assertEquals(reads, service.reads.toList())
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
    }

    @Test fun replacingAnAuthorizedServiceDisposesAllOldCachesAndStartsAtTheHub() {
        val first = FrontlineService()
        val configuration = show(first, 600)
        openFrontline()
        val old = populateSiblingCaches(first)
        val firstReads = first.reads.toList()
        val second = FrontlineService()
        compose.runOnIdle { configuration.service = second }
        waitTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { models() !== old.models && models().main.state.value.identity != null }
        assertTrue(first.enabled)
        assertCleared(old)
        assertFalse(old.models.frontline.hasCommunityIdentity)
        assertFalse(old.models.dashboard.hasCommunityIdentity)
        assertNull(models().main.state.value.selectedBoard)
        assertTrue(second.reads.isEmpty())
        openFrontline()
        assertReadsOnce(second)
        assertEquals(firstReads, first.reads.toList())
    }

    private fun show(service: FrontlineService, width: Int): FrontlineConfiguration {
        val configuration = FrontlineConfiguration(service, width)
        compose.runOnIdle { FrontlineFixture.configuration = configuration }
        waitTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { models().main.state.value.identity != null }
        return configuration
    }

    private fun openFrontline() {
        compose.onNodeWithTag("personal-data-hub-pane").performScrollToNode(hasTestTag("personal-data-board-Frontline"))
        compose.onNodeWithTag("personal-data-board-Frontline").performClick()
        waitTag("frontline-list")
        compose.waitUntil(5_000) { Section.entries.all { model().state.value.sections[it]?.status in listOf(
            PersonalDataFrontlineLoadStatus.Loaded, PersonalDataFrontlineLoadStatus.Failed) } &&
            model().state.value.catalogStatus == PersonalDataFrontlineLoadStatus.Loaded }
    }

    private fun section(section: Section) {
        compose.onNodeWithTag("frontline-list").performScrollToIndex(1)
        compose.onNodeWithTag("frontline-section-$section").performScrollTo().performClick().assertIsSelected()
        compose.waitUntil(5_000) { model().state.value.selectedSection == section }
    }

    private fun choose(tag: String, option: Int) {
        node(tag).performClick()
        compose.onNodeWithTag("$tag-option-$option").performScrollTo().performClick()
    }

    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("frontline-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun models() = compose.runOnIdle { requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java].currentModels) }
    private fun model() = models().frontline
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun number(value: Long) = NumberFormat.getIntegerInstance(compose.activity.resources.configuration.locales[0]).format(value)
    private fun within(tag: String, value: String) = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    private fun nodeTop(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
    private fun assertReadsOnce(service: FrontlineService) {
        assertEquals(7, service.reads.size)
        Section.entries.forEach { assertEquals(it.name, 1, service.reads.count { row -> row == it }) }
        assertEquals(0, service.legacyReads)
    }

    private fun populateSiblingCaches(service: FrontlineService): FrontlinePopulatedModels {
        val models = models()
        compose.runOnIdle { models.dashboard.open(PersonalDataBoard.Glamour) }
        compose.waitUntil(5_000) { PersonalDataDashboardSectionKind.forBoard(PersonalDataBoard.Glamour).all {
            models.dashboard.state.value.sections[it]?.data != null } }
        compose.runOnIdle { models.reading.open(PersonalDataReadingPage.Sets) }
        waitTag("reading-list")
        compose.waitUntil(5_000) { models.reading.state.value.currentPageState.hasLoaded }
        Espresso.pressBack()
        waitTag("frontline-list")
        val exploration = compose.runOnIdle { models.exploration.model(service, ExplorationBoard.OccultCrescent) }
        compose.waitUntil(5_000) { exploration.state.value.overview != null }
        assertNotNull(models.main.state.value.identity)
        assertFalse(models.reading.state.value.pages.isEmpty())
        assertFalse(models.dashboard.state.value.sections.isEmpty())
        assertFalse(models.frontline.state.value.sections.isEmpty())
        return FrontlinePopulatedModels(models, exploration)
    }

    private fun assertCleared(old: FrontlinePopulatedModels) {
        compose.waitUntil(5_000) { old.models.main.state.value.identity == null && old.models.reading.state.value.pages.isEmpty() &&
            old.models.dashboard.state.value.sections.values.all { it.data == null } &&
            old.models.frontline.state.value.sections.values.all { it.data == null } && old.exploration.state.value.overview == null }
        assertNull(old.models.main.state.value.selectedBoard)
        assertEquals(PersonalDataOfficialCatalogs(), old.models.main.state.value.catalogs)
        assertNull(old.models.reading.state.value.catalogs)
        assertNull(old.models.dashboard.state.value.catalogs)
        assertNull(old.models.dashboard.state.value.supplementary)
        assertNull(old.models.frontline.state.value.catalogs)
        assertTrue(old.exploration.state.value.histories.isEmpty())
    }

    private fun capture(width: Int) {
        node("frontline-radar").assertIsDisplayed()
        compose.onNodeWithTag("personal-data-refresh").assertIsDisplayed()
        captureImage("frontline-$width.png")
    }

    private fun captureImage(name: String) {
        val frame = CountDownLatch(1)
        compose.runOnIdle { val view = compose.activity.window.decorView; view.postOnAnimation { view.postOnAnimation { frame.countDown() } } }
        assertTrue("Synthetic frontline frame did not render", frame.await(3, TimeUnit.SECONDS))
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

private data class FrontlinePopulatedModels(val models: PersonalDataScreenModels, val exploration: ExplorationViewModel)

class PersonalDataFrontlineTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val icon = File(cacheDir, "frontline-synthetic-icon.png")
        if (!icon.exists()) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(75, 112, 178)) }
            icon.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        FrontlineFixture.iconPath = icon.toURI().toString()
        setContent { FrontlineFixture.configuration?.let { configuration ->
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
        } }
    }
}

private object FrontlineFixture {
    var configuration by mutableStateOf<FrontlineConfiguration?>(null)
    var iconPath: String? = null
}

private class FrontlineConfiguration(service: PersonalDataService, width: Int) {
    var service by mutableStateOf(service)
    var width by mutableStateOf(width)
    var measuredWidthDp = 0f
}

private class FrontlineService : PersonalDataFrontlineService, PersonalDataDashboardService, PersonalDataExplorationService {
    var enabled by mutableStateOf(true)
    override val hasCommunityIdentity get() = enabled
    var failedSection: Section? = null
    var authenticationFailure = false
    var missingAchievementCatalog = false
    var legacyReads = 0
    var identityReads = 0
    var frontlineCatalogReads = 0
    val reads = CopyOnWriteArrayList<Section>()
    private val date = Instant.parse("2026-01-01T00:00:00Z")
    private val setRecords = listOf(PersonalDataGlamourSetRecord("synthetic-set-record", 1, setOf(1001), date))
    override fun frontlineJobIconUrl(jobName: String, hollow: Boolean) = FrontlineFixture.iconPath
    override fun frontlineCompanyFlagUrl(companyName: String) = FrontlineFixture.iconPath
    override fun frontlineAchievementImageUrl() = FrontlineFixture.iconPath
    override fun itemIconUrl(iconId: Int) = FrontlineFixture.iconPath
    override suspend fun fetchIdentity(): PersonalDataIdentity { identityReads++; return PersonalDataIdentity("Synthetic frontline reader", "Synthetic area", "Synthetic world", null) }
    override suspend fun fetchAvailability() = PersonalDataAvailability(PersonalDataBoard.entries.flatMap { it.statusKeys }.associateWith { "1" })
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent { legacyReads++; return PersonalDataBoardContent(board, emptyList(), emptyList()) }
    override suspend fun fetchUltimateDashboard() = UltimateDashboard(emptyList())
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary) = UltimateEncounterDetail(summary, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs(glamour = GlamourCatalogSummary(
        1, 0, 0, sets = listOf(GlamourCatalogSet(1, "Synthetic set", 1, listOf(GlamourCatalogSetItem(1, 1001, "Synthetic item", 1))))))
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind) = listOf(PersonalDataFishingRank("Synthetic fish", 1, "Synthetic category"))
    override suspend fun fetchRaceUsage() = listOf(PersonalDataRaceUsage("Synthetic race", "Synthetic gender", 0.5, 1, true, true))
    override suspend fun fetchGlamourSetRecords() = setRecords
    override suspend fun fetchDashboardSection(section: PersonalDataDashboardSectionKind): PersonalDataDashboardData = when (section) {
        PersonalDataDashboardSectionKind.GlamourSummary -> PersonalDataDashboardData.GlamourSummary(GlamourOverview(1, 2, 3))
        PersonalDataDashboardSectionKind.Races -> PersonalDataDashboardData.Races(fetchRaceUsage().map { PersonalDataRankedRace(it, 1) })
        PersonalDataDashboardSectionKind.Stains -> PersonalDataDashboardData.Stains(listOf(PersonalDataStainUsage(1, 1, 1)))
        PersonalDataDashboardSectionKind.Accessories -> PersonalDataDashboardData.Accessories(listOf(PersonalDataAccessoryUsage(1, 1, 1)))
        PersonalDataDashboardSectionKind.Vanity -> PersonalDataDashboardData.Vanity(listOf(PersonalDataVanityUsage(PersonalDataVanityPeriod.AllTime, 1, 1001, "Synthetic item", null, 1)))
        PersonalDataDashboardSectionKind.Sets -> PersonalDataDashboardData.Sets(setRecords)
        else -> error("This synthetic fixture only seeds the Glamour sibling cache")
    }
    override suspend fun fetchExplorationOverview(board: ExplorationBoard) = ExplorationOverview(board, true,
        listOf(ExplorationField(ExplorationFieldKind.KnowledgeLevel, "1")), listOf(ExplorationSection(ExplorationSectionKind.Overview,
            listOf(ExplorationRecord("synthetic-exploration", "Synthetic exploration", listOf(ExplorationField(ExplorationFieldKind.KnowledgeLevel, "1")))))))
    override suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind) = ExplorationSection(section)
    override suspend fun fetchFrontlineCatalogs(): PersonalDataFrontlineCatalogs {
        frontlineCatalogReads++
        return PersonalDataFrontlineCatalogs(listOf("昂萨哈凯尔", "尘封秘岩", "荣誉野", "周边遗迹群", "沃刻其特"),
            if (missingAchievementCatalog) emptyList() else (1..28).map {
                PersonalDataAchievementCatalogEntry(it, "Entry ${it.toString().padStart(2, '0')}", "Synthetic achievement description", null)
            })
    }
    override suspend fun fetchFrontlineSection(section: Section): Data {
        reads += section
        if (authenticationFailure) throw PersonalDataException.AuthenticationRequired
        if (failedSection == section) throw IOException("Synthetic section failure")
        return when (section) {
            Section.Overview -> Data.Overview(FrontlinePeriodKind.entries.map { period -> FrontlineOverviewRecord(
                period, when (period) { FrontlinePeriodKind.Total -> 100; FrontlinePeriodKind.Since51 -> 51; FrontlinePeriodKind.Last30Days -> 30 },
                12, 120, 0.12, 3.0, "Synthetic company", 20, 10, 2.5, 9,
                FrontlineAverages(1.2, 2.3, 0.4, 1000.0, 2000.0, 3000.0), FrontlineRanks(80.0, 50.0, 40.0, 90.0, 70.0, 60.0)) })
            Section.Weekly -> {
                val today = LocalDate.now()
                Data.Weekly(listOf(
                    FrontlineDayRecord(FrontlineDayStamp.CalendarDate(today.minusDays(6)), null, 1, 2, 1, 3),
                    FrontlineDayRecord(FrontlineDayStamp.CalendarDate(today.minusDays(5)), 8, 2, 4, 2, 6),
                    FrontlineDayRecord(FrontlineDayStamp.CalendarDate(today), 900, 800, 700, 600, 500),
                    FrontlineDayRecord(null, 1, 0, 1, 0, 1),
                ))
            }
            Section.Jobs -> Data.Jobs(FrontlinePeriodKind.entries.flatMap { period -> listOf(
                FrontlineJobRecord(period, "骑士", 20, 0.7, 10, 0.5, 2.0, 0.75, 3, FrontlineAverages(1.0, 2.0, 3.0)),
                FrontlineJobRecord(period, "战士", 10, 0.3, 5, 0.25, 1.5, 0.5, 2, FrontlineAverages(2.0, 1.0, 3.0)),
            ) })
            Section.Best -> Data.Best(FrontlineBestKind.entries.filter { it != FrontlineBestKind.Unknown }.map { kind ->
                FrontlineBestRecord(kind, "昂萨哈凯尔", FrontlineDayStamp.OffsetTime(date), "骑士", FrontlinePlacement.Second, 10, 2, 20, 1000, 2000, 3000,
                    listOf(FrontlineTeamScore(3, 300), FrontlineTeamScore(2, 200), FrontlineTeamScore(1, 100))) })
            Section.Maps -> Data.Maps(listOf(FrontlineMapRecord("昂萨哈凯尔", 10, 5, 20, 0.5), FrontlineMapRecord("尘封秘岩", 20, 4, 30, 0.2)))
            Section.MapJobs -> Data.MapJobs(listOf(FrontlineMapJobRecord("昂萨哈凯尔", "骑士", 7, 3, 12, 3.0 / 7), FrontlineMapJobRecord("尘封秘岩", "战士", 8, 2, 10, 0.25)))
            Section.Achievements -> Data.Achievements((1..24).map { FrontlineAchievementRecord(it,
                if (it == 1) null else "Entry ${it.toString().padStart(2, '0')}", null, FrontlineDayStamp.OffsetTime(date.minusSeconds(it.toLong()))) })
        }
    }
}
