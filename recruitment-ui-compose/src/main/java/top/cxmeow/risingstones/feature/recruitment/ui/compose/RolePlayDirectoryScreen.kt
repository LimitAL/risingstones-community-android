package top.cxmeow.risingstones.feature.recruitment.ui.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RolePlayDirectoryLinks(onOpen: (RolePlayDirectorySection) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onOpen(RolePlayDirectorySection.Members) },
            modifier = Modifier.testTag("recruitment-open-members")) {
            Text(stringResource(R.string.recruitment_view_all_members))
        }
        OutlinedButton(onClick = { onOpen(RolePlayDirectorySection.Activities) },
            modifier = Modifier.testTag("recruitment-open-activities")) {
            Text(stringResource(R.string.recruitment_activities))
        }
    }
}

@Composable
internal fun RecruitmentDirectoryNavigation(service: DutyRecruitmentService, source: DutyRecruitmentUiState,
    scopeKey: String): ((RolePlayDirectorySection) -> Unit)? {
    if (service !is RolePlayDirectoryService) return null
    val owner: RolePlayDirectoryRouteStore = viewModel(
        key = "roleplay-directory-" + scopeKey + "-" + System.identityHashCode(service),
        factory = RolePlayDirectoryStoreFactory,
    )
    val activity = LocalContext.current.directoryActivity()
    DisposableEffect(owner, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear() }
    }
    val model: RolePlayDirectoryViewModel = viewModel(viewModelStoreOwner = owner,
        factory = remember(service) { RolePlayDirectoryViewModelFactory(service) })
    val directory by model.state.collectAsStateWithLifecycle()
    val parentId = source.selectedId?.takeIf {
        source.board == RecruitmentBoardKind.RolePlay && source.communityDetail?.summary?.id == it
    }
    val savedState = rememberSaveableStateHolder()
    val hostDensity = LocalDensity.current
    val hostUriHandler = LocalUriHandler.current
    var previousParent by remember { mutableStateOf(parentId) }
    LaunchedEffect(parentId) {
        model.setParent(parentId)
        if (previousParent != parentId) previousParent?.let(savedState::removeState)
        previousParent = parentId
    }
    if (parentId != null && directory.parentId == parentId && directory.isOpen) {
        savedState.SaveableStateProvider(parentId) {
            Dialog(onDismissRequest = { directoryNavigateBack(model, model::close) },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                CompositionLocalProvider(LocalDensity provides hostDensity, LocalUriHandler provides hostUriHandler) {
                    Surface(Modifier.fillMaxSize().testTag("roleplay-directory")) {
                        RisingStonesRolePlayDirectoryScreen(model, onNavigateBack = model::close)
                    }
                }
            }
        }
    }
    return parentId?.let { id -> { section -> model.open(id, section) } }
}

