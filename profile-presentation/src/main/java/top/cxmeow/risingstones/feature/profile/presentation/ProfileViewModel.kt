package top.cxmeow.risingstones.feature.profile.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.profile.domain.*

enum class ProfileLoadStatus { Idle, Loading, Loaded, Failed, AuthenticationRequired, Unavailable }
data class ProfileUiState(
    val owner: ProfileOwner = ProfileOwner.Self,
    val profile: CommunityProfile? = null,
    val profileStatus: ProfileLoadStatus = ProfileLoadStatus.Idle,
    val section: ProfileSection = ProfileSection.Overview,
    val items: List<ProfileListItem> = emptyList(),
    val listStatus: ProfileLoadStatus = ProfileLoadStatus.Idle,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val canNavigateBack: Boolean = false,
)

class ProfileViewModel(private val service: ProfileService) : ViewModel() {
    private val mutableState = MutableStateFlow(ProfileUiState())
    val state = mutableState.asStateFlow()
    private val history = ArrayDeque<NavigationEntry>()
    private val sections = mutableMapOf<ProfileSection, SectionSnapshot>()
    private var rootOwner: ProfileOwner? = null
    private var profileJob: Job? = null
    private var listJob: Job? = null
    private var moreJob: Job? = null
    private var profileGeneration = 0L
    private var listGeneration = 0L

    fun ensureLoaded() {
        if (state.value.profileStatus == ProfileLoadStatus.Idle) refreshProfile()
    }

    /** Opens an external reading root without adding the unopened default profile to history. */
    fun openRoot(owner: ProfileOwner) {
        if (rootOwner == owner) return
        cancelRequests()
        history.clear()
        sections.clear()
        rootOwner = owner
        mutableState.value = ProfileUiState(owner = owner)
        ensureLoaded()
    }

    fun open(owner: ProfileOwner) {
        if (owner == state.value.owner) { ensureLoaded(); return }
        if (rootOwner == null) rootOwner = state.value.owner
        history.addLast(NavigationEntry(state.value, sections.toMap()))
        cancelRequests()
        sections.clear()
        mutableState.value = ProfileUiState(owner = owner, canNavigateBack = true)
        ensureLoaded()
    }

    fun navigateBack(): Boolean {
        if (history.isEmpty()) return false
        cancelRequests()
        val entry = history.removeLast()
        val previous = entry.state
        sections.clear()
        sections.putAll(entry.sections)
        mutableState.value = previous.copy(canNavigateBack = history.isNotEmpty(), loadingMore = false,
            profileStatus = if (previous.profileStatus == ProfileLoadStatus.Loading) ProfileLoadStatus.Idle else previous.profileStatus,
            listStatus = if (previous.listStatus == ProfileLoadStatus.Loading) ProfileLoadStatus.Idle else previous.listStatus)
        ensureLoaded()
        // Returning must not silently acknowledge followers again.
        if (state.value.section !in listOf(ProfileSection.Overview, ProfileSection.Followers) &&
            state.value.listStatus == ProfileLoadStatus.Idle) refreshSection()
        return true
    }

    fun refreshProfile() {
        if (!service.canRead) { deny(ProfileLoadStatus.Unavailable); return }
        val owner = state.value.owner
        val generation = ++profileGeneration
        profileJob?.cancel()
        mutableState.update { it.copy(profileStatus = ProfileLoadStatus.Loading) }
        profileJob = viewModelScope.launch {
            try {
                val profile = service.fetchProfile(owner)
                if (generation == profileGeneration) mutableState.update {
                    it.copy(profile = profile, profileStatus = ProfileLoadStatus.Loaded)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == profileGeneration && !accessFailure(error)) {
                    mutableState.update { it.copy(profileStatus = ProfileLoadStatus.Failed) }
                }
            }
        }
    }

