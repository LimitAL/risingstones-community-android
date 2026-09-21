package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationSectionKind.*
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponStage.*

@OptIn(ExperimentalCoroutinesApi::class)
class PhantomWeaponViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun optionalOccultReaderUsesOneSharedSnapshotAndLegacyOrOtherBoardUsesOldRead() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertEquals(1, service.snapshotCalls); assertEquals(1, service.catalogCalls); assertEquals(0, service.legacyCalls)
        assertEquals("same-batch", model.state.value.section?.records?.single()?.title)
        assertEquals(1, model.phantomWeapons.value.acquiredCount)
        val legacy = ExplorationViewModel(object : PersonalDataExplorationService by service {}, ExplorationBoard.OccultCrescent)
        val deep = ExplorationViewModel(service, ExplorationBoard.DeepDungeon)
        advanceUntilIdle()
        assertFalse(legacy.supportsPhantomWeapons); assertFalse(deep.supportsPhantomWeapons)
        assertEquals(2, service.legacyCalls); assertEquals(1, service.snapshotCalls); assertEquals(1, service.catalogCalls)
        legacy.openPhantomWeapons(); deep.openPhantomWeapons()
        assertFalse(legacy.phantomWeapons.value.isOpen); assertFalse(deep.phantomWeapons.value.isOpen)
    }

    @Test fun snapshotAndCatalogAreIndependentAndCatalogCannotProvePersonalDataLoaded() = runTest {
        val snapshot = PhantomGate<PhantomWeaponExplorationSnapshot>()
        val service = PhantomFixture().apply { read = { snapshot.await() } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        runCurrent()
        assertNotNull(model.phantomWeapons.value.catalog)
        assertNull(model.phantomWeapons.value.items); assertNull(model.phantomWeapons.value.maximumStage)
        assertTrue(model.phantomWeapons.value.visibleWeapons.isEmpty())
        model.openPhantomWeapons(); assertFalse(model.phantomWeapons.value.isOpen)
        snapshot.complete(phantomSnapshot()); advanceUntilIdle()
        model.openPhantomWeapons(); assertTrue(model.phantomWeapons.value.isOpen)
        assertEquals(Penumbrae, model.phantomWeapons.value.selectedStage)
    }

    @Test fun catalogFailureHasIndependentRetryAndNeverReissuesBusinessRead() = runTest {
        val service = PhantomFixture().apply { catalog = { error("private catalog error") } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertEquals(ExplorationError.Network, model.phantomWeapons.value.catalogError)
        assertNotNull(model.phantomWeapons.value.items)
        assertNull(model.state.value.error)
        service.catalog = { phantomCatalog() }
        model.retryPhantomWeaponCatalog(); model.retryPhantomWeaponCatalog(); advanceUntilIdle()
        assertEquals(2, service.catalogCalls); assertEquals(1, service.snapshotCalls)
        assertNull(model.phantomWeapons.value.catalogError)
        assertEquals(22, model.phantomWeapons.value.weapons.size)
    }

    @Test fun emptyOrFailedCatalogRefreshRetainsLastUsableCatalog() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        val previous = model.phantomWeapons.value.catalog
        service.catalog = { PhantomWeaponCatalog(emptyList(), emptyList(), emptyList()) }
        model.retryPhantomWeaponCatalog(); advanceUntilIdle()
        assertEquals(ExplorationError.Unavailable, model.phantomWeapons.value.catalogError)
        assertSame(previous, model.phantomWeapons.value.catalog)
        service.catalog = { throw ExplorationException.InvalidResponse }
        model.retryPhantomWeaponCatalog(); advanceUntilIdle()
        assertEquals(ExplorationError.InvalidResponse, model.phantomWeapons.value.catalogError)
        assertSame(previous, model.phantomWeapons.value.catalog)
    }

    @Test fun lateCatalogKeepsStageUnknownUntilItsArrivalThenSelectsFurthest() = runTest {
        val gate = PhantomGate<PhantomWeaponCatalog>()
        val service = PhantomFixture().apply { read = { phantomSnapshot(stageFiveItems()) }; catalog = { gate.await() } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        runCurrent()
        assertNull(model.phantomWeapons.value.selectedStage)
        assertNull(model.phantomWeapons.value.maximumStage)
        model.selectPhantomWeaponStage(Penumbrae)
        gate.complete(phantomCatalog()); advanceUntilIdle()
        assertEquals(Occultum, model.phantomWeapons.value.selectedStage)
    }

    @Test fun failedFirstSourcesStayUnknownAndDoNotBecomeUnobtainedWhileSuccessfulEmptyIsKnown() = runTest {
        val service = PhantomFixture().apply { read = { phantomSnapshot().copy(items = PhantomWeaponItemSection(failure = ExplorationFailure.Network), aether = null) } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertNull(model.phantomWeapons.value.items); assertNull(model.phantomWeapons.value.aether)
        assertEquals(ExplorationError.Network, model.phantomWeapons.value.itemError)
        assertEquals(ExplorationError.InvalidResponse, model.phantomWeapons.value.aetherError)
        assertNull(model.phantomWeapons.value.maximumStage); assertTrue(model.phantomWeapons.value.visibleWeapons.isEmpty())
        service.read = { phantomSnapshot(emptyList()) }
        model.loadOverview(); advanceUntilIdle()
        assertEquals(emptyList<PhantomWeaponItemRecord>(), model.phantomWeapons.value.items)
        assertEquals(emptyList<PhantomWeaponAetherRecord>(), model.phantomWeapons.value.aether)
        assertNull(model.phantomWeapons.value.maximumStage); assertNull(model.phantomWeapons.value.itemError)
    }

    @Test fun partialAndTotalRefreshFailuresKeepOnlyPreviouslySuccessfulSourcesAndUserCriteria() = runTest {
        val service = PhantomFixture().apply { read = { phantomSnapshot(stageFiveItems()) } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); model.openPhantomWeapons(); model.selectPhantomWeaponStage(Penumbrae)
        model.setPhantomWeaponQuery(" Weapon "); model.setPhantomWeaponsObtainedOnly(true); model.setPhantomWeaponsExpanded(true)
        val before = model.phantomWeapons.value
        service.read = { phantomSnapshot().copy(items = PhantomWeaponItemSection(failure = ExplorationFailure.Business)) }
        model.refresh(); advanceUntilIdle()
        assertEquals(before.items, model.phantomWeapons.value.items)
        assertEquals(ExplorationError.Business, model.phantomWeapons.value.itemError)
        assertNull(model.phantomWeapons.value.aetherError)
        assertEquals(Penumbrae, model.phantomWeapons.value.selectedStage)
        service.read = { throw ExplorationException.Network }
        model.refresh(); advanceUntilIdle()
        assertEquals(before.items, model.phantomWeapons.value.items)
        assertEquals(before.query, model.phantomWeapons.value.query); assertTrue(model.phantomWeapons.value.isExpanded)
        assertEquals(ExplorationError.Network, model.phantomWeapons.value.itemError)
        assertEquals(ExplorationError.Network, model.phantomWeapons.value.aetherError)
        assertFalse(model.phantomWeapons.value.isLoading)
    }

    @Test fun localControlsNeverReadAndStageChangeResetsOnlyExpansion() = runTest {
        val service = PhantomFixture().apply { read = { phantomSnapshot(stageFiveItems()) } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); model.openPhantomWeapons()
        model.setPhantomWeaponQuery("47869"); model.setPhantomWeaponsObtainedOnly(true); model.setPhantomWeaponsExpanded(true)
        model.selectPhantomWeaponStage(Penumbrae)
        assertFalse(model.phantomWeapons.value.isExpanded)
        assertEquals("47869", model.phantomWeapons.value.query); assertTrue(model.phantomWeapons.value.obtainedOnly)
        model.setPhantomWeaponsExpanded(true); model.closePhantomWeapons(); model.openPhantomWeapons()
        assertTrue(model.phantomWeapons.value.isExpanded); assertEquals(Penumbrae, model.phantomWeapons.value.selectedStage)
        assertEquals(1, service.snapshotCalls); assertEquals(1, service.catalogCalls); assertEquals(0, service.historyCalls)
    }

    @Test fun aetherFailureRetainsItsOwnLastSuccessfulRowsWhileSuccessfulEmptyItemsReplaceOldRows() = runTest {
        val points = listOf(PhantomWeaponAetherRecord("yellow", 250), PhantomWeaponAetherRecord("future", null))
        val service = PhantomFixture().apply { read = { phantomSnapshot().copy(aether = PhantomWeaponAetherSection(points)) } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        service.read = { phantomSnapshot(emptyList()).copy(aether = PhantomWeaponAetherSection(failure = ExplorationFailure.Network)) }
        model.loadOverview(); advanceUntilIdle()
        assertEquals(emptyList<PhantomWeaponItemRecord>(), model.phantomWeapons.value.items)
        assertEquals(points, model.phantomWeapons.value.aether)
        assertNull(model.phantomWeapons.value.itemError)
        assertEquals(ExplorationError.Network, model.phantomWeapons.value.aetherError)
        assertEquals(Umbrae, model.phantomWeapons.value.maximumStage)
        assertEquals(250L, (model.phantomWeapons.value.materials as PhantomWeaponMaterials.Aether).rows.first().points)
    }

    @Test fun successfulLowerStageRefreshClampsSelectionAndFoldsButUnknownSourceCannotDowngradeCache() = runTest {
        val service = PhantomFixture().apply { read = { phantomSnapshot(stageFiveItems()) } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); model.selectPhantomWeaponStage(Occultum); model.setPhantomWeaponsExpanded(true)
        service.read = { phantomSnapshot() }
        model.loadOverview(); advanceUntilIdle()
        assertEquals(Penumbrae, model.phantomWeapons.value.selectedStage); assertFalse(model.phantomWeapons.value.isExpanded)
        model.selectPhantomWeaponStage(Occultum); assertEquals(Penumbrae, model.phantomWeapons.value.selectedStage)
    }

    @Test fun relicHistoryRoundTripKeepsCriteriaUsesHistoryCacheAndWeaponRefreshUsesOverview() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); model.openPhantomWeapons(); model.setPhantomWeaponQuery("47869"); model.setPhantomWeaponsExpanded(true)
        model.openPhantomWeaponHistory(); advanceUntilIdle()
        assertEquals(RelicHistory, model.state.value.selectedSection)
        assertTrue(model.phantomWeapons.value.returnFromHistory); assertFalse(model.phantomWeapons.value.isOpen)
        assertTrue(model.returnToPhantomWeapons())
        assertEquals(Overview, model.state.value.selectedSection)
        assertEquals("47869", model.phantomWeapons.value.query); assertTrue(model.phantomWeapons.value.isExpanded)
        model.refresh(); advanceUntilIdle()
        assertEquals(2, service.snapshotCalls); assertEquals(1, service.historyCalls)
        model.openPhantomWeaponHistory(); advanceUntilIdle(); assertEquals(1, service.historyCalls)
        model.selectSection(Overview); assertFalse(model.returnToPhantomWeapons())
    }

    @Test fun returningAndClosingWeaponsRestoresTheParentSectionChosenBeforeOpening() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        for (parent in listOf(Overview, Aether)) {
            model.selectSection(parent); model.openPhantomWeapons(); model.openPhantomWeaponHistory(); advanceUntilIdle()
            assertTrue(model.returnToPhantomWeapons()); model.closePhantomWeapons()
            assertEquals(parent, model.state.value.selectedSection)
            assertFalse(model.phantomWeapons.value.isOpen)
        }
    }

    @Test fun synchronousInitialReadsFetchCatalogOnlyOnceAndExplicitRetryStillWorks() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        assertEquals(1, service.snapshotCalls); assertEquals(1, service.catalogCalls)
        model.retryPhantomWeaponCatalog()
        assertEquals(2, service.catalogCalls)
        service.history = { throw ExplorationException.AuthenticationRequired }
        model.openPhantomWeapons(); model.openPhantomWeaponHistory()
        assertEquals(ExplorationError.AuthenticationRequired, model.state.value.error)
        assertFalse(model.phantomWeapons.value.returnFromHistory)
        model.clearProtectedContent()
        val rejected = PhantomFixture().apply { read = { throw ExplorationException.AuthenticationRequired } }
        val authModel = ExplorationViewModel(rejected, ExplorationBoard.OccultCrescent)
        assertEquals(1, rejected.snapshotCalls); assertEquals(0, rejected.catalogCalls)
        assertEquals(ExplorationError.AuthenticationRequired, authModel.state.value.error)
    }

    @Test fun returningFromPendingHistoryDiscardsLateResponseAndClosingWeaponsDoesNotCancelSharedOverview() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); model.openPhantomWeapons()
        val history = PhantomGate<ExplorationSection>()
        service.history = { history.await() }
        model.openPhantomWeaponHistory(); runCurrent(); assertTrue(model.returnToPhantomWeapons())
        assertTrue(history.job.isCancelled)
        history.complete(ExplorationSection(RelicHistory)); advanceUntilIdle()
        assertFalse(RelicHistory in model.state.value.histories)
        val read = PhantomGate<PhantomWeaponExplorationSnapshot>()
        service.read = { read.await() }
        model.refresh(); runCurrent(); model.closePhantomWeapons()
        assertFalse(read.job.isCancelled)
        read.complete(phantomSnapshot(stageFiveItems())); advanceUntilIdle()
        assertEquals(Occultum, model.phantomWeapons.value.maximumStage)
        assertFalse(model.phantomWeapons.value.isOpen)
    }

    @Test fun allThreeIndependentCancellationsPropagateWithoutErrorOrStuckLoading() = runTest {
        val service = PhantomFixture().apply { read = { throw CancellationException() }; catalog = { throw CancellationException() } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertFalse(model.state.value.isLoadingOverview); assertFalse(model.phantomWeapons.value.isLoadingCatalog)
        assertFalse(model.phantomWeapons.value.isLoading); assertNull(model.state.value.error); assertNull(model.phantomWeapons.value.catalogError)
        service.read = { phantomSnapshot() }; service.catalog = { phantomCatalog() }
        model.loadOverview(); advanceUntilIdle(); model.openPhantomWeapons()
        service.history = { throw CancellationException() }
        model.openPhantomWeaponHistory(); advanceUntilIdle()
        assertFalse(model.state.value.isLoadingHistory); assertNull(model.state.value.error)
        assertTrue(model.returnToPhantomWeapons())
    }

    @Test fun overviewABAAndCancelledAuthenticationCannotReplaceCurrentSuccessfulSources() = runTest {
        val old = PhantomGate<PhantomWeaponExplorationSnapshot>()
        val service = PhantomFixture().apply { read = { old.await() } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        runCurrent()
        service.read = { phantomSnapshot(stageFiveItems()) }
        model.loadOverview(); advanceUntilIdle()
        old.fail(ExplorationException.AuthenticationRequired); advanceUntilIdle()
        assertEquals(Occultum, model.phantomWeapons.value.maximumStage)
        assertNull(model.state.value.error)
        assertTrue(old.job.isCancelled)
    }

    @Test fun authenticationFromOverviewHistoryOrCatalogClearsAllPrivateCachesAndGeneration() = runTest {
        repeat(3) { origin ->
            val service = PhantomFixture()
            val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
            advanceUntilIdle(); model.openPhantomWeapons(); model.openPhantomWeaponHistory(); advanceUntilIdle(); model.returnToPhantomWeapons()
            val generation = model.phantomWeapons.value.contentGeneration
            when (origin) {
                0 -> { service.read = { throw ExplorationException.AuthenticationRequired }; model.refresh() }
                1 -> { service.history = { throw PersonalDataException.AuthenticationRequired }; model.openPhantomWeaponHistory(); model.refresh() }
                else -> { service.catalog = { throw ExplorationException.AuthenticationRequired }; model.retryPhantomWeaponCatalog() }
            }
            advanceUntilIdle()
            assertEquals(ExplorationError.AuthenticationRequired, model.state.value.error)
            assertNull(model.state.value.overview); assertTrue(model.state.value.histories.isEmpty())
            assertNull(model.phantomWeapons.value.items); assertNull(model.phantomWeapons.value.catalog)
            assertFalse(model.phantomWeapons.value.isOpen); assertFalse(model.returnToPhantomWeapons())
            assertTrue(model.phantomWeapons.value.contentGeneration > generation)
        }
    }

    @Test fun availabilityClosingClearsSourcesCatalogHistoryAndRejectsLateCatalogThenCanReenter() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); model.openPhantomWeapons(); model.openPhantomWeaponHistory(); advanceUntilIdle()
        val catalog = PhantomGate<PhantomWeaponCatalog>()
        service.catalog = { catalog.await() }; model.retryPhantomWeaponCatalog(); runCurrent()
        service.read = { phantomSnapshot().copy(overview = phantomOverview().copy(available = false)) }
        model.loadOverview(); advanceUntilIdle()
        assertNull(model.phantomWeapons.value.items); assertTrue(model.state.value.histories.isEmpty())
        assertTrue(catalog.job.isCancelled)
        catalog.complete(phantomCatalog()); advanceUntilIdle(); assertNull(model.phantomWeapons.value.catalog)
        service.read = { phantomSnapshot() }; service.catalog = { phantomCatalog() }
        model.loadOverview(); advanceUntilIdle(); model.openPhantomWeapons()
        assertTrue(model.phantomWeapons.value.isOpen); assertNotNull(model.phantomWeapons.value.catalog)
    }

    @Test fun revocationBeforeCallsOrAfterAResponseClearsWithoutPublishingAndExplicitClearRejectsLateCatalog() = runTest {
        val denied = PhantomFixture().apply { hasCommunityIdentity = false }
        val absent = ExplorationViewModel(denied, ExplorationBoard.OccultCrescent)
        advanceUntilIdle(); assertEquals(0, denied.snapshotCalls); assertEquals(0, denied.catalogCalls)
        assertFalse(absent.hasCommunityIdentity)
        val read = PhantomGate<PhantomWeaponExplorationSnapshot>()
        val catalog = PhantomGate<PhantomWeaponCatalog>()
        val service = PhantomFixture().apply { this.read = { read.await() }; this.catalog = { catalog.await() } }
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        runCurrent(); service.hasCommunityIdentity = false
        read.complete(phantomSnapshot()); runCurrent()
        assertNull(model.state.value.overview); assertTrue(catalog.job.isCancelled)
        service.hasCommunityIdentity = true
        model.clearProtectedContent(); service.read = { phantomSnapshot(stageFiveItems()) }; service.catalog = { phantomCatalog() }
        model.loadOverview(); advanceUntilIdle()
        catalog.complete(PhantomWeaponCatalog(emptyList(), emptyList(), emptyList())); advanceUntilIdle()
        assertEquals(Occultum, model.phantomWeapons.value.maximumStage); assertNotNull(model.phantomWeapons.value.catalog)
    }

    @Test fun storeDisposalCancelsAllReadsClearsStateAndBlocksOldReferenceForever() = runTest {
        val read = PhantomGate<PhantomWeaponExplorationSnapshot>()
        val catalog = PhantomGate<PhantomWeaponCatalog>()
        val service = PhantomFixture().apply { this.read = { read.await() }; this.catalog = { catalog.await() } }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, ExplorationViewModelFactory(service, ExplorationBoard.OccultCrescent))[ExplorationViewModel::class.java]
        runCurrent(); owner.viewModelStore.clear()
        assertTrue(read.job.isCancelled); assertTrue(catalog.job.isCancelled)
        read.complete(phantomSnapshot()); catalog.complete(phantomCatalog()); advanceUntilIdle()
        model.loadOverview(); model.retryPhantomWeaponCatalog(); model.openPhantomWeapons(); advanceUntilIdle()
        assertEquals(1, service.snapshotCalls); assertEquals(1, service.catalogCalls)
        assertNull(model.phantomWeapons.value.items); assertNull(model.phantomWeapons.value.catalog); assertFalse(model.hasCommunityIdentity)
    }

    @Test fun imageHelpersOnlyDelegateAndDoNotTriggerAnyBusinessReads() = runTest {
        val service = PhantomFixture()
        val model = ExplorationViewModel(service, ExplorationBoard.OccultCrescent)
        advanceUntilIdle()
        assertEquals("item:123", model.phantomWeaponItemIconUrl(123))
        assertEquals("element:Red", model.phantomWeaponElementIconUrl(PhantomWeaponElement.Red))
        assertEquals("lens:5", model.phantomWeaponLensImageUrl(5))
        assertEquals(1, service.snapshotCalls); assertEquals(1, service.catalogCalls)
    }
}

