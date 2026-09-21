package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

enum class PersonalDataUltimateLoadStatus { Idle, Loading, Loaded, Failed, AuthRequired, Unavailable }
enum class PersonalDataUltimateFailure { AuthenticationRequired, Unavailable, LoadFailed }
data class PersonalDataUltimateSectionState(
    val status: PersonalDataUltimateLoadStatus = PersonalDataUltimateLoadStatus.Idle,
    val data: PersonalDataUltimateData? = null,
    val failure: PersonalDataUltimateFailure? = null,
)
data class PersonalDataUltimateEncounterState(
    val selectedSection: PersonalDataUltimateSection = PersonalDataUltimateSection.Party,
    val sections: Map<PersonalDataUltimateSection, PersonalDataUltimateSectionState> = emptyMap(),
    val partnerLimit: Int = 3,
    val deathLimit: Int = 20,
    val jobOrders: Map<String, Int> = emptyMap(),
)
data class PersonalDataUltimateUiState(
    val isOpen: Boolean = false,
    val overviewStatus: PersonalDataUltimateLoadStatus = PersonalDataUltimateLoadStatus.Idle,
    val overviewFailure: PersonalDataUltimateFailure? = null,
    val records: List<PersonalDataUltimateRecord>? = null,
    val selectedTerritoryType: Int? = null,
    val encounters: Map<Int, PersonalDataUltimateEncounterState> = emptyMap(),
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val selectedRecord: PersonalDataUltimateRecord? get() = records?.firstOrNull { it.territoryType == selectedTerritoryType }
    val currentEncounter: PersonalDataUltimateEncounterState get() = encounters[selectedTerritoryType] ?: PersonalDataUltimateEncounterState()
    val selectedSection: PersonalDataUltimateSection get() = currentEncounter.selectedSection
    val currentSection: PersonalDataUltimateSectionState get() = currentEncounter.sections[selectedSection] ?: PersonalDataUltimateSectionState()
    val availableSections: List<PersonalDataUltimateSection> get() = if (selectedRecord == null) emptyList() else ultimateSections(selectedRecord!!.territoryType)
    val totalClears: Long? get() = records?.let(PersonalDataUltimateViews::totalClears)
    val party: List<UltimatePartyMember> get() = PersonalDataUltimateViews.party(
        (currentEncounter.sections[PersonalDataUltimateSection.Party]?.data as? PersonalDataUltimateData.Party)?.rows.orEmpty(),
    ) { currentEncounter.jobOrders[it] }
    val jobs: List<UltimateJobUsage> get() = PersonalDataUltimateViews.jobs((currentEncounter.sections[PersonalDataUltimateSection.Jobs]?.data as? PersonalDataUltimateData.Jobs)?.rows.orEmpty())
    val partners: List<UltimateCompanion> get() = PersonalDataUltimateViews.partners((currentEncounter.sections[PersonalDataUltimateSection.Partners]?.data as? PersonalDataUltimateData.Partners)?.rows.orEmpty())
    val visiblePartners: List<UltimateCompanion> get() = partners.take(currentEncounter.partnerLimit)
    val hasMorePartners: Boolean get() = partners.size > currentEncounter.partnerLimit
    val phases: List<UltimatePhaseRecord> get() = if (selectedTerritoryType == 733) emptyList() else PersonalDataUltimateViews.phases(
        (currentEncounter.sections[PersonalDataUltimateSection.Phases]?.data as? PersonalDataUltimateData.Phases)?.rows.orEmpty(), zone,
    )
    val deathPlot: UltimateDeathPlot? get() {
        val territory = selectedTerritoryType ?: return null
        val rows = (currentEncounter.sections[PersonalDataUltimateSection.Deaths]?.data as? PersonalDataUltimateData.Deaths)?.rows ?: return null
        return PersonalDataUltimateViews.deathPlot(rows, territory)
    }
    val visibleDeaths: List<UltimatePlotPoint> get() = deathPlot?.points.orEmpty().take(currentEncounter.deathLimit)
    val hasMoreDeaths: Boolean get() = deathPlot?.points.orEmpty().size > currentEncounter.deathLimit
}