/** Native member and activity reading UI; the host explicitly opens the model's parent directory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesRolePlayDirectoryScreen(
    viewModel: RolePlayDirectoryViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = { directoryNavigateBack(viewModel, onNavigateBack) }
    BackHandler(onBack = back)
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.recruitment_roleplay_directory)) },
            navigationIcon = {
                TextButton(onClick = back, modifier = Modifier.testTag("roleplay-directory-back")) {
                    Text(stringResource(R.string.recruitment_back))
                }
            },
            actions = {
                TextButton(onClick = viewModel::refresh, modifier = Modifier.testTag("roleplay-directory-refresh")) {
                    Text(stringResource(R.string.recruitment_refresh))
                }
            })
    }) { padding ->
        key(state.parentId) {
            val memberScroll = rememberLazyListState()
            val activityScroll = rememberLazyListState()
            val memberDetailScroll = rememberSaveable(state.members.selectedId, saver = LazyListState.Saver) { LazyListState() }
            val activityDetailScroll = rememberSaveable(state.activities.selectedId, saver = LazyListState.Saver) { LazyListState() }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RolePlayDirectorySection.entries.forEach { section ->
                        FilterChip(selected = state.section == section, onClick = { viewModel.selectSection(section) },
                            modifier = Modifier.testTag(if (section == RolePlayDirectorySection.Members)
                                "roleplay-directory-members" else "roleplay-directory-activities"),
                            label = { Text(stringResource(if (section == RolePlayDirectorySection.Members)
                                R.string.recruitment_members else R.string.recruitment_activities)) })
                    }
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val layout = risingStonesRecruitmentLayoutMode(maxWidth.value.toInt())
                    val selected = if (state.section == RolePlayDirectorySection.Members)
                        state.members.selectedId else state.activities.selectedId
                    val listScroll = if (state.section == RolePlayDirectorySection.Members) memberScroll else activityScroll
                    val detailScroll = if (state.section == RolePlayDirectorySection.Members) memberDetailScroll else activityDetailScroll
                    if (layout == RisingStonesRecruitmentLayoutMode.Compact) {
                        if (selected == null) RolePlayDirectoryList(state, viewModel, listScroll, Modifier.fillMaxSize())
                        else RolePlayDirectoryDetail(state, viewModel, detailScroll, Modifier.fillMaxSize())
                    } else Row(Modifier.fillMaxSize()) {
                        RolePlayDirectoryList(state, viewModel, listScroll,
                            Modifier.width(if (layout == RisingStonesRecruitmentLayoutMode.Medium) 280.dp else 360.dp).fillMaxHeight())
                        VerticalDivider()
                        RolePlayDirectoryDetail(state, viewModel, detailScroll, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun RolePlayDirectoryList(state: RolePlayDirectoryState, model: RolePlayDirectoryViewModel,
    scroll: LazyListState, modifier: Modifier) {
    val members = state.section == RolePlayDirectorySection.Members
    val loading = if (members) state.members.isLoading else state.activities.isLoading
    val refreshing = if (members) state.members.isRefreshing else state.activities.isRefreshing
    val loaded = if (members) state.members.hasLoaded else state.activities.hasLoaded
    val error = if (members) state.members.error else state.activities.error
    val empty = if (members) state.members.items.isEmpty() else state.activities.items.isEmpty()
    LazyColumn(modifier.testTag("roleplay-directory-list"), state = scroll,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading || refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) item {
            RolePlayDirectoryError(error)
            TextButton(onClick = { if (members && state.members.errorIsPagination) model.loadMore() else model.refresh() },
                modifier = Modifier.testTag("roleplay-directory-retry")) { Text(stringResource(R.string.recruitment_retry)) }
        }
        if (loaded && empty && error == null && !loading) item {
            Text(stringResource(if (members) R.string.recruitment_no_members else R.string.recruitment_no_activities))
        }
        if (!loaded && !loading && error == null) item {
            TextButton(onClick = model::refresh, modifier = Modifier.testTag("roleplay-directory-retry")) {
                Text(stringResource(R.string.recruitment_retry))
            }
        }
        if (members) items(state.members.items, key = { it.id }) { member ->
            DirectoryCard(member.name, member.identity, member.avatarUrl, member.id == state.members.selectedId,
                "roleplay-member-" + member.id, { model.selectMember(member.id) })
        } else items(state.activities.items, key = { it.id }) { activity ->
            DirectoryCard(activity.name, null, activity.coverUrl, activity.id == state.activities.selectedId,
                "roleplay-activity-" + activity.id, { model.selectActivity(activity.id) }) {
                DirectoryActivitySchedule(activity)
            }
        }
        if (members && state.members.isLoadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (members && state.members.hasMore) item {
            TextButton(onClick = model::loadMore, modifier = Modifier.testTag("roleplay-directory-more")) {
                Text(stringResource(R.string.recruitment_more))
            }
        }
    }
}

@Composable
private fun DirectoryCard(title: String, subtitle: String?, imageUrl: String?, selected: Boolean,
    tag: String, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.elevatedCardColors(containerColor = if (selected)
            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            imageUrl?.let { RecruitmentRemoteImage(it, null, Modifier.fillMaxWidth().height(100.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, fallback = {}) }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            extra()
        }
    }
}

@Composable
private fun RolePlayDirectoryDetail(state: RolePlayDirectoryState, model: RolePlayDirectoryViewModel,
    scroll: LazyListState, modifier: Modifier) {
    val members = state.section == RolePlayDirectorySection.Members
    val selected = if (members) state.members.selectedId else state.activities.selectedId
    val loading = if (members) state.members.isLoadingDetail else state.activities.isLoadingDetail
    val error = if (members) state.members.detailError else state.activities.detailError
    val member = state.members.detail
    val activity = state.activities.detail
    if (selected == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(if (members) R.string.recruitment_select_member else R.string.recruitment_select_activity),
                modifier = Modifier.padding(24.dp))
        }
        return
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("roleplay-directory-detail"),
            state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item {
                RolePlayDirectoryError(error)
                TextButton(onClick = model::retryDetail, modifier = Modifier.testTag("roleplay-directory-detail-retry")) {
                    Text(stringResource(R.string.recruitment_retry))
                }
            }
            if (!loading && error == null && (if (members) member == null else activity == null)) item {
                TextButton(onClick = model::retryDetail, modifier = Modifier.testTag("roleplay-directory-detail-retry")) {
                    Text(stringResource(R.string.recruitment_retry))
                }
            }
            if (members && member != null) {
                item {
                    Text(member.name, style = MaterialTheme.typography.headlineSmall)
                    member.identity?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
                member.avatarUrl?.let { item { RecruitmentContentImage(it) } }
                member.detailImageUrl?.let { item { RecruitmentContentImage(it) } }
                member.description?.takeIf(String::isNotBlank)?.let { item { Text(it) } }
            } else if (!members && activity != null) {
                item { Text(activity.activity.name, style = MaterialTheme.typography.headlineSmall) }
                activity.activity.coverUrl?.let { item { RecruitmentContentImage(it) } }
                item { DirectoryActivitySchedule(activity.activity) }
                items(activity.bodyBlocks) { block ->
                    when (block) {
                        is RolePlayActivityBodyBlock.Html -> RecruitmentFormattedText(block.contentHtml)
                        is RolePlayActivityBodyBlock.Image -> RolePlayActivityImage(block)
                    }
                }
                if (activity.hasUnsupportedContent) item { Text(stringResource(R.string.recruitment_content_partial)) }
            }
            if (if (members) member != null else activity != null) item {
                TextButton(onClick = model::retryDetail, enabled = !loading,
                    modifier = Modifier.testTag("roleplay-directory-detail-refresh")) {
                    Text(stringResource(R.string.recruitment_refresh_detail))
                }
            }
        }
    }
}

@Composable
private fun RolePlayActivityImage(block: RolePlayActivityBodyBlock.Image) {
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember(block.linkUrl) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecruitmentRemoteImage(block.url, block.alternativeText ?: stringResource(R.string.recruitment_content_image),
            Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 600.dp),
            fallback = { Text(stringResource(R.string.recruitment_image_failed)) })
        block.linkUrl?.let { url ->
            TextButton(onClick = { linkFailed = !openRecruitmentContentLink(uriHandler, url) }) {
                Text(stringResource(R.string.recruitment_image_link))
            }
        }
        if (linkFailed) Text(stringResource(R.string.recruitment_link_unavailable), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DirectoryActivitySchedule(activity: RolePlayActivity) {
    activity.beginTime?.takeIf(String::isNotBlank)?.let {
        Text(stringResource(R.string.recruitment_activity_begins, it), style = MaterialTheme.typography.bodyMedium)
    }
    activity.endTime?.takeIf(String::isNotBlank)?.let {
        Text(stringResource(R.string.recruitment_activity_ends, it), style = MaterialTheme.typography.bodyMedium)
    }
    val status = when (activity.activityStatus) {
        0 -> R.string.recruitment_activity_upcoming
        1 -> R.string.recruitment_activity_ongoing
        else -> null
    }
    status?.let { Text(stringResource(it), fontWeight = FontWeight.Medium) }
}

@Composable
private fun RolePlayDirectoryError(error: RecruitmentInteractionError) {
    Text(stringResource(when (error) {
        RecruitmentInteractionError.Unavailable -> R.string.recruitment_directory_unavailable
        RecruitmentInteractionError.AuthenticationRequired -> R.string.recruitment_auth_required
        RecruitmentInteractionError.InvalidInput -> R.string.recruitment_directory_wrong_parent
        RecruitmentInteractionError.Failed -> R.string.recruitment_load_failed
    }), color = MaterialTheme.colorScheme.error)
}

private fun directoryNavigateBack(model: RolePlayDirectoryViewModel, onNavigateBack: () -> Unit) {
    val state = model.state.value
    val selected = if (state.section == RolePlayDirectorySection.Members) state.members.selectedId else state.activities.selectedId
    if (selected != null) model.clearSelection() else onNavigateBack()
}

private class RolePlayDirectoryRouteStore : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    override fun onCleared() { viewModelStore.clear() }
}
private object RolePlayDirectoryStoreFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RolePlayDirectoryRouteStore() as T
}
private tailrec fun Context.directoryActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.directoryActivity()
    else -> null
}