private fun stageFiveItems() = List(3) { phantomItem(50974, "消幻晶", 100) } + phantomItem(50978) + phantomItem(47869)
private fun phantomOverview(board: ExplorationBoard = ExplorationBoard.OccultCrescent) = ExplorationOverview(board, true, emptyList(),
    listOf(ExplorationSection(board.sections().first(), listOf(ExplorationRecord("same", "same-batch", emptyList())))))
private fun phantomSnapshot(items: List<PhantomWeaponItemRecord> = listOf(phantomItem(47744, "半魂晶", 1), phantomItem(47869))) =
    PhantomWeaponExplorationSnapshot(phantomOverview(), PhantomWeaponItemSection(items), PhantomWeaponAetherSection())
private class PhantomGate<T> {
    private lateinit var continuation: Continuation<T>
    lateinit var job: Job
    suspend fun await(): T { job = checkNotNull(currentCoroutineContext()[Job]); return suspendCoroutine { continuation = it } }
    fun complete(value: T) = continuation.resume(value)
    fun fail(error: Exception) = continuation.resumeWithException(error)
}
private class PhantomFixture : PersonalDataPhantomWeaponService {
    override var hasCommunityIdentity = true
    var snapshotCalls = 0; var catalogCalls = 0; var legacyCalls = 0; var historyCalls = 0
    var read: suspend () -> PhantomWeaponExplorationSnapshot = { phantomSnapshot() }
    var catalog: suspend () -> PhantomWeaponCatalog = { phantomCatalog() }
    var history: suspend (ExplorationSectionKind) -> ExplorationSection = { ExplorationSection(it) }
    override suspend fun fetchPhantomWeaponExploration(): PhantomWeaponExplorationSnapshot { snapshotCalls++; return read() }
    override suspend fun fetchPhantomWeaponCatalog(): PhantomWeaponCatalog { catalogCalls++; return catalog() }
    override suspend fun fetchExplorationOverview(board: ExplorationBoard): ExplorationOverview { legacyCalls++; return phantomOverview(board) }
    override suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind): ExplorationSection { historyCalls++; return history(section) }
    override fun phantomWeaponItemIconUrl(iconId: Int) = "item:$iconId"
    override fun phantomWeaponElementIconUrl(element: PhantomWeaponElement) = "element:$element"
    override fun phantomWeaponLensImageUrl(step: Int) = "lens:$step"
    override suspend fun fetchIdentity(): PersonalDataIdentity = error("Unused")
    override suspend fun fetchAvailability(): PersonalDataAvailability = error("Unused")
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent = error("Unused")
    override suspend fun fetchUltimateDashboard(): UltimateDashboard = error("Unused")
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused")
    override suspend fun fetchOfficialCatalogs() = PersonalDataOfficialCatalogs()
}
