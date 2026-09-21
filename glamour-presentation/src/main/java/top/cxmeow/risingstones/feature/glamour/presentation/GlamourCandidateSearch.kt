package top.cxmeow.risingstones.feature.glamour.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService

enum class GlamourCandidateKind { Equipment, Glasses, Ornament }

data class GlamourCandidate(
    val id: Int,
    val name: String,
    val description: String,
    val groupName: String? = null,
    val jobNames: List<String> = emptyList(),
)

data class GlamourCandidateSearchUiState(
    val kind: GlamourCandidateKind = GlamourCandidateKind.Equipment,
    val query: String = "",
    val items: List<GlamourCandidate> = emptyList(),
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val hasLoaded: Boolean = false,
)

class GlamourCandidateSearchViewModel(private val service: GlamourService) : ViewModel() {
    private val mutableState = MutableStateFlow(GlamourCandidateSearchUiState())
    private var searchJob: Job? = null
    private var generation = 0L
    val state: StateFlow<GlamourCandidateSearchUiState> = mutableState.asStateFlow()

    fun search(kind: GlamourCandidateKind, query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            resetCancel()
            mutableState.update { it.copy(kind = kind) }
            return
        }
        if (!service.hasCommunityIdentity) {
            clearProtectedContent()
            return
        }
        val current = mutableState.value
        val preserveContent = current.kind == kind && current.query == trimmedQuery
        val requestGeneration = ++generation
        searchJob?.cancel()
        mutableState.value = (if (preserveContent) current else GlamourCandidateSearchUiState(kind, trimmedQuery))
            .let {
                it.copy(
                    isLoading = !it.hasLoaded,
                    isRefreshing = it.hasLoaded,
                    isLoadingMore = false,
                    error = null,
                )
            }
        searchJob = viewModelScope.launch {
            try {
                val result = fetchPage(kind, trimmedQuery, 1)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                mutableState.update {
                    it.copy(
                        items = result.items.distinctBy(GlamourCandidate::id),
                        page = 1,
                        hasNextPage = result.hasNextPage,
                        hasLoaded = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                if (error is GlamourException.AuthenticationRequired) clearProtectedContent()
                else mutableState.update { it.copy(error = error.message) }
            } finally {
                if (requestGeneration == generation) {
                    mutableState.update { it.copy(isLoading = false, isRefreshing = false) }
                }
            }
        }
    }

    fun loadMore() {
        if (!service.hasCommunityIdentity) {
            clearProtectedContent()
            return
        }
        val current = mutableState.value
        if (current.kind != GlamourCandidateKind.Equipment || !current.hasNextPage ||
            current.isLoading || current.isRefreshing || current.isLoadingMore || current.query.isEmpty()
        ) return
        val requestGeneration = ++generation
        searchJob?.cancel()
        mutableState.update { it.copy(isLoadingMore = true, error = null) }
        searchJob = viewModelScope.launch {
            try {
                val page = current.page + 1
                val result = fetchPage(current.kind, current.query, page)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                mutableState.update { state ->
                    val ids = state.items.mapTo(mutableSetOf(), GlamourCandidate::id)
                    state.copy(
                        items = state.items + result.items.filter { ids.add(it.id) },
                        page = page,
                        hasNextPage = result.hasNextPage,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                if (error is GlamourException.AuthenticationRequired) clearProtectedContent()
                else mutableState.update { it.copy(error = error.message) }
            } finally {
                if (requestGeneration == generation) mutableState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun selection(candidate: GlamourCandidate): GlamourSearchSelection {
        if (!service.hasCommunityIdentity) {
            clearProtectedContent()
            throw GlamourException.AuthenticationRequired
        }
        val current = mutableState.value
        require(candidate in current.items) { "Select a candidate from the current search results" }
        return GlamourSearchSelection(
            keywords = candidate.id.toString(),
            searchByEquipment = current.kind == GlamourCandidateKind.Equipment,
            searchByGlasses = current.kind == GlamourCandidateKind.Glasses,
            searchByOrnament = current.kind == GlamourCandidateKind.Ornament,
            displayTitle = candidate.name,
        )
    }

    fun clearProtectedContent() {
        resetCancel()
        mutableState.update { it.copy(error = GlamourException.AuthenticationRequired.message) }
    }

    fun resetCancel() {
        ++generation
        searchJob?.cancel()
        searchJob = null
        mutableState.value = GlamourCandidateSearchUiState()
    }

    private suspend fun fetchPage(kind: GlamourCandidateKind, query: String, page: Int): CandidatePage = when (kind) {
        GlamourCandidateKind.Equipment -> {
            val items = service.searchEquipment(query, page)
            CandidatePage(
                items = items.map { GlamourCandidate(it.id, it.name, it.description, jobNames = it.jobNames) },
                hasNextPage = items.size >= EquipmentPageSize,
            )
        }
        GlamourCandidateKind.Glasses -> CandidatePage(
            items = service.searchGlasses(query).flatMap { group ->
                group.accessories.map { GlamourCandidate(it.id, it.name, it.description, groupName = group.name) }
            },
            hasNextPage = false,
        )
        GlamourCandidateKind.Ornament -> CandidatePage(
            items = service.searchOrnaments(query).map { GlamourCandidate(it.id, it.name, it.description) },
            hasNextPage = false,
        )
    }

    private data class CandidatePage(val items: List<GlamourCandidate>, val hasNextPage: Boolean)

    private companion object {
        const val EquipmentPageSize = 20
    }
}

class GlamourCandidateSearchViewModelFactory(private val service: GlamourService) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GlamourCandidateSearchViewModel::class.java))
        return GlamourCandidateSearchViewModel(service) as T
    }
}