    fun selectSection(section: ProfileSection) {
        if (section == state.value.section) return
        if (state.value.owner != ProfileOwner.Self && section in
            listOf(ProfileSection.FavoritePosts, ProfileSection.FavoriteGuides)) return
        listGeneration++
        listJob?.cancel()
        moreJob?.cancel()
        sections[state.value.section] = SectionSnapshot.from(state.value)
        val restored = sections[section] ?: SectionSnapshot()
        mutableState.update { restored.restore(it, section) }
        if (section != ProfileSection.Overview && state.value.listStatus == ProfileLoadStatus.Idle) refreshSection()
    }

    /** Explicit refresh; a followers refresh can acknowledge new followers. */
    fun refreshSection() {
        val snapshot = state.value
        if (snapshot.section == ProfileSection.Overview) { refreshProfile(); return }
        if (!service.canRead) { deny(ProfileLoadStatus.Unavailable); return }
        val generation = ++listGeneration
        listJob?.cancel()
        moreJob?.cancel()
        mutableState.update { it.copy(listStatus = ProfileLoadStatus.Loading, loadingMore = false, loadMoreFailed = false) }
        listJob = viewModelScope.launch {
            try {
                val page = service.readSection(ProfileListQuery(snapshot.owner, snapshot.section))
                if (generation == listGeneration) mutableState.update { it.copy(items = page.items.distinctBy { item -> item.key },
                    listStatus = ProfileLoadStatus.Loaded, page = page.page, hasMore = page.hasMore) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == listGeneration && !accessFailure(error)) mutableState.update { it.copy(listStatus = ProfileLoadStatus.Failed) }
            }
        }
    }

    fun loadMore() {
        val snapshot = state.value
        if (!snapshot.hasMore || snapshot.loadingMore || snapshot.listStatus != ProfileLoadStatus.Loaded) return
        if (!service.canRead) { deny(ProfileLoadStatus.Unavailable); return }
        val generation = listGeneration
        mutableState.update { it.copy(loadingMore = true, loadMoreFailed = false) }
        moreJob = viewModelScope.launch {
            try {
                val page = service.readSection(ProfileListQuery(snapshot.owner, snapshot.section, snapshot.page + 1))
                if (generation == listGeneration) mutableState.update {
                    val merged = (it.items + page.items).distinctBy { item -> item.key }
                    it.copy(items = merged, page = page.page, loadingMore = false,
                        hasMore = page.hasMore && merged.size > it.items.size)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == listGeneration && !accessFailure(error)) mutableState.update { it.copy(loadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    fun clearProtectedContent() {
        cancelRequests()
        history.clear()
        sections.clear()
        rootOwner = null
        mutableState.value = ProfileUiState()
    }

    override fun onCleared() {
        clearProtectedContent()
        super.onCleared()
    }
    private fun accessFailure(error: Exception): Boolean = when (error) {
        ProfileException.AuthenticationRequired -> { deny(ProfileLoadStatus.AuthenticationRequired); true }
        ProfileException.Unavailable -> { deny(ProfileLoadStatus.Unavailable); true }
        else -> false
    }
    private fun deny(status: ProfileLoadStatus) {
        clearProtectedContent()
        mutableState.value = ProfileUiState(profileStatus = status, listStatus = status)
    }
    private fun cancelRequests() {
        profileGeneration++
        listGeneration++
        profileJob?.cancel()
        listJob?.cancel()
        moreJob?.cancel()
    }

    private data class NavigationEntry(val state: ProfileUiState,
        val sections: Map<ProfileSection, SectionSnapshot>)

    private data class SectionSnapshot(
        val items: List<ProfileListItem> = emptyList(),
        val status: ProfileLoadStatus = ProfileLoadStatus.Idle,
        val page: Int = 1,
        val hasMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
    ) {
        fun restore(state: ProfileUiState, section: ProfileSection) = state.copy(
            section = section, items = items, listStatus = status, page = page,
            hasMore = hasMore, loadingMore = false, loadMoreFailed = loadMoreFailed)

        companion object {
            fun from(state: ProfileUiState) = SectionSnapshot(state.items,
                if (state.listStatus == ProfileLoadStatus.Loading) ProfileLoadStatus.Idle else state.listStatus,
                state.page, state.hasMore, state.loadMoreFailed)
        }
    }
}
