package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
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
    val hasCommunityIdentity: Boolean get() = service.hasCommunityIdentity
    private val boardCache = mutableMapOf<PersonalDataBoard, PersonalDataBoardContent>()
    private val detailCache = mutableMapOf<Int, UltimateEncounterDetail>()
    private var dashboardCache: UltimateDashboard? = null
    private var catalogsResolved = false
    private var rootJob: Job? = null
    private var boardJob: Job? = null
    private var detailJob: Job? = null

    init {
        if (hasCommunityIdentity) {
            loadRoot()
            loadCatalogs()
        }
    }

    fun loadRoot(force: Boolean = false) {
        val current = mutableState.value
        if (!force && (current.identity != null || current.isLoadingRoot)) return
        rootJob?.cancel()
        rootJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoadingRoot = true, rootError = null) }
            try {
                val identity = async { service.fetchIdentity() }
                val availability = async { service.fetchAvailability() }
                mutableState.update {
                    it.copy(
                        identity = identity.await(),
                        availability = availability.await(),
                        isLoadingRoot = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(isLoadingRoot = false, rootError = error.message ?: error.toString()) }
            }
        }
    }

    fun selectBoard(board: PersonalDataBoard) {
        if (mutableState.value.selectedBoard == board) return
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedBoard = board,
                content = boardCache[board],
                dashboard = if (board == PersonalDataBoard.Ultimate) dashboardCache else null,
                selectedEncounter = null,
                encounterDetail = null,
                isLoadingBoard = false,
                isLoadingDetail = false,
                boardError = null,
                detailError = null,
            )
        }
        loadBoard()
    }

    fun clearBoardSelection() {
        boardJob?.cancel()
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedBoard = null,
                content = null,
                dashboard = null,
                selectedEncounter = null,
                encounterDetail = null,
                isLoadingBoard = false,
                isLoadingDetail = false,
                boardError = null,
                detailError = null,
            )
        }
    }

    fun loadBoard(force: Boolean = false) {
        val board = mutableState.value.selectedBoard ?: return
        if (!force) {
            if (board == PersonalDataBoard.Ultimate && dashboardCache != null) return
            if (board != PersonalDataBoard.Ultimate && boardCache[board] != null) return
        }
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoadingBoard = true, boardError = null) }
            try {
                if (board == PersonalDataBoard.Ultimate) {
                    val dashboard = service.fetchUltimateDashboard()
                    dashboardCache = dashboard
                    mutableState.update {
                        if (it.selectedBoard == board) it.copy(dashboard = dashboard, content = null, isLoadingBoard = false)
                        else it
                    }
                } else {
                    val content = service.fetchBoardContent(board)
                    boardCache[board] = content
                    mutableState.update {
                        if (it.selectedBoard == board) it.copy(content = content, dashboard = null, isLoadingBoard = false)
                        else it
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.selectedBoard == board) it.copy(isLoadingBoard = false, boardError = error.message ?: error.toString())
                    else it
                }
            }
        }
    }

    fun selectEncounter(summary: UltimateEncounterSummary) {
        if (
            mutableState.value.selectedEncounter?.territoryType == summary.territoryType &&
            (mutableState.value.encounterDetail != null || mutableState.value.isLoadingDetail)
        ) return
        mutableState.update {
            it.copy(
                selectedEncounter = summary,
                encounterDetail = detailCache[summary.territoryType],
                detailError = null,
            )
        }
        loadEncounterDetail(summary)
    }

    fun clearEncounterSelection() {
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedEncounter = null,
                encounterDetail = null,
                isLoadingDetail = false,
                detailError = null,
            )
        }
    }

    fun loadEncounterDetail(
        summary: UltimateEncounterSummary? = mutableState.value.selectedEncounter,
        force: Boolean = false,
    ) {
        val target = summary ?: return
        if (!force && detailCache[target.territoryType] != null) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoadingDetail = true, detailError = null) }
            try {
                val detail = service.fetchUltimateEncounterDetail(target)
                detailCache[target.territoryType] = detail
                mutableState.update {
                    if (it.selectedEncounter?.territoryType == target.territoryType) {
                        it.copy(encounterDetail = detail, isLoadingDetail = false)
                    } else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.selectedEncounter?.territoryType == target.territoryType) {
                        it.copy(isLoadingDetail = false, detailError = error.message ?: error.toString())
                    } else it
                }
            }
        }
    }

    fun loadCatalogs(force: Boolean = false) {
        if (!force && (catalogsResolved || mutableState.value.isLoadingCatalogs)) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingCatalogs = true, catalogsError = null) }
            try {
                val catalogs = service.fetchOfficialCatalogs()
                catalogsResolved = true
                mutableState.update {
                    it.copy(catalogs = catalogs, isLoadingCatalogs = false, catalogsError = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                catalogsResolved = true
                mutableState.update {
                    it.copy(
                        isLoadingCatalogs = false,
                        catalogsError = error.message ?: error.toString(),
                    )
                }
            }
        }
    }

    fun refresh() {
        loadRoot(force = true)
        loadBoard(force = true)
        loadEncounterDetail(force = true)
    }
}

class PersonalDataViewModelFactory(private val service: PersonalDataService) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PersonalDataViewModel(service) as T
}
