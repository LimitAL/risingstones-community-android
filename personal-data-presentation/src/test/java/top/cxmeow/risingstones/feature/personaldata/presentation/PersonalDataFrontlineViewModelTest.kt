package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineSection.*
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineLoadStatus.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataFrontlineViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val clock = Clock.fixed(Instant.parse("2026-09-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test fun noInitReadAndRepeatedOpenOrClosePreservesCompleteSectionCaches() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        advanceUntilIdle(); assertEquals(0, service.calls)
        model.open(); advanceUntilIdle()
        model.selectSection(Jobs); model.selectJob("B")
        model.open(); model.close(); model.open(); advanceUntilIdle()
        assertEquals(PersonalDataFrontlineSection.entries, service.sectionCalls)
        assertEquals(1, service.catalogCalls)
        assertEquals(Jobs, model.state.value.selectedSection)
        assertEquals("B", model.state.value.selectedJob)
        assertTrue(model.state.value.sections.values.all { it.status == Loaded })
    }

    @Test fun oldServiceAndMissingCapabilityNeverReadOptionalOrLegacyEndpoints() = runTest {
        val backing = FrontlineFixture()
        val old = PersonalDataFrontlineViewModel(object : PersonalDataService by backing {}, clock)
        old.open(); old.refresh(); old.retryCatalogs(); old.retrySection(Weekly); advanceUntilIdle()
        assertFalse(old.supportsFrontline)
        assertEquals(Unavailable, old.state.value.currentSection.status)
        assertEquals(0, backing.calls)
        backing.hasCommunityIdentity = false
        val denied = PersonalDataFrontlineViewModel(backing, clock)
        denied.open(); advanceUntilIdle()
        assertEquals(PersonalDataFrontlineUiState(zone = clock.zone), denied.state.value)
        assertEquals(0, backing.calls)
    }

    @Test fun sectionsAndCatalogLoadIndependentlyAndWeeklyDoesNotInventZerosBeforeSuccess() = runTest {
        val weekly = FrontlineGate<PersonalDataFrontlineData>()
        val catalog = FrontlineGate<PersonalDataFrontlineCatalogs>()
        val service = FrontlineFixture().apply {
            sections = { if (it == Weekly) weekly.await() else frontlineData(it) }
            catalogs = { catalog.await() }
        }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent()
        assertEquals(Loaded, model.state.value.sections[Overview]?.status)
        assertEquals(Loading, model.state.value.sections[Weekly]?.status)
        assertNull(model.state.value.weekly)
        weekly.fail(IllegalStateException("private failure")); runCurrent()
        assertNull(model.state.value.weekly)
        assertEquals(PersonalDataFrontlineFailure.LoadFailed, model.state.value.sections[Weekly]?.failure)
        assertEquals(Loading, model.state.value.catalogStatus)
        catalog.complete(frontlineCatalog()); advanceUntilIdle()
        service.sections = ::frontlineData
        model.retrySection(Weekly); advanceUntilIdle()
        assertEquals(7, model.state.value.weekly?.days?.size)
        assertEquals(0L, model.state.value.weekly?.totals?.battles)
        assertEquals(8, service.sectionCalls.size)
    }

    @Test fun allSevenMismatchedPayloadTypesFailAndPreserveTheirPreviousData() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle()
        val previous = model.state.value.sections
        service.sections = { frontlineData(if (it == Overview) Weekly else Overview) }
        model.refresh(); advanceUntilIdle()
        PersonalDataFrontlineSection.entries.forEach { section ->
            assertEquals(Failed, model.state.value.sections[section]?.status)
            assertEquals(previous[section]?.data, model.state.value.sections[section]?.data)
            assertEquals(PersonalDataFrontlineFailure.LoadFailed, model.state.value.sections[section]?.failure)
        }
        assertEquals(1, service.catalogCalls)
    }

    @Test fun partialRefreshFailureKeepsSelectionAndRetriesOnlyThatSection() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle(); model.selectSection(Jobs); model.selectJob("B")
        val previous = model.state.value.currentSection.data
        service.sections = { if (it == Jobs) error("private") else frontlineData(it) }
        model.refresh(); advanceUntilIdle()
        assertEquals(previous, model.state.value.currentSection.data)
        assertEquals("B", model.state.value.selectedJob)
        assertEquals(Failed, model.state.value.currentSection.status)
        model.close(); model.open(); advanceUntilIdle()
        assertEquals(14, service.sectionCalls.size)
        val retry = FrontlineGate<PersonalDataFrontlineData>()
        service.sections = { retry.await() }
        model.retrySection(Jobs); model.retrySection(Jobs); runCurrent()
        assertEquals(15, service.sectionCalls.size)
        retry.complete(frontlineData(Jobs)); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.currentSection.status)
        assertEquals("B", model.state.value.selectedJob)
        assertEquals(1, service.catalogCalls)
    }

    @Test fun independentPeriodsAndAllSelectionsRemainLocalAndSurviveReopen() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle()
        assertEquals("A", model.state.value.selectedJob)
        model.selectOverallPeriod(FrontlinePeriodKind.Since51)
        assertEquals(FrontlinePeriodKind.Total, model.state.value.jobPeriod)
        assertEquals(10L, model.state.value.allTimeOverview?.battles)
        assertEquals(5L, model.state.value.overall?.battles)
        model.selectJob("B"); model.selectJobPeriod(FrontlinePeriodKind.Since51)
        assertEquals("C", model.state.value.selectedJob)
        model.selectJob("A")
        assertEquals("C", model.state.value.selectedJob)
        model.selectWeeklyMetric(PersonalDataFrontlineWeeklyMetric.Kda)
        model.selectBestKind(FrontlineBestKind.Healing)
        model.selectMap("A"); model.selectMapJob("JobA")
        assertEquals(1L, model.state.value.currentMapStats?.battles)
        model.selectMap("B")
        assertNull(model.state.value.selectedMapJob)
        assertEquals(8L, model.state.value.currentMapStats?.battles)
        model.selectMapJob("JobA"); model.selectMap("Not in source")
        assertNull(model.state.value.selectedMapJob)
        assertEquals("B", model.state.value.selectedMap)
        model.selectSection(Maps); model.updateQuery("achievement"); model.setIncludeUnobtained(true)
        val before = model.state.value
        model.close(); model.open(); advanceUntilIdle()
        assertEquals(before, model.state.value)
        assertEquals(8, service.calls)
    }

    @Test fun successfulRefreshReconcilesRemovedJobAndMapJobButPreservesValidSelections() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle(); model.selectJob("B"); model.selectMapJob("JobA")
        model.refresh(); advanceUntilIdle()
        assertEquals("B", model.state.value.selectedJob)
        assertEquals("JobA", model.state.value.selectedMapJob)
        service.sections = { when (it) {
            Jobs -> PersonalDataFrontlineData.Jobs(listOf(frontlineJob("A")))
            MapJobs -> PersonalDataFrontlineData.MapJobs(emptyList())
            else -> frontlineData(it)
        } }
        model.refresh(); advanceUntilIdle()
        assertEquals("A", model.state.value.selectedJob)
        assertNull(model.state.value.selectedMapJob)
        assertEquals("A", model.state.value.selectedMap)
    }

    @Test fun mapsCanDisplayAndSelectAllEvenWhenMapJobsRequestFails() = runTest {
        val service = FrontlineFixture().apply { sections = { if (it == MapJobs) error("unavailable") else frontlineData(it) } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle()
        assertEquals("A", model.state.value.selectedMap)
        assertEquals(10L, model.state.value.currentMapStats?.battles)
        model.selectMap("B")
        assertEquals(8L, model.state.value.currentMapStats?.battles)
        assertTrue(model.state.value.availableMapJobs.isEmpty())
    }

    @Test fun fullAchievementRowsAreKeptAndQueryLimitAndUnobtainedOnlyChangeLocally() = runTest {
        val rows = (1..25).map { FrontlineAchievementRecord(it, "Achievement $it", null, null) }
        val service = FrontlineFixture().apply { sections = { if (it == Achievements) PersonalDataFrontlineData.Achievements(rows) else frontlineData(it) } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle(); model.selectSection(Achievements)
        assertEquals(rows, (model.state.value.currentSection.data as PersonalDataFrontlineData.Achievements).rows)
        assertEquals(10, model.state.value.visibleAchievements.size)
        model.showMore(); assertEquals(20, model.state.value.visibleAchievements.size)
        model.showMore(); assertEquals(25, model.state.value.visibleAchievements.size)
        model.updateQuery("Achievement 2")
        assertEquals(7, model.state.value.totalAchievements)
        assertEquals(10, model.state.value.visibleLimit)
        model.updateQuery(""); model.setIncludeUnobtained(true)
        assertEquals(26, model.state.value.totalAchievements)
        assertEquals(8, service.calls)
    }

    @Test fun catalogueDoesNotInventUnobtainedAchievementsWhileBusinessRecordsAreUnloaded() = runTest {
        val gate = FrontlineGate<PersonalDataFrontlineData>()
        val service = FrontlineFixture().apply { sections = { if (it == Achievements) gate.await() else frontlineData(it) } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent(); model.setIncludeUnobtained(true)
        assertTrue(model.state.value.visibleAchievements.isEmpty())
        gate.complete(PersonalDataFrontlineData.Achievements(emptyList())); advanceUntilIdle()
        assertEquals(1, model.state.value.totalAchievements)
    }

    @Test fun catalogFailuresEmptyResponsesAndCancellationPreserveOldCatalogAndDoNotRetrySections() = runTest {
        val service = FrontlineFixture().apply { catalogs = { error("private catalog failure") } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle()
        assertEquals(Failed, model.state.value.catalogStatus)
        assertEquals(Loaded, model.state.value.currentSection.status)
        service.catalogs = { frontlineCatalog() }; model.retryCatalogs(); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.catalogStatus)
        val old = model.state.value.catalogs
        service.catalogs = { PersonalDataFrontlineCatalogs() }; model.retryCatalogs(); advanceUntilIdle()
        assertEquals(Unavailable, model.state.value.catalogStatus)
        assertEquals(old, model.state.value.catalogs)
        service.catalogs = { throw CancellationException() }; model.retryCatalogs(); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.catalogStatus)
        assertNull(model.state.value.catalogFailure)
        assertEquals(7, service.sectionCalls.size)
    }

    @Test fun legacyMapOnlyCatalogDoesNotProveAchievementsAvailableAndCanRetryWithoutBusinessReads() = runTest {
        val maps = listOf("Map 1", "Map 2", "Map 3", "Map 4", "Map 5")
        val service = FrontlineFixture().apply { catalogs = { PersonalDataFrontlineCatalogs(mapNames = maps) } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle(); model.selectSection(Achievements); model.setIncludeUnobtained(true)
        assertEquals(Loaded, model.state.value.catalogStatus)
        assertFalse(model.state.value.hasAchievementCatalog)
        assertTrue(model.state.value.availableMaps.containsAll(maps))
        assertTrue(model.state.value.visibleAchievements.isEmpty())
        service.catalogs = { PersonalDataFrontlineCatalogs(achievements = frontlineCatalog().achievements) }
        model.retryCatalogs(); advanceUntilIdle()
        assertTrue(model.state.value.hasAchievementCatalog)
        assertEquals(maps, model.state.value.catalogs?.mapNames)
        assertEquals(1, model.state.value.visibleAchievements.size)
        assertEquals(2, service.catalogCalls)
        assertEquals(7, service.sectionCalls.size)
    }

    @Test fun partialAndEmptyRefreshKeepPreviouslyUsableAchievementCatalogAndSelection() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle(); model.selectMap("B"); model.setIncludeUnobtained(true)
        val achievements = model.state.value.catalogs?.achievements
        assertTrue(model.state.value.hasAchievementCatalog)
        service.catalogs = { PersonalDataFrontlineCatalogs(mapNames = listOf("A", "B", "New map")) }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(achievements, model.state.value.catalogs?.achievements)
        assertTrue(model.state.value.hasAchievementCatalog)
        assertEquals("B", model.state.value.selectedMap)
        assertEquals(1, model.state.value.visibleAchievements.size)
        assertTrue("New map" in model.state.value.availableMaps)
        service.catalogs = { PersonalDataFrontlineCatalogs() }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(Unavailable, model.state.value.catalogStatus)
        assertTrue(model.state.value.hasAchievementCatalog)
        assertEquals(achievements, model.state.value.catalogs?.achievements)
        assertEquals("B", model.state.value.selectedMap)
        assertEquals(1, model.state.value.visibleAchievements.size)
        model.clearProtectedContent()
        assertFalse(model.state.value.hasAchievementCatalog)
    }

    @Test fun cancellationPropagatesAndFinishesLoadingWithoutCancellingSiblingReads() = runTest {
        val weekly = FrontlineGate<PersonalDataFrontlineData>()
        lateinit var cancelled: Job
        val service = FrontlineFixture().apply { sections = { when (it) {
            Jobs -> { cancelled = checkNotNull(currentCoroutineContext()[Job]); throw CancellationException() }
            Weekly -> weekly.await()
            else -> frontlineData(it)
        } } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent()
        assertTrue(cancelled.isCancelled)
        assertFalse(weekly.job.isCancelled)
        assertEquals(Idle, model.state.value.sections[Jobs]?.status)
        assertNull(model.state.value.sections[Jobs]?.failure)
        weekly.complete(frontlineData(Weekly)); advanceUntilIdle()
        service.sections = ::frontlineData
        model.retrySection(Jobs); advanceUntilIdle()
        service.sections = { throw CancellationException() }
        model.retrySection(Jobs); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.sections[Jobs]?.status)
        assertEquals("A", model.state.value.selectedJob)
    }

    @Test fun closeCancelsPendingReadsAndReopenDoesNotAcceptTheirLateNonCooperativeResults() = runTest {
        val first = FrontlineGate<PersonalDataFrontlineData>()
        val second = FrontlineGate<PersonalDataFrontlineData>()
        val catalog = FrontlineGate<PersonalDataFrontlineCatalogs>()
        val service = FrontlineFixture().apply {
            sections = { if (it == Overview) first.await() else frontlineData(it) }; catalogs = { catalog.await() }
        }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent(); model.close()
        assertTrue(first.job.isCancelled); assertTrue(catalog.job.isCancelled)
        assertEquals(Idle, model.state.value.sections[Overview]?.status)
        assertEquals(Idle, model.state.value.catalogStatus)
        service.sections = { if (it == Overview) second.await() else frontlineData(it) }; service.catalogs = { frontlineCatalog() }
        model.open(); runCurrent()
        first.fail(PersonalDataException.AuthenticationRequired); catalog.complete(PersonalDataFrontlineCatalogs(listOf("Stale"))); runCurrent()
        assertEquals(Loading, model.state.value.sections[Overview]?.status)
        assertEquals(frontlineCatalog(), model.state.value.catalogs)
        second.complete(frontlineData(Overview)); advanceUntilIdle()
        assertEquals(2, service.sectionCalls.count { it == Overview })
        assertEquals(1, service.sectionCalls.count { it == Jobs })
    }

    @Test fun newerRefreshResponseCannotBeReplacedByAnOlderSuccess() = runTest {
        val old = FrontlineGate<PersonalDataFrontlineData>()
        val current = FrontlineGate<PersonalDataFrontlineData>()
        val service = FrontlineFixture().apply { sections = { if (it == Overview) old.await() else frontlineData(it) } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent()
        service.sections = { if (it == Overview) current.await() else frontlineData(it) }
        model.refresh(); runCurrent()
        current.complete(PersonalDataFrontlineData.Overview(listOf(frontlineOverview(battles = 20)))); runCurrent()
        old.complete(PersonalDataFrontlineData.Overview(listOf(frontlineOverview(battles = 1)))); advanceUntilIdle()
        assertEquals(20L, model.state.value.overall?.battles)
    }

    @Test fun sectionAndCatalogAuthenticationFailuresClearAllDataAndSelectionsWithoutAutomaticReopenReads() = runTest {
        repeat(2) { source ->
            val service = FrontlineFixture()
            val model = PersonalDataFrontlineViewModel(service, clock)
            model.open(); advanceUntilIdle(); model.selectSection(Jobs); model.selectJob("B"); model.updateQuery("private")
            if (source == 0) { service.sections = { throw PersonalDataException.AuthenticationRequired }; model.retrySection(Jobs) }
            else { service.catalogs = { throw PersonalDataException.AuthenticationRequired }; model.retryCatalogs() }
            advanceUntilIdle()
            assertTrue(model.state.value.isOpen)
            assertEquals(Jobs, model.state.value.selectedSection)
            assertEquals(AuthRequired, model.state.value.currentSection.status)
            assertTrue(model.state.value.sections.values.all { it.data == null })
            assertNull(model.state.value.catalogs)
            assertNull(model.state.value.selectedJob)
            assertNull(model.state.value.selectedMap)
            assertEquals("", model.state.value.query)
            val calls = service.calls
            model.close(); model.open(); advanceUntilIdle()
            assertEquals(calls, service.calls)
            assertEquals(AuthRequired, model.state.value.currentSection.status)
        }
    }

    @Test fun authenticationCancelsSiblingsAndTheirLateDataCannotFillTheAuthScreen() = runTest {
        val auth = FrontlineGate<PersonalDataFrontlineData>()
        val late = FrontlineGate<PersonalDataFrontlineData>()
        val service = FrontlineFixture().apply { sections = { when (it) { Overview -> auth.await(); Weekly -> late.await(); else -> frontlineData(it) } } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent(); auth.fail(PersonalDataException.AuthenticationRequired); runCurrent()
        assertTrue(late.job.isCancelled)
        late.complete(frontlineData(Weekly)); advanceUntilIdle()
        assertNull(model.state.value.weekly)
        assertEquals(AuthRequired, model.state.value.currentSection.status)
        assertNull(model.state.value.catalogs)
    }

    @Test fun capabilityIsRecheckedBeforeExecutionAfterSuspensionAndOnLocalSelections() = runTest {
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); service.hasCommunityIdentity = false; advanceUntilIdle()
        assertEquals(0, service.calls)
        assertFalse(model.state.value.isOpen)
        service.hasCommunityIdentity = true
        val gate = FrontlineGate<PersonalDataFrontlineData>()
        service.sections = { if (it == Weekly) gate.await() else frontlineData(it) }
        model.open(); runCurrent(); service.hasCommunityIdentity = false
        gate.complete(frontlineData(Weekly)); advanceUntilIdle()
        assertEquals(PersonalDataFrontlineUiState(zone = clock.zone), model.state.value)
        service.hasCommunityIdentity = true; service.sections = ::frontlineData
        model.open(); advanceUntilIdle(); service.hasCommunityIdentity = false
        model.selectJob("B"); model.open(); model.refresh(); model.retryCatalogs(); model.retrySection(Weekly); model.close()
        advanceUntilIdle()
        assertEquals(PersonalDataFrontlineUiState(zone = clock.zone), model.state.value)
        assertEquals(16, service.calls)
    }

    @Test fun clearAndReenterWithSameSectionGenerationStillRejectsPreviousSessionData() = runTest {
        val old = FrontlineGate<PersonalDataFrontlineData>()
        val service = FrontlineFixture().apply { sections = { if (it == Overview) old.await() else frontlineData(it) } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); runCurrent(); model.selectSection(Jobs); model.selectJob("B"); model.clearProtectedContent()
        service.sections = ::frontlineData
        model.open(); advanceUntilIdle()
        old.complete(PersonalDataFrontlineData.Overview(listOf(frontlineOverview(battles = 999)))); advanceUntilIdle()
        assertEquals(10L, model.state.value.overall?.battles)
        assertEquals(Overview, model.state.value.selectedSection)
        assertEquals("A", model.state.value.selectedJob)
        assertEquals(16, service.calls)
    }

    @Test fun disposingStoreClearsAndPermanentlyDisablesTheRetainedModelReference() = runTest {
        val gate = FrontlineGate<PersonalDataFrontlineData>()
        val directory = FrontlineGate<PersonalDataFrontlineCatalogs>()
        val service = FrontlineFixture().apply {
            sections = { if (it == Weekly) gate.await() else frontlineData(it) }; catalogs = { directory.await() }
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, PersonalDataFrontlineViewModelFactory(service, clock))[PersonalDataFrontlineViewModel::class.java]
        model.open(); runCurrent(); owner.viewModelStore.clear()
        assertTrue(gate.job.isCancelled); assertTrue(directory.job.isCancelled)
        gate.complete(frontlineData(Weekly)); directory.complete(frontlineCatalog()); advanceUntilIdle()
        model.open(); model.refresh(); model.retryCatalogs(); model.selectSection(Jobs); advanceUntilIdle()
        assertFalse(model.hasCommunityIdentity)
        assertEquals(PersonalDataFrontlineUiState(zone = clock.zone), model.state.value)
        assertEquals(8, service.calls)
    }

    @Test fun injectedClockUpdatesCalendarWindowOnReopenWithoutRepeatingCachedReads() = runTest {
        val mutableClock = FrontlineClock(Instant.parse("2026-09-19T15:59:59Z"), clock.zone)
        val service = FrontlineFixture()
        val model = PersonalDataFrontlineViewModel(service, mutableClock)
        model.open(); advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 19), model.state.value.today)
        assertEquals(LocalDate.of(2026, 9, 18), model.state.value.weekly?.days?.last()?.date)
        mutableClock.now = Instant.parse("2026-09-19T16:00:00Z")
        model.close(); model.open(); advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 20), model.state.value.today)
        assertEquals(LocalDate.of(2026, 9, 19), model.state.value.weekly?.days?.last()?.date)
        assertEquals(8, service.calls)
    }

    @Test fun achievementOrderingUsesStateZoneAndBestKeepsItsLocalTimestampType() = runTest {
        val localStamp = FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 12, 0))
        val local = FrontlineAchievementRecord(1, "Local", null, localStamp)
        val offset = FrontlineAchievementRecord(2, "Offset", null, FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-20T08:00:00Z")))
        val service = FrontlineFixture().apply { sections = { when (it) {
            Achievements -> PersonalDataFrontlineData.Achievements(listOf(local, offset))
            Best -> PersonalDataFrontlineData.Best(listOf(frontlineBest().copy(recordedAt = localStamp)))
            else -> frontlineData(it)
        } } }
        val model = PersonalDataFrontlineViewModel(service, clock)
        model.open(); advanceUntilIdle()
        assertEquals(listOf(2, 1), model.state.value.visibleAchievements.map { it.achievementId })
        assertEquals(listOf(1, 2), model.state.value.copy(zone = ZoneId.of("UTC")).visibleAchievements.map { it.achievementId })
        assertSame(localStamp, model.state.value.currentBest?.recordedAt)
        assertEquals(listOf(local, offset), (model.state.value.sections[Achievements]?.data as PersonalDataFrontlineData.Achievements).rows)
        assertEquals(8, service.calls)
    }
}

