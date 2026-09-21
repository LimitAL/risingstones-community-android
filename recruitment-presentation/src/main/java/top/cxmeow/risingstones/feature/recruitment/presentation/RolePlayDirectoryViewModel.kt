package top.cxmeow.risingstones.feature.recruitment.presentation

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
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentException
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentService
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivity
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivityDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayDirectoryService
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberQuery
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentMember

enum class RolePlayDirectorySection { Members, Activities }

data class RolePlayMemberDirectoryState(
    val items: List<RolePlayRecruitmentMember> = emptyList(),
    val selectedId: Int? = null,
    val detail: RolePlayMemberDetail? = null,
    val hasLoaded: Boolean = false,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: RecruitmentInteractionError? = null,
    val errorIsPagination: Boolean = false,
    val isLoadingDetail: Boolean = false,
    val detailError: RecruitmentInteractionError? = null,
)

data class RolePlayActivityDirectoryState(
    val items: List<RolePlayActivity> = emptyList(),
    val selectedId: Int? = null,
    val detail: RolePlayActivityDetail? = null,
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: RecruitmentInteractionError? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: RecruitmentInteractionError? = null,
)

data class RolePlayDirectoryState(
    val parentId: Int? = null,
    val isOpen: Boolean = false,
    val section: RolePlayDirectorySection = RolePlayDirectorySection.Members,
    val members: RolePlayMemberDirectoryState = RolePlayMemberDirectoryState(),
    val activities: RolePlayActivityDirectoryState = RolePlayActivityDirectoryState(),
)

/** Explicitly opened reading state; construction and parent binding never issue requests. */
class RolePlayDirectoryViewModel(private val service: DutyRecruitmentService) : ViewModel() {
    private val mutableState = MutableStateFlow(RolePlayDirectoryState())
    val state: StateFlow<RolePlayDirectoryState> = mutableState.asStateFlow()
    private var cleared = false
    val supportsDirectory: Boolean get() = !cleared && service is RolePlayDirectoryService
    private var memberPageTime: String? = null
    private var revision = 0L
    private val requests = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()

    fun setParent(rpId: Int?) {
        if (cleared) return
        val id = rpId?.takeIf { it > 0 }
        if (state.value.parentId == id) return
        reset()
        mutableState.value = RolePlayDirectoryState(parentId = id)
    }

    fun open(rpId: Int, section: RolePlayDirectorySection) {
        if (cleared || rpId <= 0) return
        setParent(rpId)
        mutableState.update { it.copy(isOpen = true, section = section) }
        ensureLoaded()
    }

    /** Hiding the reader preserves its current parent's pages and selections. */
    fun close() {
        mutableState.update { it.copy(isOpen = false) }
    }

    fun selectSection(section: RolePlayDirectorySection) {
        if (cleared || state.value.parentId == null) return
        mutableState.update { it.copy(section = section) }
        if (state.value.isOpen) ensureLoaded()
    }

    fun refresh() {
        if (cleared || state.value.parentId == null) return
        when (state.value.section) {
            RolePlayDirectorySection.Members -> loadMembers(append = false)
            RolePlayDirectorySection.Activities -> loadActivities()
        }
    }

    fun loadMore() {
        if (cleared || state.value.section != RolePlayDirectorySection.Members) return
        val members = state.value.members
        if (!members.hasMore || members.isLoading || members.isRefreshing || members.isLoadingMore) return
        loadMembers(append = true)
    }

    fun selectMember(id: Int) {
        if (cleared || state.value.members.items.none { it.id == id }) return
        val current = state.value.members
        if (current.selectedId == id && (current.detail != null || current.isLoadingDetail)) return
        cancel("memberDetail")
        mutableState.update { it.copy(members = it.members.copy(selectedId = id, detail = null, detailError = null, isLoadingDetail = false)) }
        loadMemberDetail()
    }

    fun selectActivity(id: Int) {
        if (cleared || state.value.activities.items.none { it.id == id }) return
        val current = state.value.activities
        if (current.selectedId == id && (current.detail != null || current.isLoadingDetail)) return
        cancel("activityDetail")
        mutableState.update { it.copy(activities = it.activities.copy(selectedId = id, detail = null, detailError = null, isLoadingDetail = false)) }
        loadActivityDetail()
    }

