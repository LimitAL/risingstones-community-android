package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataReadingViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun noInitReadsAndExplicitPagesUseExactEndpointsWithIndependentCaches() = runTest {
        val service = ReadingFixture()
        val model = PersonalDataReadingViewModel(service)
        advanceUntilIdle()
        assertEquals(0, service.calls)
        PersonalDataReadingPage.entries.forEach { page -> model.open(page); advanceUntilIdle(); model.open(page); advanceUntilIdle() }
        assertEquals(listOf(PersonalDataFishingRankingKind.Fish, PersonalDataFishingRankingKind.Bait), service.fishingCalls)
        assertEquals(1, service.raceCalls)
        assertEquals(1, service.recordCalls)
        assertEquals(1, service.catalogCalls)
        model.close(); model.open(PersonalDataReadingPage.Fish); advanceUntilIdle()
        assertEquals(5, service.calls)
    }

    @Test fun legacyServiceIsUnavailableAndCannotPretendToReadTheOptionalPages() = runTest {
        val backing = ReadingFixture()
        val service = object : PersonalDataService by backing {}
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets)
        model.refresh(); model.retryCatalogs(); advanceUntilIdle()
        assertFalse(model.supportsReading)
        assertEquals(PersonalDataReadingLoadStatus.Unavailable, model.state.value.currentPageState.status)
        assertEquals(0, backing.calls)
        assertNull(model.state.value.setsProgress)
    }

    @Test fun fishingKeepsAllSourceRowsWhileSortingFilteringAndShowingMoreLocally() = runTest {
        val rows = (1..25).map { PersonalDataFishingRank("Fish $it", it.toLong(), when (it % 3) { 0 -> null; 1 -> ""; else -> " River " }) }
        val service = ReadingFixture().apply { fishing = { rows } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Fish); advanceUntilIdle()
        assertEquals(rows, model.state.value.currentPageState.fishingRows)
        assertEquals(10, model.state.value.visibleFishingRows.size)
        assertEquals(25L, model.state.value.visibleFishingRows.first().count)
        model.showMore(); model.showMore()
        assertEquals(25, model.state.value.visibleFishingRows.size)
        assertFalse(model.state.value.hasMore)
        model.selectCategory("")
        assertEquals(rows.count { it.category.isNullOrEmpty() }, model.state.value.totalFiltered)
        model.selectCategory("River")
        assertEquals(0, model.state.value.totalFiltered)
        model.selectCategory(" River ")
        model.updateQuery(" FISH 2 ")
        assertEquals(listOf("Fish 23", "Fish 20", "Fish 2"), model.state.value.visibleFishingRows.map { it.name })
        model.selectCategory(null)
        assertEquals(rows.count { it.name.contains("Fish 2") }, model.state.value.totalFiltered)
        assertEquals(1, service.calls)
    }

    @Test fun eachPageRetainsItsOwnCriteriaAndLimitAcrossCloseAndSwitch() = runTest {
        val service = ReadingFixture().apply { fishing = { (1..30).map { PersonalDataFishingRank("Fish $it", 1, "Sea") } } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Fish); advanceUntilIdle()
        model.updateQuery("Fish"); model.selectCategory("Sea"); model.showMore()
        model.open(PersonalDataReadingPage.Baits); advanceUntilIdle()
        assertEquals("", model.state.value.query)
        assertNull(model.state.value.category)
        assertEquals(10, model.state.value.visibleLimit)
        model.updateQuery("bait"); model.close(); model.open(PersonalDataReadingPage.Fish); advanceUntilIdle()
        assertEquals("Fish", model.state.value.query)
        assertEquals("Sea", model.state.value.category)
        assertEquals(20, model.state.value.visibleLimit)
        model.open(PersonalDataReadingPage.Baits)
        assertEquals("bait", model.state.value.query)
        assertEquals(2, service.calls)
    }

    @Test fun raceDisplaySortsFractionsDescendingWithUnknownLastWithoutChangingSource() = runTest {
        val rows = listOf(race("Unknown", null), race("Small", 0.1), race("Large", 0.8), race("Zero", 0.0))
        val service = ReadingFixture().apply { races = { rows } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Races); advanceUntilIdle()
        assertEquals(rows, model.state.value.currentPageState.races)
        assertEquals(listOf("Large", "Small", "Zero", "Unknown"), model.state.value.visibleRaces.map { it.race })
        model.updateQuery("female")
        assertEquals(4, model.state.value.totalFiltered)
        model.updateQuery("large")
        assertEquals(listOf(rows[2]), model.state.value.visibleRaces)
        assertEquals(1, service.raceCalls)
    }

    @Test fun repeatedSetRecordsRemainSeparateButProgressCountsUniqueValidCompleteSets() = runTest {
        val records = listOf(record("same", 1, 10, 11), record("same", 1, 10), record("unknown-item", 2, 20, 99), record("outside", 9, 90))
        val service = ReadingFixture().apply {
            sets = { records }
            catalogs = { catalog(set(1, 10, 11), set(1, 10, 11), set(2, 20), set(3), set(4, 40)) }
        }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(records, model.state.value.currentPageState.setRecords)
        assertEquals(6, model.state.value.allSets.size)
        assertEquals(6, model.state.value.allSets.map { it.key }.toSet().size)
        assertEquals(PersonalDataSetsProgress(1, 3), model.state.value.setsProgress)
        assertEquals(PersonalDataSetCompletion.Partial, model.state.value.allSets[1].completion)
        assertEquals(setOf(20), model.state.value.allSets[2].knownItemIds)
        assertEquals(PersonalDataSetCompletion.Unknown, model.state.value.allSets[2].completion)
        assertNull(model.state.value.allSets[3].catalog)
        model.setSetFilter(PersonalDataSetFilter.Recorded)
        assertEquals(4, model.state.value.totalFiltered)
        model.setSetFilter(PersonalDataSetFilter.Unrecorded)
        assertEquals(1, model.state.value.totalFiltered)
        assertEquals(2, service.calls)
    }

    @Test fun equalItemCountInvalidIdsAndInvalidCatalogNeverProveCompletion() = runTest {
        val service = ReadingFixture().apply {
            catalogs = { catalog(set(1, 10, 11), set(2), set(3, -1)) }
            sets = { listOf(record("same-count", 1, 10, 99), record("invalid", 1, 10, 11).copy(hasInvalidItemIds = true),
                record("negative", 1, 10, -1), record("empty", 1), record("bad-catalog", 2, 10)) }
        }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        val completed = model.state.value.allSets.filter { it.record != null }.map { it.completion }
        assertEquals(listOf(PersonalDataSetCompletion.Unknown, PersonalDataSetCompletion.Unknown,
            PersonalDataSetCompletion.Unknown, PersonalDataSetCompletion.Partial, PersonalDataSetCompletion.Unknown), completed)
        assertEquals(PersonalDataSetsProgress(0, 1), model.state.value.setsProgress)
    }

    @Test fun mixedValidAndUndefinedCatalogSetsDoNotLabelUnknownDefinitionsUnrecorded() = runTest {
        val service = ReadingFixture().apply {
            sets = { emptyList() }
            catalogs = { catalog(set(1, 10), set(2), set(3, -1)) }
        }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(listOf(PersonalDataSetCompletion.Unrecorded, PersonalDataSetCompletion.Unknown, PersonalDataSetCompletion.Unknown),
            model.state.value.allSets.map { it.completion })
        assertEquals(PersonalDataSetsProgress(0, 1), model.state.value.setsProgress)
        model.setSetFilter(PersonalDataSetFilter.Unrecorded)
        assertEquals(listOf(1), model.state.value.visibleSets.map { it.setId })
        model.setSetFilter(PersonalDataSetFilter.All)
        assertEquals(3, model.state.value.totalFiltered)
    }

    @Test fun emptyCatalogProviderIsUnavailableAndCannotInventUnrecordedSetsOrProgress() = runTest {
        val service = ReadingFixture().apply { catalogs = { PersonalDataOfficialCatalogs() }; sets = { listOf(record("known-record", 1, 10)) } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(PersonalDataReadingLoadStatus.Loaded, model.state.value.currentPageState.status)
        assertEquals(PersonalDataReadingLoadStatus.Unavailable, model.state.value.catalogStatus)
        assertEquals(listOf(PersonalDataSetCompletion.Unknown), model.state.value.allSets.map { it.completion })
        assertNull(model.state.value.setsProgress)
        model.setSetFilter(PersonalDataSetFilter.Unrecorded)
        assertTrue(model.state.value.visibleSets.isEmpty())
        service.catalogs = { catalog(set(1, 10), set(2, 20)) }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(PersonalDataReadingLoadStatus.Loaded, model.state.value.catalogStatus)
        assertEquals(PersonalDataSetsProgress(1, 2), model.state.value.setsProgress)
        assertEquals(listOf(2), model.state.value.visibleSets.map { it.setId })
        assertEquals(1, service.recordCalls)
    }

    @Test fun pendingCatalogAndSuccessfulEmptyRecordsRemainDistinctFromKnownUnrecordedSets() = runTest {
        val gate = ReadingGate<PersonalDataOfficialCatalogs>()
        val service = ReadingFixture().apply { sets = { emptyList() }; catalogs = { gate.await() } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); runCurrent()
        assertTrue(model.state.value.currentPageState.hasLoaded)
        assertEquals(PersonalDataReadingLoadStatus.Loading, model.state.value.catalogStatus)
        assertTrue(model.state.value.allSets.isEmpty())
        assertNull(model.state.value.setsProgress)
        gate.complete(catalog(set(1, 10))); advanceUntilIdle()
        assertEquals(PersonalDataSetCompletion.Unrecorded, model.state.value.allSets.single().completion)
        assertEquals(PersonalDataSetsProgress(0, 1), model.state.value.setsProgress)
    }

    @Test fun pageAndCatalogFailuresRetryIndependentlyAndRefreshPreservesSelectedContent() = runTest {
        val service = ReadingFixture().apply { sets = { error("Synthetic private response") } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(PersonalDataReadingLoadStatus.Failed, model.state.value.currentPageState.status)
        assertEquals(PersonalDataReadingFailure.LoadFailed, model.state.value.currentPageState.failure)
        assertEquals(PersonalDataReadingLoadStatus.Loaded, model.state.value.catalogStatus)
        assertTrue(model.state.value.allSets.isEmpty())
        service.sets = { listOf(record("selected", 1, 10)) }
        model.refresh(); advanceUntilIdle()
        val selected = model.state.value.allSets.single()
        model.selectSet(selected.key)
        service.catalogs = { error("Synthetic private catalog") }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(PersonalDataReadingLoadStatus.Failed, model.state.value.catalogStatus)
        assertEquals(PersonalDataReadingLoadStatus.Loaded, model.state.value.currentPageState.status)
        assertEquals(selected, model.state.value.selectedSet)
        service.sets = { error("Synthetic private refresh") }
        model.refresh(); advanceUntilIdle()
        assertEquals(selected, model.state.value.selectedSet)
        assertEquals(3, service.recordCalls)
        assertEquals(2, service.catalogCalls)
    }

    @Test fun setSortKeepsRecordedRowsFirstMissingDatesLastAndReversesUnrecordedIds() = runTest {
        val service = ReadingFixture().apply {
            catalogs = { catalog(set(1, 10), set(2, 20), set(3, 30), set(4, 40), set(5, 50)) }
            sets = { listOf(record("missing", 3, 30), record("late", 2, 20).copy(recordedAt = Instant.ofEpochSecond(20)),
                record("early", 1, 10).copy(recordedAt = Instant.ofEpochSecond(10))) }
        }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4, 5), model.state.value.visibleSets.map { it.setId })
        model.setSetSort(PersonalDataSetSort.NewestFirst)
        assertEquals(listOf(2, 1, 3, 5, 4), model.state.value.visibleSets.map { it.setId })
        model.selectSet(model.state.value.visibleSets.first().key)
        model.setSetFilter(PersonalDataSetFilter.Recorded)
        assertNotNull(model.state.value.selectedSet)
        model.setSetFilter(PersonalDataSetFilter.Unrecorded)
        assertNull(model.state.value.selectedSetKey)
        model.updateQuery("Set 4")
        assertEquals(listOf(4), model.state.value.visibleSets.map { it.setId })
        assertEquals(2, service.calls)
    }

    @Test fun closeCancelsRefreshPreservingLimitCriteriaSelectionAndConfirmedRecords() = runTest {
        val source = (1..25).map { record("record-$it", it, it * 10) }
        val service = ReadingFixture().apply { sets = { source }; catalogs = { catalog(*(1..25).map { set(it, it * 10) }.toTypedArray()) } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        model.setSetSort(PersonalDataSetSort.NewestFirst); model.showMore()
        val selected = model.state.value.visibleSets[12]
        model.selectSet(selected.key)
        val gate = ReadingGate<List<PersonalDataGlamourSetRecord>>()
        service.sets = { gate.await() }
        model.refresh(); runCurrent(); model.close()
        assertTrue(gate.job.isCancelled)
        gate.complete(emptyList()); advanceUntilIdle()
        assertNull(model.state.value.activePage)
        assertEquals(selected, model.state.value.selectedSet)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(20, model.state.value.visibleLimit)
        assertEquals(PersonalDataSetSort.NewestFirst, model.state.value.setSort)
        assertEquals(source, model.state.value.currentPageState.setRecords)
        assertEquals(2, service.recordCalls)
        assertEquals(1, service.catalogCalls)
    }

    @Test fun pageAbaRejectsOldResponseAndOldFinallyCannotEndReplacementLoading() = runTest {
        val old = ReadingGate<List<PersonalDataFishingRank>>()
        val fresh = ReadingGate<List<PersonalDataFishingRank>>()
        val service = ReadingFixture().apply { fishing = { old.await() } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Fish); runCurrent()
        service.fishing = { listOf(PersonalDataFishingRank("Bait", 1, null)) }
        model.open(PersonalDataReadingPage.Baits); advanceUntilIdle()
        service.fishing = { fresh.await() }
        model.open(PersonalDataReadingPage.Fish); runCurrent()
        old.complete(listOf(PersonalDataFishingRank("Old", 9, null))); runCurrent()
        assertEquals(PersonalDataReadingLoadStatus.Loading, model.state.value.currentPageState.status)
        assertTrue(model.state.value.visibleFishingRows.isEmpty())
        fresh.complete(listOf(PersonalDataFishingRank("Fresh", 1, null))); advanceUntilIdle()
        model.close(); model.open(PersonalDataReadingPage.Fish)
        assertEquals("Fresh", model.state.value.visibleFishingRows.single().name)
    }

    @Test fun independentCancellationRejectsNonCooperativeResultAndAllowsExplicitRetry() = runTest {
        val gate = ReadingGate<List<PersonalDataRaceUsage>>()
        val service = ReadingFixture().apply { races = { gate.await() } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Races); runCurrent()
        gate.job.cancel(); gate.complete(listOf(race("Cancelled", 1.0))); advanceUntilIdle()
        assertEquals(PersonalDataReadingLoadStatus.Idle, model.state.value.currentPageState.status)
        assertNull(model.state.value.currentPageState.failure)
        assertFalse(model.state.value.currentPageState.hasLoaded)
        service.races = { listOf(race("Retry", 0.5)) }
        model.open(PersonalDataReadingPage.Races); advanceUntilIdle()
        assertEquals("Retry", model.state.value.visibleRaces.single().race)
    }

    @Test fun catalogAbaRejectsLateFailureAndDoesNotReplayCachedRecords() = runTest {
        val old = ReadingGate<PersonalDataOfficialCatalogs>()
        val fresh = ReadingGate<PersonalDataOfficialCatalogs>()
        val service = ReadingFixture().apply { catalogs = { old.await() } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); runCurrent()
        model.open(PersonalDataReadingPage.Fish); advanceUntilIdle()
        service.catalogs = { fresh.await() }
        model.open(PersonalDataReadingPage.Sets); runCurrent()
        old.fail(IllegalStateException("Late synthetic catalog failure")); runCurrent()
        assertEquals(PersonalDataReadingLoadStatus.Loading, model.state.value.catalogStatus)
        fresh.complete(catalog(set(2, 20))); advanceUntilIdle()
        assertNull(model.state.value.catalogFailure)
        assertEquals(listOf(2), model.state.value.catalogs?.glamour?.sets?.map { it.mirageSetId })
        assertEquals(1, service.recordCalls)
    }

    @Test fun authenticationFailureFromRecordsOrCatalogClearsAllPageCachesAndSelection() = runTest {
        for (catalogFailure in listOf(false, true)) {
            val service = ReadingFixture()
            val model = PersonalDataReadingViewModel(service)
            model.open(PersonalDataReadingPage.Fish); advanceUntilIdle(); model.updateQuery("Fish")
            model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
            model.selectSet(model.state.value.allSets.first().key)
            if (catalogFailure) {
                service.catalogs = { throw PersonalDataException.AuthenticationRequired }; model.retryCatalogs()
            } else {
                service.sets = { throw PersonalDataException.AuthenticationRequired }; model.refresh()
            }
            advanceUntilIdle()
            assertEquals(setOf(PersonalDataReadingPage.Sets), model.state.value.pages.keys)
            assertEquals(PersonalDataReadingLoadStatus.AuthRequired, model.state.value.currentPageState.status)
            assertFalse(model.state.value.currentPageState.hasLoaded)
            assertEquals("", model.state.value.query)
            assertNull(model.state.value.catalogs)
            assertNull(model.state.value.selectedSet)
            assertTrue(model.state.value.allSets.isEmpty())
            model.clearProtectedContent()
        }
    }

    @Test fun capabilityLossOnCompletionAndLocalActionsClearsPrivateDataAndCloseStillWorks() = runTest {
        val service = ReadingFixture()
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        val gate = ReadingGate<List<PersonalDataRaceUsage>>()
        service.races = { gate.await() }
        model.open(PersonalDataReadingPage.Races); runCurrent()
        service.hasCommunityIdentity = false
        gate.complete(listOf(race("Forbidden", 1.0))); advanceUntilIdle()
        assertEquals(PersonalDataReadingLoadStatus.AuthRequired, model.state.value.currentPageState.status)
        assertNull(model.state.value.catalogs)
        val before = service.calls
        model.updateQuery("private"); model.showMore(); model.refresh(); model.retryCatalogs(); advanceUntilIdle()
        assertEquals(before, service.calls)
        model.close()
        assertEquals(PersonalDataReadingUiState(), model.state.value)
        service.hasCommunityIdentity = true
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        assertEquals(2, service.recordCalls)
    }

    @Test fun clearAndStoreDisposalRejectLateCatalogAndPageResultsAndFurtherReads() = runTest {
        val service = ReadingFixture()
        val page = ReadingGate<List<PersonalDataGlamourSetRecord>>()
        val directory = ReadingGate<PersonalDataOfficialCatalogs>()
        service.sets = { page.await() }; service.catalogs = { directory.await() }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, PersonalDataReadingViewModelFactory(service))[PersonalDataReadingViewModel::class.java]
        model.open(PersonalDataReadingPage.Sets); runCurrent()
        owner.viewModelStore.clear()
        page.complete(listOf(record("late", 1, 10))); directory.complete(catalog(set(1, 10))); advanceUntilIdle()
        assertEquals(PersonalDataReadingUiState(), model.state.value)
        assertFalse(model.hasCommunityIdentity)
        model.open(PersonalDataReadingPage.Fish); model.refresh(); model.retryCatalogs(); model.showMore(); advanceUntilIdle()
        assertEquals(2, service.calls)
        assertEquals(PersonalDataReadingUiState(), model.state.value)
    }

    @Test fun successfulRefreshKeepsOnlyStillValidSetSelection() = runTest {
        val service = ReadingFixture().apply { sets = { listOf(record("selected", 1, 10)) } }
        val model = PersonalDataReadingViewModel(service)
        model.open(PersonalDataReadingPage.Sets); advanceUntilIdle()
        model.selectSet(model.state.value.allSets.first().key)
        val selected = model.state.value.selectedSetKey
        service.sets = { listOf(record("selected", 1, 10).copy(recordedAt = Instant.ofEpochSecond(2))) }
        model.refresh(); advanceUntilIdle()
        assertEquals(selected, model.state.value.selectedSetKey)
        service.sets = { emptyList() }
        model.refresh(); advanceUntilIdle()
        assertNull(model.state.value.selectedSetKey)
        assertEquals(PersonalDataSetCompletion.Unrecorded, model.state.value.visibleSets.single().completion)
    }
}

