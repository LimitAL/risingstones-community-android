package top.cxmeow.risingstones.feature.glamour.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthorProfile
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowsePage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowseRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowsingService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourCollectionService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFolderNameMaximumLength
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListOrder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTagCategory
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTribe

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

data class GlamourBrowsingUiState(
    val following: Boolean = false,
    val tribeId: Int? = null,
    val tagIds: Set<Int> = emptySet(),
    val tagCategories: List<GlamourTagCategory> = emptyList(),
    val tribes: List<GlamourTribe> = emptyList(),
    val isLoadingCatalog: Boolean = false,
    val catalogError: String? = null,
)

data class GlamourInteractionUiState(
    val favoriteTargetId: Int? = null,
    val favoriteFolders: List<GlamourFavoriteFolder> = emptyList(),
    val selectedFavoriteFolderId: Int? = null,
    val isLoadingFavoriteFolders: Boolean = false,
    val favoriteFolderError: String? = null,
    val isSubmittingFavorite: Boolean = false,
    val updatingFolderId: Int? = null,
    val folderRefreshError: String? = null,
    val folderMutationRevision: Long = 0,
    val isClaimingCoupon: Boolean = false,
    val couponError: String? = null,
    val couponClaimedNotice: Boolean = false,
)

class GlamourViewModel(
    private val service: GlamourService,
    private val profileAuthor: GlamourAuthor? = null,
    private val autoLoadList: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        GlamourUiState(
            source = if (profileAuthor == null) GlamourListSource.Community else GlamourListSource.Profile,
            filter = GlamourFilter(order = if (profileAuthor == null) GlamourListOrder.Default else GlamourListOrder.Latest),
        ),
    )
    private val browsingService = service as? GlamourBrowsingService
    private val mutableBrowsingState = MutableStateFlow(GlamourBrowsingUiState())
    private val collectionService = service as? GlamourCollectionService
    private val mutableInteractionState = MutableStateFlow(GlamourInteractionUiState())
    private var interactionEpoch = 0L
    private var favoritePickerGeneration = 0L
    private var favoritePickerJob: Job? = null
    private var folderGeneration = 0L
    private val claimingCouponIds = mutableSetOf<Int>()
    private val claimedCouponIds = mutableSetOf<Int>()
    private var favoriteMutationRevision = 0L
    private val favoriteMutations = mutableMapOf<Int, Pair<Long, Boolean>>()
    private var nextPageTime: String? = null
    private var catalogLoaded = false
    private var catalogGeneration = 0L
    private var catalogJob: Job? = null
    private var listJob: Job? = null
    private var listGeneration = 0L
    private var detailJob: Job? = null
    val state: StateFlow<GlamourUiState> = mutableState.asStateFlow()
    val browsingState: StateFlow<GlamourBrowsingUiState> = mutableBrowsingState.asStateFlow()
    val interactionState: StateFlow<GlamourInteractionUiState> = mutableInteractionState.asStateFlow()
    val canManageFolders: Boolean get() = profileAuthor == null
    val supportsCollectionManagement: Boolean get() = collectionService != null
    val supportsBrowsing: Boolean get() = browsingService != null
    val hasCommunityIdentity: Boolean get() = service.hasCommunityIdentity

    init {
        if (hasCommunityIdentity) {
            if (autoLoadList) {
                refresh()
                loadRaces()
                loadBrowsingCatalog()
            }
        }
    }

    fun selectSource(source: GlamourListSource) {
        if (source == mutableState.value.source && !mutableBrowsingState.value.following) return
        clearBrowsingSelection()
        clearSelection()
        mutableState.update {
            it.copy(
                source = source,
                items = emptyList(),
                page = 1,
                hasNextPage = false,
                selectedId = null,
                selectedDetail = null,
                search = null,
                filter = defaultFilter(source),
                listError = null,
                detailError = null,
            )
        }
        refresh()
    }

    fun applyFilter(filter: GlamourFilter) {
        val browsing = mutableBrowsingState.value
        val preserveBrowsing = !browsing.following
        applyFilters(
            filter,
            if (preserveBrowsing && mutableState.value.source == GlamourListSource.Community) {
                browsing.tribeId
            } else {
                null
            },
            if (preserveBrowsing) browsing.tagIds else emptySet(),
        )
    }

    fun applyBrowsingFilter(filter: GlamourFilter, tribeId: Int?, tagIds: Set<Int>) {
        applyFilters(filter, tribeId, tagIds)
    }

    private fun applyFilters(filter: GlamourFilter, tribeId: Int?, tagIds: Set<Int>) {
        val current = mutableState.value
        val browsing = mutableBrowsingState.value
        val allowBrowsingFilters = supportsBrowsing && catalogLoaded
        val validTags = if (allowBrowsingFilters) selectedTags(tagIds, browsing.tagCategories) else emptySet()
        val validTribe = tribeId?.takeIf { id ->
            id > 0 && current.source == GlamourListSource.Community &&
                browsing.tribes.any { it.id == id && it.raceId == filter.raceId }
        }
        val effectiveFilter = normalizeFilter(filter, validTribe, validTags, current.source)
        if (current.filter == effectiveFilter && current.search == null && !browsing.following &&
            browsing.tribeId == validTribe && browsing.tagIds == validTags
        ) {
            refresh()
            return
        }
        nextPageTime = null
        mutableBrowsingState.update { it.copy(following = false, tribeId = validTribe, tagIds = validTags) }
        clearSelection()
        mutableState.update {
            it.copy(filter = effectiveFilter, search = null, items = emptyList(), page = 1, hasNextPage = false)
        }
        refresh()
    }

    fun selectFollowing() {
        if (!supportsBrowsing || profileAuthor != null || mutableBrowsingState.value.following) return
        clearBrowsingSelection()
        mutableBrowsingState.update { it.copy(following = true) }
        clearSelection()
        mutableState.update {
            it.copy(
                source = GlamourListSource.Community,
                filter = GlamourFilter(),
                search = null,
                items = emptyList(),
                page = 1,
                hasNextPage = false,
            )
        }
        refresh()
    }

    fun search(selection: GlamourSearchSelection?) {
        val browsing = mutableBrowsingState.value
        if (mutableState.value.search == selection && !browsing.following &&
            browsing.tribeId == null && browsing.tagIds.isEmpty()
        ) {
            refresh()
            return
        }
        clearBrowsingSelection()
        clearSelection()
        mutableState.update {
            it.copy(
                search = selection,
                filter = if (selection == null) it.filter else defaultFilter(it.source),
                items = emptyList(),
                page = 1,
                hasNextPage = false,
            )
        }
        refresh()
    }

    fun selectFolder(id: Int) {
        if (id == mutableState.value.selectedFolderId) return
        nextPageTime = null
        clearSelection()
        mutableState.update {
            it.copy(
                selectedFolderId = id,
                items = emptyList(),
                page = 1,
                hasNextPage = false,
                selectedId = null,
                selectedDetail = null,
                detailError = null,
            )
        }
        if (mutableState.value.source == GlamourListSource.Favorites) refresh()
    }

    fun refresh() {
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val generation = ++listGeneration
        listJob?.cancel()
        mutableState.update {
            it.copy(
                isLoading = it.items.isEmpty(),
                isRefreshing = it.items.isNotEmpty(),
                isLoadingMore = false,
                isLoadingFolders = false,
                listError = null,
            )
        }
        listJob = viewModelScope.launch {
            try {
                ensureFoldersIfNeeded()
                currentCoroutineContext().ensureActive()
                if (generation != listGeneration) return@launch
                val favoriteRevision = favoriteMutationRevision
                val result = if (
                    mutableState.value.source == GlamourListSource.Favorites &&
                    mutableState.value.selectedFolderId == null
                ) {
                    if (profileAuthor?.id.isNullOrBlank()) {
                        throw top.cxmeow.risingstones.feature.glamour.domain.GlamourException
                            .MissingDefaultFavoriteFolder
                    }
                    GlamourBrowsePage(GlamourListPage(emptyList(), 1, false), null)
                }
                else fetchPage(browseRequest(request(page = 1), pageTime = null))
                currentCoroutineContext().ensureActive()
                if (generation != listGeneration) return@launch
                nextPageTime = result.nextPageTime
                val page = result.page
                mutableState.update {
                    it.copy(
                        items = page.items.distinctBy(GlamourListingSummary::id).map { mergeFavorite(it, favoriteRevision) },
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
                currentCoroutineContext().ensureActive()
                if (generation != listGeneration || handleAuthenticationFailure(error)) return@launch
                mutableState.update {
                    it.copy(
                        hasNextPage = if (it.items.isEmpty()) false else it.hasNextPage,
                        isLoading = false,
                        isRefreshing = false,
                        listError = error.message,
                    )
                }
            } finally {
                if (generation == listGeneration) mutableState.update {
                    it.copy(isLoading = false, isRefreshing = false, isLoadingFolders = false)
                }
            }
        }
        if (generation == listGeneration && mutableState.value.source == GlamourListSource.Profile) {
            if (mutableState.value.profileStatistics == null) loadProfileStatistics()
            if (mutableState.value.authorProfile == null) loadAuthorProfile()
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
                currentCoroutineContext().ensureActive()
                loadAuthorProfile()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (handleAuthenticationFailure(error)) return@launch
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
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val current = mutableState.value
        if (!current.hasNextPage || current.isLoading || current.isRefreshing || current.isLoadingMore) return
        val generation = ++listGeneration
        val nextRequest = browseRequest(request(page = current.page + 1), nextPageTime)
        val favoriteRevision = favoriteMutationRevision
        listJob?.cancel()
        mutableState.update { it.copy(isLoadingMore = true, listError = null) }
        listJob = viewModelScope.launch {
            try {
                val result = fetchPage(nextRequest)
                currentCoroutineContext().ensureActive()
                if (generation != listGeneration) return@launch
                nextPageTime = result.nextPageTime
                val page = result.page
                mutableState.update { state ->
                    val ids = state.items.mapTo(mutableSetOf(), GlamourListingSummary::id)
                    val next = page.items.filter { ids.add(it.id) }.map { mergeFavorite(it, favoriteRevision) }
                    state.copy(
                        items = state.items + next,
                        page = page.currentPage,
                        hasNextPage = page.hasNextPage && page.items.isNotEmpty(),
                        isLoadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (generation != listGeneration || handleAuthenticationFailure(error)) return@launch
                mutableState.update { it.copy(isLoadingMore = false, listError = error.message) }
            } finally {
                if (generation == listGeneration) mutableState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun selectDetail(id: Int) {
        if (mutableState.value.selectedId == id && mutableState.value.selectedDetail != null) return
        if (mutableState.value.selectedId != id) resetCouponPresentation(id)
        mutableState.update { it.copy(selectedId = id, detailError = null) }
        loadDetail(id, preserveContent = false)
    }

    fun clearSelection() {
        detailJob?.cancel()
        resetCouponPresentation(null)
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
        val favoriteRevision = favoriteMutationRevision
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
                val fetched = service.fetchDetail(id)
                currentCoroutineContext().ensureActive()
                val favorite = mergeFavorite(fetched, favoriteRevision)
                val detail = if (id in claimedCouponIds) favorite.copy(isCouponClaimed = true, isFollowingAuthor = true) else favorite
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
                currentCoroutineContext().ensureActive()
                if (handleAuthenticationFailure(error)) return@launch
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
        currentCoroutineContext().ensureActive()
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
        currentCoroutineContext().ensureActive()
        applyFavorite(id, !current)
        retryFolderRefresh()
    }

    fun createFolder(name: String, isPublic: Boolean, onSuccess: () -> Unit = {}) {
        if (!canStartFolderMutation()) return
        val trimmed = validatedFolderName(name) ?: return
        val epoch = interactionEpoch
        mutableState.update { it.copy(isCreatingFolder = true, folderMutationError = null) }
        mutableInteractionState.update { it.copy(folderRefreshError = null) }
        viewModelScope.launch {
            try {
                service.createFavoriteFolder(trimmed, isPublic)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch) return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || handleAuthenticationFailure(error)) return@launch
                mutableState.update { it.copy(folderMutationError = error.message) }
                return@launch
            } finally {
                if (epoch == interactionEpoch) mutableState.update { it.copy(isCreatingFolder = false) }
            }
            ++folderGeneration
            mutableInteractionState.update {
                it.copy(selectedFavoriteFolderId = null, folderMutationRevision = it.folderMutationRevision + 1)
            }
            onSuccess()
            retryFolderRefresh()
        }
    }

    fun updateFolder(id: Int, name: String, isPublic: Boolean, onSuccess: () -> Unit = {}) {
        val collection = collectionService ?: return
        if (!canManageFolders || !canStartFolderMutation()) return
        if (mutableState.value.folders.none { it.id == id }) {
            mutableState.update { it.copy(folderMutationError = "Select a known favorite folder") }
            return
        }
        val trimmed = validatedFolderName(name) ?: return
        val epoch = interactionEpoch
        mutableState.update { it.copy(folderMutationError = null) }
        mutableInteractionState.update { it.copy(updatingFolderId = id, folderRefreshError = null) }
        viewModelScope.launch {
            try {
                collection.updateFavoriteFolder(id, trimmed, isPublic)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch) return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || handleAuthenticationFailure(error)) return@launch
                mutableState.update { it.copy(folderMutationError = error.message) }
                return@launch
            } finally {
                if (epoch == interactionEpoch) mutableInteractionState.update { it.copy(updatingFolderId = null) }
            }
            ++folderGeneration
            transformOwnFolders { folders -> folders.map { if (it.id == id) it.copy(name = trimmed, isPublic = isPublic) else it } }
            mutableInteractionState.update { it.copy(folderMutationRevision = it.folderMutationRevision + 1) }
            onSuccess()
            retryFolderRefresh()
        }
    }

    fun deleteFolder(id: Int, onSuccess: () -> Unit = {}) {
        if (!canManageFolders || !canStartFolderMutation()) return
        val folder = mutableState.value.folders.firstOrNull { it.id == id }
        if (folder == null || folder.isDefault) {
            mutableState.update { it.copy(folderMutationError = "Only known non-default folders can be deleted") }
            return
        }
        val epoch = interactionEpoch
        mutableState.update { it.copy(deletingFolderId = id, folderMutationError = null) }
        mutableInteractionState.update { it.copy(folderRefreshError = null) }
        viewModelScope.launch {
            try {
                service.deleteFavoriteFolder(id)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch) return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || handleAuthenticationFailure(error)) return@launch
                mutableState.update { it.copy(folderMutationError = error.message) }
                return@launch
            } finally {
                if (epoch == interactionEpoch) mutableState.update { it.copy(deletingFolderId = null) }
            }
            ++folderGeneration
            val deletedSelection = mutableState.value.selectedFolderId == id
            transformOwnFolders { folders -> folders.filterNot { it.id == id } }
            mutableInteractionState.update { it.copy(folderMutationRevision = it.folderMutationRevision + 1) }
            if (mutableState.value.source == GlamourListSource.Favorites) {
                if (deletedSelection) {
                    clearSelection()
                    nextPageTime = null
                    mutableState.update { it.copy(items = emptyList(), page = 1, hasNextPage = false) }
                }
                refresh()
            }
            onSuccess()
            retryFolderRefresh()
        }
    }

    fun openFavoritePicker(id: Int) {
        if (!supportsCollectionManagement || id <= 0 || mutableInteractionState.value.isSubmittingFavorite) return
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        ++favoritePickerGeneration
        favoritePickerJob?.cancel()
        mutableInteractionState.update {
            it.copy(favoriteTargetId = id, favoriteFolders = emptyList(), selectedFavoriteFolderId = null,
                favoriteFolderError = null, isLoadingFavoriteFolders = false)
        }
        retryFavoriteFolders()
    }

    fun closeFavoritePicker() {
        if (mutableInteractionState.value.isSubmittingFavorite) return
        ++favoritePickerGeneration
        favoritePickerJob?.cancel()
        mutableInteractionState.update {
            it.copy(favoriteTargetId = null, favoriteFolders = emptyList(), selectedFavoriteFolderId = null,
                isLoadingFavoriteFolders = false, favoriteFolderError = null)
        }
    }

    fun selectFavoriteFolder(id: Int) {
        val interaction = mutableInteractionState.value
        if (interaction.favoriteTargetId == null || interaction.isSubmittingFavorite ||
            interaction.favoriteFolders.none { it.id == id }
        ) return
        mutableInteractionState.update { it.copy(selectedFavoriteFolderId = id, favoriteFolderError = null) }
    }

    fun retryFavoriteFolders() {
        val target = mutableInteractionState.value.favoriteTargetId ?: return
        if (mutableInteractionState.value.isSubmittingFavorite) return
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val generation = ++favoritePickerGeneration
        val epoch = interactionEpoch
        favoritePickerJob?.cancel()
        mutableInteractionState.update { it.copy(isLoadingFavoriteFolders = true, favoriteFolderError = null) }
        favoritePickerJob = viewModelScope.launch {
            try {
                val folders = service.fetchFavoriteFolders(null)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || generation != favoritePickerGeneration) return@launch
                mutableInteractionState.update {
                    if (it.favoriteTargetId == target) it.copy(
                        favoriteFolders = folders,
                        selectedFavoriteFolderId = it.selectedFavoriteFolderId?.takeIf { id -> folders.any { folder -> folder.id == id } },
                    ) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || generation != favoritePickerGeneration || handleAuthenticationFailure(error)) return@launch
                mutableInteractionState.update { it.copy(favoriteFolderError = error.message) }
            } finally {
                if (epoch == interactionEpoch && generation == favoritePickerGeneration) {
                    mutableInteractionState.update { it.copy(isLoadingFavoriteFolders = false) }
                }
            }
        }
    }

    fun submitFavorite(folderId: Int) {
        val collection = collectionService ?: return
        val interaction = mutableInteractionState.value
        val target = interaction.favoriteTargetId ?: return
        if (interaction.isSubmittingFavorite || interaction.isLoadingFavoriteFolders ||
            interaction.favoriteFolders.none { it.id == folderId } || target in mutableState.value.mutatingIds
        ) return
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val epoch = interactionEpoch
        mutableInteractionState.update { it.copy(isSubmittingFavorite = true, selectedFavoriteFolderId = folderId, favoriteFolderError = null) }
        mutableState.update { it.copy(mutatingIds = it.mutatingIds + target) }
        viewModelScope.launch {
            try {
                collection.favoriteInFolder(target, folderId)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch) return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || handleAuthenticationFailure(error)) return@launch
                mutableInteractionState.update { it.copy(favoriteFolderError = error.message) }
                return@launch
            } finally {
                if (epoch == interactionEpoch) {
                    mutableInteractionState.update { it.copy(isSubmittingFavorite = false) }
                    mutableState.update { it.copy(mutatingIds = it.mutatingIds - target) }
                }
            }
            applyFavorite(target, true)
            closeFavoritePicker()
            retryFolderRefresh()
        }
    }

    fun claimSelectedCoupon() {
        val detail = mutableState.value.selectedDetail ?: return
        if (mutableState.value.selectedId != detail.id) return
        val inviteCode = detail.couponInviteCode?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (!detail.isCouponEligible || detail.isCouponClaimed || detail.id in claimingCouponIds) return
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val epoch = interactionEpoch
        claimingCouponIds += detail.id
        mutableInteractionState.update { it.copy(isClaimingCoupon = true, couponError = null, couponClaimedNotice = false) }
        viewModelScope.launch {
            try {
                service.claimCoupon(inviteCode, detail.id)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch) return@launch
                claimedCouponIds += detail.id
                mutableState.update {
                    it.copy(selectedDetail = it.selectedDetail?.let { selected ->
                        if (selected.id == detail.id) selected.copy(isCouponClaimed = true, isFollowingAuthor = true) else selected
                    })
                }
                if (mutableState.value.selectedId == detail.id) {
                    mutableInteractionState.update { it.copy(couponClaimedNotice = true, couponError = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || handleAuthenticationFailure(error)) return@launch
                if (mutableState.value.selectedId == detail.id) {
                    mutableInteractionState.update { it.copy(couponError = error.message) }
                }
            } finally {
                if (epoch == interactionEpoch) {
                    claimingCouponIds -= detail.id
                    mutableInteractionState.update { it.copy(isClaimingCoupon = mutableState.value.selectedId in claimingCouponIds) }
                }
            }
        }
    }

    fun clearCouponNotice() {
        mutableInteractionState.update { it.copy(couponError = null, couponClaimedNotice = false) }
    }

    fun clearNotice() {
        mutableState.update {
            it.copy(error = null, folderMutationError = null)
        }
        mutableInteractionState.update { it.copy(folderRefreshError = null) }
    }

    private fun mutate(id: Int, operation: suspend () -> Unit) {
        if (id in mutableState.value.mutatingIds) return
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val epoch = interactionEpoch
        mutableState.update { it.copy(mutatingIds = it.mutatingIds + id) }
        viewModelScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (handleAuthenticationFailure(error)) return@launch
                mutableState.update { it.copy(error = error.message) }
            } finally {
                if (epoch == interactionEpoch) mutableState.update { it.copy(mutatingIds = it.mutatingIds - id) }
            }
        }
    }

    private fun canStartFolderMutation(): Boolean {
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return false
        }
        return !mutableState.value.isCreatingFolder && mutableState.value.deletingFolderId == null &&
            mutableInteractionState.value.updatingFolderId == null && !mutableInteractionState.value.isSubmittingFavorite
    }

    private fun validatedFolderName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.length in 1..GlamourFolderNameMaximumLength) return trimmed
        mutableState.update { it.copy(folderMutationError = "Favorite folder name must contain 1 to $GlamourFolderNameMaximumLength characters") }
        return null
    }

    private fun applyFavorite(id: Int, favorite: Boolean) {
        favoriteMutations[id] = ++favoriteMutationRevision to favorite
        updateItem(id) { item ->
            item.copy(isFavorite = favorite, favorites = (item.favorites +
                if (item.isFavorite == favorite) 0 else if (favorite) 1 else -1).coerceAtLeast(0))
        }
        mutableState.update { state ->
            state.copy(selectedDetail = state.selectedDetail?.let { detail ->
                if (detail.id == id) detail.copy(isFavorite = favorite, favorites = (detail.favorites +
                    if (detail.isFavorite == favorite) 0 else if (favorite) 1 else -1).coerceAtLeast(0)) else detail
            })
        }
    }

    private fun mergeFavorite(item: GlamourListingSummary, requestedRevision: Long): GlamourListingSummary {
        val (revision, favorite) = favoriteMutations[item.id] ?: return item
        if (revision <= requestedRevision) return item
        return item.copy(isFavorite = favorite, favorites = (item.favorites +
            if (item.isFavorite == favorite) 0 else if (favorite) 1 else -1).coerceAtLeast(0))
    }

    private fun mergeFavorite(detail: GlamourDetail, requestedRevision: Long): GlamourDetail {
        val (revision, favorite) = favoriteMutations[detail.id] ?: return detail
        if (revision <= requestedRevision) return detail
        return detail.copy(isFavorite = favorite, favorites = (detail.favorites +
            if (detail.isFavorite == favorite) 0 else if (favorite) 1 else -1).coerceAtLeast(0))
    }

    private fun transformOwnFolders(transform: (List<GlamourFavoriteFolder>) -> List<GlamourFavoriteFolder>) {
        val folders = transform(mutableState.value.folders)
        replaceFolders(folders)
        mutableInteractionState.update {
            val pickerFolders = transform(it.favoriteFolders)
            it.copy(favoriteFolders = pickerFolders,
                selectedFavoriteFolderId = it.selectedFavoriteFolderId?.takeIf { id -> pickerFolders.any { folder -> folder.id == id } })
        }
    }

    private fun replaceFolders(folders: List<GlamourFavoriteFolder>) {
        mutableState.update { state ->
            state.copy(folders = folders, foldersResolved = true,
                selectedFolderId = state.selectedFolderId?.takeIf { id -> folders.any { it.id == id } }
                    ?: folders.firstOrNull { it.isDefault }?.id ?: folders.firstOrNull()?.id)
        }
    }

    fun retryFolderRefresh() {
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        val target = mutableInteractionState.value.favoriteTargetId
        if (!canManageFolders && target == null) return
        val epoch = interactionEpoch
        val foldersGeneration = if (canManageFolders) ++folderGeneration else folderGeneration
        val pickerGeneration = if (target != null) ++favoritePickerGeneration else favoritePickerGeneration
        if (target != null) favoritePickerJob?.cancel()
        if (canManageFolders) mutableState.update { it.copy(isLoadingFolders = true) }
        mutableInteractionState.update {
            it.copy(folderRefreshError = null, isLoadingFavoriteFolders = target != null,
                favoriteFolderError = if (target != null) null else it.favoriteFolderError)
        }
        val job = viewModelScope.launch {
            try {
                val folders = service.fetchFavoriteFolders(null)
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch) return@launch
                if (canManageFolders && foldersGeneration == folderGeneration) replaceFolders(folders)
                if (target != null && pickerGeneration == favoritePickerGeneration) {
                    mutableInteractionState.update {
                        if (it.favoriteTargetId == target) it.copy(favoriteFolders = folders,
                            selectedFavoriteFolderId = it.selectedFavoriteFolderId?.takeIf { id -> folders.any { folder -> folder.id == id } }) else it
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (epoch != interactionEpoch || handleAuthenticationFailure(error)) return@launch
                if ((canManageFolders && foldersGeneration == folderGeneration) ||
                    (target != null && pickerGeneration == favoritePickerGeneration)
                ) mutableInteractionState.update { it.copy(folderRefreshError = error.message) }
            } finally {
                if (epoch == interactionEpoch) {
                    if (canManageFolders && foldersGeneration == folderGeneration) mutableState.update { it.copy(isLoadingFolders = false) }
                    if (target != null && pickerGeneration == favoritePickerGeneration) {
                        mutableInteractionState.update { it.copy(isLoadingFavoriteFolders = false) }
                    }
                }
            }
        }
        if (target != null) favoritePickerJob = job
    }

    private fun resetCouponPresentation(id: Int?) {
        mutableInteractionState.update {
            it.copy(isClaimingCoupon = id in claimingCouponIds, couponError = null, couponClaimedNotice = false)
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
        val generation = ++folderGeneration
        mutableState.update { it.copy(isLoadingFolders = true) }
        try {
            val folders = service.fetchFavoriteFolders(profileAuthor?.id)
            currentCoroutineContext().ensureActive()
            if (generation == folderGeneration) replaceFolders(folders)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            if (generation != folderGeneration) return
            throw error
        } finally {
            if (generation == folderGeneration) mutableState.update { it.copy(isLoadingFolders = false) }
        }
    }

    fun loadRaces() {
        if (mutableState.value.isLoadingRaces) return
        mutableState.update { it.copy(isLoadingRaces = true, raceError = null) }
        viewModelScope.launch {
            try {
                val races = service.fetchRaces()
                currentCoroutineContext().ensureActive()
                mutableState.update {
                    it.copy(races = races, isLoadingRaces = false, raceError = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (handleAuthenticationFailure(error)) return@launch
                mutableState.update {
                    it.copy(isLoadingRaces = false, raceError = error.message)
                }
            }
        }
    }

    fun loadBrowsingCatalog() {
        val browsing = browsingService ?: return
        if (!hasCommunityIdentity) {
            clearAuthenticationState()
            return
        }
        if (mutableBrowsingState.value.isLoadingCatalog) return
        if (mutableState.value.raceError != null && !mutableState.value.isLoadingRaces) loadRaces()
        val generation = ++catalogGeneration
        catalogJob?.cancel()
        mutableBrowsingState.update { it.copy(isLoadingCatalog = true, catalogError = null) }
        catalogJob = viewModelScope.launch {
            try {
                val categories = browsing.fetchTagCategories()
                currentCoroutineContext().ensureActive()
                val tribes = browsing.fetchTribes()
                currentCoroutineContext().ensureActive()
                if (generation != catalogGeneration) return@launch
                catalogLoaded = true
                val previous = mutableBrowsingState.value
                val validTags = selectedTags(previous.tagIds, categories)
                val validTribe = previous.tribeId?.takeIf { id ->
                    id > 0 && tribes.any { it.id == id && it.raceId == mutableState.value.filter.raceId }
                }
                mutableBrowsingState.update {
                    it.copy(
                        tagCategories = categories,
                        tribes = tribes,
                        tagIds = validTags,
                        tribeId = validTribe,
                        isLoadingCatalog = false,
                        catalogError = null,
                    )
                }
                if (validTags != previous.tagIds || validTribe != previous.tribeId) {
                    nextPageTime = null
                    clearSelection()
                    mutableState.update { it.copy(items = emptyList(), page = 1, hasNextPage = false) }
                    refresh()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (generation != catalogGeneration || handleAuthenticationFailure(error)) return@launch
                mutableBrowsingState.update { it.copy(catalogError = error.message) }
            } finally {
                if (generation == catalogGeneration) {
                    mutableBrowsingState.update { it.copy(isLoadingCatalog = false) }
                }
            }
        }
    }

    private fun loadProfileStatistics() {
        if (mutableState.value.isLoadingProfileStatistics) return
        mutableState.update { it.copy(isLoadingProfileStatistics = true) }
        viewModelScope.launch {
            try {
                val stats = service.fetchProfileStatistics(profileAuthor?.id)
                currentCoroutineContext().ensureActive()
                mutableState.update {
                    it.copy(
                        profileStatistics = stats,
                        isLoadingProfileStatistics = false,
                        profileStatisticsResolved = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (handleAuthenticationFailure(error)) return@launch
                mutableState.update {
                    it.copy(
                        isLoadingProfileStatistics = false,
                        profileStatisticsResolved = true,
                    )
                }
            }
        }
    }

    private fun loadAuthorProfile() {
        val authorId = profileAuthor?.id?.takeIf(String::isNotBlank) ?: return
        if (mutableState.value.isLoadingAuthorProfile) return
        mutableState.update { it.copy(isLoadingAuthorProfile = true, followError = null) }
        viewModelScope.launch {
            try {
                val profile = service.fetchAuthorProfile(authorId)
                currentCoroutineContext().ensureActive()
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
                currentCoroutineContext().ensureActive()
                if (handleAuthenticationFailure(error)) return@launch
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

    private fun handleAuthenticationFailure(error: Throwable): Boolean {
        if (error !is GlamourException.AuthenticationRequired) return false
        clearAuthenticationState()
        return true
    }

    private fun clearAuthenticationState() {
        ++listGeneration
        ++catalogGeneration
        ++interactionEpoch
        ++favoritePickerGeneration
        ++folderGeneration
        viewModelScope.coroutineContext.cancelChildren()
        claimingCouponIds.clear()
        claimedCouponIds.clear()
        favoriteMutations.clear()
        favoriteMutationRevision = 0
        mutableInteractionState.value = GlamourInteractionUiState()
        nextPageTime = null
        catalogLoaded = false
        mutableBrowsingState.value = GlamourBrowsingUiState()
        mutableState.update {
            GlamourUiState(
                source = it.source,
                filter = it.filter,
                search = it.search,
                listError = GlamourException.AuthenticationRequired.message,
                error = GlamourException.AuthenticationRequired.message,
            )
        }
    }

    private fun clearBrowsingSelection() {
        nextPageTime = null
        mutableBrowsingState.update { it.copy(following = false, tribeId = null, tagIds = emptySet()) }
    }

    private fun selectedTags(ids: Set<Int>, categories: List<GlamourTagCategory>): Set<Int> {
        val previous = mutableBrowsingState.value.tagIds
        return categories.mapNotNull { category ->
            category.tags.firstOrNull { it.id > 0 && it.id in ids && it.id !in previous }?.id
                ?: category.tags.firstOrNull { it.id > 0 && it.id in ids }?.id
        }.toSet()
    }

    private fun defaultFilter(source: GlamourListSource): GlamourFilter = GlamourFilter(
        order = if (source == GlamourListSource.Profile) GlamourListOrder.Latest else GlamourListOrder.Default,
    )

    private fun normalizeFilter(
        filter: GlamourFilter,
        tribeId: Int?,
        tagIds: Set<Int>,
        source: GlamourListSource,
    ): GlamourFilter {
        val hasFilters = filter.raceId != null || filter.genderId != null ||
            !filter.createTime.isNullOrBlank() || tribeId != null || tagIds.isNotEmpty()
        return if (filter.order == GlamourListOrder.Default && (hasFilters || source == GlamourListSource.Profile)) {
            filter.copy(order = GlamourListOrder.Latest)
        } else filter
    }

    private fun browseRequest(listing: GlamourListRequest, pageTime: String?): GlamourBrowseRequest {
        val browsing = mutableBrowsingState.value
        val filterable = listing.search == null
        val community = listing.source == GlamourListSource.Community && filterable
        return GlamourBrowseRequest(
            listing = listing,
            following = community && browsing.following,
            tribeId = browsing.tribeId.takeIf { community && !browsing.following },
            tagIds = if (filterable && !browsing.following) browsing.tagIds else emptySet(),
            pageTime = pageTime,
        )
    }

    private suspend fun fetchPage(request: GlamourBrowseRequest): GlamourBrowsePage =
        browsingService?.fetchBrowsePage(request)
            ?: GlamourBrowsePage(service.fetchGlamours(request.listing), null)

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
