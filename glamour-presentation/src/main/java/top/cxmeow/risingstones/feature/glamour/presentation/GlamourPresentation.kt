package top.cxmeow.risingstones.feature.glamour.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthorProfile
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService

data class GlamourUiState(
    val source: GlamourListSource = GlamourListSource.Community,
    val items: List<GlamourListingSummary> = emptyList(),
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val filter: GlamourFilter = GlamourFilter(),
    val search: GlamourSearchSelection? = null,
    val races: List<GlamourRace> = emptyList(),
    val isLoadingRaces: Boolean = false,
    val raceError: String? = null,
    val folders: List<GlamourFavoriteFolder> = emptyList(),
    val foldersResolved: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val selectedFolderId: Int? = null,
    val profileStatistics: GlamourProfileStatistics? = null,
    val isLoadingProfileStatistics: Boolean = false,
    val profileStatisticsResolved: Boolean = false,
    val authorProfile: GlamourAuthorProfile? = null,
    val isLoadingAuthorProfile: Boolean = false,
    val authorProfileResolved: Boolean = false,
    val isUpdatingFollow: Boolean = false,
    val followError: String? = null,
    val selectedId: Int? = null,
    val selectedDetail: GlamourDetail? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,
    val mutatingIds: Set<Int> = emptySet(),
    val isCreatingFolder: Boolean = false,
    val deletingFolderId: Int? = null,
    val folderMutationError: String? = null,
    val listError: String? = null,
    val error: String? = null,
)

