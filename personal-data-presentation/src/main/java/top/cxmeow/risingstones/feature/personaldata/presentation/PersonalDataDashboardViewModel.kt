package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

enum class PersonalDataDashboardLoadStatus { Idle, Loading, Loaded, Failed, AuthRequired, Unavailable }
enum class PersonalDataDashboardFailure { AuthenticationRequired, Unavailable, LoadFailed }
enum class PersonalDataDashboardFishGroup { Kings, Ocean }
enum class PersonalDataDashboardFishSort { Newest, CountDescending }

data class PersonalDataDashboardCriteria(
    val query: String = "",
    val category: String? = null,
    val patch: String? = null,
    val fishGroup: PersonalDataDashboardFishGroup = PersonalDataDashboardFishGroup.Kings,
    val fishSort: PersonalDataDashboardFishSort = PersonalDataDashboardFishSort.Newest,
    val vanityPeriod: PersonalDataVanityPeriod = PersonalDataVanityPeriod.AllTime,
    val vanityMajor: Int = 1,
    val vanityCategoryId: Int? = null,
    val includeUnobtained: Boolean = false,
    val visibleLimit: Int = 10,
)

data class PersonalDataDashboardSectionState(
    val status: PersonalDataDashboardLoadStatus = PersonalDataDashboardLoadStatus.Idle,
    val data: PersonalDataDashboardData? = null,
    val failure: PersonalDataDashboardFailure? = null,
    val criteria: PersonalDataDashboardCriteria = PersonalDataDashboardCriteria(),
)

data class PersonalDataDashboardUiState(
    val activeBoard: PersonalDataBoard? = null,
    val selectedSection: PersonalDataDashboardSectionKind? = null,
    val sections: Map<PersonalDataDashboardSectionKind, PersonalDataDashboardSectionState> = emptyMap(),
    val catalogs: PersonalDataOfficialCatalogs? = null,
    val catalogStatus: PersonalDataDashboardLoadStatus = PersonalDataDashboardLoadStatus.Idle,
    val catalogFailure: PersonalDataDashboardFailure? = null,
    val supplementary: PersonalDataSupplementaryCatalogs? = null,
    val supplementaryStatus: PersonalDataDashboardLoadStatus = PersonalDataDashboardLoadStatus.Idle,
    val supplementaryFailure: PersonalDataDashboardFailure? = null,
) {
    val currentSection: PersonalDataDashboardSectionState get() = sections[selectedSection] ?: PersonalDataDashboardSectionState()
    val currentCriteria: PersonalDataDashboardCriteria get() = currentSection.criteria
}