    fun clearSelection() {
        when (state.value.section) {
            RolePlayDirectorySection.Members -> {
                cancel("memberDetail")
                mutableState.update { it.copy(members = it.members.copy(selectedId = null, detail = null, isLoadingDetail = false, detailError = null)) }
            }
            RolePlayDirectorySection.Activities -> {
                cancel("activityDetail")
                mutableState.update { it.copy(activities = it.activities.copy(selectedId = null, detail = null, isLoadingDetail = false, detailError = null)) }
            }
        }
    }

    fun retryDetail() {
        when (state.value.section) {
            RolePlayDirectorySection.Members -> loadMemberDetail()
            RolePlayDirectorySection.Activities -> loadActivityDetail()
        }
    }

    fun clearProtectedContent() {
        reset()
        mutableState.value = RolePlayDirectoryState()
    }

    override fun onCleared() {
        clearProtectedContent()
        cleared = true
        super.onCleared()
    }

    private fun ensureLoaded() {
        when (state.value.section) {
            RolePlayDirectorySection.Members -> state.value.members.let {
                if (!it.hasLoaded && !it.isLoading && !it.isRefreshing && it.error == null) loadMembers(append = false)
            }
            RolePlayDirectorySection.Activities -> state.value.activities.let {
                if (!it.hasLoaded && !it.isLoading && !it.isRefreshing && it.error == null) loadActivities()
            }
        }
    }

    private fun loadMembers(append: Boolean) {
        val parent = state.value.parentId ?: return
        val directory = service as? RolePlayDirectoryService
        if (cleared || directory == null) {
            if (!cleared) mutableState.update { it.copy(members = it.members.copy(error = RecruitmentInteractionError.Unavailable)) }
            return
        }
        val current = state.value.members
        val query = RolePlayMemberQuery(parent, if (append) current.page + 1 else 1, MemberPageSize,
            if (append) memberPageTime else null)
        mutableState.update { it.copy(members = it.members.copy(
            isLoading = !append && !current.hasLoaded, isRefreshing = !append && current.hasLoaded,
            isLoadingMore = append, error = null, errorIsPagination = false)) }
        launchRequest("members", onFailure = { error ->
            mutableState.update { it.copy(members = it.members.copy(error = error, errorIsPagination = append)) }
        }, onFinish = {
            mutableState.update { it.copy(members = it.members.copy(isLoading = false, isRefreshing = false, isLoadingMore = false)) }
        }) { request ->
            val result = directory.fetchRolePlayMemberPage(query)
            if (!request.accept()) return@launchRequest
            // The first page's snapshot is reused for every later page, including after a failed refresh.
            if (!append) memberPageTime = result.pageTime
            mutableState.update {
                val old = if (append) it.members.items else emptyList()
                val items = (old + result.items).distinctBy(RolePlayRecruitmentMember::id)
                it.copy(members = it.members.copy(items = items, page = result.page, hasLoaded = true,
                    hasMore = result.hasMore && (!append || result.page > it.members.page)))
            }
        }
    }

    private fun loadActivities() {
        val parent = state.value.parentId ?: return
        val directory = service as? RolePlayDirectoryService
        if (cleared || directory == null) {
            if (!cleared) mutableState.update { it.copy(activities = it.activities.copy(error = RecruitmentInteractionError.Unavailable)) }
            return
        }
        val loaded = state.value.activities.hasLoaded
        mutableState.update { it.copy(activities = it.activities.copy(isLoading = !loaded, isRefreshing = loaded, error = null)) }
        launchRequest("activities", onFailure = { error ->
            mutableState.update { it.copy(activities = it.activities.copy(error = error)) }
        }, onFinish = {
            mutableState.update { it.copy(activities = it.activities.copy(isLoading = false, isRefreshing = false)) }
        }) { request ->
            val items = directory.fetchRolePlayActivities(parent)
            if (request.accept()) mutableState.update {
                it.copy(activities = it.activities.copy(items = items.distinctBy(RolePlayActivity::id), hasLoaded = true))
            }
        }
    }

