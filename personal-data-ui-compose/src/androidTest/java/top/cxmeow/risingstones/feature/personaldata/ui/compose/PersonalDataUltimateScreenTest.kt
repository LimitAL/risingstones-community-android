package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateData as Data
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateSection as Section

/** Only the real root UI is used; all records, identities, and image files are synthetic. */
class PersonalDataUltimateScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PersonalDataUltimateTestActivity>()

    @After fun clear() { compose.runOnIdle { UltimateFixture.configuration = null } }

    @Test fun nativeNavigationAt599() = navigation(599)
    @Test fun nativeNavigationAt600() = navigation(600)
    @Test fun nativeNavigationAt839() = navigation(839)
    @Test fun nativeNavigationAt840() = navigation(840)

    private fun navigation(width: Int) {
        val service = UltimateService()
        val configuration = show(service, width)
        openUltimate()
        assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.02f)
        encounter(968)
        section(Section.Partners)
        node("ultimate-partners-more").performClick()
        node("ultimate-partner-5").assertIsDisplayed()
        awaitRenderedFrame()
        val top = nodeTop("ultimate-partner-5")
        val retainedModel = model()
        compose.activityRule.scenario.recreate()
        waitTag("ultimate-list")
        assertSame(retainedModel, model())
        compose.onNodeWithTag("ultimate-partner-5").assertIsDisplayed()
        awaitRenderedFrame()
        assertEquals(top, nodeTop("ultimate-partner-5"), 0.5f)
        assertEquals(968, model().state.value.selectedTerritoryType)
        assertEquals(Section.Partners, model().state.value.selectedSection)
        assertEquals(6, model().state.value.currentEncounter.partnerLimit)

        compose.runOnIdle { configuration.width = if (width < 600) 600 else 599 }
        compose.onNodeWithTag("ultimate-list").assertIsDisplayed()
        assertEquals(968, model().state.value.selectedTerritoryType)
        assertEquals(Section.Partners, model().state.value.selectedSection)
        assertEquals(6, model().state.value.currentEncounter.partnerLimit)
        compose.runOnIdle { configuration.width = width }
        if (width < 600) compose.onNodeWithTag("personal-data-hub-pane").assertDoesNotExist()
        else compose.onNodeWithTag("personal-data-hub-pane").assertIsDisplayed()

        Espresso.pressBack()
        compose.waitUntil(5_000) { model().state.value.selectedTerritoryType == null }
        assertEquals(PersonalDataBoard.Ultimate, models().main.state.value.selectedBoard)
        encounter(968)
        assertEquals(6, model().state.value.currentEncounter.partnerLimit)
        // Exercise the compact board back handler as well as the encounter's own back handler.
        if (width >= 600) compose.runOnIdle { configuration.width = 599 }
        Espresso.pressBack()
        compose.waitUntil(5_000) { model().state.value.selectedTerritoryType == null }
        Espresso.pressBack()
        waitTag("personal-data-hub-pane")
        assertNull(models().main.state.value.selectedBoard)
        openUltimate()
        encounter(968)
        compose.runOnIdle { configuration.width = width }
        assertSixReads(service)
        section(Section.Deaths)
        node("ultimate-scatter").assertIsDisplayed()
        capture("ultimate-$width.png")
    }

    @Test fun sixInterfacesAreReadOnceAcrossSectionsAndRepeatedEncounterNavigation() {
        val service = UltimateService()
        show(service, 840)
        openUltimate()
        assertEquals(1, service.overviewReads)
        assertTrue(service.sectionReads.isEmpty())
        node("ultimate-encounter-777").assertHasNoClickAction()
        encounter(968)
        val sections = linkedMapOf(Section.Party to "ultimate-party-0", Section.Jobs to "ultimate-job-0",
            Section.Partners to "ultimate-partner-0", Section.Phases to "ultimate-phase-0", Section.Deaths to "ultimate-scatter")
        repeat(2) { sections.forEach { (kind, tag) -> section(kind); node(tag).assertIsDisplayed() } }
        summary()
        node("ultimate-first-clear").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(5_000) { model().state.value.selectedTerritoryType == null }
        encounter(968)
        assertSixReads(service)
    }

    @Test fun bahamutHasNoPhaseTabOrReadAndUsesItsOwnDeathCoordinateTransform() {
        val service = UltimateService()
        show(service, 600)
        openUltimate()
        encounter(733)
        node("ultimate-phases-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("ultimate-section-Phases").assertDoesNotExist()
        assertEquals(4, service.sectionReads.size)
        assertFalse(service.sectionReads.any { it.second == Section.Phases })
        section(Section.Deaths)
        node("ultimate-death-1")
        within("ultimate-death-1", text(R.string.pdu_plot_coordinates, coordinate(10.0), coordinate(-20.0))).assertIsDisplayed()
        within("ultimate-death-1", text(R.string.pdu_raw_coordinates, coordinate(10.0), coordinate(20.0))).assertIsDisplayed()
        compose.onNodeWithTag("personal-data-refresh").performClick()
        compose.waitUntil(5_000) { service.overviewReads == 2 && service.sectionReads.size == 8 && loadedSections(733) }
        assertFalse(service.sectionReads.any { it.second == Section.Phases })
        assertNoLegacyReads(service)
    }

    @Test fun partyUsesStableRoleGroupsAndShowsAreaAndWorldWithoutGuessingProfileIdentity() {
        val service = UltimateService()
        show(service, 600)
        openUltimate()
        encounter(968)
        section(Section.Party)
        val names = listOf("Tank B", "Tank A", "Healer B", "Healer A", "Damage A", "Unknown A", "Unknown B")
        assertEquals(names, model().state.value.party.map { it.characterName })
        names.forEachIndexed { index, name ->
            node("ultimate-party-$index").assertHasNoClickAction()
            within("ultimate-party-$index", name).assertIsDisplayed()
            within("ultimate-party-$index", "Synthetic area · Synthetic world").assertIsDisplayed()
        }
        within("ultimate-party-6", text(R.string.pdr_unknown)).assertIsDisplayed()
        assertSixReads(service)
    }

    @Test fun partnersExpandLocallyAndKeepTheirLimitAfterReturningToTheEncounter() {
        val service = UltimateService()
        show(service, 599)
        openUltimate()
        encounter(968)
        section(Section.Partners)
        assertEquals(3, model().state.value.visiblePartners.size)
        node("ultimate-partner-0")
        within("ultimate-partner-0", "Partner 8").assertIsDisplayed()
        within("ultimate-partner-0", text(R.string.pdu_joint_entries, number(8))).assertIsDisplayed()
        compose.onNodeWithTag("ultimate-partner-3").assertDoesNotExist()
        node("ultimate-partners-more").performClick()
        assertEquals(6, model().state.value.visiblePartners.size)
        node("ultimate-partner-5")
        within("ultimate-partner-5", "Partner 3").assertIsDisplayed()
        node("ultimate-partners-more").performClick()
        assertEquals(8, model().state.value.visiblePartners.size)
        assertFalse(model().state.value.hasMorePartners)
        Espresso.pressBack()
        compose.waitUntil(5_000) { model().state.value.selectedTerritoryType == null }
        encounter(968)
        assertEquals(8, model().state.value.visiblePartners.size)
        assertEquals(9, model().state.value.currentEncounter.partnerLimit)
        assertSixReads(service)
    }

    @Test fun deathScatterIsTappableAndHasMatchingReadableRawAndTransformedCoordinates() {
        val service = UltimateService()
        show(service, 840)
        openUltimate()
        encounter(968)
        section(Section.Deaths)
        node("ultimate-excluded").assertTextEquals(text(R.string.pdu_excluded_points, 2))
        tapOrigin()
        node("ultimate-selected-point").assertTextEquals(pointText(0.0, 0.0))
        node("ultimate-death-1")
        within("ultimate-death-1", pointText(30.0, 20.0)).assertIsDisplayed()
        within("ultimate-death-1", text(R.string.pdu_raw_coordinates, coordinate(130.0), coordinate(80.0))).assertIsDisplayed()
        assertEquals(26, model().state.value.deathPlot?.points?.size)
        assertEquals(20, model().state.value.visibleDeaths.size)
        node("ultimate-deaths-more").performClick()
        assertEquals(26, model().state.value.visibleDeaths.size)
        node("ultimate-death-27").assertIsDisplayed()
        assertSixReads(service)
    }

    @Test fun realActivityRotationKeepsTheEncounterSectionAndSameCoordinateSelection() {
        val service = UltimateService()
        show(service, 599)
        openUltimate()
        encounter(968)
        section(Section.Deaths)
        tapOrigin()
        node("ultimate-selected-point").assertTextEquals(pointText(0.0, 0.0))
        val retained = model()
        val original = compose.activity.resources.configuration.orientation
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val resolver = compose.activity.contentResolver
        val originalRotation = displayRotation()
        val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val target = if (original == Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        val targetRotation = when (originalRotation) {
            Surface.ROTATION_0 -> Surface.ROTATION_90
            Surface.ROTATION_90 -> Surface.ROTATION_180
            Surface.ROTATION_180 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        fun rotate(rotation: Int) {
            val command = when (rotation) {
                Surface.ROTATION_0 -> UiAutomation.ROTATION_FREEZE_0
                Surface.ROTATION_90 -> UiAutomation.ROTATION_FREEZE_90
                Surface.ROTATION_180 -> UiAutomation.ROTATION_FREEZE_180
                Surface.ROTATION_270 -> UiAutomation.ROTATION_FREEZE_270
                else -> error("Unexpected synthetic test display rotation")
            }
            assertTrue("Display rotation request failed", automation.setRotation(command))
        }
        try {
            rotate(targetRotation)
            compose.waitUntil(10_000) { runCatching {
                displayRotation() == targetRotation && compose.activity.resources.configuration.orientation == target
            }.getOrDefault(false) }
            waitTag("ultimate-list")
            assertSame(retained, model())
            assertEquals(968, model().state.value.selectedTerritoryType)
            assertEquals(Section.Deaths, model().state.value.selectedSection)
            compose.onNodeWithTag("ultimate-list").performScrollToIndex(6)
            compose.onNodeWithTag("ultimate-selected-point").performScrollTo().assertTextEquals(pointText(0.0, 0.0))
            assertSixReads(service)
        } finally {
            try {
                rotate(originalRotation)
                compose.waitUntil(10_000) { runCatching {
                    displayRotation() == originalRotation && compose.activity.resources.configuration.orientation == original
                }.getOrDefault(false) }
            } finally {
                if (autoRotate != 0) assertTrue("Could not restore automatic rotation", automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
                compose.waitUntil(5_000) { Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == autoRotate }
            }
        }
    }

    @Test fun offscreenScatterSelectionSurvivesActivityRecreation() {
        val service = UltimateService()
        show(service, 599)
        openUltimate()
        encounter(968)
        section(Section.Deaths)
        tapOrigin()
        node("ultimate-selected-point").assertTextEquals(pointText(0.0, 0.0))
        node("ultimate-death-19").assertIsDisplayed()
        compose.onNodeWithTag("ultimate-scatter").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitTag("ultimate-list")
        node("ultimate-selected-point").assertTextEquals(pointText(0.0, 0.0))
        assertSixReads(service)
    }

    @Test fun refreshingChangedCoordinatesClearsSelectionEvenWhenTheSourceIndexIsReused() {
        val service = UltimateService()
        show(service, 600)
        openUltimate()
        encounter(968)
        section(Section.Deaths)
        tapOrigin()
        node("ultimate-selected-point").assertTextEquals(pointText(0.0, 0.0))
        compose.runOnIdle { service.shiftFirstDeath = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        compose.waitUntil(5_000) { service.overviewReads == 2 && service.sectionReads.size == 10 && loadedSections(968) }
        assertEquals(0, model().state.value.deathPlot?.points?.first()?.sourceIndex)
        assertEquals(10.0, requireNotNull(model().state.value.deathPlot).points.first().x, 0.0)
        node("ultimate-scatter").assertIsDisplayed()
        compose.onNodeWithTag("ultimate-selected-point").assertDoesNotExist()
        assertNoLegacyReads(service)
    }

    @Test fun detailRefreshUpdatesTheCurrentSummaryAndOverviewWithoutAStaleSnapshot() {
        val service = UltimateService()
        show(service, 600)
        openUltimate()
        encounter(968)
        node("ultimate-first-clear")
        within("ultimate-first-clear", "骑士").assertIsDisplayed()
        compose.runOnIdle { service.clearCount = 37; service.firstClearJob = "战士"; service.durationSeconds = 125 }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        compose.waitUntil(5_000) { service.overviewReads == 2 && service.sectionReads.size == 10 && loadedSections(968) }
        assertEquals(37L, model().state.value.selectedRecord?.clearCount)
        assertEquals(39L, model().state.value.totalClears)
        node("ultimate-summary")
        within("ultimate-summary", number(37)).performScrollTo().assertIsDisplayed()
        within("ultimate-summary", text(R.string.pdu_minutes_seconds, 2L, 5L)).performScrollTo().assertIsDisplayed()
        node("ultimate-first-clear")
        within("ultimate-first-clear", "战士").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(5_000) { model().state.value.selectedTerritoryType == null }
        node("ultimate-encounter-968")
        within("ultimate-encounter-968", text(R.string.pdu_clear_count, number(37))).assertIsDisplayed()
        Section.entries.forEach { section -> assertEquals(2, service.sectionReads.count { it == (968 to section) }) }
        assertNoLegacyReads(service)
    }

    @Test fun failedSectionRetriesIndependentlyWhileSuccessfulPartyRemainsCached() {
        val service = UltimateService().apply { failedSection = Section.Jobs }
        show(service, 600)
        openUltimate()
        encounter(968)
        section(Section.Jobs)
        node("ultimate-error-Jobs").assertIsDisplayed()
        assertNotNull(model().state.value.currentEncounter.sections[Section.Party]?.data)
        compose.runOnIdle { service.failedSection = null }
        node("ultimate-retry-Jobs").performClick()
        compose.waitUntil(5_000) { model().state.value.currentSection.status == PersonalDataUltimateLoadStatus.Loaded }
        node("ultimate-job-0")
        within("ultimate-job-0", "战士").assertIsDisplayed()
        assertEquals(1, service.overviewReads)
        Section.entries.forEach { section -> assertEquals(if (section == Section.Jobs) 2 else 1, service.sectionReads.count { it == (968 to section) }) }
        assertNoLegacyReads(service)
    }

    @Test fun authenticationFailureClearsUltimateAndReadingCachesAndStaysRejectedAfterRecreation() {
        val service = UltimateService()
        show(service, 600)
        openUltimate()
        encounter(968)
        val old = populateReadingCache()
        compose.runOnIdle { service.authenticationFailure = true }
        compose.onNodeWithTag("personal-data-refresh").performClick()
        waitTag("personal-data-auth")
        assertTrue(service.enabled)
        assertCleared(old)
        val counts = service.readCounts()
        compose.activityRule.scenario.recreate()
        waitTag("personal-data-auth")
        assertCleared(old)
        assertEquals(counts, service.readCounts())
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
    }

    @Test fun capabilityRevocationRemovesTheOpenEncounterAndItsSiblingReadingCache() {
        val service = UltimateService()
        show(service, 600)
        openUltimate()
        encounter(968)
        val old = populateReadingCache()
        val counts = service.readCounts()
        compose.runOnIdle { service.enabled = false }
        assertCleared(old)
        compose.onNodeWithText(text(R.string.personal_data_identity_required)).assertIsDisplayed()
        compose.onNodeWithTag("ultimate-list").assertDoesNotExist()
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
        assertEquals(counts, service.readCounts())
    }

    @Test fun serviceReplacementDisposesTheOldSelectionAndStartsWithAnUnreadNewOverview() {
        val first = UltimateService()
        val configuration = show(first, 600)
        openUltimate()
        encounter(968)
        val old = populateReadingCache()
        val counts = first.readCounts()
        val second = UltimateService()
        compose.runOnIdle { configuration.service = second }
        waitTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { models() !== old && models().main.state.value.identity != null }
        assertCleared(old)
        assertFalse(old.ultimate.hasCommunityIdentity)
        assertTrue(first.enabled)
        assertEquals(0, second.overviewReads)
        assertNull(models().main.state.value.selectedBoard)
        openUltimate()
        encounter(968)
        assertSixReads(second)
        assertEquals(counts, first.readCounts())
    }

    private fun show(service: UltimateService, width: Int): UltimateConfiguration {
        val configuration = UltimateConfiguration(service, width)
        compose.runOnIdle { UltimateFixture.configuration = configuration }
        waitTag("personal-data-hub-pane")
        compose.waitUntil(5_000) { models().main.state.value.identity != null }
        return configuration
    }
    private fun openUltimate() {
        compose.onNodeWithTag("personal-data-hub-pane").performScrollToNode(hasTestTag("personal-data-board-Ultimate"))
        compose.onNodeWithTag("personal-data-board-Ultimate").performClick()
        waitTag("ultimate-list")
        compose.waitUntil(5_000) { model().state.value.overviewStatus == PersonalDataUltimateLoadStatus.Loaded }
    }
    private fun encounter(territory: Int) {
        node("ultimate-encounter-$territory").performClick()
        compose.waitUntil(5_000) { model().state.value.selectedTerritoryType == territory && loadedSections(territory) }
    }
    private fun loadedSections(territory: Int): Boolean = Section.entries.filter { territory != 733 || it != Section.Phases }.all {
        model().state.value.encounters[territory]?.sections[it]?.status in listOf(PersonalDataUltimateLoadStatus.Loaded, PersonalDataUltimateLoadStatus.Failed)
    }
    private fun section(kind: Section) {
        compose.onNodeWithTag("ultimate-list").performScrollToIndex(2)
        compose.onNodeWithTag("ultimate-section-$kind").performScrollTo().performClick().assertIsSelected()
        compose.waitUntil(5_000) { model().state.value.selectedSection == kind }
    }
    private fun summary() {
        compose.onNodeWithTag("ultimate-list").performScrollToIndex(2)
        compose.onNodeWithTag("ultimate-section-Summary").performScrollTo().performClick().assertIsSelected()
    }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ultimate-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun tapOrigin() {
        node("ultimate-scatter").assertContentDescriptionEquals(text(R.string.pdu_scatter_description))
            .performTouchInput { click(center) }
    }
    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun models() = compose.runOnIdle { requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java].currentModels) }
    private fun model() = models().ultimate
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun number(value: Long) = NumberFormat.getIntegerInstance(compose.activity.resources.configuration.locales[0]).format(value)
    private fun coordinate(value: Double) = String.format(compose.activity.resources.configuration.locales[0], "%.1f", value)
    private fun pointText(x: Double, y: Double) = text(R.string.pdu_plot_coordinates, coordinate(x), coordinate(y))
    private fun within(tag: String, value: String) = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    private fun nodeTop(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
    private fun displayRotation() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(android.hardware.display.DisplayManager::class.java)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation

    private fun assertSixReads(service: UltimateService) {
        assertEquals(1, service.overviewReads)
        assertEquals(5, service.sectionReads.size)
        Section.entries.forEach { section -> assertEquals(1, service.sectionReads.count { it == (968 to section) }) }
        assertNoLegacyReads(service)
    }
    private fun assertNoLegacyReads(service: UltimateService) {
        assertEquals(0, service.legacyBoardReads)
        assertEquals(0, service.legacyDashboardReads)
        assertEquals(0, service.legacyDetailReads)
    }
    private fun populateReadingCache(): PersonalDataScreenModels {
        val old = models()
        compose.runOnIdle { old.reading.open(PersonalDataReadingPage.Sets) }
        waitTag("reading-list")
        compose.waitUntil(5_000) { old.reading.state.value.currentPageState.hasLoaded }
        Espresso.pressBack()
        waitTag("ultimate-list")
        assertNotNull(old.ultimate.state.value.records)
        assertFalse(old.ultimate.state.value.encounters.isEmpty())
        assertFalse(old.reading.state.value.pages.isEmpty())
        return old
    }
    private fun assertCleared(old: PersonalDataScreenModels) {
        compose.waitUntil(5_000) { old.main.state.value.identity == null && old.ultimate.state.value.records == null &&
            old.ultimate.state.value.encounters.isEmpty() && old.reading.state.value.pages.isEmpty() }
        assertNull(old.ultimate.state.value.selectedTerritoryType)
        assertNull(old.reading.state.value.catalogs)
        assertNull(old.main.state.value.selectedBoard)
        assertEquals(PersonalDataOfficialCatalogs(), old.main.state.value.catalogs)
    }
    private fun awaitRenderedFrame() {
        val frame = CountDownLatch(1)
        compose.runOnIdle { val view = compose.activity.window.decorView; view.postOnAnimation { view.postOnAnimation { frame.countDown() } } }
        assertTrue("Synthetic ultimate frame did not render", frame.await(3, TimeUnit.SECONDS))
        compose.waitForIdle()
    }
    private fun capture(fileName: String) {
        awaitRenderedFrame()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, fileName)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

class PersonalDataUltimateTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val icon = File(cacheDir, "ultimate-synthetic-icon.png")
        if (!icon.exists()) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(92, 78, 148)) }
            icon.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        UltimateFixture.iconPath = icon.toURI().toString()
        setContent { UltimateFixture.configuration?.let { configuration ->
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

private object UltimateFixture {
    var configuration by mutableStateOf<UltimateConfiguration?>(null)
    var iconPath: String? = null
}
private class UltimateConfiguration(service: PersonalDataService, width: Int) {
    var service by mutableStateOf(service)
    var width by mutableStateOf(width)
    var measuredWidthDp = 0f
}
private class UltimateService : PersonalDataUltimateService, PersonalDataReadingService {
    var enabled by mutableStateOf(true)
    override val hasCommunityIdentity get() = enabled
    var authenticationFailure = false
    var failedSection: Section? = null
    var shiftFirstDeath = false
    var clearCount = 4L
    var firstClearJob = "骑士"
    var durationSeconds = 3_661L
    var overviewReads = 0
    var identityReads = 0
    var setReads = 0
    var legacyBoardReads = 0
    var legacyDashboardReads = 0
    var legacyDetailReads = 0
    val sectionReads = CopyOnWriteArrayList<Pair<Int, Section>>()
    private val time = Instant.parse("2026-01-02T03:04:05Z")
    fun readCounts() = listOf(overviewReads, sectionReads.size, identityReads, setReads, legacyBoardReads, legacyDashboardReads, legacyDetailReads)
    override fun ultimateCoverUrl(territoryType: Int) = UltimateFixture.iconPath
    override fun ultimateJobIconUrl(jobName: String) = UltimateFixture.iconPath
    override fun ultimateMedalImageUrl(territoryType: Int) = UltimateFixture.iconPath
    override fun itemIconUrl(iconId: Int) = UltimateFixture.iconPath
    override fun ultimateJobOrder(jobName: String): Int? = when (jobName) {
        "战士", "骑士" -> 0
        "学者", "白魔法师" -> 1
        "赤魔法师" -> 2
        else -> null
    }
    override suspend fun fetchIdentity(): PersonalDataIdentity { identityReads++; return PersonalDataIdentity("Synthetic ultimate reader", "Synthetic area", "Synthetic world", null) }
    override suspend fun fetchAvailability() = PersonalDataAvailability(PersonalDataBoard.entries.flatMap { it.statusKeys }.associateWith { "1" })
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent { legacyBoardReads++; return PersonalDataBoardContent(board, emptyList(), emptyList()) }
    override suspend fun fetchUltimateDashboard(): UltimateDashboard { legacyDashboardReads++; return UltimateDashboard(emptyList()) }
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail {
        legacyDetailReads++
        return UltimateEncounterDetail(summary, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs(glamour = GlamourCatalogSummary(
        1, 0, 0, sets = listOf(GlamourCatalogSet(1, "Synthetic set", 1, listOf(GlamourCatalogSetItem(1, 1001, "Synthetic item", 1))))))
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind) = emptyList<PersonalDataFishingRank>()
    override suspend fun fetchRaceUsage() = emptyList<PersonalDataRaceUsage>()
    override suspend fun fetchGlamourSetRecords(): List<PersonalDataGlamourSetRecord> {
        setReads++
        return listOf(PersonalDataGlamourSetRecord("synthetic-set", 1, setOf(1001), time))
    }
    override suspend fun fetchUltimateRecords(): List<PersonalDataUltimateRecord> {
        overviewReads++
        if (authenticationFailure) throw PersonalDataException.AuthenticationRequired
        return listOf(PersonalDataUltimateRecord(733, 2, 100, "骑士", UltimateRecordTime.OffsetTime(time), 3661, 90),
            PersonalDataUltimateRecord(968, clearCount, 120, firstClearJob, UltimateRecordTime.OffsetTime(time), durationSeconds, 95))
    }
    override suspend fun fetchUltimateSection(territoryType: Int, section: Section): Data {
        sectionReads += territoryType to section
        if (authenticationFailure) throw PersonalDataException.AuthenticationRequired
        if (failedSection == section) throw IOException("Synthetic section failure")
        return when (section) {
            Section.Party -> Data.Party(listOf(
                "Damage A" to "赤魔法师", "Tank B" to "战士", "Unknown A" to "Synthetic unknown job",
                "Tank A" to "骑士", "Healer B" to "学者", "Healer A" to "白魔法师", "Unknown B" to null,
            ).map { (name, job) -> UltimatePartyMember(name, "Synthetic area", "Synthetic world", job) })
            Section.Jobs -> Data.Jobs(listOf(UltimateJobUsage("骑士", 2), UltimateJobUsage("战士", 7), UltimateJobUsage("Synthetic unknown job", null)))
            Section.Partners -> Data.Partners((1..8).map { UltimateCompanion("Partner $it", "Synthetic area", "World $it", it.toLong()) })
            Section.Phases -> Data.Phases(listOf(
                UltimatePhaseRecord("finish", UltimateRecordTime.OffsetTime(time.plusSeconds(30))),
                UltimatePhaseRecord("p2", UltimateRecordTime.OffsetTime(time.plusSeconds(20))),
                UltimatePhaseRecord("p1", UltimateRecordTime.OffsetTime(time.plusSeconds(10))),
                UltimatePhaseRecord("synthetic-stage", null),
            ))
            Section.Deaths -> Data.Deaths(buildList {
                add(if (territoryType == 733) UltimateDeathRecord(0.0, 0.0) else UltimateDeathRecord(if (shiftFirstDeath) 110.0 else 100.0, 100.0))
                add(if (territoryType == 733) UltimateDeathRecord(10.0, 20.0) else UltimateDeathRecord(130.0, 80.0))
                add(UltimateDeathRecord(null, 100.0))
                add(UltimateDeathRecord(Double.NaN, 100.0))
                for (index in 4..27) add(UltimateDeathRecord(105.0 + index, 103.0 + index % 5))
            })
        }
    }
}