/** Explicitly opened boards share cached sections, while every read has its own cancellation boundary. */
class PersonalDataDashboardViewModel(private val service: PersonalDataService) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonalDataDashboardUiState())
    val state: StateFlow<PersonalDataDashboardUiState> = mutableState.asStateFlow()
    val supportsDashboards: Boolean get() = service is PersonalDataDashboardService
    val hasCommunityIdentity: Boolean get() = !isCleared && service.hasCommunityIdentity

    private val sectionJobs = mutableMapOf<PersonalDataDashboardSectionKind, Job>()
    private val sectionGenerations = mutableMapOf<PersonalDataDashboardSectionKind, Long>()
    private val selectedSections = mutableMapOf<PersonalDataBoard, PersonalDataDashboardSectionKind>()
    private var catalogJob: Job? = null
    private var supplementaryJob: Job? = null
    private var catalogGeneration = 0L
    private var supplementaryGeneration = 0L
    private var generation = 0L
    private var isCleared = false

    fun open(board: PersonalDataBoard) {
        if (!requireIdentity()) return
        val kinds = PersonalDataDashboardSectionKind.forBoard(board)
        if (kinds.isEmpty()) return
        if (state.value.activeBoard != board) {
            cancelRequests()
            mutableState.update { it.copy(activeBoard = board, selectedSection = selectedSections[board] ?: kinds.first()) }
        }
        if (!supportsDashboards) {
            kinds.forEach { kind -> updateSection(kind) { it.copy(status = PersonalDataDashboardLoadStatus.Unavailable, failure = PersonalDataDashboardFailure.Unavailable) } }
            return
        }
        val currentGeneration = generation
        for (kind in kinds) {
            if (generation != currentGeneration || !requireIdentity()) return
            if ((state.value.sections[kind]?.status ?: PersonalDataDashboardLoadStatus.Idle) == PersonalDataDashboardLoadStatus.Idle) loadSection(kind)
        }
        if (generation != currentGeneration) return
        if (state.value.catalogStatus == PersonalDataDashboardLoadStatus.Idle) loadCatalogs()
        if (generation != currentGeneration) return
        if (state.value.supplementaryStatus == PersonalDataDashboardLoadStatus.Idle) loadSupplementary()
    }

    fun close() {
        if (!requireIdentity()) return
        cancelRequests()
        mutableState.update { it.copy(activeBoard = null, selectedSection = null) }
    }

    fun selectSection(section: PersonalDataDashboardSectionKind) {
        if (!requireIdentity() || section.board != state.value.activeBoard) return
        selectedSections[section.board] = section
        mutableState.update { it.copy(selectedSection = section) }
    }

    /** Refreshes only the active board's data; catalog retries are independent. */
    fun refresh() {
        if (!requireIdentity() || !supportsDashboards) return
        val board = state.value.activeBoard ?: return
        val currentGeneration = generation
        for (kind in PersonalDataDashboardSectionKind.forBoard(board)) {
            if (generation != currentGeneration || !requireIdentity()) return
            loadSection(kind)
        }
    }

    fun retrySection(section: PersonalDataDashboardSectionKind) {
        if (!requireIdentity() || !supportsDashboards || section.board != state.value.activeBoard) return
        if (state.value.sections[section]?.status != PersonalDataDashboardLoadStatus.Loading) loadSection(section)
    }

    fun retryCatalogs() {
        if (!requireIdentity() || !supportsDashboards || state.value.activeBoard == null) return
        val currentGeneration = generation
        if (state.value.catalogStatus != PersonalDataDashboardLoadStatus.Loading) loadCatalogs()
        if (generation == currentGeneration && state.value.supplementaryStatus != PersonalDataDashboardLoadStatus.Loading) loadSupplementary()
    }

    fun updateQuery(query: String) = updateCriteria { it.copy(query = query, visibleLimit = 10) }
    fun selectCategory(category: String?) = updateCriteria { it.copy(category = category, visibleLimit = 10) }
    fun selectPatch(patch: String?) = updateCriteria { it.copy(patch = patch, visibleLimit = 10) }
    fun selectFishGroup(group: PersonalDataDashboardFishGroup) = updateCriteria { it.copy(fishGroup = group, visibleLimit = 10) }
    fun setFishSort(sort: PersonalDataDashboardFishSort) = updateCriteria { it.copy(fishSort = sort, visibleLimit = 10) }
    fun setVanityPeriod(period: PersonalDataVanityPeriod) = updateCriteria {
        it.copy(vanityPeriod = period, vanityCategoryId = if (it.vanityPeriod == period) it.vanityCategoryId else null, visibleLimit = 10)
    }
    fun selectVanityMajor(major: Int) {
        if (!requireIdentity() || major !in setOf(1, 3, 4)) return
        updateCriteria { it.copy(vanityMajor = major, vanityCategoryId = if (it.vanityMajor == major) it.vanityCategoryId else null, visibleLimit = 10) }
    }
    fun selectVanityCategory(categoryId: Int?) = updateCriteria { it.copy(vanityCategoryId = categoryId, visibleLimit = 10) }
    fun setIncludeUnobtained(include: Boolean) = updateCriteria { it.copy(includeUnobtained = include, visibleLimit = 10) }
    fun showMore() = updateCriteria { it.copy(visibleLimit = (it.visibleLimit.toLong() + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }

    private fun updateCriteria(change: (PersonalDataDashboardCriteria) -> PersonalDataDashboardCriteria) {
        if (!requireIdentity()) return
        val section = state.value.selectedSection ?: return
        updateSection(section) { it.copy(criteria = change(it.criteria)) }
    }

    private fun loadSection(section: PersonalDataDashboardSectionKind) {
        val reader = service as? PersonalDataDashboardService ?: return
        val requestGeneration = (sectionGenerations[section] ?: 0L) + 1
        sectionGenerations[section] = requestGeneration
        val currentGeneration = generation
        sectionJobs.remove(section)?.cancel()
        updateSection(section) { it.copy(status = PersonalDataDashboardLoadStatus.Loading, failure = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = generation == currentGeneration && sectionGenerations[section] == requestGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val result = reader.fetchDashboardSection(section)
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (result.matches(section)) updateSection(section) { it.copy(status = PersonalDataDashboardLoadStatus.Loaded, data = result, failure = null) }
                    else updateSection(section) { it.copy(status = PersonalDataDashboardLoadStatus.Failed, failure = PersonalDataDashboardFailure.LoadFailed) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else updateSection(section) { it.copy(status = PersonalDataDashboardLoadStatus.Failed, failure = PersonalDataDashboardFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    sectionJobs.remove(section)
                    updateSection(section) { it.finishLoading() }
                }
            }
        }
        sectionJobs[section] = job
        job.start()
    }

    private fun loadCatalogs() {
        val requestGeneration = ++catalogGeneration
        val currentGeneration = generation
        catalogJob?.cancel()
        mutableState.update { it.copy(catalogStatus = PersonalDataDashboardLoadStatus.Loading, catalogFailure = null) }
        catalogJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = generation == currentGeneration && catalogGeneration == requestGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val result = service.fetchOfficialCatalogs()
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    mutableState.update { if (result.hasEntries()) it.copy(catalogs = result, catalogStatus = PersonalDataDashboardLoadStatus.Loaded, catalogFailure = null)
                    else it.copy(catalogStatus = PersonalDataDashboardLoadStatus.Unavailable, catalogFailure = PersonalDataDashboardFailure.Unavailable) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else mutableState.update { it.copy(catalogStatus = PersonalDataDashboardLoadStatus.Failed, catalogFailure = PersonalDataDashboardFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    catalogJob = null
                    mutableState.update { it.finishCatalogLoading() }
                }
            }
        }.also { it.start() }
    }

    private fun loadSupplementary() {
        val reader = service as? PersonalDataDashboardService ?: return
        val requestGeneration = ++supplementaryGeneration
        val currentGeneration = generation
        supplementaryJob?.cancel()
        mutableState.update { it.copy(supplementaryStatus = PersonalDataDashboardLoadStatus.Loading, supplementaryFailure = null) }
        supplementaryJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = generation == currentGeneration && supplementaryGeneration == requestGeneration
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || !requireIdentity()) return@launch
                val result = reader.fetchSupplementaryCatalogs()
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    mutableState.update { if (result.hasEntries()) it.copy(supplementary = result, supplementaryStatus = PersonalDataDashboardLoadStatus.Loaded, supplementaryFailure = null)
                    else it.copy(supplementaryStatus = PersonalDataDashboardLoadStatus.Unavailable, supplementaryFailure = PersonalDataDashboardFailure.Unavailable) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else mutableState.update { it.copy(supplementaryStatus = PersonalDataDashboardLoadStatus.Failed, supplementaryFailure = PersonalDataDashboardFailure.LoadFailed) }
                }
            } finally {
                if (isCurrent() && requireIdentity()) {
                    supplementaryJob = null
                    mutableState.update { it.finishSupplementaryLoading() }
                }
            }
        }.also { it.start() }
    }

    fun clearProtectedContent() {
        cancelRequests()
        selectedSections.clear()
        sectionGenerations.clear()
        mutableState.value = PersonalDataDashboardUiState()
    }

    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }

    private fun cancelRequests() {
        generation++
        sectionJobs.values.forEach(Job::cancel)
        sectionJobs.clear()
        catalogJob?.cancel(); catalogJob = null
        supplementaryJob?.cancel(); supplementaryJob = null
        mutableState.update { it.copy(sections = it.sections.mapValues { entry -> entry.value.finishLoading() }).finishCatalogLoading().finishSupplementaryLoading() }
    }

    private fun updateSection(section: PersonalDataDashboardSectionKind, change: (PersonalDataDashboardSectionState) -> PersonalDataDashboardSectionState) {
        mutableState.update { it.copy(sections = it.sections + (section to change(it.sections[section] ?: PersonalDataDashboardSectionState()))) }
    }

    private fun requireIdentity(): Boolean {
        if (isCleared) return false
        if (service.hasCommunityIdentity) return true
        clearProtectedContent()
        return false
    }

    private fun authenticationFailed() {
        val board = state.value.activeBoard
        val section = state.value.selectedSection
        clearProtectedContent()
        mutableState.value = PersonalDataDashboardUiState(
            activeBoard = board, selectedSection = section,
            sections = board?.let { PersonalDataDashboardSectionKind.forBoard(it).associateWith {
                PersonalDataDashboardSectionState(status = PersonalDataDashboardLoadStatus.AuthRequired, failure = PersonalDataDashboardFailure.AuthenticationRequired)
            } }.orEmpty(),
            catalogStatus = PersonalDataDashboardLoadStatus.AuthRequired, catalogFailure = PersonalDataDashboardFailure.AuthenticationRequired,
            supplementaryStatus = PersonalDataDashboardLoadStatus.AuthRequired, supplementaryFailure = PersonalDataDashboardFailure.AuthenticationRequired,
        )
    }
}