class GlamourViewModel(
    private val service: GlamourService,
    private val profileAuthor: GlamourAuthor? = null,
    private val autoLoadList: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        GlamourUiState(source = if (profileAuthor == null) GlamourListSource.Community else GlamourListSource.Profile),
    )
    private var listJob: Job? = null
    private var detailJob: Job? = null
    val state: StateFlow<GlamourUiState> = mutableState.asStateFlow()
    val hasCommunityIdentity: Boolean get() = service.hasCommunityIdentity

    init {
        if (hasCommunityIdentity) {
            if (autoLoadList) {
                refresh()
                loadRaces()
                if (profileAuthor != null) {
                    loadProfileStatistics()
                    loadAuthorProfile()
                }
            }
        }
    }

    fun selectSource(source: GlamourListSource) {
        if (source == mutableState.value.source) return
        mutableState.update {
            it.copy(
                source = source,
                items = emptyList(),
                selectedId = null,
                selectedDetail = null,
                search = null,
                listError = null,
                detailError = null,
            )
        }
        refresh()
    }

    fun applyFilter(filter: GlamourFilter) {
        mutableState.update { it.copy(filter = filter, search = null) }
        refresh()
    }

    fun search(selection: GlamourSearchSelection?) {
        mutableState.update {
            it.copy(
                search = selection,
                filter = if (selection == null) it.filter else GlamourFilter(),
            )
        }
        refresh()
    }

    fun selectFolder(id: Int) {
        if (id == mutableState.value.selectedFolderId) return
        mutableState.update {
            it.copy(
                selectedFolderId = id,
                items = emptyList(),
                selectedId = null,
                selectedDetail = null,
                detailError = null,
            )
        }
        if (mutableState.value.source == GlamourListSource.Favorites) refresh()
    }

    fun refresh() {
        if (!hasCommunityIdentity) return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isLoading = it.items.isEmpty(),
                    isRefreshing = it.items.isNotEmpty(),
                    listError = null,
                    page = 1,
                )
            }
            try {
                ensureFoldersIfNeeded()
                val page = if (
                    mutableState.value.source == GlamourListSource.Favorites &&
                    mutableState.value.selectedFolderId == null
                ) {
                    if (profileAuthor?.id.isNullOrBlank()) {
                        throw top.cxmeow.risingstones.feature.glamour.domain.GlamourException
                            .MissingDefaultFavoriteFolder
                    }
                    GlamourListPage(emptyList(), 1, false)
                }
                else service.fetchGlamours(request(page = 1))
                mutableState.update {
                    it.copy(
                        items = page.items,
                        page = page.currentPage,
                        hasNextPage = page.hasNextPage,
                        isLoading = false,
                        isRefreshing = false,
                        listError = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        hasNextPage = if (it.items.isEmpty()) false else it.hasNextPage,
                        isLoading = false,
                        isRefreshing = false,
                        listError = error.message,
                    )
                }
            }
        }
    }

    fun toggleFollow() {
        val authorId = profileAuthor?.id?.takeIf(String::isNotBlank) ?: return
        val current = mutableState.value.authorProfile?.isFollowing == true
        if (mutableState.value.isUpdatingFollow) return
        viewModelScope.launch {
            mutableState.update { it.copy(isUpdatingFollow = true, followError = null) }
            try {
                if (current) service.cancelFollowAuthor(authorId) else service.followAuthor(authorId)
                loadAuthorProfile()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isUpdatingFollow = false, followError = error.message)
                }
            }
        }
    }

    fun clearFollowError() {
        mutableState.update { it.copy(followError = null) }
    }

    fun loadMore() {
        val current = mutableState.value
        if (!current.hasNextPage || current.isLoading || current.isLoadingMore) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingMore = true, listError = null) }
            try {
                val page = service.fetchGlamours(request(page = current.page + 1))
                mutableState.update { state ->
                    val ids = state.items.mapTo(mutableSetOf(), GlamourListingSummary::id)
                    val next = page.items.filterNot { it.id in ids }
                    state.copy(
                        items = state.items + next,
                        page = page.currentPage,
                        hasNextPage = page.hasNextPage && next.isNotEmpty(),
                        isLoadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(isLoadingMore = false, listError = error.message) }
            }
        }
    }

    fun selectDetail(id: Int) {
        if (mutableState.value.selectedId == id && mutableState.value.selectedDetail != null) return
        mutableState.update { it.copy(selectedId = id, detailError = null) }
        loadDetail(id, preserveContent = false)
    }

    fun clearSelection() {
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedId = null,
                selectedDetail = null,
                isLoadingDetail = false,
                detailError = null,
            )
        }
    }

    fun refreshDetail() {
        mutableState.value.selectedId?.let { loadDetail(it, preserveContent = true) }
    }

    fun retryDetail() {
        mutableState.value.selectedId?.let { loadDetail(it, preserveContent = false) }
    }

    private fun loadDetail(id: Int, preserveContent: Boolean) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isLoadingDetail = true,
                    selectedDetail = if (preserveContent) {
                        it.selectedDetail?.takeIf { detail -> detail.id == id }
                    } else {
                        null
                    },
                    detailError = null,
                )
            }
            try {
                val detail = service.fetchDetail(id)
                mutableState.update {
                    if (it.selectedId == id) it.copy(
                        selectedDetail = detail,
                        isLoadingDetail = false,
                        detailError = null,
                    )
                    else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.selectedId == id) it.copy(
                        isLoadingDetail = false,
                        detailError = error.message,
                    ) else it
                }
            }
        }
    }

    fun toggleLike(id: Int) = mutate(id) {
        val liked = service.toggleLike(id)
        updateItem(id) { item ->
            item.copy(isLiked = liked, likes = (item.likes + if (liked) 1 else -1).coerceAtLeast(0))
        }
        mutableState.update { state ->
            state.copy(selectedDetail = state.selectedDetail?.takeIf { it.id == id }?.let {
                it.copy(isLiked = liked, likes = (it.likes + if (liked) 1 else -1).coerceAtLeast(0))
            } ?: state.selectedDetail)
        }
    }

    fun toggleFavorite(id: Int) = mutate(id) {
        val current = mutableState.value.selectedDetail?.takeIf { it.id == id }?.isFavorite
            ?: mutableState.value.items.firstOrNull { it.id == id }?.isFavorite ?: false
        if (current) service.cancelFavorite(id) else service.favorite(id)
        updateItem(id) { item ->
            item.copy(
                isFavorite = !current,
                favorites = (item.favorites + if (current) -1 else 1).coerceAtLeast(0),
            )
        }
        mutableState.update { state ->
            state.copy(selectedDetail = state.selectedDetail?.takeIf { it.id == id }?.let {
                it.copy(
                    isFavorite = !current,
                    favorites = (it.favorites + if (current) -1 else 1).coerceAtLeast(0),
                )
            } ?: state.selectedDetail)
        }
        reloadFolders()
    }

    fun createFolder(name: String, isPublic: Boolean, onSuccess: () -> Unit = {}) {
        if (mutableState.value.isCreatingFolder) return
        viewModelScope.launch {
            mutableState.update { it.copy(isCreatingFolder = true, folderMutationError = null) }
            try {
                service.createFavoriteFolder(name.trim(), isPublic)
                reloadFolders()
                mutableState.update {
                    it.copy(
                        isCreatingFolder = false,
                        folderMutationError = null,
                    )
                }
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isCreatingFolder = false, folderMutationError = error.message)
                }
            }
        }
    }

    fun deleteFolder(id: Int, onSuccess: () -> Unit = {}) {
        if (mutableState.value.deletingFolderId != null) return
        viewModelScope.launch {
            mutableState.update { it.copy(deletingFolderId = id, folderMutationError = null) }
            try {
                service.deleteFavoriteFolder(id)
                reloadFolders()
                mutableState.update { it.copy(deletingFolderId = null, folderMutationError = null) }
                if (mutableState.value.source == GlamourListSource.Favorites) refresh()
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(deletingFolderId = null, folderMutationError = error.message)
                }
            }
        }
    }

    fun clearNotice() {
        mutableState.update {
            it.copy(error = null, folderMutationError = null)
        }
    }

    private fun mutate(id: Int, operation: suspend () -> Unit) {
        if (id in mutableState.value.mutatingIds) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutatingIds = it.mutatingIds + id) }
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.message) }
            } finally {
                mutableState.update { it.copy(mutatingIds = it.mutatingIds - id) }
            }
        }
    }

    private suspend fun ensureFoldersIfNeeded() {
        if (mutableState.value.source != GlamourListSource.Favorites) return
        if (!mutableState.value.foldersResolved) reloadFolders()
        if (mutableState.value.selectedFolderId == null) {
            mutableState.update { state ->
                state.copy(selectedFolderId = state.folders.firstOrNull { it.isDefault }?.id
                    ?: state.folders.firstOrNull()?.id)
            }
        }
    }

    private suspend fun reloadFolders() {
        mutableState.update { it.copy(isLoadingFolders = true) }
        try {
            val folders = service.fetchFavoriteFolders(profileAuthor?.id)
            mutableState.update { state ->
                state.copy(
                    folders = folders,
                    foldersResolved = true,
                    isLoadingFolders = false,
                    selectedFolderId = state.selectedFolderId?.takeIf { selected ->
                        folders.any { it.id == selected }
                    } ?: folders.firstOrNull { it.isDefault }?.id ?: folders.firstOrNull()?.id,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableState.update { it.copy(isLoadingFolders = false) }
            throw error
        }
    }

    fun loadRaces() {
        if (mutableState.value.isLoadingRaces) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingRaces = true, raceError = null) }
            try {
                val races = service.fetchRaces()
                mutableState.update {
                    it.copy(races = races, isLoadingRaces = false, raceError = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isLoadingRaces = false, raceError = error.message)
                }
            }
        }
    }

    private fun loadProfileStatistics() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingProfileStatistics = true) }
            try {
                val stats = service.fetchProfileStatistics(profileAuthor?.id)
                mutableState.update {
                    it.copy(
                        profileStatistics = stats,
                        isLoadingProfileStatistics = false,
                        profileStatisticsResolved = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        profileStatistics = null,
                        isLoadingProfileStatistics = false,
                        profileStatisticsResolved = true,
                    )
                }
            }
        }
    }

    private fun loadAuthorProfile() {
        val authorId = profileAuthor?.id?.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingAuthorProfile = true, followError = null) }
            try {
                val profile = service.fetchAuthorProfile(authorId)
                mutableState.update {
                    it.copy(
                        authorProfile = profile,
                        isLoadingAuthorProfile = false,
                        authorProfileResolved = true,
                        isUpdatingFollow = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        isLoadingAuthorProfile = false,
                        authorProfileResolved = true,
                        isUpdatingFollow = false,
                        followError = error.message,
                    )
                }
            }
        }
    }

    private fun request(page: Int): GlamourListRequest {
        val state = mutableState.value
        return GlamourListRequest(
            source = state.source,
            page = page,
            limit = when {
                state.source == GlamourListSource.Favorites -> 16
                state.source == GlamourListSource.Profile -> 20
                state.search != null -> 20
                else -> 12
            },
            filter = state.filter,
            search = state.search,
            favoriteFolderId = state.selectedFolderId,
            authorId = profileAuthor?.id,
        )
    }

    private fun updateItem(id: Int, transform: (GlamourListingSummary) -> GlamourListingSummary) {
        mutableState.update { state ->
            state.copy(items = state.items.map { if (it.id == id) transform(it) else it })
        }
    }
}

class GlamourViewModelFactory(
    private val service: GlamourService,
    private val profileAuthor: GlamourAuthor? = null,
    private val autoLoadList: Boolean = true,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        GlamourViewModel(service, profileAuthor, autoLoadList) as T
}