private fun race(name: String, proportion: Double?) = PersonalDataRaceUsage(name, "Female", proportion, 10, false, false)
private fun record(key: String, setId: Int, vararg items: Int) = PersonalDataGlamourSetRecord(key, setId, items.toSet(), null)
private fun set(id: Int, vararg items: Int) = GlamourCatalogSet(id, "Set $id", null, items.mapIndexed { index, item -> GlamourCatalogSetItem(index, item, "Item $item", item) })
private fun catalog(vararg sets: GlamourCatalogSet) = PersonalDataOfficialCatalogs(glamour = GlamourCatalogSummary(sets.size, 0, 0, sets.toList()))

private class ReadingGate<T> {
    private lateinit var continuation: Continuation<T>
    lateinit var job: Job
    suspend fun await(): T { job = checkNotNull(currentCoroutineContext()[Job]); return suspendCoroutine { continuation = it } }
    fun complete(value: T) = continuation.resume(value)
    fun fail(error: Exception) = continuation.resumeWithException(error)
}

private class ReadingFixture : PersonalDataReadingService {
    override var hasCommunityIdentity = true
    val fishingCalls = mutableListOf<PersonalDataFishingRankingKind>()
    var raceCalls = 0
    var recordCalls = 0
    var catalogCalls = 0
    val calls get() = fishingCalls.size + raceCalls + recordCalls + catalogCalls
    var fishing: suspend (PersonalDataFishingRankingKind) -> List<PersonalDataFishingRank> = { listOf(PersonalDataFishingRank("Fish", 1, null)) }
    var races: suspend () -> List<PersonalDataRaceUsage> = { listOf(race("Race", 1.0)) }
    var sets: suspend () -> List<PersonalDataGlamourSetRecord> = { listOf(record("record", 1, 10)) }
    var catalogs: suspend () -> PersonalDataOfficialCatalogs = { catalog(set(1, 10)) }
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind): List<PersonalDataFishingRank> { fishingCalls += kind; return fishing(kind) }
    override suspend fun fetchRaceUsage(): List<PersonalDataRaceUsage> { raceCalls++; return races() }
    override suspend fun fetchGlamourSetRecords(): List<PersonalDataGlamourSetRecord> { recordCalls++; return sets() }
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs { catalogCalls++; return catalogs() }
    override suspend fun fetchIdentity(): PersonalDataIdentity = error("Unused root read")
    override suspend fun fetchAvailability(): PersonalDataAvailability = error("Unused root read")
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent = error("Unused board read")
    override suspend fun fetchUltimateDashboard(): UltimateDashboard = error("Unused dashboard read")
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused encounter read")
}