private fun PersonalDataDashboardSectionState.finishLoading() = if (status == PersonalDataDashboardLoadStatus.Loading)
    copy(status = if (data == null) PersonalDataDashboardLoadStatus.Idle else PersonalDataDashboardLoadStatus.Loaded) else this
private fun PersonalDataDashboardUiState.finishCatalogLoading() = if (catalogStatus == PersonalDataDashboardLoadStatus.Loading)
    copy(catalogStatus = if (catalogs == null) PersonalDataDashboardLoadStatus.Idle else PersonalDataDashboardLoadStatus.Loaded) else this
private fun PersonalDataDashboardUiState.finishSupplementaryLoading() = if (supplementaryStatus == PersonalDataDashboardLoadStatus.Loading)
    copy(supplementaryStatus = if (supplementary == null) PersonalDataDashboardLoadStatus.Idle else PersonalDataDashboardLoadStatus.Loaded) else this
private fun PersonalDataOfficialCatalogs.hasEntries() = fish.isNotEmpty() || savageRaids.isNotEmpty() || savageSeries.isNotEmpty() ||
    glamour?.let { it.sets.isNotEmpty() || it.fashionAccessories.isNotEmpty() || it.stains.isNotEmpty() } == true
private fun PersonalDataSupplementaryCatalogs.hasEntries() = oceanFish.isNotEmpty() || fishingAchievements.isNotEmpty() ||
    frontlineAchievements.isNotEmpty() || vanityCategories.isNotEmpty()

