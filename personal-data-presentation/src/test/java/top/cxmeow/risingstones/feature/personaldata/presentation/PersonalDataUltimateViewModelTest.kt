package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
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
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateSection.*
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUltimateLoadStatus.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataUltimateViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val zone = ZoneId.of("UTC")

    @Test fun explicitOpenOnlyReadsOverviewAndSelectionMustComeFromReturnedRecords() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        advanceUntilIdle(); assertEquals(0, service.calls)
        assertNull(model.state.value.records); assertNull(model.state.value.totalClears)
        model.selectEncounter(968); advanceUntilIdle(); assertEquals(0, service.calls)
        model.open(); advanceUntilIdle()
        assertEquals(1, service.overviewCalls)
        assertTrue(service.sectionCalls.isEmpty())
        model.selectEncounter(777); advanceUntilIdle(); assertTrue(service.sectionCalls.isEmpty())
        model.selectEncounter(968); advanceUntilIdle(); model.selectEncounter(968); model.open(); advanceUntilIdle()
        assertEquals(PersonalDataUltimateSection.entries.map { 968 to it }, service.sectionCalls)
        assertEquals(ultimateRecord(), model.state.value.selectedRecord)
        assertEquals(1, service.overviewCalls)
    }

    @Test fun legacyServiceAndMissingIdentityNeverReadAnyEndpoints() = runTest {
        val service = UltimateFixture()
        val legacy = PersonalDataUltimateViewModel(object : PersonalDataService by service {}, zone)
        legacy.open(); legacy.refresh(); legacy.retryOverview(); legacy.selectEncounter(968); advanceUntilIdle()
        assertFalse(legacy.supportsUltimate)
        assertEquals(Unavailable, legacy.state.value.overviewStatus)
        service.hasCommunityIdentity = false
        val denied = PersonalDataUltimateViewModel(service, zone)
        denied.open(); denied.refresh(); advanceUntilIdle()
        assertEquals(PersonalDataUltimateUiState(zone = zone), denied.state.value)
        assertEquals(0, service.calls)
    }

    @Test fun successfulEmptyOverviewIsDistinctFromUnloadedAndDoesNotRepeatOnOpen() = runTest {
        val service = UltimateFixture().apply { records = { emptyList() } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle()
        assertEquals(emptyList<PersonalDataUltimateRecord>(), model.state.value.records)
        assertEquals(0L, model.state.value.totalClears)
        assertEquals(Loaded, model.state.value.overviewStatus)
        model.close(); model.open(); advanceUntilIdle()
        assertEquals(1, service.calls)
    }

    @Test fun summaryFirstDuplicatesRemainInRawRecordsAndAreNotCountedTwice() = runTest {
        val first = ultimateRecord(968, 2)
        val service = UltimateFixture().apply { records = { listOf(first, ultimateRecord(968, 99), ultimateRecord(733, 3)) } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        assertEquals(first, model.state.value.selectedRecord)
        assertEquals(3, model.state.value.records?.size)
        assertEquals(5L, model.state.value.totalClears)
    }

    @Test fun bahamutNeverLoadsOrSelectsPhasesIncludingRefreshAndDirectRetry() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(733); advanceUntilIdle()
        assertEquals(4, service.sectionCalls.size)
        assertFalse(Phases in model.state.value.availableSections)
        model.selectSection(Phases); model.retrySection(Phases); advanceUntilIdle()
        assertEquals(Party, model.state.value.selectedSection)
        assertTrue(model.state.value.phases.isEmpty())
        model.refresh(); advanceUntilIdle()
        assertEquals(8, service.sectionCalls.size)
        assertFalse(service.sectionCalls.any { it.second == Phases })
    }

    @Test fun fiveSectionsLoadIndependentlyAndOneFailureOnlyRetriesItself() = runTest {
        val gate = UltimateGate<PersonalDataUltimateData>()
        val service = UltimateFixture().apply { sections = { _, section -> when (section) {
            Party -> gate.await(); Jobs -> error("private failure"); else -> ultimateData(section)
        } } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); runCurrent()
        assertEquals(Loading, model.state.value.currentEncounter.sections[Party]?.status)
        assertEquals(Failed, model.state.value.currentEncounter.sections[Jobs]?.status)
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Deaths]?.status)
        gate.complete(ultimateData(Party)); advanceUntilIdle()
        service.sections = { _, section -> ultimateData(section) }
        model.retrySection(Jobs); advanceUntilIdle()
        assertEquals(6, service.sectionCalls.size)
        assertEquals(1, service.overviewCalls)
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Jobs]?.status)
    }

    @Test fun everyMismatchedSectionPayloadFailsWithoutReplacingGoodCachedRows() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        val before = model.state.value.currentEncounter.sections
        service.sections = { _, section -> ultimateData(if (section == Party) Jobs else Party) }
        model.refresh(); advanceUntilIdle()
        PersonalDataUltimateSection.entries.forEach { section ->
            assertEquals(Failed, model.state.value.currentEncounter.sections[section]?.status)
            assertEquals(before[section]?.data, model.state.value.currentEncounter.sections[section]?.data)
        }
    }

    @Test fun eachTerritoryRetainsSelectedSectionExpandedPartnersAndDeathLimitAcrossClose() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        assertEquals(3, model.state.value.visiblePartners.size)
        assertEquals(20, model.state.value.visibleDeaths.size)
        model.showMorePartners(); model.showMoreDeaths(); model.selectSection(Deaths)
        val old = model.state.value.currentEncounter
        model.selectEncounter(733); advanceUntilIdle()
        assertEquals(3, model.state.value.currentEncounter.partnerLimit)
        assertEquals(20, model.state.value.currentEncounter.deathLimit)
        model.selectSection(Jobs); model.selectEncounter(968); advanceUntilIdle()
        assertEquals(old, model.state.value.currentEncounter)
        model.close(); model.open(); advanceUntilIdle()
        assertEquals(old, model.state.value.currentEncounter)
        assertEquals(9, service.sectionCalls.size)
        assertEquals(1, service.overviewCalls)
    }

    @Test fun derivedPartyOrderComesFromServiceAndCoordinateListUsesExactlyThePlottedRecords() = runTest {
        val service = UltimateFixture().apply { sections = { _, section -> when (section) {
            Party -> PersonalDataUltimateData.Party(listOf(UltimatePartyMember("D", null, null, "Damage"), UltimatePartyMember("Unknown", null, null, "Future"), UltimatePartyMember("T", null, null, "Tank")))
            Deaths -> PersonalDataUltimateData.Deaths(listOf(UltimateDeathRecord(null, 1.0), UltimateDeathRecord(103.0, 97.0)))
            else -> ultimateData(section)
        } } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        assertEquals(listOf("T", "D", "Unknown"), model.state.value.party.map { it.characterName })
        assertEquals(1, model.state.value.deathPlot?.excludedCount)
        assertEquals(model.state.value.deathPlot?.points, model.state.value.visibleDeaths)
        assertEquals(1, model.state.value.visibleDeaths.single().sourceIndex)
        assertEquals(3.0, model.state.value.visibleDeaths.single().x, 0.0)
    }

    @Test fun refreshReadsOverviewAndAllSelectedSectionsAndSynchronizesSummaryImmediately() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle(); model.selectSection(Jobs)
        val updated = ultimateRecord(968, 12).copy(firstClearDurationSeconds = 1234)
        val overview = UltimateGate<List<PersonalDataUltimateRecord>>()
        val detail = UltimateGate<PersonalDataUltimateData>()
        service.records = { overview.await() }
        service.sections = { _, section -> if (section == Jobs) detail.await() else ultimateData(section) }
        model.refresh(); runCurrent()
        assertEquals(2, service.overviewCalls); assertEquals(10, service.sectionCalls.size)
        overview.complete(listOf(updated, ultimateRecord(733))); runCurrent()
        assertEquals(updated, model.state.value.selectedRecord)
        assertEquals(Loading, model.state.value.currentSection.status)
        detail.fail(IllegalStateException("ordinary")); advanceUntilIdle()
        assertEquals(updated, model.state.value.selectedRecord)
        assertEquals(Failed, model.state.value.currentSection.status)
        assertNotNull(model.state.value.currentSection.data)
        assertEquals(Jobs, model.state.value.selectedSection)
    }

    @Test fun overviewFailureKeepsRecordsAndSelectedDetailAndRetryOverviewDoesNotReloadSections() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        val record = model.state.value.selectedRecord
        service.records = { error("private response") }
        model.refresh(); advanceUntilIdle()
        assertEquals(Failed, model.state.value.overviewStatus)
        assertEquals(PersonalDataUltimateFailure.LoadFailed, model.state.value.overviewFailure)
        assertEquals(record, model.state.value.selectedRecord)
        assertEquals(Loaded, model.state.value.currentSection.status)
        val calls = service.sectionCalls.size
        service.records = { listOf(ultimateRecord(968, 20)) }
        model.retryOverview(); model.retryOverview(); advanceUntilIdle()
        assertEquals(3, service.overviewCalls)
        assertEquals(20L, model.state.value.selectedRecord?.clearCount)
        assertEquals(calls, service.sectionCalls.size)
    }

    @Test fun successfulOverviewDeletionCancelsLateDetailAndPrunesOnlyRemovedCaches() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(733); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        val gate = UltimateGate<PersonalDataUltimateData>()
        service.sections = { _, section -> if (section == Jobs) gate.await() else ultimateData(section) }
        model.retrySection(Jobs); runCurrent()
        service.records = { listOf(ultimateRecord(733)) }
        model.retryOverview(); runCurrent()
        assertTrue(gate.job.isCancelled)
        assertNull(model.state.value.selectedTerritoryType)
        assertFalse(968 in model.state.value.encounters)
        assertTrue(733 in model.state.value.encounters)
        gate.complete(ultimateData(Jobs)); advanceUntilIdle()
        assertFalse(968 in model.state.value.encounters)
        model.selectEncounter(968); advanceUntilIdle()
        assertNull(model.state.value.selectedTerritoryType)
        model.selectEncounter(733); advanceUntilIdle()
        assertEquals(733, model.state.value.selectedTerritoryType)
        assertEquals(4, service.sectionCalls.count { it.first == 733 })
    }

    @Test fun selectionABAIsolatesNonCooperativePreviousResponseAndLeavesCurrentLoadingIntact() = runTest {
        val first = UltimateGate<PersonalDataUltimateData>()
        val second = UltimateGate<PersonalDataUltimateData>()
        val service = UltimateFixture().apply { sections = { id, section -> if (id == 968 && section == Jobs) first.await() else ultimateData(section) } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); runCurrent()
        model.selectEncounter(733); runCurrent()
        service.sections = { id, section -> if (id == 968 && section == Jobs) second.await() else ultimateData(section) }
        model.selectEncounter(968); runCurrent(); first.fail(PersonalDataException.AuthenticationRequired); runCurrent()
        assertEquals(Loading, model.state.value.currentEncounter.sections[Jobs]?.status)
        assertEquals(Loaded, model.state.value.overviewStatus)
        second.complete(ultimateData(Jobs)); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Jobs]?.status)
        assertEquals(2, service.sectionCalls.count { it == (968 to Jobs) })
    }

    @Test fun clearSelectionCancelsReadsAndReopeningOnlyLoadsCancelledMissingSections() = runTest {
        val gate = UltimateGate<PersonalDataUltimateData>()
        val service = UltimateFixture().apply { sections = { _, section -> if (section == Jobs) gate.await() else ultimateData(section) } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); runCurrent(); model.clearEncounterSelection()
        assertTrue(gate.job.isCancelled)
        assertNull(model.state.value.selectedRecord)
        service.sections = { _, section -> ultimateData(section) }
        model.selectEncounter(968); runCurrent(); gate.complete(ultimateData(Jobs)); advanceUntilIdle()
        assertEquals(6, service.sectionCalls.size)
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Jobs]?.status)
    }

    @Test fun closeAndReopenDoNotAcceptStaleOverviewOrDetailAcrossSameId() = runTest {
        val oldOverview = UltimateGate<List<PersonalDataUltimateRecord>>()
        val service = UltimateFixture().apply { records = { oldOverview.await() } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); runCurrent(); model.close()
        assertEquals(Idle, model.state.value.overviewStatus)
        service.records = { listOf(ultimateRecord(968, 9)) }
        model.open(); runCurrent(); oldOverview.complete(listOf(ultimateRecord(968, 1))); advanceUntilIdle()
        model.selectEncounter(968); advanceUntilIdle()
        assertEquals(9L, model.state.value.selectedRecord?.clearCount)
        val gate = UltimateGate<PersonalDataUltimateData>()
        service.sections = { _, section -> if (section == Jobs) gate.await() else ultimateData(section) }
        model.retrySection(Jobs); runCurrent(); model.close()
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Jobs]?.status)
        model.open(); runCurrent(); gate.fail(PersonalDataException.AuthenticationRequired); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.overviewStatus)
        assertEquals(9L, model.state.value.selectedRecord?.clearCount)
    }

    @Test fun independentCancellationPropagatesAndRestoresUnloadedOrCachedStatus() = runTest {
        lateinit var cancelledOverview: Job
        val service = UltimateFixture().apply { records = { cancelledOverview = checkNotNull(currentCoroutineContext()[Job]); throw CancellationException() } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle()
        assertTrue(cancelledOverview.isCancelled)
        assertEquals(Idle, model.state.value.overviewStatus)
        assertNull(model.state.value.overviewFailure)
        service.records = { listOf(ultimateRecord()) }
        model.retryOverview(); advanceUntilIdle()
        lateinit var cancelledSection: Job
        service.sections = { _, section -> if (section == Jobs) { cancelledSection = checkNotNull(currentCoroutineContext()[Job]); throw CancellationException() } else ultimateData(section) }
        model.selectEncounter(968); advanceUntilIdle()
        assertTrue(cancelledSection.isCancelled)
        assertEquals(Idle, model.state.value.currentEncounter.sections[Jobs]?.status)
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Party]?.status)
        service.sections = { _, section -> ultimateData(section) }
        model.retrySection(Jobs); advanceUntilIdle()
        service.sections = { _, _ -> throw CancellationException() }
        model.retrySection(Jobs); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.currentEncounter.sections[Jobs]?.status)
        assertNull(model.state.value.currentEncounter.sections[Jobs]?.failure)
    }

    @Test fun eitherAuthenticationSourceClearsAllTerritoriesAndOpenNeverAutomaticallyRetries() = runTest {
        repeat(2) { origin ->
            val service = UltimateFixture()
            val model = PersonalDataUltimateViewModel(service, zone)
            model.open(); advanceUntilIdle(); model.selectEncounter(733); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
            if (origin == 0) { service.records = { throw PersonalDataException.AuthenticationRequired }; model.retryOverview() }
            else { service.sections = { _, _ -> throw PersonalDataException.AuthenticationRequired }; model.retrySection(Jobs) }
            advanceUntilIdle()
            assertTrue(model.state.value.isOpen)
            assertEquals(AuthRequired, model.state.value.overviewStatus)
            assertNull(model.state.value.records)
            assertNull(model.state.value.selectedTerritoryType)
            assertTrue(model.state.value.encounters.isEmpty())
            val calls = service.calls
            model.close(); model.open(); model.selectEncounter(968); advanceUntilIdle()
            assertEquals(calls, service.calls)
            assertEquals(AuthRequired, model.state.value.overviewStatus)
        }
    }

    @Test fun authenticationCancelsSiblingAndLateResponseCannotRestorePrivateCache() = runTest {
        val auth = UltimateGate<PersonalDataUltimateData>()
        val late = UltimateGate<PersonalDataUltimateData>()
        val service = UltimateFixture().apply { sections = { _, section -> when (section) { Party -> auth.await(); Jobs -> late.await(); else -> ultimateData(section) } } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); runCurrent()
        auth.fail(PersonalDataException.AuthenticationRequired); runCurrent()
        assertTrue(late.job.isCancelled)
        late.complete(ultimateData(Jobs)); advanceUntilIdle()
        assertEquals(AuthRequired, model.state.value.overviewStatus)
        assertTrue(model.state.value.encounters.isEmpty())
    }

    @Test fun revocationBeforeQueuedReadsAndAfterResponsesClearsEverythingAndPreventsNewCalls() = runTest {
        val service = UltimateFixture()
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); service.hasCommunityIdentity = false; advanceUntilIdle()
        assertEquals(0, service.calls)
        assertEquals(PersonalDataUltimateUiState(zone = zone), model.state.value)
        service.hasCommunityIdentity = true
        model.open(); advanceUntilIdle()
        val gate = UltimateGate<PersonalDataUltimateData>()
        service.sections = { _, section -> if (section == Jobs) gate.await() else ultimateData(section) }
        model.selectEncounter(968); runCurrent(); service.hasCommunityIdentity = false
        gate.complete(ultimateData(Jobs)); advanceUntilIdle()
        assertEquals(PersonalDataUltimateUiState(zone = zone), model.state.value)
        val calls = service.calls
        model.open(); model.refresh(); model.retryOverview(); model.retrySection(Jobs); model.selectEncounter(968); model.showMoreDeaths(); advanceUntilIdle()
        assertEquals(calls, service.calls)
    }

    @Test fun clearThenReenterRejectsPreviousSessionGenerationEvenWhenIdsAreTheSame() = runTest {
        val gate = UltimateGate<PersonalDataUltimateData>()
        val service = UltimateFixture().apply { sections = { _, section -> if (section == Jobs) gate.await() else ultimateData(section) } }
        val model = PersonalDataUltimateViewModel(service, zone)
        model.open(); advanceUntilIdle(); model.selectEncounter(968); runCurrent(); model.clearProtectedContent()
        service.sections = { _, section -> ultimateData(section) }
        service.records = { listOf(ultimateRecord(968, 25)) }
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        gate.complete(PersonalDataUltimateData.Jobs(listOf(UltimateJobUsage("Stale", 1)))); advanceUntilIdle()
        assertEquals(25L, model.state.value.selectedRecord?.clearCount)
        assertFalse(model.state.value.jobs.any { it.jobName == "Stale" })
    }

    @Test fun storeDisposalClearsObservableCachesAndPermanentlyBlocksOldModelReference() = runTest {
        val overview = UltimateGate<List<PersonalDataUltimateRecord>>()
        val detail = UltimateGate<PersonalDataUltimateData>()
        val service = UltimateFixture()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, PersonalDataUltimateViewModelFactory(service, zone))[PersonalDataUltimateViewModel::class.java]
        model.open(); advanceUntilIdle(); model.selectEncounter(968); advanceUntilIdle()
        service.records = { overview.await() }; service.sections = { _, section -> if (section == Jobs) detail.await() else ultimateData(section) }
        model.refresh(); runCurrent(); owner.viewModelStore.clear()
        assertTrue(overview.job.isCancelled); assertTrue(detail.job.isCancelled)
        overview.complete(listOf(ultimateRecord())); detail.complete(ultimateData(Jobs)); advanceUntilIdle()
        val calls = service.calls
        model.open(); model.refresh(); model.selectEncounter(968); model.retryOverview(); advanceUntilIdle()
        assertEquals(calls, service.calls)
        assertFalse(model.hasCommunityIdentity)
        assertEquals(PersonalDataUltimateUiState(zone = zone), model.state.value)
    }
}

