package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.personaldata.domain.*

enum class PersonalDataFrontlineLoadStatus { Idle, Loading, Loaded, Failed, AuthRequired, Unavailable }
enum class PersonalDataFrontlineFailure { AuthenticationRequired, Unavailable, LoadFailed }

data class PersonalDataFrontlineSectionState(
    val status: PersonalDataFrontlineLoadStatus = PersonalDataFrontlineLoadStatus.Idle,
    val data: PersonalDataFrontlineData? = null,
    val failure: PersonalDataFrontlineFailure? = null,
)

data class PersonalDataFrontlineUiState(
    val isOpen: Boolean = false,
    val selectedSection: PersonalDataFrontlineSection = PersonalDataFrontlineSection.Overview,
    val sections: Map<PersonalDataFrontlineSection, PersonalDataFrontlineSectionState> = emptyMap(),
    val catalogs: PersonalDataFrontlineCatalogs? = null,
    val catalogStatus: PersonalDataFrontlineLoadStatus = PersonalDataFrontlineLoadStatus.Idle,
    val catalogFailure: PersonalDataFrontlineFailure? = null,
    val overallPeriod: FrontlinePeriodKind = FrontlinePeriodKind.Total,
    val jobPeriod: FrontlinePeriodKind = FrontlinePeriodKind.Total,
    val selectedJob: String? = null,
    val weeklyMetric: PersonalDataFrontlineWeeklyMetric = PersonalDataFrontlineWeeklyMetric.Battles,
    val bestKind: FrontlineBestKind = FrontlineBestKind.Kills,
    val selectedMap: String? = null,
    val selectedMapJob: String? = null,
    val includeUnobtained: Boolean = false,
    val query: String = "",
    val visibleLimit: Int = 10,
    val today: LocalDate? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val currentSection: PersonalDataFrontlineSectionState get() = sections[selectedSection] ?: PersonalDataFrontlineSectionState()
    val allTimeOverview: FrontlineOverviewRecord? get() = PersonalDataFrontlineViews.overview(overviewRows, FrontlinePeriodKind.Total)
    val overall: FrontlineOverviewRecord? get() = PersonalDataFrontlineViews.overview(overviewRows, overallPeriod)
    val availableJobs: List<FrontlineJobRecord> get() = PersonalDataFrontlineViews.jobs((sections[PersonalDataFrontlineSection.Jobs]?.data as? PersonalDataFrontlineData.Jobs)?.rows.orEmpty(), jobPeriod)
    val currentJob: FrontlineJobRecord? get() = availableJobs.firstOrNull { it.jobName == selectedJob }
    val currentBest: FrontlineBestRecord? get() = PersonalDataFrontlineViews.best((sections[PersonalDataFrontlineSection.Best]?.data as? PersonalDataFrontlineData.Best)?.rows.orEmpty(), bestKind)
    /** A map-only legacy provider does not define which achievements remain unobtained. */
    val hasAchievementCatalog: Boolean get() = catalogs?.achievements?.isNotEmpty() == true
    val availableMaps: List<String> get() = PersonalDataFrontlineViews.mapNames(catalogs, mapRows, mapJobRows)
    val availableMapJobs: List<FrontlineMapJobRecord> get() = PersonalDataFrontlineViews.mapJobs(mapJobRows, selectedMap)
    val currentMapStats: FrontlineMapRecord? get() = PersonalDataFrontlineViews.mapStats(mapRows, mapJobRows, selectedMap, selectedMapJob)
    val weekly: FrontlineWeekView? get() {
        val rows = (sections[PersonalDataFrontlineSection.Weekly]?.data as? PersonalDataFrontlineData.Weekly)?.rows ?: return null
        return today?.let { PersonalDataFrontlineViews.weekly(rows, it, zone) }
    }
    val visibleAchievements: List<FrontlineAchievementRow> get() = achievementRows.take(visibleLimit)
    val totalAchievements: Int get() = achievementRows.size
    val hasMoreAchievements: Boolean get() = totalAchievements > visibleLimit
    private val overviewRows get() = (sections[PersonalDataFrontlineSection.Overview]?.data as? PersonalDataFrontlineData.Overview)?.rows.orEmpty()
    private val mapRows get() = (sections[PersonalDataFrontlineSection.Maps]?.data as? PersonalDataFrontlineData.Maps)?.rows.orEmpty()
    private val mapJobRows get() = (sections[PersonalDataFrontlineSection.MapJobs]?.data as? PersonalDataFrontlineData.MapJobs)?.rows.orEmpty()
    private val achievementRows: List<FrontlineAchievementRow> get() {
        val rows = (sections[PersonalDataFrontlineSection.Achievements]?.data as? PersonalDataFrontlineData.Achievements)?.rows ?: return emptyList()
        return PersonalDataFrontlineViews.achievements(rows, catalogs, includeUnobtained, query, zone)
    }
}