private fun PersonalDataDashboardData.matches(section: PersonalDataDashboardSectionKind): Boolean = when (this) {
    is PersonalDataDashboardData.FishingSummary -> section == PersonalDataDashboardSectionKind.FishingSummary
    is PersonalDataDashboardData.FishRanking -> section == PersonalDataDashboardSectionKind.FishRanking
    is PersonalDataDashboardData.BaitRanking -> section == PersonalDataDashboardSectionKind.BaitRanking
    is PersonalDataDashboardData.BigFish -> section == PersonalDataDashboardSectionKind.BigFish
    is PersonalDataDashboardData.FishingAchievements -> section == PersonalDataDashboardSectionKind.FishingAchievements
    is PersonalDataDashboardData.OceanFishing -> section == PersonalDataDashboardSectionKind.OceanFishing
    is PersonalDataDashboardData.GlamourSummary -> section == PersonalDataDashboardSectionKind.GlamourSummary
    is PersonalDataDashboardData.Races -> section == PersonalDataDashboardSectionKind.Races
    is PersonalDataDashboardData.Stains -> section == PersonalDataDashboardSectionKind.Stains
    is PersonalDataDashboardData.Accessories -> section == PersonalDataDashboardSectionKind.Accessories
    is PersonalDataDashboardData.Vanity -> section == PersonalDataDashboardSectionKind.Vanity
    is PersonalDataDashboardData.Sets -> section == PersonalDataDashboardSectionKind.Sets
    is PersonalDataDashboardData.SavageSummary -> section == PersonalDataDashboardSectionKind.SavageSummary
    is PersonalDataDashboardData.SavageRaids -> section == PersonalDataDashboardSectionKind.SavageRaids
}

class PersonalDataDashboardViewModelFactory(private val service: PersonalDataService) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PersonalDataDashboardViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return PersonalDataDashboardViewModel(service) as T
    }
}
