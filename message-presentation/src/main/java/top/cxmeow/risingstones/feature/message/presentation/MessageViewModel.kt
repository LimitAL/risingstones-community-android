package top.cxmeow.risingstones.feature.message.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.message.domain.*

enum class MessageLoadStatus { Idle, Loading, Loaded, Failed, AuthenticationRequired, Unavailable }

data class MessageUiState(
    val unread: MessageUnreadSummary? = null,
    val summaryStatus: MessageLoadStatus = MessageLoadStatus.Idle,
    val query: MessageQuery? = null,
    val items: List<CommunityMessage> = emptyList(),
    val status: MessageLoadStatus = MessageLoadStatus.Idle,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val failureCode: Int? = null,
    val selectedKey: String? = null,
)

class MessageViewModel(private val service: MessageService) : ViewModel() {
    private val mutableState = MutableStateFlow(MessageUiState())
    val state = mutableState.asStateFlow()
    private val mutableAuthorState = MutableStateFlow(MessageAuthorState())
    val authorState = mutableAuthorState.asStateFlow()
    private var summaryJob: Job? = null
    private var listJob: Job? = null
    private var moreJob: Job? = null
    private var summaryGeneration = 0L
    private var listGeneration = 0L

    fun ensureLoaded() {
        if (state.value.summaryStatus == MessageLoadStatus.Idle) refreshSummary()
    }

    fun clearProtectedContent() {
        summaryGeneration++
        listGeneration++
        summaryJob?.cancel()
        listJob?.cancel()
        moreJob?.cancel()
        mutableState.value = MessageUiState()
        mutableAuthorState.value = MessageAuthorState()
    }

    fun refreshSummary() {
        if (!service.canRead) { deny(MessageLoadStatus.Unavailable); return }
        val generation = ++summaryGeneration
        summaryJob?.cancel()
        mutableState.update { it.copy(summaryStatus = MessageLoadStatus.Loading) }
        summaryJob = viewModelScope.launch {
            try {
                val unread = service.fetchUnreadSummary()
                if (generation == summaryGeneration) mutableState.update {
                    it.copy(unread = unread, summaryStatus = MessageLoadStatus.Loaded)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == summaryGeneration && !accessFailure(error)) {
                    mutableState.update { it.copy(summaryStatus = MessageLoadStatus.Failed) }
                }
            }
        }
    }

    /** Selecting a category is an explicit request to read and possibly acknowledge its messages. */
    fun selectCategory(category: MessageCategory) = selectQuery(MessageQuery(category))

    fun selectCommentChannel(channel: MessageCommentChannel) {
        val query = state.value.query ?: return
        if (query.category == MessageCategory.Comments && query.commentChannel != channel) {
            selectQuery(query.copy(commentChannel = channel, page = 1))
        }
    }

    fun selectRecruitmentChannel(channel: MessageRecruitmentChannel) {
        val query = state.value.query ?: return
        if (query.category in listOf(MessageCategory.Recruitment, MessageCategory.Responses) && query.recruitmentChannel != channel) {
            selectQuery(query.copy(recruitmentChannel = channel, page = 1))
        }
    }

    private fun selectQuery(query: MessageQuery) {
        mutableAuthorState.value = MessageAuthorState()
        mutableState.update { it.copy(query = query, items = emptyList(), selectedKey = null, hasMore = false) }
        refreshMessages()
    }

    fun clearCategory() {
        mutableAuthorState.value = MessageAuthorState()
        listGeneration++
        listJob?.cancel()
        moreJob?.cancel()
        mutableState.update { it.copy(query = null, items = emptyList(), selectedKey = null,
            status = MessageLoadStatus.Idle, loadingMore = false, loadMoreFailed = false, hasMore = false) }
    }

    fun selectMessage(key: String) {
        if (state.value.items.none { it.key == key }) return
        mutableState.update { it.copy(selectedKey = if (it.selectedKey == key) null else key) }
    }

    fun refreshMessages() {
        val query = state.value.query?.copy(page = 1) ?: return
        if (!service.canRead) { deny(MessageLoadStatus.Unavailable); return }
        val generation = ++listGeneration
        listJob?.cancel()
        moreJob?.cancel()
        mutableState.update { it.copy(status = MessageLoadStatus.Loading, loadingMore = false,
            loadMoreFailed = false, failureCode = null) }
        listJob = viewModelScope.launch {
            try {
                val result = readWithAuthors(query)
                val page = result.page
                if (generation != listGeneration) return@launch
                mutableAuthorState.value = MessageAuthorState(result.authorsByKey.filterKeys { key -> page.items.any { it.key == key } })
                mutableState.update { it.copy(items = page.items.distinctBy(CommunityMessage::key),
                    status = MessageLoadStatus.Loaded, page = page.page, hasMore = page.hasMore,
                    selectedKey = it.selectedKey?.takeIf { key -> page.items.any { item -> item.key == key } }) }
                refreshSummary()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == listGeneration && !accessFailure(error)) mutableState.update {
                    it.copy(status = MessageLoadStatus.Failed, failureCode = (error as? MessageException.Business)?.code)
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = state.value
        val query = snapshot.query ?: return
        if (!snapshot.hasMore || snapshot.loadingMore || snapshot.status != MessageLoadStatus.Loaded) return
        val generation = listGeneration
        mutableState.update { it.copy(loadingMore = true, loadMoreFailed = false) }
        moreJob = viewModelScope.launch {
            try {
                val result = readWithAuthors(query.copy(page = snapshot.page + 1))
                val page = result.page
                if (generation != listGeneration) return@launch
                val existingKeys = state.value.items.mapTo(hashSetOf(), CommunityMessage::key)
                mutableAuthorState.update { authors ->
                    authors.copy(authorsByKey = authors.authorsByKey + result.authorsByKey.filterKeys { key ->
                        key !in existingKeys && page.items.any { it.key == key }
                    })
                }
                mutableState.update {
                    val merged = (it.items + page.items).distinctBy(CommunityMessage::key)
                    it.copy(items = merged, page = page.page, loadingMore = false,
                        hasMore = page.hasMore && merged.size > it.items.size)
                }
                refreshSummary()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == listGeneration && !accessFailure(error)) mutableState.update {
                    it.copy(loadingMore = false, loadMoreFailed = true)
                }
            }
        }
    }

    private suspend fun readWithAuthors(query: MessageQuery): MessageAuthorPage =
        (service as? MessageAuthorService)?.readMessagesWithAuthors(query)
            ?: MessageAuthorPage(service.readMessages(query), emptyMap())

    private fun accessFailure(error: Exception): Boolean = when (error) {
        MessageException.AuthenticationRequired -> { deny(MessageLoadStatus.AuthenticationRequired); true }
        MessageException.Unavailable -> { deny(MessageLoadStatus.Unavailable); true }
        else -> false
    }

    private fun deny(status: MessageLoadStatus) {
        clearProtectedContent()
        mutableState.value = MessageUiState(status = status, summaryStatus = status)
    }
}
