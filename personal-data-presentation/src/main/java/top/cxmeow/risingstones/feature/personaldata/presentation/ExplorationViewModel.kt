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
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.personaldata.domain.*

enum class ExplorationError { Network, InvalidResponse, Business, AuthenticationRequired, Unavailable }

data class ExplorationUiState(
    val board: ExplorationBoard,
    val selectedSection: ExplorationSectionKind = board.sections().first(),
    val overview: ExplorationOverview? = null,
    val histories: Map<ExplorationSectionKind, ExplorationSection> = emptyMap(),
    val isLoadingOverview: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val error: ExplorationError? = null,
) {
    val section: ExplorationSection?
        get() = if (selectedSection.isHistory) histories[selectedSection]
        else overview?.sections?.firstOrNull { it.kind == selectedSection }
}

class ExplorationViewModel(
    private val service: PersonalDataExplorationService,
    private val board: ExplorationBoard,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExplorationUiState(board))
    val state: StateFlow<ExplorationUiState> = mutableState.asStateFlow()
    private val mutablePhantomWeapons = MutableStateFlow(PhantomWeaponUiState())
    val phantomWeapons: StateFlow<PhantomWeaponUiState> = mutablePhantomWeapons.asStateFlow()
    val supportsPhantomWeapons: Boolean get() = board == ExplorationBoard.OccultCrescent && service is PersonalDataPhantomWeaponService
    val hasCommunityIdentity: Boolean get() = !isCleared && service.hasCommunityIdentity
    private var overviewJob: Job? = null
    private var historyJob: Job? = null
    private var catalogJob: Job? = null
    private var overviewGeneration = 0L
    private var historyGeneration = 0L
    private var catalogGeneration = 0L
    private var contentGeneration = 0L
    private var isCleared = false
    private var hasSelectedPhantomStage = false
    private var phantomParentSection: ExplorationSectionKind? = null

    init {
        loadOverview()
        if (state.value.error != ExplorationError.AuthenticationRequired) ensurePhantomWeaponCatalog()
    }

    fun selectSection(kind: ExplorationSectionKind) {
        if (!requireIdentity() || kind !in board.sections() || state.value.overview?.available != true) return
        phantomParentSection = null
        mutablePhantomWeapons.value = phantomWeapons.value.copy(isOpen = false, returnFromHistory = false)
        if (kind != state.value.selectedSection) {
            cancelHistory()
            mutableState.value = state.value.copy(selectedSection = kind, error = null)
        }
        if (kind.isHistory && kind !in state.value.histories) loadHistory(kind)
    }

    fun refresh() {
        if (!requireIdentity()) return
        val current = state.value
        if (!phantomWeapons.value.isOpen && current.selectedSection.isHistory && current.overview?.available == true) loadHistory(current.selectedSection)
        else loadOverview()
    }

    fun loadOverview() {
        if (!requireIdentity()) return
        val generation = ++overviewGeneration
        overviewJob?.cancel()
        mutableState.value = state.value.copy(isLoadingOverview = true, error = null)
        mutablePhantomWeapons.value = phantomWeapons.value.copy(isLoading = supportsPhantomWeapons)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                if (generation != overviewGeneration || !requireIdentity()) return@launch
                val snapshot = if (supportsPhantomWeapons) (service as PersonalDataPhantomWeaponService).fetchPhantomWeaponExploration() else null
                val result = snapshot?.overview ?: service.fetchExplorationOverview(board)
                currentCoroutineContext().ensureActive()
                if (generation != overviewGeneration || !requireIdentity()) return@launch
                if (result.board != board) throw ExplorationException.InvalidResponse
                if (!result.available) {
                    clearProtectedContent()
                    mutableState.value = ExplorationUiState(board, overview = result)
                } else {
                    val previous = state.value.overview
                    val sections = result.sections.map { section ->
                        if (section.failure == null) section else section.copy(records = previous?.sections
                            ?.firstOrNull { it.kind == section.kind }?.records.orEmpty())
                    }
                    mutableState.value = state.value.copy(overview = result.copy(sections = sections,
                        metrics = sections.firstOrNull { it.kind == ExplorationSectionKind.Overview }
                            ?.records?.firstOrNull()?.fields.orEmpty()), isLoadingOverview = false, error = null)
                    if (snapshot != null) applyPhantomSnapshot(snapshot)
                    ensurePhantomWeaponCatalog()
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == overviewGeneration && requireIdentity()) {
                    val reason = failureReason(error)
                    if (supportsPhantomWeapons) mutablePhantomWeapons.value = phantomWeapons.value.copy(itemError = reason, aetherError = reason)
                    handleFailure(error, false)
                }
            } finally {
                if (generation == overviewGeneration && requireIdentity()) {
                    overviewJob = null
                    mutableState.value = state.value.copy(isLoadingOverview = false)
                    mutablePhantomWeapons.value = phantomWeapons.value.copy(isLoading = false)
                }
            }
        }
        overviewJob = job
        job.start()
    }

    private fun applyPhantomSnapshot(snapshot: PhantomWeaponExplorationSnapshot) {
        val previous = phantomWeapons.value
        val itemError = snapshot.items?.failure?.toError() ?: if (snapshot.items == null) ExplorationError.InvalidResponse else null
        val aetherError = snapshot.aether?.failure?.toError() ?: if (snapshot.aether == null) ExplorationError.InvalidResponse else null
        mutablePhantomWeapons.value = previous.copy(
            items = if (itemError == null) snapshot.items?.records else previous.items,
            aether = if (aetherError == null) snapshot.aether?.records else previous.aether,
            itemError = itemError, aetherError = aetherError,
        ).normalizeStage(hasSelectedPhantomStage)
    }

    private fun loadHistory(kind: ExplorationSectionKind) {
        if (!requireIdentity()) return
        val generation = ++historyGeneration
        historyJob?.cancel()
        mutableState.value = state.value.copy(isLoadingHistory = true, error = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                if (generation != historyGeneration || !requireIdentity()) return@launch
                val result = service.fetchExplorationHistory(board, kind)
                currentCoroutineContext().ensureActive()
                if (generation != historyGeneration || !requireIdentity()) return@launch
                if (result.kind != kind) throw ExplorationException.InvalidResponse
                val merged = if (result.failure == null) result else result.copy(records = state.value.histories[kind]?.records.orEmpty())
                mutableState.value = state.value.copy(histories = state.value.histories + (kind to merged), isLoadingHistory = false)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == historyGeneration && requireIdentity()) handleFailure(error, true)
            } finally {
                if (generation == historyGeneration && requireIdentity()) {
                    historyJob = null
                    mutableState.value = state.value.copy(isLoadingHistory = false)
                }
            }
        }
        historyJob = job
        job.start()
    }

    fun openPhantomWeapons() {
        if (!requireIdentity() || !supportsPhantomWeapons || state.value.overview?.available != true) return
        if (!phantomWeapons.value.isOpen) phantomParentSection = state.value.selectedSection
        cancelHistory()
        mutablePhantomWeapons.value = phantomWeapons.value.copy(isOpen = true, returnFromHistory = false)
    }
    fun closePhantomWeapons() {
        if (!requireIdentity()) return
        mutablePhantomWeapons.value = phantomWeapons.value.copy(isOpen = false, returnFromHistory = false)
    }
    fun selectPhantomWeaponStage(stage: PhantomWeaponStage) {
        if (!requireIdentity() || stage !in phantomWeapons.value.availableStages) return
        hasSelectedPhantomStage = true
        if (stage != phantomWeapons.value.selectedStage) mutablePhantomWeapons.value = phantomWeapons.value.copy(selectedStage = stage, isExpanded = false)
    }
    fun setPhantomWeaponQuery(query: String) {
        if (requireIdentity() && supportsPhantomWeapons) mutablePhantomWeapons.value = phantomWeapons.value.copy(query = query)
    }
    fun setPhantomWeaponsObtainedOnly(enabled: Boolean) {
        if (requireIdentity() && supportsPhantomWeapons) mutablePhantomWeapons.value = phantomWeapons.value.copy(obtainedOnly = enabled)
    }
    fun setPhantomWeaponsExpanded(expanded: Boolean) {
        if (requireIdentity() && supportsPhantomWeapons) mutablePhantomWeapons.value = phantomWeapons.value.copy(isExpanded = expanded)
    }
    fun openPhantomWeaponHistory() {
        if (!requireIdentity() || !supportsPhantomWeapons || !phantomWeapons.value.isOpen || state.value.overview?.available != true) return
        val generation = contentGeneration
        val parent = phantomParentSection ?: state.value.selectedSection
        selectSection(ExplorationSectionKind.RelicHistory)
        if (generation == contentGeneration && state.value.overview?.available == true) {
            phantomParentSection = parent
            mutablePhantomWeapons.value = phantomWeapons.value.copy(isOpen = false, returnFromHistory = true)
        }
    }
    fun returnToPhantomWeapons(): Boolean {
        if (!requireIdentity() || !phantomWeapons.value.returnFromHistory || state.value.overview?.available != true) return false
        cancelHistory()
        mutableState.value = state.value.copy(selectedSection = phantomParentSection ?: board.sections().first(), error = null)
        mutablePhantomWeapons.value = phantomWeapons.value.copy(isOpen = true, returnFromHistory = false)
        return true
    }

    private fun ensurePhantomWeaponCatalog() {
        if (phantomWeapons.value.catalog == null && phantomWeapons.value.catalogError == null && !phantomWeapons.value.isLoadingCatalog) retryPhantomWeaponCatalog()
    }
    fun retryPhantomWeaponCatalog() {
        if (!requireIdentity() || !supportsPhantomWeapons || phantomWeapons.value.isLoadingCatalog || state.value.overview?.available == false) return
        val reader = service as PersonalDataPhantomWeaponService
        val generation = ++catalogGeneration
        catalogJob?.cancel()
        mutablePhantomWeapons.value = phantomWeapons.value.copy(isLoadingCatalog = true, catalogError = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                if (generation != catalogGeneration || !requireIdentity()) return@launch
                val result = reader.fetchPhantomWeaponCatalog()
                currentCoroutineContext().ensureActive()
                if (generation != catalogGeneration || !requireIdentity()) return@launch
                mutablePhantomWeapons.value = if (result.weapons.isEmpty()) phantomWeapons.value.copy(catalogError = ExplorationError.Unavailable)
                    else phantomWeapons.value.copy(catalog = result, catalogError = null).normalizeStage(hasSelectedPhantomStage)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == catalogGeneration && requireIdentity()) {
                    val reason = failureReason(error)
                    if (reason == ExplorationError.AuthenticationRequired) handleFailure(error, false)
                    else mutablePhantomWeapons.value = phantomWeapons.value.copy(catalogError = reason)
                }
            } finally {
                if (generation == catalogGeneration && requireIdentity()) {
                    catalogJob = null
                    mutablePhantomWeapons.value = phantomWeapons.value.copy(isLoadingCatalog = false)
                }
            }
        }
        catalogJob = job
        job.start()
    }

    fun phantomWeaponItemIconUrl(iconId: Int): String? = (service as? PersonalDataPhantomWeaponService)?.phantomWeaponItemIconUrl(iconId)
    fun phantomWeaponElementIconUrl(element: PhantomWeaponElement): String? = (service as? PersonalDataPhantomWeaponService)?.phantomWeaponElementIconUrl(element)
    fun phantomWeaponLensImageUrl(step: Int): String? = (service as? PersonalDataPhantomWeaponService)?.phantomWeaponLensImageUrl(step)

    fun clearProtectedContent() {
        overviewGeneration++
        catalogGeneration++
        contentGeneration++
        hasSelectedPhantomStage = false
        phantomParentSection = null
        overviewJob?.cancel(); overviewJob = null
        catalogJob?.cancel(); catalogJob = null
        cancelHistory()
        mutableState.value = ExplorationUiState(board, error = ExplorationError.Unavailable)
        mutablePhantomWeapons.value = PhantomWeaponUiState(contentGeneration = contentGeneration)
    }

    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }
    private fun requireIdentity(): Boolean {
        if (isCleared) return false
        if (service.hasCommunityIdentity) return true
        clearProtectedContent()
        return false
    }
    private fun cancelHistory() {
        historyGeneration++
        historyJob?.cancel(); historyJob = null
        mutableState.value = state.value.copy(isLoadingHistory = false)
    }
    private fun handleFailure(error: Exception, history: Boolean) {
        val reason = failureReason(error)
        if (reason in listOf(ExplorationError.AuthenticationRequired, ExplorationError.Unavailable)) {
            clearProtectedContent()
            mutableState.value = state.value.copy(error = reason)
        } else {
            mutableState.value = state.value.copy(error = reason,
                isLoadingHistory = if (history) false else state.value.isLoadingHistory,
                isLoadingOverview = if (!history) false else state.value.isLoadingOverview)
        }
    }
}