private class UltimateGate<T> {
    private lateinit var continuation: Continuation<T>
    lateinit var job: Job
    suspend fun await(): T { job = checkNotNull(currentCoroutineContext()[Job]); return suspendCoroutine { continuation = it } }
    fun complete(value: T) = continuation.resume(value)
    fun fail(error: Exception) = continuation.resumeWithException(error)
}
private fun ultimateData(section: PersonalDataUltimateSection): PersonalDataUltimateData = when (section) {
    Party -> PersonalDataUltimateData.Party(listOf(UltimatePartyMember("Party", "Area", "World", "Tank")))
    Jobs -> PersonalDataUltimateData.Jobs(listOf(UltimateJobUsage("Job", 1)))
    Partners -> PersonalDataUltimateData.Partners((1..8).map { UltimateCompanion("Partner $it", "Area", "World", it.toLong()) })
    Phases -> PersonalDataUltimateData.Phases(listOf(UltimatePhaseRecord("p1", null)))
    Deaths -> PersonalDataUltimateData.Deaths((1..45).map { UltimateDeathRecord(it.toDouble(), it.toDouble()) })
}
private class UltimateFixture : PersonalDataUltimateService {
    override var hasCommunityIdentity = true
    var overviewCalls = 0
    val sectionCalls = mutableListOf<Pair<Int, PersonalDataUltimateSection>>()
    val calls get() = overviewCalls + sectionCalls.size
    var records: suspend () -> List<PersonalDataUltimateRecord> = { listOf(ultimateRecord(), ultimateRecord(733)) }
    var sections: suspend (Int, PersonalDataUltimateSection) -> PersonalDataUltimateData = { _, section -> ultimateData(section) }
    override suspend fun fetchUltimateRecords(): List<PersonalDataUltimateRecord> { overviewCalls++; return records() }
    override suspend fun fetchUltimateSection(territoryType: Int, section: PersonalDataUltimateSection): PersonalDataUltimateData { sectionCalls += territoryType to section; return sections(territoryType, section) }
    override fun ultimateJobOrder(jobName: String): Int? = when (jobName) { "Tank" -> 0; "Damage" -> 2; else -> null }
    override suspend fun fetchIdentity(): PersonalDataIdentity = error("Unused root read")
    override suspend fun fetchAvailability(): PersonalDataAvailability = error("Unused root read")
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent = error("Unused legacy board read")
    override suspend fun fetchUltimateDashboard(): UltimateDashboard = error("Unused legacy dashboard read")
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused legacy detail read")
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs = error("Unused catalog read")
}
