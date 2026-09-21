package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDashboard
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterDetail
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterSummary

data class PersonalDataUiState(
    val selectedBoard: PersonalDataBoard? = null,
    val identity: PersonalDataIdentity? = null,
    val availability: PersonalDataAvailability? = null,
    val content: PersonalDataBoardContent? = null,
    val dashboard: UltimateDashboard? = null,
    val selectedEncounter: UltimateEncounterSummary? = null,
    val encounterDetail: UltimateEncounterDetail? = null,
    val catalogs: PersonalDataOfficialCatalogs = PersonalDataOfficialCatalogs(),
    val isLoadingRoot: Boolean = false,
    val isLoadingBoard: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val isLoadingCatalogs: Boolean = false,
    val rootError: String? = null,
    val boardError: String? = null,
    val detailError: String? = null,
    val catalogsError: String? = null,
)

class PersonalDataViewModel(private val service: PersonalDataService) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonalDataUiState())
    val state: StateFlow<PersonalDataUiState> = mutableState.asStateFlow()
    val hasCommunityIdentity: Boolean get() = !isCleared && service.hasCommunityIdentity
    private val boardCache = mutableMapOf<PersonalDataBoard, PersonalDataBoardContent>()
    private val detailCache = mutableMapOf<Int, UltimateEncounterDetail>()
    private var dashboardCache: UltimateDashboard? = null
    private var catalogsResolved = false
    private var rootJob: Job? = null
    private var boardJob: Job? = null
    private var detailJob: Job? = null
    private var catalogsJob: Job? = null
    private var rootGeneration = 0L
    private var boardGeneration = 0L
    private var detailGeneration = 0L
    private var catalogsGeneration = 0L
    private var isCleared = false

    init {
        if (hasCommunityIdentity) {
            loadRoot()
            loadCatalogs()
        }
    }

    fun loadRoot(force: Boolean = false) {
        if (!requireIdentity()) return
        val current = mutableState.value
        if (!force && (current.identity != null && current.availability != null || current.isLoadingRoot)) return
        val generation = ++rootGeneration
        rootJob?.cancel()
        mutableState.update { it.copy(isLoadingRoot = true, rootError = null) }
        rootJob = viewModelScope.launch {
            try {
                val (identity, availability) = coroutineScope {
                    val identity = async { service.fetchIdentity() }
                    val availability = async { service.fetchAvailability() }
                    identity.await() to availability.await()
                }
                currentCoroutineContext().ensureActive()
                if (generation == rootGeneration && requireIdentity()) {
                    mutableState.update { it.copy(identity = identity, availability = availability) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == rootGeneration) handleFailure(error) { it.copy(rootError = LoadFailed) }
            } finally {
                if (generation == rootGeneration && requireIdentity()) {
                    rootJob = null
                    mutableState.update { it.copy(isLoadingRoot = false) }
                }
            }
        }
    }

    fun selectBoard(board: PersonalDataBoard) = selectBoard(board, loadContent = true)

    /** Allows an optional structured board owner to manage content without issuing legacy reads. */
    fun selectBoard(board: PersonalDataBoard, loadContent: Boolean) {
        if (!requireIdentity()) return
        if (mutableState.value.selectedBoard == board && loadContent) {
            mutableState.update { it.copy(
                content = it.content ?: boardCache[board],
                dashboard = if (board == PersonalDataBoard.Ultimate) it.dashboard ?: dashboardCache else null,
            ) }
            loadBoard()
            return
        }
        cancelBoardAndDetail()
        mutableState.update {
            it.copy(selectedBoard = board, content = if (loadContent) boardCache[board] else null,
                dashboard = if (loadContent && board == PersonalDataBoard.Ultimate) dashboardCache else null,
                selectedEncounter = null, encounterDetail = null, isLoadingBoard = false,
                isLoadingDetail = false, boardError = null, detailError = null)
        }
        if (loadContent) loadBoard()
    }

    fun clearBoardSelection() {
        if (!requireIdentity()) return
        cancelBoardAndDetail()
        mutableState.update {
            it.copy(selectedBoard = null, content = null, dashboard = null, selectedEncounter = null,
                encounterDetail = null, isLoadingBoard = false, isLoadingDetail = false,
                boardError = null, detailError = null)
        }
    }

    fun loadBoard(force: Boolean = false) {
        if (!requireIdentity()) return
        val current = mutableState.value
        val board = current.selectedBoard ?: return
        if (!force && (current.isLoadingBoard || if (board == PersonalDataBoard.Ultimate) dashboardCache != null
                else boardCache[board] != null)) return
        val generation = ++boardGeneration
        boardJob?.cancel()
        mutableState.update { it.copy(isLoadingBoard = true, boardError = null) }
        boardJob = viewModelScope.launch {
            try {
                if (board == PersonalDataBoard.Ultimate) {
                    val dashboard = service.fetchUltimateDashboard()
                    currentCoroutineContext().ensureActive()
                    if (generation == boardGeneration && requireIdentity()) {
                        dashboardCache = dashboard
                        mutableState.update { it.copy(dashboard = dashboard, content = null) }
                    }
                } else {
                    val response = service.fetchBoardContent(board)
                    currentCoroutineContext().ensureActive()
                    if (generation == boardGeneration && requireIdentity()) {
                        if (response.board != board) throw PersonalDataException.MissingPayload
                        val previous = boardCache[board]
                        val content = response.copy(sections = response.sections.map { section ->
                            if (section.error == null) section else section.copy(error = LoadFailed,
                                entries = previous?.sections?.firstOrNull { it.id == section.id }?.entries ?: section.entries)
                        })
                        boardCache[board] = content
                        mutableState.update { it.copy(content = content, dashboard = null) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == boardGeneration) handleFailure(error) { it.copy(boardError = LoadFailed) }
            } finally {
                if (generation == boardGeneration && requireIdentity()) {
                    boardJob = null
                    mutableState.update { it.copy(isLoadingBoard = false) }
                }
            }
        }
    }

    fun selectEncounter(summary: UltimateEncounterSummary) {
        if (!requireIdentity()) return
        val current = mutableState.value
        if (current.selectedBoard != PersonalDataBoard.Ultimate) return
        if (current.selectedEncounter?.territoryType == summary.territoryType &&
            (current.encounterDetail != null || current.isLoadingDetail)) return
        cancelDetail()
        mutableState.update {
            it.copy(selectedEncounter = summary, encounterDetail = detailCache[summary.territoryType],
                isLoadingDetail = false, detailError = null)
        }
        loadEncounterDetail(summary)
    }

    fun clearEncounterSelection() {
        if (!requireIdentity()) return
        cancelDetail()
        mutableState.update {
            it.copy(selectedEncounter = null, encounterDetail = null, isLoadingDetail = false, detailError = null)
        }
    }

    fun loadEncounterDetail(
        summary: UltimateEncounterSummary? = mutableState.value.selectedEncounter,
        force: Boolean = false,
    ) {
        if (!requireIdentity()) return
        val target = summary ?: return
        val current = mutableState.value
        if (current.selectedBoard != PersonalDataBoard.Ultimate || current.selectedEncounter?.territoryType != target.territoryType) return
        if (!force && (current.isLoadingDetail || detailCache[target.territoryType] != null)) return
        val generation = ++detailGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(isLoadingDetail = true, detailError = null) }
        detailJob = viewModelScope.launch {
            try {
                val response = service.fetchUltimateEncounterDetail(target)
                currentCoroutineContext().ensureActive()
                if (generation == detailGeneration && requireIdentity()) {
                    if (response.summary.territoryType != target.territoryType) throw PersonalDataException.MissingPayload
                    val previous = detailCache[target.territoryType]
                    val detail = response.copy(
                        teammates = if ("team" in response.sectionErrors) previous?.teammates ?: response.teammates else response.teammates,
                        jobs = if ("jobs" in response.sectionErrors) previous?.jobs ?: response.jobs else response.jobs,
                        partners = if ("partners" in response.sectionErrors) previous?.partners ?: response.partners else response.partners,
                        phases = if ("phases" in response.sectionErrors) previous?.phases ?: response.phases else response.phases,
                        deathPoints = if ("deaths" in response.sectionErrors) previous?.deathPoints ?: response.deathPoints else response.deathPoints,
                        sectionErrors = response.sectionErrors.mapValues { LoadFailed },
                    )
                    detailCache[target.territoryType] = detail
                    mutableState.update { it.copy(encounterDetail = detail) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == detailGeneration) handleFailure(error) { it.copy(detailError = LoadFailed) }
            } finally {
                if (generation == detailGeneration && requireIdentity()) {
                    detailJob = null
                    mutableState.update { it.copy(isLoadingDetail = false) }
                }
            }
        }
    }

    fun loadCatalogs(force: Boolean = false) {
        if (!requireIdentity()) return
        if (!force && (catalogsResolved || mutableState.value.isLoadingCatalogs)) return
        val generation = ++catalogsGeneration
        catalogsJob?.cancel()
        mutableState.update { it.copy(isLoadingCatalogs = true, catalogsError = null) }
        catalogsJob = viewModelScope.launch {
            try {
                val catalogs = service.fetchOfficialCatalogs()
                currentCoroutineContext().ensureActive()
                if (generation == catalogsGeneration && requireIdentity()) {
                    catalogsResolved = true
                    mutableState.update { it.copy(catalogs = catalogs, catalogsError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == catalogsGeneration) {
                    catalogsResolved = false
                    handleFailure(error) { it.copy(catalogsError = LoadFailed) }
                }
            } finally {
                if (generation == catalogsGeneration && requireIdentity()) {
                    catalogsJob = null
                    mutableState.update { it.copy(isLoadingCatalogs = false) }
                }
            }
        }
    }

    fun refresh() {
        if (!requireIdentity()) return
        loadRoot(force = true)
        loadBoard(force = true)
        loadEncounterDetail(force = true)
        if (!catalogsResolved) loadCatalogs()
    }

    fun clearProtectedContent() {
        ++rootGeneration
        ++boardGeneration
        ++detailGeneration
        ++catalogsGeneration
        rootJob?.cancel(); rootJob = null
        boardJob?.cancel(); boardJob = null
        detailJob?.cancel(); detailJob = null
        catalogsJob?.cancel(); catalogsJob = null
        boardCache.clear()
        detailCache.clear()
        dashboardCache = null
        catalogsResolved = false
        mutableState.value = PersonalDataUiState()
    }

    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }

    private fun cancelDetail() {
        ++detailGeneration
        detailJob?.cancel()
        detailJob = null
    }

    private fun cancelBoardAndDetail() {
        ++boardGeneration
        boardJob?.cancel()
        boardJob = null
        cancelDetail()
    }

    private fun requireIdentity(): Boolean {
        if (isCleared) return false
        if (service.hasCommunityIdentity) return true
        clearProtectedContent()
        mutableState.update { it.copy(rootError = AuthenticationRequired) }
        return false
    }

    private fun handleFailure(error: Exception, update: (PersonalDataUiState) -> PersonalDataUiState) {
        if (isCleared) return
        if (error is PersonalDataException.AuthenticationRequired || !service.hasCommunityIdentity) {
            clearProtectedContent()
            mutableState.update { it.copy(rootError = AuthenticationRequired) }
        } else mutableState.update(update)
    }

    private companion object {
        const val AuthenticationRequired = "authentication_required"
        const val LoadFailed = "load_failed"
    }
}

class PersonalDataViewModelFactory(private val service: PersonalDataService) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PersonalDataViewModel(service) as T
}