    private fun loadMemberDetail() {
        val parent = state.value.parentId ?: return
        val id = state.value.members.selectedId ?: return
        val directory = service as? RolePlayDirectoryService ?: return
        if (cleared) return
        mutableState.update { it.copy(members = it.members.copy(isLoadingDetail = true, detailError = null)) }
        launchRequest("memberDetail", onFailure = { error ->
            mutableState.update { it.copy(members = it.members.copy(detailError = error)) }
        }, onFinish = {
            mutableState.update { it.copy(members = it.members.copy(isLoadingDetail = false)) }
        }) { request ->
            val detail = directory.fetchRolePlayMemberDetail(id)
            if (!request.accept()) return@launchRequest
            mutableState.update {
                if (detail.id != id || (detail.recruitmentId != null && detail.recruitmentId != parent)) {
                    it.copy(members = it.members.copy(detailError = RecruitmentInteractionError.InvalidInput))
                } else it.copy(members = it.members.copy(detail = detail))
            }
        }
    }

    private fun loadActivityDetail() {
        val parent = state.value.parentId ?: return
        val id = state.value.activities.selectedId ?: return
        val directory = service as? RolePlayDirectoryService ?: return
        if (cleared) return
        mutableState.update { it.copy(activities = it.activities.copy(isLoadingDetail = true, detailError = null)) }
        launchRequest("activityDetail", onFailure = { error ->
            mutableState.update { it.copy(activities = it.activities.copy(detailError = error)) }
        }, onFinish = {
            mutableState.update { it.copy(activities = it.activities.copy(isLoadingDetail = false)) }
        }) { request ->
            val detail = directory.fetchRolePlayActivityDetail(id)
            if (!request.accept()) return@launchRequest
            mutableState.update {
                if (detail.activity.id != id || (detail.activity.recruitmentId != null && detail.activity.recruitmentId != parent)) {
                    it.copy(activities = it.activities.copy(detailError = RecruitmentInteractionError.InvalidInput))
                } else it.copy(activities = it.activities.copy(detail = detail))
            }
        }
    }

    private inner class Request(private val key: String, private val version: Long) {
        fun isCurrent(): Boolean = !cleared && requests[key] == version
        suspend fun accept(): Boolean {
            currentCoroutineContext().ensureActive()
            return isCurrent()
        }
    }

    private fun launchRequest(key: String, onFailure: (RecruitmentInteractionError) -> Unit,
        onFinish: () -> Unit, block: suspend (Request) -> Unit) {
        cancel(key)
        val request = Request(key, ++revision)
        requests[key] = revision
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (request.isCurrent()) {
                    if (error is DutyRecruitmentException.AuthenticationRequired) {
                        val navigation = state.value
                        clearProtectedContent()
                        mutableState.value = RolePlayDirectoryState(
                            parentId = navigation.parentId, isOpen = navigation.isOpen, section = navigation.section,
                            members = RolePlayMemberDirectoryState(error = RecruitmentInteractionError.AuthenticationRequired
                                .takeIf { navigation.section == RolePlayDirectorySection.Members }),
                            activities = RolePlayActivityDirectoryState(error = RecruitmentInteractionError.AuthenticationRequired
                                .takeIf { navigation.section == RolePlayDirectorySection.Activities }),
                        )
                    } else onFailure(RecruitmentInteractionError.Failed)
                }
            } finally {
                if (request.isCurrent()) {
                    onFinish()
                    requests.remove(key)
                    jobs.remove(key)
                }
            }
        }
        jobs[key] = job
        job.start()
    }

    private fun cancel(key: String) {
        requests.remove(key)
        jobs.remove(key)?.cancel()
    }

    private fun reset() {
        requests.keys.toList().forEach(::cancel)
        memberPageTime = null
    }

    private companion object {
        const val MemberPageSize = 10
    }
}

class RolePlayDirectoryViewModelFactory(private val service: DutyRecruitmentService) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RolePlayDirectoryViewModel(service) as T
}
