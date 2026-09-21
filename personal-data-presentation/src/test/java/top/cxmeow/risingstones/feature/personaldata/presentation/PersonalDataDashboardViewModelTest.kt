package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataDashboardSectionKind.*
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataDashboardLoadStatus.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataDashboardViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun explicitBoardsReadEverySectionOnceAndKeepSelectionAcrossReopen() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        advanceUntilIdle()
        assertEquals(0, service.calls)
        listOf(PersonalDataBoard.Fishing, PersonalDataBoard.Glamour, PersonalDataBoard.Savage).forEach { board ->
            model.open(board); advanceUntilIdle()
            assertEquals(PersonalDataDashboardSectionKind.forBoard(board).first(), model.state.value.selectedSection)
            model.selectSection(PersonalDataDashboardSectionKind.forBoard(board).last())
        }
        assertEquals(PersonalDataDashboardSectionKind.entries.toSet(), service.sectionCalls.toSet())
        assertEquals(14, service.sectionCalls.size)
        assertEquals(1, service.catalogCalls)
        assertEquals(1, service.supplementaryCalls)
        model.close(); model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(OceanFishing, model.state.value.selectedSection)
        assertEquals(16, service.calls)
        assertTrue(model.state.value.sections.values.all { it.status == Loaded })
    }

    @Test fun unsupportedBoardsAndOldServicesNeverFetchOptionalOrFallbackEndpoints() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Frontline); model.open(PersonalDataBoard.Ultimate)
        advanceUntilIdle()
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
        val oldModel = PersonalDataDashboardViewModel(object : PersonalDataService by service {})
        oldModel.open(PersonalDataBoard.Fishing); oldModel.refresh(); oldModel.retrySection(BigFish); oldModel.retryCatalogs()
        advanceUntilIdle()
        assertFalse(oldModel.supportsDashboards)
        assertEquals(Unavailable, oldModel.state.value.currentSection.status)
        assertEquals(0, service.calls)
    }

    @Test fun slowFirstSectionDoesNotBlockOtherSectionsOrEitherCatalog() = runTest {
        val gate = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { if (it == FishingSummary) gate.await() else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent()
        assertEquals(Loading, model.state.value.sections[FishingSummary]?.status)
        assertEquals(Loaded, model.state.value.sections[OceanFishing]?.status)
        assertEquals(Loaded, model.state.value.catalogStatus)
        assertEquals(Loaded, model.state.value.supplementaryStatus)
        assertEquals(8, service.calls)
        gate.complete(dashboardData(FishingSummary)); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.sections[FishingSummary]?.status)
    }

    @Test fun eachSectionRejectsOtherPayloadTypesWithoutOverwritingPreviousData() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        listOf(PersonalDataBoard.Fishing, PersonalDataBoard.Glamour, PersonalDataBoard.Savage).forEach { board ->
            service.sections = ::dashboardData
            model.open(board); advanceUntilIdle()
            val before = model.state.value.sections
            service.sections = { kind -> dashboardData(if (kind == FishingSummary) GlamourSummary else FishingSummary) }
            model.refresh(); advanceUntilIdle()
            PersonalDataDashboardSectionKind.forBoard(board).forEach { kind ->
                assertEquals(Failed, model.state.value.sections[kind]?.status)
                assertEquals(PersonalDataDashboardFailure.LoadFailed, model.state.value.sections[kind]?.failure)
                assertEquals(before[kind]?.data, model.state.value.sections[kind]?.data)
            }
        }
    }

    @Test fun fullSourceRowsAndSuccessfulEmptySectionsAreCachedWithoutTruncation() = runTest {
        val rows = (1..37).map { PersonalDataFishingRank("Fish $it", it.toLong(), null) }
        val service = DashboardFixture().apply { sections = { if (it == FishRanking) PersonalDataDashboardData.FishRanking(rows) else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(rows, (model.state.value.sections[FishRanking]?.data as PersonalDataDashboardData.FishRanking).rows)
        assertEquals(emptyList<PersonalDataFishCatch>(), (model.state.value.sections[BigFish]?.data as PersonalDataDashboardData.BigFish).rows)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(6, service.sectionCalls.size)
    }

    @Test fun partialRefreshFailurePreservesOldDataAndCriteriaWithoutReloadingCatalogs() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        model.selectSection(BigFish); model.updateQuery("fish"); model.showMore()
        val old = model.state.value.sections[BigFish]?.data
        service.sections = { if (it == BigFish) error("private exception details") else dashboardData(it, 42) }
        model.refresh(); advanceUntilIdle()
        assertEquals(old, model.state.value.currentSection.data)
        assertEquals(Failed, model.state.value.currentSection.status)
        assertEquals(PersonalDataDashboardFailure.LoadFailed, model.state.value.currentSection.failure)
        assertEquals("fish", model.state.value.currentCriteria.query)
        assertEquals(20, model.state.value.currentCriteria.visibleLimit)
        assertEquals(dashboardData(FishingSummary, 42), model.state.value.sections[FishingSummary]?.data)
        assertEquals(1, service.catalogCalls)
        assertEquals(1, service.supplementaryCalls)
        model.close(); model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(12, service.sectionCalls.size)
    }

    @Test fun retryOnlyReadsChosenActiveSectionAndIgnoresDuplicatePendingCalls() = runTest {
        val service = DashboardFixture().apply { sections = { if (it == BigFish) error("failed") else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        val gate = DashboardGate<PersonalDataDashboardData>()
        service.sections = { gate.await() }
        model.retrySection(BigFish); model.retrySection(BigFish); model.retrySection(Vanity); runCurrent()
        assertEquals(7, service.sectionCalls.size)
        gate.complete(dashboardData(BigFish)); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.sections[BigFish]?.status)
        model.close(); model.retrySection(BigFish); advanceUntilIdle()
        assertEquals(7, service.sectionCalls.size)
    }

    @Test fun allCriteriaStayLocalAndAreRetainedPerSectionAndBoard() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        model.selectSection(FishRanking)
        assertEquals(PersonalDataDashboardCriteria(), model.state.value.currentCriteria)
        model.updateQuery(" fish "); model.selectCategory(""); model.showMore()
        assertEquals("", model.state.value.currentCriteria.category)
        model.selectSection(BigFish); model.selectPatch("7.0"); model.selectFishGroup(PersonalDataDashboardFishGroup.Ocean)
        model.setFishSort(PersonalDataDashboardFishSort.CountDescending); model.setIncludeUnobtained(true); model.showMore(); model.showMore()
        val criteria = model.state.value.currentCriteria
        model.open(PersonalDataBoard.Glamour); advanceUntilIdle()
        assertEquals(PersonalDataDashboardCriteria(), model.state.value.currentCriteria)
        model.selectSection(BigFish)
        assertEquals(GlamourSummary, model.state.value.selectedSection)
        model.close(); model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(BigFish, model.state.value.selectedSection)
        assertEquals(criteria, model.state.value.currentCriteria)
        model.selectSection(FishRanking)
        assertEquals(" fish ", model.state.value.currentCriteria.query)
        assertEquals("", model.state.value.currentCriteria.category)
        assertEquals(20, model.state.value.currentCriteria.visibleLimit)
        model.selectCategory(null)
        assertNull(model.state.value.currentCriteria.category)
        assertEquals(10, model.state.value.currentCriteria.visibleLimit)
        assertEquals(14, service.calls)
    }

    @Test fun vanityPeriodAndMajorChangesClearCategoryAndInvalidMajorIsIgnored() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Glamour); advanceUntilIdle(); model.selectSection(Vanity)
        model.selectVanityCategory(5); model.showMore()
        model.selectVanityMajor(2)
        assertEquals(1, model.state.value.currentCriteria.vanityMajor)
        assertEquals(5, model.state.value.currentCriteria.vanityCategoryId)
        assertEquals(20, model.state.value.currentCriteria.visibleLimit)
        model.selectVanityMajor(3)
        assertNull(model.state.value.currentCriteria.vanityCategoryId)
        assertEquals(10, model.state.value.currentCriteria.visibleLimit)
        model.selectVanityCategory(6); model.selectVanityMajor(3)
        assertEquals(6, model.state.value.currentCriteria.vanityCategoryId)
        model.setVanityPeriod(PersonalDataVanityPeriod.LastYear)
        assertNull(model.state.value.currentCriteria.vanityCategoryId)
        model.selectVanityCategory(7); model.selectVanityMajor(4)
        assertNull(model.state.value.currentCriteria.vanityCategoryId)
        assertEquals(8, service.calls)
    }

    @Test fun criteriaEditedWhileLoadingSurviveResultDelivery() = runTest {
        val gate = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { if (it == BigFish) gate.await() else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent()
        model.selectSection(BigFish); model.updateQuery("during read"); model.selectPatch("7.1"); model.showMore()
        val criteria = model.state.value.currentCriteria
        gate.complete(dashboardData(BigFish)); advanceUntilIdle()
        assertEquals(criteria, model.state.value.currentCriteria)
        assertEquals(Loaded, model.state.value.currentSection.status)
    }

    @Test fun closeCancelsOnlyUnfinishedReadsAndReopenOnlyReloadsThoseSections() = runTest {
        val gate = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { if (it == BigFish) gate.await() else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent(); model.close()
        assertTrue(gate.job.isCancelled)
        assertNull(model.state.value.activeBoard)
        assertEquals(Idle, model.state.value.sections[BigFish]?.status)
        assertEquals(Loaded, model.state.value.sections[FishRanking]?.status)
        service.sections = ::dashboardData
        model.open(PersonalDataBoard.Fishing); runCurrent()
        gate.complete(PersonalDataDashboardData.BigFish(listOf(PersonalDataFishCatch("stale", null, 5)))); advanceUntilIdle()
        assertEquals(dashboardData(BigFish), model.state.value.sections[BigFish]?.data)
        assertEquals(7, service.sectionCalls.size)
        assertEquals(1, service.catalogCalls)
    }

    @Test fun cancellationFromServicePropagatesAndFinishesInitialAndRefreshLoading() = runTest {
        lateinit var cancelledJob: Job
        val service = DashboardFixture().apply { sections = {
            if (it == BigFish) { cancelledJob = checkNotNull(currentCoroutineContext()[Job]); throw CancellationException("cancelled") }
            dashboardData(it)
        } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertTrue(cancelledJob.isCancelled)
        assertEquals(Idle, model.state.value.sections[BigFish]?.status)
        assertNull(model.state.value.sections[BigFish]?.failure)
        service.sections = ::dashboardData
        model.retrySection(BigFish); advanceUntilIdle()
        service.sections = { throw CancellationException("cancelled refresh") }
        model.retrySection(BigFish); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.sections[BigFish]?.status)
        assertEquals(dashboardData(BigFish), model.state.value.sections[BigFish]?.data)
        assertNull(model.state.value.sections[BigFish]?.failure)
    }

    @Test fun cancellingOneJobDoesNotCancelItsSiblingOrPublishItsLateResult() = runTest {
        val fish = DashboardGate<PersonalDataDashboardData>()
        val bait = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { when (it) { FishRanking -> fish.await(); BaitRanking -> bait.await(); else -> dashboardData(it) } } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent()
        fish.job.cancel(); fish.complete(dashboardData(FishRanking)); runCurrent()
        assertEquals(Idle, model.state.value.sections[FishRanking]?.status)
        assertEquals(Loading, model.state.value.sections[BaitRanking]?.status)
        assertFalse(bait.job.isCancelled)
        bait.complete(dashboardData(BaitRanking)); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.sections[BaitRanking]?.status)
    }

    @Test fun boardABAAndLateFailureCannotReplaceTheNewRequestOrItsCache() = runTest {
        val old = DashboardGate<PersonalDataDashboardData>()
        val current = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { if (it == BigFish) old.await() else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent()
        model.open(PersonalDataBoard.Glamour); runCurrent()
        service.sections = { if (it == BigFish) current.await() else dashboardData(it) }
        model.open(PersonalDataBoard.Fishing); runCurrent()
        old.fail(PersonalDataException.AuthenticationRequired); runCurrent()
        assertEquals(Loading, model.state.value.sections[BigFish]?.status)
        assertNotNull(model.state.value.catalogs)
        current.complete(dashboardData(BigFish)); advanceUntilIdle()
        model.close(); model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.sections[BigFish]?.status)
        assertEquals(2, service.sectionCalls.count { it == BigFish })
    }

    @Test fun refreshReplacementIgnoresOldNonCooperativeSuccess() = runTest {
        val old = DashboardGate<PersonalDataDashboardData>()
        val current = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { if (it == FishingSummary) old.await() else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent()
        service.sections = { if (it == FishingSummary) current.await() else dashboardData(it) }
        model.refresh(); runCurrent()
        current.complete(dashboardData(FishingSummary, 2)); runCurrent()
        old.complete(dashboardData(FishingSummary, 1)); advanceUntilIdle()
        assertEquals(dashboardData(FishingSummary, 2), model.state.value.sections[FishingSummary]?.data)
    }

    @Test fun catalogFailuresRemainIndependentAndRetriesDoNotReadSectionsOrDuplicatePendingJobs() = runTest {
        val service = DashboardFixture().apply { catalogs = { error("private catalog error") } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(Failed, model.state.value.catalogStatus)
        assertEquals(Loaded, model.state.value.supplementaryStatus)
        val supplementary = model.state.value.supplementary
        val gate = DashboardGate<PersonalDataOfficialCatalogs>()
        val extra = DashboardGate<PersonalDataSupplementaryCatalogs>()
        service.catalogs = { gate.await() }; service.supplementary = { extra.await() }
        model.retryCatalogs(); model.retryCatalogs(); runCurrent()
        assertEquals(2, service.catalogCalls)
        assertEquals(2, service.supplementaryCalls)
        gate.complete(dashboardCatalog(2)); extra.fail(IllegalStateException("private supplementary error")); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.catalogStatus)
        assertEquals(dashboardCatalog(2), model.state.value.catalogs)
        assertEquals(Failed, model.state.value.supplementaryStatus)
        assertEquals(supplementary, model.state.value.supplementary)
        assertEquals(6, service.sectionCalls.size)
        service.catalogs = { error("refresh failed") }; service.supplementary = { dashboardSupplementary(2) }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(dashboardCatalog(2), model.state.value.catalogs)
        assertEquals(Failed, model.state.value.catalogStatus)
        assertEquals(dashboardSupplementary(2), model.state.value.supplementary)
        assertEquals(Loaded, model.state.value.supplementaryStatus)
    }

    @Test fun emptyCatalogsAreUnavailableAndCannotErasePreviousCatalogs() = runTest {
        val service = DashboardFixture().apply {
            catalogs = { PersonalDataOfficialCatalogs() }; supplementary = { PersonalDataSupplementaryCatalogs() }
        }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(Unavailable, model.state.value.catalogStatus)
        assertEquals(Unavailable, model.state.value.supplementaryStatus)
        model.close(); model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(8, service.calls)
        service.catalogs = { dashboardCatalog() }; service.supplementary = { dashboardSupplementary() }
        model.retryCatalogs(); advanceUntilIdle()
        service.catalogs = { PersonalDataOfficialCatalogs() }; service.supplementary = { PersonalDataSupplementaryCatalogs() }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(Unavailable, model.state.value.catalogStatus)
        assertEquals(dashboardCatalog(), model.state.value.catalogs)
        assertEquals(dashboardSupplementary(), model.state.value.supplementary)
        assertEquals(Loaded, model.state.value.currentSection.status)
    }

    @Test fun cancelledCatalogsReturnToIdleOrLoadedAndNeverBecomeFailures() = runTest {
        val service = DashboardFixture().apply {
            catalogs = { throw CancellationException() }; supplementary = { throw CancellationException() }
        }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(Idle, model.state.value.catalogStatus)
        assertEquals(Idle, model.state.value.supplementaryStatus)
        assertNull(model.state.value.catalogFailure)
        service.catalogs = { dashboardCatalog() }; service.supplementary = { dashboardSupplementary() }
        model.retryCatalogs(); advanceUntilIdle()
        service.catalogs = { throw CancellationException() }; service.supplementary = { throw CancellationException() }
        model.retryCatalogs(); advanceUntilIdle()
        assertEquals(Loaded, model.state.value.catalogStatus)
        assertEquals(Loaded, model.state.value.supplementaryStatus)
        assertNull(model.state.value.supplementaryFailure)
    }

    @Test fun catalogABAAndExplicitClearBlockLateResultsFromBothCatalogSources() = runTest {
        val old = DashboardGate<PersonalDataOfficialCatalogs>()
        val extra = DashboardGate<PersonalDataSupplementaryCatalogs>()
        val service = DashboardFixture().apply { catalogs = { old.await() }; supplementary = { extra.await() } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent(); model.close()
        service.catalogs = { dashboardCatalog(2) }; service.supplementary = { dashboardSupplementary(2) }
        model.open(PersonalDataBoard.Glamour); advanceUntilIdle()
        old.complete(dashboardCatalog(1)); extra.complete(dashboardSupplementary(1)); advanceUntilIdle()
        assertEquals(dashboardCatalog(2), model.state.value.catalogs)
        assertEquals(dashboardSupplementary(2), model.state.value.supplementary)
        assertEquals(2, service.catalogCalls)
    }

    @Test fun eachAuthenticationFailureClearsOtherBoardsFiltersAndBothCatalogsButKeepsVisibleNavigation() = runTest {
        repeat(3) { origin ->
            val service = DashboardFixture()
            val model = PersonalDataDashboardViewModel(service)
            model.open(PersonalDataBoard.Glamour); advanceUntilIdle(); model.selectSection(Vanity); model.updateQuery("private")
            model.open(PersonalDataBoard.Fishing); advanceUntilIdle(); model.selectSection(BigFish)
            when (origin) {
                0 -> { service.sections = { throw PersonalDataException.AuthenticationRequired }; model.retrySection(BigFish) }
                1 -> { service.catalogs = { throw PersonalDataException.AuthenticationRequired }; model.retryCatalogs() }
                else -> { service.supplementary = { throw PersonalDataException.AuthenticationRequired }; model.retryCatalogs() }
            }
            advanceUntilIdle()
            assertEquals(PersonalDataBoard.Fishing, model.state.value.activeBoard)
            assertEquals(BigFish, model.state.value.selectedSection)
            assertEquals(AuthRequired, model.state.value.currentSection.status)
            assertEquals(PersonalDataDashboardFailure.AuthenticationRequired, model.state.value.currentSection.failure)
            assertEquals(PersonalDataDashboardCriteria(), model.state.value.currentCriteria)
            assertTrue(model.state.value.sections.values.all { it.data == null })
            assertEquals(PersonalDataDashboardSectionKind.forBoard(PersonalDataBoard.Fishing).toSet(), model.state.value.sections.keys)
            assertNull(model.state.value.catalogs); assertNull(model.state.value.supplementary)
            model.clearProtectedContent()
        }
    }

    @Test fun authenticationFailureCancelsSiblingsAndLateResponsesCannotRepopulateState() = runTest {
        val denied = DashboardGate<PersonalDataDashboardData>()
        val late = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { when (it) { FishingSummary -> denied.await(); FishRanking -> late.await(); else -> dashboardData(it) } } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent()
        denied.fail(PersonalDataException.AuthenticationRequired); runCurrent()
        assertTrue(late.job.isCancelled)
        val expected = model.state.value
        late.complete(dashboardData(FishRanking)); advanceUntilIdle()
        assertEquals(expected, model.state.value)
        assertEquals(AuthRequired, model.state.value.currentSection.status)
    }

    @Test fun capabilityIsCheckedBeforeQueuedReadsAndAfterNonCooperativeResponse() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); service.hasCommunityIdentity = false; advanceUntilIdle()
        assertEquals(0, service.calls)
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
        service.hasCommunityIdentity = true
        val gate = DashboardGate<PersonalDataDashboardData>()
        service.sections = { if (it == BigFish) gate.await() else dashboardData(it) }
        model.open(PersonalDataBoard.Fishing); runCurrent()
        service.hasCommunityIdentity = false
        gate.complete(dashboardData(BigFish)); advanceUntilIdle()
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
        val calls = service.calls
        model.refresh(); model.retryCatalogs(); model.retrySection(BigFish); model.open(PersonalDataBoard.Glamour)
        model.updateQuery("forbidden"); model.showMore(); model.close(); advanceUntilIdle()
        assertEquals(calls, service.calls)
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
    }

    @Test fun capabilityRevocationDuringLocalActionClearsCacheAndReentryFetchesFreshState() = runTest {
        val service = DashboardFixture()
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle(); model.selectSection(BigFish); model.updateQuery("private")
        service.hasCommunityIdentity = false
        model.selectVanityMajor(2)
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
        service.hasCommunityIdentity = true
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        assertEquals(FishingSummary, model.state.value.selectedSection)
        assertEquals(PersonalDataDashboardCriteria(), model.state.value.currentCriteria)
        assertEquals(16, service.calls)
    }

    @Test fun clearAndSameBoardReentryCannotAcceptPreviousGenerationWithReusedSectionNumber() = runTest {
        val old = DashboardGate<PersonalDataDashboardData>()
        val service = DashboardFixture().apply { sections = { if (it == FishingSummary) old.await() else dashboardData(it) } }
        val model = PersonalDataDashboardViewModel(service)
        model.open(PersonalDataBoard.Fishing); runCurrent(); model.clearProtectedContent()
        service.sections = { dashboardData(it, 2) }
        model.open(PersonalDataBoard.Fishing); advanceUntilIdle()
        old.complete(dashboardData(FishingSummary, 1)); advanceUntilIdle()
        assertEquals(dashboardData(FishingSummary, 2), model.state.value.currentSection.data)
        assertEquals(2, service.sectionCalls.count { it == FishingSummary })
    }

    @Test fun storeDisposalClearsMemoryCancelsEveryReadAndPermanentlyPreventsReuse() = runTest {
        val section = DashboardGate<PersonalDataDashboardData>()
        val catalog = DashboardGate<PersonalDataOfficialCatalogs>()
        val extra = DashboardGate<PersonalDataSupplementaryCatalogs>()
        val service = DashboardFixture().apply {
            sections = { if (it == BigFish) section.await() else dashboardData(it) }
            catalogs = { catalog.await() }; supplementary = { extra.await() }
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val model = ViewModelProvider(owner, PersonalDataDashboardViewModelFactory(service))[PersonalDataDashboardViewModel::class.java]
        model.open(PersonalDataBoard.Fishing); runCurrent(); model.selectSection(BigFish); model.updateQuery("private")
        owner.viewModelStore.clear()
        assertTrue(section.job.isCancelled); assertTrue(catalog.job.isCancelled); assertTrue(extra.job.isCancelled)
        section.complete(dashboardData(BigFish)); catalog.complete(dashboardCatalog()); extra.complete(dashboardSupplementary()); advanceUntilIdle()
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
        assertFalse(model.hasCommunityIdentity)
        model.open(PersonalDataBoard.Glamour); model.refresh(); model.retryCatalogs(); model.updateQuery("blocked"); advanceUntilIdle()
        assertEquals(8, service.calls)
        assertEquals(PersonalDataDashboardUiState(), model.state.value)
    }
}

private fun dashboardData(section: PersonalDataDashboardSectionKind): PersonalDataDashboardData = dashboardData(section, 1)
private fun dashboardData(section: PersonalDataDashboardSectionKind, marker: Long): PersonalDataDashboardData = when (section) {
    FishingSummary -> PersonalDataDashboardData.FishingSummary(FishingOverview(marker, 0.5, null, null))
    FishRanking -> PersonalDataDashboardData.FishRanking(emptyList())
    BaitRanking -> PersonalDataDashboardData.BaitRanking(emptyList())
    BigFish -> PersonalDataDashboardData.BigFish(emptyList())
    FishingAchievements -> PersonalDataDashboardData.FishingAchievements(emptyList())
    OceanFishing -> PersonalDataDashboardData.OceanFishing(emptyList())
    GlamourSummary -> PersonalDataDashboardData.GlamourSummary(GlamourOverview(marker, null, null))
    Races -> PersonalDataDashboardData.Races(emptyList())
    Stains -> PersonalDataDashboardData.Stains(emptyList())
    Accessories -> PersonalDataDashboardData.Accessories(emptyList())
    Vanity -> PersonalDataDashboardData.Vanity(emptyList())
    Sets -> PersonalDataDashboardData.Sets(emptyList())
    SavageSummary -> PersonalDataDashboardData.SavageSummary(SavageOverview(marker, null, null, null))
    SavageRaids -> PersonalDataDashboardData.SavageRaids(emptyList())
}

private fun dashboardCatalog(id: Int = 1) = PersonalDataOfficialCatalogs(fish = mapOf(id to FishKingCatalogEntry(id, id, "Fish $id", "7.0")))
private fun dashboardSupplementary(id: Int = 1) = PersonalDataSupplementaryCatalogs(oceanFish = listOf(PersonalDataOceanFishCatalogEntry(id, id, "Ocean fish $id")))

private class DashboardGate<T> {
    private lateinit var continuation: Continuation<T>
    lateinit var job: Job
    suspend fun await(): T { job = checkNotNull(currentCoroutineContext()[Job]); return suspendCoroutine { continuation = it } }
    fun complete(value: T) = continuation.resume(value)
    fun fail(error: Exception) = continuation.resumeWithException(error)
}

private class DashboardFixture : PersonalDataDashboardService {
    override var hasCommunityIdentity = true
    val sectionCalls = mutableListOf<PersonalDataDashboardSectionKind>()
    var catalogCalls = 0
    var supplementaryCalls = 0
    val calls get() = sectionCalls.size + catalogCalls + supplementaryCalls
    var sections: suspend (PersonalDataDashboardSectionKind) -> PersonalDataDashboardData = ::dashboardData
    var catalogs: suspend () -> PersonalDataOfficialCatalogs = { dashboardCatalog() }
    var supplementary: suspend () -> PersonalDataSupplementaryCatalogs = { dashboardSupplementary() }
    override suspend fun fetchDashboardSection(section: PersonalDataDashboardSectionKind): PersonalDataDashboardData { sectionCalls += section; return sections(section) }
    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs { catalogCalls++; return catalogs() }
    override suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs { supplementaryCalls++; return supplementary() }
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind): List<PersonalDataFishingRank> = error("Unused legacy reading")
    override suspend fun fetchRaceUsage(): List<PersonalDataRaceUsage> = error("Unused legacy reading")
    override suspend fun fetchGlamourSetRecords(): List<PersonalDataGlamourSetRecord> = error("Unused legacy reading")
    override suspend fun fetchIdentity(): PersonalDataIdentity = error("Unused root read")
    override suspend fun fetchAvailability(): PersonalDataAvailability = error("Unused root read")
    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent = error("Unused legacy board read")
    override suspend fun fetchUltimateDashboard(): UltimateDashboard = error("Unused legacy dashboard read")
    override suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail = error("Unused encounter read")
}