private fun PhantomWeaponUiState.normalizeStage(preserveSelection: Boolean): PhantomWeaponUiState {
    val maximum = maximumStage
    val selection = selectedStage?.takeIf { preserveSelection && maximum != null && it.order <= maximum.order } ?: maximum
    return copy(selectedStage = selection, isExpanded = if (selection == selectedStage) isExpanded else false)
}
private fun ExplorationFailure.toError(): ExplorationError = when (this) {
    ExplorationFailure.Network -> ExplorationError.Network
    ExplorationFailure.InvalidResponse -> ExplorationError.InvalidResponse
    ExplorationFailure.Business -> ExplorationError.Business
}
private fun failureReason(error: Exception): ExplorationError = when (error) {
    ExplorationException.AuthenticationRequired, PersonalDataException.AuthenticationRequired -> ExplorationError.AuthenticationRequired
    ExplorationException.Unavailable -> ExplorationError.Unavailable
    ExplorationException.InvalidResponse -> ExplorationError.InvalidResponse
    is ExplorationException.Business -> ExplorationError.Business
    else -> ExplorationError.Network
}

class ExplorationViewModelFactory(private val service: PersonalDataExplorationService, private val board: ExplorationBoard) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ExplorationViewModel::class.java))
        return ExplorationViewModel(service, board) as T
    }
}