class PersonalDataFrontlineViewModel(
    private val service: PersonalDataService,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonalDataFrontlineUiState(zone = clock.zone))
    val state: StateFlow<PersonalDataFrontlineUiState> = mutableState.asStateFlow()
    val supportsFrontline: Boolean get() = service is PersonalDataFrontlineService
    val hasCommunityIdentity: Boolean get() = !isCleared && service.hasCommunityIdentity
    private val jobs = mutableMapOf<PersonalDataFrontlineSection, Job>()
    private val sectionGenerations = mutableMapOf<PersonalDataFrontlineSection, Long>()
    private var catalogJob: Job? = null
    private var catalogGeneration = 0L
    private var generation = 0L
    private var isCleared = false

    fun open() {
        if (!requireIdentity()) return
        mutableState.update { it.copy(isOpen = true, today = LocalDate.now(clock), zone = clock.zone) }
        if (!supportsFrontline) {
            mutableState.update { it.copy(sections = PersonalDataFrontlineSection.entries.associateWith {
                PersonalDataFrontlineSectionState(PersonalDataFrontlineLoadStatus.Unavailable, failure = PersonalDataFrontlineFailure.Unavailable)
            }) }
            return
        }
        val currentGeneration = generation
        for (section in PersonalDataFrontlineSection.entries) {
            if (generation != currentGeneration || !requireIdentity()) return
            if ((state.value.sections[section]?.status ?: PersonalDataFrontlineLoadStatus.Idle) == PersonalDataFrontlineLoadStatus.Idle) loadSection(section)
        }
        if (generation == currentGeneration && state.value.catalogStatus == PersonalDataFrontlineLoadStatus.Idle) loadCatalogs()
    }

    fun close() {
        if (!requireIdentity()) return
        cancelRequests()
        mutableState.update { it.copy(isOpen = false) }
    }

    fun refresh() {
        if (!canRead()) return
        mutableState.update { it.copy(today = LocalDate.now(clock), zone = clock.zone) }
        val currentGeneration = generation
        for (section in PersonalDataFrontlineSection.entries) {
            if (generation != currentGeneration || !requireIdentity()) return
            loadSection(section)
        }
    }

    fun retrySection(section: PersonalDataFrontlineSection) {
        if (!canRead() || state.value.sections[section]?.status == PersonalDataFrontlineLoadStatus.Loading) return
        if (section == PersonalDataFrontlineSection.Weekly) mutableState.update { it.copy(today = LocalDate.now(clock), zone = clock.zone) }
        loadSection(section)
    }

    fun retryCatalogs() {
        if (canRead() && state.value.catalogStatus != PersonalDataFrontlineLoadStatus.Loading) loadCatalogs()
    }

    fun selectSection(section: PersonalDataFrontlineSection) = changeSelection { it.copy(selectedSection = section) }
    fun selectOverallPeriod(period: FrontlinePeriodKind) = changeSelection { it.copy(overallPeriod = period) }
    fun selectJobPeriod(period: FrontlinePeriodKind) = changeSelection {
        if (it.jobPeriod == period) it else it.copy(jobPeriod = period, selectedJob = null).reconcileSelections()
    }
    fun selectJob(job: String) = changeSelection { if (it.availableJobs.any { row -> row.jobName == job }) it.copy(selectedJob = job) else it }
    fun selectWeeklyMetric(metric: PersonalDataFrontlineWeeklyMetric) = changeSelection { it.copy(weeklyMetric = metric) }
    fun selectBestKind(kind: FrontlineBestKind) = changeSelection { it.copy(bestKind = kind) }
    fun selectMap(map: String) = changeSelection {
        if (map in it.availableMaps && map != it.selectedMap) it.copy(selectedMap = map, selectedMapJob = null) else it
    }
    fun selectMapJob(job: String?) = changeSelection {
        if (job == null || it.availableMapJobs.any { row -> row.jobName == job }) it.copy(selectedMapJob = job) else it
    }
    fun setIncludeUnobtained(include: Boolean) = changeSelection { it.copy(includeUnobtained = include, visibleLimit = 10) }
    fun updateQuery(query: String) = changeSelection { it.copy(query = query, visibleLimit = 10) }
    fun showMore() = changeSelection {
        if (it.hasMoreAchievements) it.copy(visibleLimit = (it.visibleLimit.toLong() + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else it
    }
    private fun changeSelection(change: (PersonalDataFrontlineUiState) -> PersonalDataFrontlineUiState) {
        if (requireIdentity() && state.value.isOpen) mutableState.update(change)
    }

    private fun loadSection(section: PersonalDataFrontlineSection) {
        val reader = service as? PersonalDataFrontlineService ?: return
        val requestGeneration = (sectionGenerations[section] ?: 0L) + 1
        sectionGenerations[section] = requestGeneration
        val currentGeneration = generation
        jobs.remove(section)?.cancel()
        updateSection(section) { it.copy(status = PersonalDataFrontlineLoadStatus.Loading, failure = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = generation == currentGeneration && sectionGenerations[section] == requestGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val result = reader.fetchFrontlineSection(section)
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (result.matches(section)) {
                        updateSection(section) { it.copy(status = PersonalDataFrontlineLoadStatus.Loaded, data = result, failure = null) }
                        mutableState.update { it.reconcileSelections() }
                    } else updateSection(section) { it.copy(status = PersonalDataFrontlineLoadStatus.Failed, failure = PersonalDataFrontlineFailure.LoadFailed) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else updateSection(section) { it.copy(status = PersonalDataFrontlineLoadStatus.Failed, failure = PersonalDataFrontlineFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    jobs.remove(section)
                    updateSection(section) { it.finishLoading() }
                }
            }
        }
        jobs[section] = job
        job.start()
    }

    private fun loadCatalogs() {
        val reader = service as? PersonalDataFrontlineService ?: return
        val requestGeneration = ++catalogGeneration
        val currentGeneration = generation
        catalogJob?.cancel()
        mutableState.update { it.copy(catalogStatus = PersonalDataFrontlineLoadStatus.Loading, catalogFailure = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = generation == currentGeneration && catalogGeneration == requestGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val result = reader.fetchFrontlineCatalogs()
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    mutableState.update { old ->
                        if (result.mapNames.any(String::isNotBlank) || result.achievements.isNotEmpty()) {
                            // Optional providers may supply just one component. An empty component
                            // is unavailable, and must not erase an already usable definition set.
                            val merged = result.copy(
                                mapNames = result.mapNames.takeIf { names -> names.any(String::isNotBlank) } ?: old.catalogs?.mapNames.orEmpty(),
                                achievements = result.achievements.takeIf { it.isNotEmpty() } ?: old.catalogs?.achievements.orEmpty(),
                            )
                            old.copy(catalogs = merged, catalogStatus = PersonalDataFrontlineLoadStatus.Loaded, catalogFailure = null).reconcileSelections()
                        } else old.copy(catalogStatus = PersonalDataFrontlineLoadStatus.Unavailable, catalogFailure = PersonalDataFrontlineFailure.Unavailable)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else mutableState.update { it.copy(catalogStatus = PersonalDataFrontlineLoadStatus.Failed, catalogFailure = PersonalDataFrontlineFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    catalogJob = null
                    mutableState.update { it.finishCatalogLoading() }
                }
            }
        }
        catalogJob = job
        job.start()
    }

    fun clearProtectedContent() {
        cancelRequests()
        sectionGenerations.clear()
        mutableState.value = PersonalDataFrontlineUiState(zone = clock.zone)
    }
    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }
    private fun cancelRequests() {
        generation++
        jobs.values.forEach(Job::cancel); jobs.clear()
        catalogJob?.cancel(); catalogJob = null
        mutableState.update { it.copy(sections = it.sections.mapValues { row -> row.value.finishLoading() }).finishCatalogLoading() }
    }
    private fun updateSection(section: PersonalDataFrontlineSection, change: (PersonalDataFrontlineSectionState) -> PersonalDataFrontlineSectionState) {
        mutableState.update { it.copy(sections = it.sections + (section to change(it.sections[section] ?: PersonalDataFrontlineSectionState()))) }
    }
    private fun requireIdentity(): Boolean {
        if (isCleared) return false
        if (service.hasCommunityIdentity) return true
        clearProtectedContent()
        return false
    }
    private fun canRead() = requireIdentity() && state.value.isOpen && supportsFrontline
    private fun authenticationFailed() {
        val old = state.value
        clearProtectedContent()
        mutableState.value = PersonalDataFrontlineUiState(
            isOpen = old.isOpen, selectedSection = old.selectedSection, zone = clock.zone,
            sections = PersonalDataFrontlineSection.entries.associateWith { PersonalDataFrontlineSectionState(
                PersonalDataFrontlineLoadStatus.AuthRequired, failure = PersonalDataFrontlineFailure.AuthenticationRequired) },
            catalogStatus = PersonalDataFrontlineLoadStatus.AuthRequired, catalogFailure = PersonalDataFrontlineFailure.AuthenticationRequired,
        )
    }
}

private fun PersonalDataFrontlineUiState.reconcileSelections(): PersonalDataFrontlineUiState {
    val job = selectedJob?.takeIf { selected -> availableJobs.any { it.jobName == selected } } ?: availableJobs.firstOrNull()?.jobName
    val map = selectedMap?.takeIf { it in availableMaps } ?: availableMaps.firstOrNull()
    val withMap = copy(selectedJob = job, selectedMap = map)
    return withMap.copy(selectedMapJob = selectedMapJob?.takeIf { selected -> map == selectedMap && withMap.availableMapJobs.any { it.jobName == selected } })
}
private fun PersonalDataFrontlineSectionState.finishLoading() = if (status == PersonalDataFrontlineLoadStatus.Loading)
    copy(status = if (data == null) PersonalDataFrontlineLoadStatus.Idle else PersonalDataFrontlineLoadStatus.Loaded) else this
private fun PersonalDataFrontlineUiState.finishCatalogLoading() = if (catalogStatus == PersonalDataFrontlineLoadStatus.Loading)
    copy(catalogStatus = if (catalogs == null) PersonalDataFrontlineLoadStatus.Idle else PersonalDataFrontlineLoadStatus.Loaded) else this
private fun PersonalDataFrontlineData.matches(section: PersonalDataFrontlineSection): Boolean = when (this) {
    is PersonalDataFrontlineData.Overview -> section == PersonalDataFrontlineSection.Overview
    is PersonalDataFrontlineData.Weekly -> section == PersonalDataFrontlineSection.Weekly
    is PersonalDataFrontlineData.Jobs -> section == PersonalDataFrontlineSection.Jobs
    is PersonalDataFrontlineData.Best -> section == PersonalDataFrontlineSection.Best
    is PersonalDataFrontlineData.Maps -> section == PersonalDataFrontlineSection.Maps
    is PersonalDataFrontlineData.MapJobs -> section == PersonalDataFrontlineSection.MapJobs
    is PersonalDataFrontlineData.Achievements -> section == PersonalDataFrontlineSection.Achievements
}

class PersonalDataFrontlineViewModelFactory(
    private val service: PersonalDataService,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PersonalDataFrontlineViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return PersonalDataFrontlineViewModel(service, clock) as T
    }
}