private class FrontlineClock(var now: Instant, private val timeZone: ZoneId) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = timeZone
    override fun withZone(zone: ZoneId): Clock = FrontlineClock(now, zone)
}
private class FrontlineGate<T> {
    private lateinit var continuation: Continuation<T>
    lateinit var job: Job
    suspend fun await(): T { job = checkNotNull(currentCoroutineContext()[Job]); return suspendCoroutine { continuation = it } }
    fun complete(value: T) = continuation.resume(value)
    fun fail(error: Exception) = continuation.resumeWithException(error)
}
private fun frontlineCatalog() = PersonalDataFrontlineCatalogs(listOf("A", "B"), listOf(PersonalDataAchievementCatalogEntry(50, "Unobtained", null, null)))
private fun frontlineData(section: PersonalDataFrontlineSection): PersonalDataFrontlineData = when (section) {
    Overview -> PersonalDataFrontlineData.Overview(listOf(frontlineOverview(battles = 10), frontlineOverview(FrontlinePeriodKind.Since51, 5)))
    Weekly -> PersonalDataFrontlineData.Weekly(emptyList())
    Jobs -> PersonalDataFrontlineData.Jobs(listOf(frontlineJob("A", usage = 0.8), frontlineJob("B", usage = 0.2), frontlineJob("C", FrontlinePeriodKind.Since51)))
    Best -> PersonalDataFrontlineData.Best(listOf(frontlineBest()))
    Maps -> PersonalDataFrontlineData.Maps(listOf(FrontlineMapRecord("A", 10, 2, 3, 0.2), FrontlineMapRecord("B", 8, 1, 1, 0.125)))
    MapJobs -> PersonalDataFrontlineData.MapJobs(listOf(FrontlineMapJobRecord("A", "JobA", 1, 1, 2, 1.0)))
    Achievements -> PersonalDataFrontlineData.Achievements(emptyList())
}
private class FrontlineFixture : PersonalDataFrontlineService {
    override var hasCommunityIdentity = true
    val sectionCalls = mutableListOf<PersonalDataFrontlineSection>()
    var catalogCalls = 0
    val calls get() = sectionCalls.size + catalogCalls
    var sections: suspend (PersonalDataFrontlineSection) -> PersonalDataFrontlineData = ::frontlineData
    var catalogs: suspend () -> PersonalDataFrontlineCatalogs = { frontlineCatalog() }
    override suspend fun fetchFrontlineSection(section: PersonalDataFrontlineSection): PersonalDataFrontlineData { sectionCalls += section; return sections(section) }
    override suspend fun fetchFrontlineCatalogs(): PersonalDataFrontlineCatalogs { catalogCalls++; return catalogs() }
    override suspend fun fetchIdentity(): PersonalDataIdentity = error("Unused root read")
    override suspend fun fetchAvailability(): PersonalDataAvailability = error("Unused root read")
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent = error("Unused legacy board read")
    override suspend fun fetchUltimateDashboard(): UltimateDashboard = error("Unused legacy dashboard read")
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused encounter read")
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs = error("Unused old catalog read")
}
