package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

/** Native root and navigation with synthetic responses only. No account or network is used. */
class PersonalDataPhantomWeaponScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PersonalDataPhantomWeaponTestActivity>()
    @After fun clear() { compose.runOnIdle { PhantomFixture.configuration = null } }

    @Test fun nativeNavigationAt599() = navigation(599)
    @Test fun nativeNavigationAt600() = navigation(600)
    @Test fun nativeNavigationAt839() = navigation(839)
    @Test fun nativeNavigationAt840() = navigation(840)

    private fun navigation(width: Int) {
        val service = PhantomService()
        val config = show(service, width)
        openWeapons()
        assertEquals(width.toFloat(), config.measuredWidthDp, .02f)
        compose.onNodeWithTag(if (width < 600) "exploration-compact" else "exploration-wide").assertIsDisplayed()
        stage(PhantomWeaponStage.Penumbrae)
        node("phantom-expand").performClick()
        node("phantom-weapon-47880").assertIsDisplayed()
        awaitFrame()
        val top = compose.onNodeWithTag("phantom-weapon-47880").getUnclippedBoundsInRoot().top.value
        val oldModel = model(service)
        compose.activityRule.scenario.recreate()
        waitTag("phantom-list")
        assertSame(oldModel, model(service))
        compose.onNodeWithTag("phantom-weapon-47880").assertIsDisplayed()
        awaitFrame()
        assertEquals(top, compose.onNodeWithTag("phantom-weapon-47880").getUnclippedBoundsInRoot().top.value, .5f)
        assertTrue(oldModel.phantomWeapons.value.isExpanded)
        // A narrower emulated dp width also shortens the viewport: preserve its first full anchor,
        // not a record that happened to be near the old viewport bottom.
        val firstVisible = firstFullyVisibleWeapon()
        compose.runOnIdle { config.width = if (width < 600) 600 else 599 }
        compose.onNodeWithTag(firstVisible).assertIsDisplayed()
        assertEquals(PhantomWeaponStage.Penumbrae, oldModel.phantomWeapons.value.selectedStage)
        compose.runOnIdle { config.width = width }
        compose.onNodeWithTag("phantom-weapon-47880").assertIsDisplayed()
        node("phantom-history").performClick()
        waitTag("exploration-content-RelicHistory")
        Espresso.pressBack()
        waitTag("phantom-list")
        compose.onNodeWithTag("phantom-history").assertIsDisplayed()
        assertTrue(oldModel.phantomWeapons.value.isExpanded)
        assertEquals(1, service.historyReads)
        assertEquals(1, service.snapshotReads)
        assertEquals(0, service.legacyReads)
        stage(PhantomWeaponStage.Obscurum)
        node("phantom-lens").assertIsDisplayed()
        capture("phantom-$width.png")
    }

    @Test fun eachStageUsesItsOwnMaterialProgressAndLocalSelection() {
        val service = PhantomService()
        show(service, 600); openWeapons()
        compose.onNodeWithTag("phantom-stage-Occultum").assertIsSelected()
        compose.onNodeWithTag("phantom-lens").assertDoesNotExist()
        stage(PhantomWeaponStage.Penumbrae)
        node("phantom-material-47744").assertIsDisplayed()
        node("phantom-history").assertIsDisplayed()
        stage(PhantomWeaponStage.Umbrae)
        node("phantom-element-Yellow").assertIsDisplayed()
        node("phantom-element-Green").assertIsDisplayed()
        stage(PhantomWeaponStage.Obscurum)
        node("phantom-cumulative").assertTextContains("650", substring = true)
        stage(PhantomWeaponStage.Eclipticum)
        node("phantom-material-50976").assertIsDisplayed()
        stage(PhantomWeaponStage.Occultum)
        node("phantom-collection").assertTextContains("2", substring = true)
        assertEquals(1, service.snapshotReads)
        assertEquals(0, service.legacyReads)
    }

    @Test fun filteringExpansionAndBackKeepLocalSelection() {
        val service = PhantomService()
        show(service, 599); openWeapons()
        stage(PhantomWeaponStage.Penumbrae)
        node("phantom-search").performTextInput("Weapon 3")
        Espresso.closeSoftKeyboard()
        node("phantom-weapon-47872").assertIsDisplayed()
        node("phantom-obtained-only").performClick()
        node("phantom-empty-filter").assertIsDisplayed()
        val old = model(service)
        compose.activityRule.scenario.recreate()
        waitTag("phantom-list")
        assertEquals("Weapon 3", old.phantomWeapons.value.query)
        assertTrue(old.phantomWeapons.value.obtainedOnly)
        compose.onNodeWithTag("exploration-back").performClick()
        waitTag("exploration-content-Overview")
        compose.onNodeWithTag("exploration-phantom").performScrollTo().performClick()
        node("phantom-empty-filter").assertIsDisplayed()
        assertEquals(1, service.snapshotReads)
    }

    @Test fun partialRefreshRetainsKnownProgressAndRetryReplacesIt() {
        val service = PhantomService()
        show(service, 840); openWeapons()
        compose.runOnIdle { service.itemFailure = true }
        compose.onNodeWithTag("exploration-refresh").performClick()
        node("phantom-records-error").assertIsDisplayed()
        node("phantom-weapon-51000").assertIsDisplayed()
        assertEquals(2, model(service).phantomWeapons.value.acquiredCount)
        compose.runOnIdle { service.itemFailure = false; service.closed = true }
        node("phantom-records-error-retry").performClick()
        waitTag("exploration-unavailable")
        assertNull(model(service).phantomWeapons.value.items)
        assertNull(model(service).phantomWeapons.value.catalog)
        compose.onNodeWithTag("phantom-weapon-51000").assertDoesNotExist()
    }

    @Test fun firstReadFailureDoesNotRenderUnobtainedWeaponsOrZeroProgress() {
        val service = PhantomService().apply { itemFailure = true }
        show(service, 600); openWeapons()
        node("phantom-records-error").assertIsDisplayed()
        node("phantom-no-progress").assertIsDisplayed()
        assertNull(model(service).phantomWeapons.value.items)
        compose.onNodeWithTag("phantom-collection").assertDoesNotExist()
        compose.onNodeWithTag("phantom-stages").performScrollToNode(hasTestTag("phantom-stage-Penumbrae"))
        compose.onNodeWithTag("phantom-stage-Penumbrae").assertIsNotEnabled()
    }

    @Test fun catalogRetryDoesNotRepeatTheBusinessBatch() {
        val service = PhantomService().apply { catalogFailure = true }
        show(service, 600); openWeapons()
        node("phantom-catalog-error").assertIsDisplayed()
        compose.runOnIdle { service.catalogFailure = false }
        node("phantom-catalog-error-retry").performClick()
        val current = model(service)
        compose.waitUntil(5_000) { current.phantomWeapons.value.catalog != null }
        node("phantom-weapon-51000").assertIsDisplayed()
        assertEquals(1, service.snapshotReads)
        assertEquals(2, service.catalogReads)
    }

    @Test fun authenticationFailureClearsRootAndWeaponsAndSurvivesRecreation() {
        val service = PhantomService()
        show(service, 600); openWeapons()
        val oldModel = model(service)
        val oldModels = models()
        compose.runOnIdle { service.authenticationFailure = true }
        compose.onNodeWithTag("exploration-refresh").performClick()
        waitTag("personal-data-auth")
        assertNull(oldModel.phantomWeapons.value.items)
        assertNull(oldModels.main.state.value.identity)
        assertNull(oldModel.phantomWeapons.value.selectedStage)
        val calls = service.snapshotReads
        compose.activityRule.scenario.recreate()
        waitTag("personal-data-auth")
        assertEquals(calls, service.snapshotReads)
        compose.onNodeWithTag("personal-data-refresh").assertIsNotEnabled()
    }

    @Test fun lateAuthenticationAfterLeavingExplorationStillClearsSiblingCaches() {
        val service = PhantomService()
        show(service, 600); openWeapons()
        val old = model(service)
        val oldRoot = models()
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { service.readGate = gate }
        compose.onNodeWithTag("exploration-refresh").performClick()
        compose.onNodeWithTag("exploration-back").performClick()
        compose.onNodeWithTag("exploration-back").performClick()
        waitTag("personal-data-hub-pane")
        assertNotNull(oldRoot.main.state.value.identity)
        compose.runOnIdle { service.authenticationFailure = true; gate.complete(Unit) }
        waitTag("personal-data-auth")
        assertNull(old.phantomWeapons.value.items)
        assertNull(oldRoot.main.state.value.identity)
        compose.activityRule.scenario.recreate()
        waitTag("personal-data-auth")
    }

    @Test fun returningFromWeaponsPreservesTheSourceOverviewAnchor() {
        val service = PhantomService()
        show(service, 600)
        compose.onNodeWithTag("personal-data-exploration-OccultCrescent").performScrollTo().performClick()
        waitTag("exploration-content-Overview")
        compose.onNodeWithTag("exploration-content-Overview").performScrollToNode(hasTestTag("exploration-record-summary-15"))
        compose.onNodeWithTag("exploration-record-summary-15").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("exploration-phantom").performScrollTo().performClick()
        stage(PhantomWeaponStage.Penumbrae)
        node("phantom-history").performClick()
        Espresso.pressBack()
        waitTag("phantom-list")
        compose.onNodeWithTag("exploration-back").performClick()
        compose.onNodeWithTag("exploration-record-summary-15").assertIsDisplayed()
        assertEquals(1, service.snapshotReads)
    }

    @Test fun capabilityRevocationClearsTheOpenCollection() {
        val service = PhantomService()
        show(service, 840); openWeapons()
        val old = model(service)
        compose.runOnIdle { service.enabled = false }
        compose.waitUntil(5_000) { old.phantomWeapons.value.items == null }
        compose.onNodeWithTag("phantom-list").assertDoesNotExist()
        assertNull(models().main.state.value.identity)
    }

    @Test fun serviceReplacementDisposesOldProgressBeforeOpeningNewData() {
        val first = PhantomService()
        val config = show(first, 600); openWeapons()
        val old = model(first)
        compose.runOnIdle { config.service = PhantomService().apply { empty = true } }
        waitTag("personal-data-hub-pane")
        assertNull(old.phantomWeapons.value.items)
        assertNull(old.phantomWeapons.value.catalog)
        openWeapons()
        node("phantom-no-progress").assertIsDisplayed()
        compose.onNodeWithTag("phantom-collection").assertDoesNotExist()
    }

    @Test fun realRotationKeepsTheSelectedStageAndFilter() {
        val service = PhantomService()
        show(service, 599); openWeapons()
        stage(PhantomWeaponStage.Umbrae)
        node("phantom-search").performTextInput("Weapon 1")
        Espresso.closeSoftKeyboard()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val automationContext = InstrumentationRegistry.getInstrumentation().targetContext
        val originalRotation = automationContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation
        val autoRotate = android.provider.Settings.System.getInt(automationContext.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
        try {
            automation.setRotation((originalRotation + 1) % 4)
            waitTag("phantom-list")
            compose.waitUntil(5_000) { InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation != originalRotation }
            assertEquals(PhantomWeaponStage.Umbrae, model(service).phantomWeapons.value.selectedStage)
            assertEquals("Weapon 1", model(service).phantomWeapons.value.query)
            assertEquals(1, service.snapshotReads)
        } finally { automation.setRotation(originalRotation); if (autoRotate == 1) automation.setRotation(-2) }
    }

    private fun show(service: PhantomService, width: Int): PhantomConfiguration {
        val config = PhantomConfiguration(service, width)
        compose.runOnIdle { PhantomFixture.configuration = config }
        waitTag("personal-data-hub-pane")
        return config
    }
    private fun openWeapons() {
        compose.onNodeWithTag("personal-data-exploration-OccultCrescent").performScrollTo().performClick()
        waitTag("exploration-content-Overview")
        compose.onNodeWithTag("exploration-phantom").performScrollTo().performClick()
        waitTag("phantom-list")
    }
    private fun stage(stage: PhantomWeaponStage) {
        compose.onNodeWithTag("phantom-stages").performScrollToNode(hasTestTag("phantom-stage-$stage"))
        compose.onNodeWithTag("phantom-stage-$stage").performScrollTo().performClick().assertIsSelected()
    }
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("phantom-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun models() = compose.runOnIdle { requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner", PersonalDataScreenModelOwner::class.java].currentModels) }
    private fun model(service: PhantomService): ExplorationViewModel {
        val current = models()
        return compose.runOnIdle { current.exploration.model(service, ExplorationBoard.OccultCrescent) }
    }
    private fun firstFullyVisibleWeapon(): String {
        val viewport = compose.onNodeWithTag("phantom-list").fetchSemanticsNode().boundsInRoot
        return (47869..47890).map { "phantom-weapon-$it" }.first { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let {
                it.height > 0 && it.top >= viewport.top && it.bottom <= viewport.bottom
            } == true
        }
    }
    private fun awaitFrame() {
        val latch = CountDownLatch(1)
        compose.runOnIdle { val view = compose.activity.window.decorView; view.postOnAnimation { view.postOnAnimation { latch.countDown() } } }
        assertTrue(latch.await(3, TimeUnit.SECONDS)); compose.waitForIdle()
    }
    private fun capture(name: String) {
        awaitFrame()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}

class PersonalDataPhantomWeaponTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val icon = File(cacheDir, "phantom-synthetic-icon.png")
        if (!icon.exists()) {
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(68, 112, 100)) }
            icon.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        PhantomFixture.iconPath = icon.toURI().toString()
        setContent { PhantomFixture.configuration?.let { config ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = Density(constraints.maxWidth.toFloat() / config.width, 1f)
                CompositionLocalProvider(LocalDensity provides density) {
                    MaterialTheme { Box(Modifier.fillMaxSize().onSizeChanged { config.measuredWidthDp = with(density) { it.width.toDp().value } }) {
                        RisingStonesPersonalDataScreen(config.service, {})
                    } }
                }
            }
        } }
    }
}
private object PhantomFixture { var configuration by mutableStateOf<PhantomConfiguration?>(null); var iconPath: String? = null }
private class PhantomConfiguration(service: PhantomService, width: Int) {
    var service by mutableStateOf(service)
    var width by mutableStateOf(width)
    var measuredWidthDp = 0f
}
private class PhantomService : PersonalDataPhantomWeaponService {
    var enabled by mutableStateOf(true)
    override val hasCommunityIdentity get() = enabled
    var snapshotReads = 0; var legacyReads = 0; var historyReads = 0; var catalogReads = 0
    var readGate: CompletableDeferred<Unit>? = null
    var authenticationFailure = false; var catalogFailure = false; var itemFailure = false; var closed = false; var empty = false
    override fun phantomWeaponItemIconUrl(iconId: Int) = PhantomFixture.iconPath
    override fun phantomWeaponElementIconUrl(element: PhantomWeaponElement) = PhantomFixture.iconPath
    override fun phantomWeaponLensImageUrl(step: Int) = PhantomFixture.iconPath
    private val catalog = PhantomWeaponCatalog(
        PhantomWeaponStage.entries.flatMap { stage ->
            val first = listOf(47869, 47006, 50032, 50978, 51000)[stage.ordinal]
            (0..21).map { index -> PhantomWeaponDefinition(stage, first + index, "${stage.name} Weapon $index", 30694) }
        },
        (47744..47749).map { PhantomWeaponMaterialDefinition(it, "Synthetic crystal $it", 26025) },
        (50974..50976).map { PhantomWeaponMaterialDefinition(it, "Synthetic demiatma $it", 26229) },
    )
    override suspend fun fetchPhantomWeaponCatalog(): PhantomWeaponCatalog { catalogReads++; if (catalogFailure) throw ExplorationException.InvalidResponse; return catalog }
    override suspend fun fetchPhantomWeaponExploration(): PhantomWeaponExplorationSnapshot {
        snapshotReads++
        readGate?.await()
        if (authenticationFailure) throw ExplorationException.AuthenticationRequired
        val items = if (empty) emptyList() else catalog.weapons.filter { (it.itemId - listOf(47869, 47006, 50032, 50978, 51000)[it.stage.ordinal]) < 2 }.map {
            PhantomWeaponItemRecord(it.itemId, "幻境武器", 0, PhantomWeaponRecordTime.CalendarDate(LocalDate.of(2026, 9, 20)), it.name)
        } + listOf(PhantomWeaponItemRecord(47744, "半魂晶", 8, null, null), PhantomWeaponItemRecord(50000, "水晶混合黏土", 650, null, null)) +
            (50974..50976).map { PhantomWeaponItemRecord(it, "消幻晶", 100, null, null) }
        return PhantomWeaponExplorationSnapshot(ExplorationOverview(ExplorationBoard.OccultCrescent, !closed, emptyList(), listOf(
            ExplorationSection(ExplorationSectionKind.Overview, (0..29).map { ExplorationRecord("summary-$it", "Synthetic overview $it", emptyList()) }))),
            if (closed) null else PhantomWeaponItemSection(items, if (itemFailure) ExplorationFailure.Network else null),
            if (closed) null else PhantomWeaponAetherSection(if (empty) emptyList() else listOf(PhantomWeaponAetherRecord("yellow", 1250))))
    }
    override suspend fun fetchExplorationOverview(board: ExplorationBoard): ExplorationOverview { legacyReads++; error("Typed extension should reuse one batch") }
    override suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind): ExplorationSection {
        historyReads++; return ExplorationSection(section, listOf(ExplorationRecord("history", "Synthetic drop", listOf(ExplorationField(ExplorationFieldKind.Quantity, "8")))))
    }
    override suspend fun fetchIdentity() = PersonalDataIdentity("Synthetic weapon reader", "", "", null)
    override suspend fun fetchAvailability() = PersonalDataAvailability(emptyMap())
    override suspend fun fetchBoardContent(board: PersonalDataBoard) = PersonalDataBoardContent(board, emptyList(), emptyList())
    override suspend fun fetchUltimateDashboard() = UltimateDashboard(emptyList())
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused")
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
}