class PersonalDataUltimateViewModel(
    private val service: PersonalDataService,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonalDataUltimateUiState(zone = zone))
    val state: StateFlow<PersonalDataUltimateUiState> = mutableState.asStateFlow()
    val supportsUltimate: Boolean get() = service is PersonalDataUltimateService
    val hasCommunityIdentity: Boolean get() = !isCleared && service.hasCommunityIdentity
    private var overviewJob: Job? = null
    private var overviewGeneration = 0L
    private var detailGeneration = 0L
    private var generation = 0L
    private val jobs = mutableMapOf<Pair<Int, PersonalDataUltimateSection>, Job>()
    private val sectionGenerations = mutableMapOf<Pair<Int, PersonalDataUltimateSection>, Long>()
    private var isCleared = false

    fun open() {
        if (!requireIdentity()) return
        mutableState.update { it.copy(isOpen = true) }
        if (!supportsUltimate) {
            mutableState.update { it.copy(overviewStatus = PersonalDataUltimateLoadStatus.Unavailable, overviewFailure = PersonalDataUltimateFailure.Unavailable) }
            return
        }
        val epoch = generation
        if (state.value.overviewStatus == PersonalDataUltimateLoadStatus.Idle) loadOverview()
        if (epoch == generation && state.value.overviewStatus != PersonalDataUltimateLoadStatus.AuthRequired) loadMissingSections()
    }
    fun close() {
        if (!requireIdentity()) return
        cancelRequests()
        mutableState.update { it.copy(isOpen = false) }
    }
    fun refresh() {
        if (!canRead()) return
        val epoch = generation
        loadOverview()
        if (epoch == generation) {
            val territory = state.value.selectedRecord?.territoryType ?: return
            val detailEpoch = detailGeneration
            for (section in ultimateSections(territory)) {
                if (epoch != generation || detailEpoch != detailGeneration || !canRead()) return
                loadSection(territory, section)
            }
        }
    }
    fun retryOverview() {
        if (canRead() && state.value.overviewStatus != PersonalDataUltimateLoadStatus.Loading) loadOverview()
    }
    fun selectEncounter(territoryType: Int) {
        if (!canRead() || state.value.records?.none { it.territoryType == territoryType } != false) return
        if (state.value.selectedTerritoryType != territoryType) {
            cancelDetailRequests()
            mutableState.update { it.copy(selectedTerritoryType = territoryType) }
        }
        loadMissingSections()
    }
    fun clearEncounterSelection() {
        if (!requireIdentity()) return
        cancelDetailRequests()
        mutableState.update { it.copy(selectedTerritoryType = null) }
    }
    fun selectSection(section: PersonalDataUltimateSection) {
        if (!canRead() || section !in state.value.availableSections) return
        state.value.selectedTerritoryType?.let { territory -> updateEncounter(territory) { it.copy(selectedSection = section) } }
    }
    fun retrySection(section: PersonalDataUltimateSection) {
        if (!canRead() || section !in state.value.availableSections) return
        val territory = state.value.selectedRecord?.territoryType ?: return
        if (state.value.currentEncounter.sections[section]?.status != PersonalDataUltimateLoadStatus.Loading) loadSection(territory, section)
    }
    fun showMorePartners() {
        if (!canRead() || !state.value.hasMorePartners) return
        state.value.selectedTerritoryType?.let { territory -> updateEncounter(territory) { it.copy(partnerLimit = grow(it.partnerLimit, 3)) } }
    }
    fun showMoreDeaths() {
        if (!canRead() || !state.value.hasMoreDeaths) return
        state.value.selectedTerritoryType?.let { territory -> updateEncounter(territory) { it.copy(deathLimit = grow(it.deathLimit, 20)) } }
    }

    private fun loadMissingSections() {
        val territory = state.value.selectedRecord?.territoryType ?: return
        val epoch = generation
        val detailEpoch = detailGeneration
        for (section in ultimateSections(territory)) {
            if (epoch != generation || detailEpoch != detailGeneration || !canRead()) return
            if ((state.value.currentEncounter.sections[section]?.status ?: PersonalDataUltimateLoadStatus.Idle) == PersonalDataUltimateLoadStatus.Idle) loadSection(territory, section)
        }
    }

    private fun loadOverview() {
        val reader = service as? PersonalDataUltimateService ?: return
        val requestGeneration = ++overviewGeneration
        val epoch = generation
        overviewJob?.cancel()
        mutableState.update { it.copy(overviewStatus = PersonalDataUltimateLoadStatus.Loading, overviewFailure = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = epoch == generation && requestGeneration == overviewGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val records = reader.fetchUltimateRecords()
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    val ids = records.map { it.territoryType }.toSet()
                    val removedSelection = state.value.selectedTerritoryType?.let { it !in ids } == true
                    if (removedSelection) cancelDetailRequests()
                    mutableState.update { it.copy(records = records, overviewStatus = PersonalDataUltimateLoadStatus.Loaded, overviewFailure = null,
                        selectedTerritoryType = it.selectedTerritoryType?.takeIf(ids::contains), encounters = it.encounters.filterKeys(ids::contains)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else mutableState.update { it.copy(overviewStatus = PersonalDataUltimateLoadStatus.Failed, overviewFailure = PersonalDataUltimateFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    overviewJob = null
                    mutableState.update { it.finishOverviewLoading() }
                }
            }
        }
        overviewJob = job
        job.start()
    }

    private fun loadSection(territory: Int, section: PersonalDataUltimateSection) {
        val reader = service as? PersonalDataUltimateService ?: return
        val key = territory to section
        val requestGeneration = (sectionGenerations[key] ?: 0L) + 1
        sectionGenerations[key] = requestGeneration
        val epoch = generation
        val detailEpoch = detailGeneration
        jobs.remove(key)?.cancel()
        updateSection(territory, section) { it.copy(status = PersonalDataUltimateLoadStatus.Loading, failure = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = epoch == generation && detailEpoch == detailGeneration && sectionGenerations[key] == requestGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val result = reader.fetchUltimateSection(territory, section)
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (result.matches(section)) {
                        val orders = if (result is PersonalDataUltimateData.Party) result.rows.mapNotNull { row ->
                            row.jobName?.let { name -> reader.ultimateJobOrder(name)?.takeIf { it >= 0 }?.let { name to it } }
                        }.toMap() else null
                        currentCoroutineContext().ensureActive()
                        if (isCurrent() && requireIdentity()) {
                            updateSection(territory, section) { it.copy(status = PersonalDataUltimateLoadStatus.Loaded, data = result, failure = null) }
                            if (orders != null) updateEncounter(territory) { it.copy(jobOrders = orders) }
                        }
                    } else updateSection(territory, section) { it.copy(status = PersonalDataUltimateLoadStatus.Failed, failure = PersonalDataUltimateFailure.LoadFailed) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else updateSection(territory, section) { it.copy(status = PersonalDataUltimateLoadStatus.Failed, failure = PersonalDataUltimateFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    jobs.remove(key)
                    updateSection(territory, section) { it.finishLoading() }
                }
            }
        }
        jobs[key] = job
        job.start()
    }

    fun clearProtectedContent() {
        cancelRequests()
        sectionGenerations.clear()
        mutableState.value = PersonalDataUltimateUiState(zone = zone)
    }
    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }
    private fun cancelRequests() {
        generation++
        overviewJob?.cancel(); overviewJob = null
        cancelDetailRequests()
        mutableState.update { it.finishOverviewLoading() }
    }
    private fun cancelDetailRequests() {
        detailGeneration++
        jobs.values.forEach(Job::cancel); jobs.clear()
        mutableState.update { it.copy(encounters = it.encounters.mapValues { entry -> entry.value.copy(
            sections = entry.value.sections.mapValues { section -> section.value.finishLoading() }) }) }
    }
    private fun updateEncounter(territory: Int, change: (PersonalDataUltimateEncounterState) -> PersonalDataUltimateEncounterState) {
        mutableState.update { it.copy(encounters = it.encounters + (territory to change(it.encounters[territory] ?: PersonalDataUltimateEncounterState()))) }
    }
    private fun updateSection(territory: Int, section: PersonalDataUltimateSection, change: (PersonalDataUltimateSectionState) -> PersonalDataUltimateSectionState) {
        updateEncounter(territory) { it.copy(sections = it.sections + (section to change(it.sections[section] ?: PersonalDataUltimateSectionState()))) }
    }
    private fun requireIdentity(): Boolean {
        if (isCleared) return false
        if (service.hasCommunityIdentity) return true
        clearProtectedContent()
        return false
    }
    private fun canRead() = requireIdentity() && supportsUltimate && state.value.isOpen
    private fun authenticationFailed() {
        val open = state.value.isOpen
        clearProtectedContent()
        mutableState.value = PersonalDataUltimateUiState(isOpen = open, zone = zone,
            overviewStatus = PersonalDataUltimateLoadStatus.AuthRequired, overviewFailure = PersonalDataUltimateFailure.AuthenticationRequired)
    }
}

private fun ultimateSections(territory: Int) = PersonalDataUltimateSection.entries.filter { territory != 733 || it != PersonalDataUltimateSection.Phases }
private fun grow(value: Int, increment: Int) = (value.toLong() + increment).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
private fun PersonalDataUltimateUiState.finishOverviewLoading() = if (overviewStatus == PersonalDataUltimateLoadStatus.Loading)
    copy(overviewStatus = if (records == null) PersonalDataUltimateLoadStatus.Idle else PersonalDataUltimateLoadStatus.Loaded) else this
private fun PersonalDataUltimateSectionState.finishLoading() = if (status == PersonalDataUltimateLoadStatus.Loading)
    copy(status = if (data == null) PersonalDataUltimateLoadStatus.Idle else PersonalDataUltimateLoadStatus.Loaded) else this
private fun PersonalDataUltimateData.matches(section: PersonalDataUltimateSection): Boolean = when (this) {
    is PersonalDataUltimateData.Party -> section == PersonalDataUltimateSection.Party
    is PersonalDataUltimateData.Jobs -> section == PersonalDataUltimateSection.Jobs
    is PersonalDataUltimateData.Partners -> section == PersonalDataUltimateSection.Partners
    is PersonalDataUltimateData.Phases -> section == PersonalDataUltimateSection.Phases
    is PersonalDataUltimateData.Deaths -> section == PersonalDataUltimateSection.Deaths
}

class PersonalDataUltimateViewModelFactory(
    private val service: PersonalDataService,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PersonalDataUltimateViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return PersonalDataUltimateViewModel(service, zone) as T
    }
}
