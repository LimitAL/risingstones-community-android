package top.cxmeow.risingstones.feature.guild.ui.compose

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import top.cxmeow.risingstones.feature.guild.domain.GuildActivitySummary
import top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility
import top.cxmeow.risingstones.feature.guild.domain.GuildInfo
import top.cxmeow.risingstones.feature.guild.domain.GuildMember
import top.cxmeow.risingstones.feature.guild.domain.GuildMemberRegistration
import top.cxmeow.risingstones.feature.guild.domain.GuildMembers
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoDetail
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoSummary
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildCommentTarget
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoViewModel
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoViewModelFactory
import top.cxmeow.risingstones.feature.guild.presentation.GuildSection
import top.cxmeow.risingstones.feature.guild.presentation.GuildUiError
import top.cxmeow.risingstones.feature.guild.presentation.GuildUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildViewModel
import top.cxmeow.risingstones.feature.guild.presentation.GuildViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesGuildScreen(
    service: GuildService,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenActivity: ((Int) -> Unit)? = null,
    canOpenActivity: (Int) -> Boolean = { false },
) {
    val model: GuildViewModel = viewModel(
        key = "rising-stones-guild-${System.identityHashCode(service)}",
        factory = remember(service) { GuildViewModelFactory(service) },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val actions = rememberGuildActionRuntime("home-${System.identityHashCode(service)}")
    val profileScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val membersScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val activitiesScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val photosScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val photoDetailScroll = rememberSaveable(state.selectedPhotoId, saver = LazyListState.Saver) { LazyListState() }
    LaunchedEffect(
        actions?.model,
        state.info?.id,
        state.selectedPhotoId,
        state.comments.map { it.id },
        state.replies.map { it.id },
        actions?.model?.canInteract,
        actions?.model?.canUploadImages,
    ) {
        actions?.model?.bindResources(
            state.info?.id,
            state.selectedPhotoId,
            state.comments + state.replies,
        )
    }
    LaunchedEffect(actions?.state?.guildInfoSuccessRevision) {
        if ((actions?.state?.guildInfoSuccessRevision ?: 0) > 0) model.refresh()
    }
    LaunchedEffect(actions?.state?.albumSuccessRevision) {
        if ((actions?.state?.albumSuccessRevision ?: 0) > 0) model.refreshPhotos()
    }
    LaunchedEffect(actions?.state?.commentSuccessRevision) {
        if ((actions?.state?.commentSuccessRevision ?: 0) > 0) {
            model.refreshComments()
            if (state.selectedCommentRootId != null) model.refreshReplies()
        }
    }
    LaunchedEffect(actions?.state?.deletedCommentRevision) {
        actions?.state?.deletedCommentId?.let { id ->
            model.applyDeletedComment(id)
            actions.model.consumeDeletedCommentEvent(id)
        }
    }
    LaunchedEffect(actions?.state?.deletedPhotoRevision) {
        actions?.state?.deletedPhotoId?.let { id ->
            model.applyDeletedPhoto(id)
            actions.model.consumeDeletedPhotoEvent(id)
        }
    }
    GuildCapabilityBoundary(service.canRead, model::clearProtectedContent)
    GuildRepliesSheet(
        rootId = state.selectedCommentRootId,
        visible = state.section == GuildSection.Photos,
        replies = state.replies,
        isLoading = state.isLoadingReplies,
        hasMore = state.hasMoreReplies,
        error = state.repliesError,
        onMore = model::loadMoreReplies,
        onRetry = model::retryReplies,
        onDismiss = model::dismissReplies,
        actions = actions,
        onReply = { comment ->
            model.dismissReplies()
            actions?.model?.openComment(comment.replyTarget())
        },
    )
    actions?.let {
        GuildActionOverlays(it.model, it.state, it.importer, state.info, state.selectedPhotoId, it.launchers)
    }
    BackHandler {
        when {
            state.section == GuildSection.Photos && state.selectedCommentRootId != null -> model.dismissReplies()
            state.section == GuildSection.Photos && state.selectedPhotoId != null -> model.clearPhotoSelection()
            else -> onNavigateBack()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guild_title)) },
                navigationIcon = {
                    TextButton(onClick = {
                        if (state.section == GuildSection.Photos && state.selectedPhotoId != null) {
                            model.clearPhotoSelection()
                        } else {
                            onNavigateBack()
                        }
                    }) { Text(stringResource(R.string.guild_back)) }
                },
                actions = {
                    TextButton(onClick = {
                        if (state.section == GuildSection.Photos && state.selectedPhotoId != null) {
                            model.refreshPhoto()
                        } else {
                            model.refreshSection()
                        }
                    }) { Text(stringResource(R.string.guild_refresh)) }
                },
            )
        },
    ) { padding ->
        GuildHomeContent(
            state = state,
            model = model,
            onOpenActivity = onOpenActivity,
            canOpenActivity = canOpenActivity,
            profileScroll = profileScroll,
            membersScroll = membersScroll,
            activitiesScroll = activitiesScroll,
            photosScroll = photosScroll,
            photoDetailScroll = photoDetailScroll,
            actions = actions,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesGuildPhotoScreen(
    service: GuildService,
    id: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(id > 0)
    val model: GuildPhotoViewModel = viewModel(
        key = "rising-stones-guild-photo-${System.identityHashCode(service)}-$id",
        factory = remember(service, id) { GuildPhotoViewModelFactory(service, id) },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val actions = rememberGuildActionRuntime("photo-${System.identityHashCode(service)}-$id")
    val photoDetailScroll = rememberSaveable(id, saver = LazyListState.Saver) { LazyListState() }
    LaunchedEffect(
        actions?.model,
        state.detail?.guildId,
        state.detail?.id,
        state.comments.map { it.id },
        state.replies.map { it.id },
        actions?.model?.canInteract,
        actions?.model?.canUploadImages,
    ) {
        actions?.model?.bindResources(state.detail?.guildId, state.detail?.id, state.comments + state.replies)
    }
    LaunchedEffect(actions?.state?.commentSuccessRevision) {
        if ((actions?.state?.commentSuccessRevision ?: 0) > 0) {
            model.refreshComments()
            if (state.selectedCommentRootId != null) model.refreshReplies()
        }
    }
    LaunchedEffect(actions?.state?.deletedCommentRevision) {
        actions?.state?.deletedCommentId?.let { deletedId ->
            model.applyDeletedComment(deletedId)
            actions.model.consumeDeletedCommentEvent(deletedId)
        }
    }
    LaunchedEffect(actions?.state?.deletedPhotoRevision) {
        actions?.state?.deletedPhotoId?.let { deletedId ->
            actions.model.consumeDeletedPhotoEvent(deletedId)
            onNavigateBack()
        }
    }
    GuildCapabilityBoundary(service.canRead, model::clearProtectedContent)
    GuildRepliesSheet(
        rootId = state.selectedCommentRootId,
        visible = true,
        replies = state.replies,
        isLoading = state.isLoadingReplies,
        hasMore = state.hasMoreReplies,
        error = state.repliesError,
        onMore = model::loadMoreReplies,
        onRetry = model::retryReplies,
        onDismiss = model::dismissReplies,
        actions = actions,
        onReply = { comment ->
            model.dismissReplies()
            actions?.model?.openComment(comment.replyTarget())
        },
    )
    actions?.let {
        GuildActionOverlays(it.model, it.state, it.importer, null, id, it.launchers)
    }
    BackHandler {
        if (state.selectedCommentRootId != null) model.dismissReplies() else onNavigateBack()
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guild_photo_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.guild_back)) }
                },
                actions = {
                    TextButton(onClick = model::retryPhoto) { Text(stringResource(R.string.guild_refresh)) }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val expanded = guildLayoutMode(maxWidth.value.toInt()) == GuildLayoutMode.Expanded
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (expanded) Modifier.widthIn(max = 920.dp) else Modifier)
                    .fillMaxSize(),
            ) {
                GuildStandalonePhotoContent(state, model, photoDetailScroll, actions)
            }
        }
    }
}

@Composable
private fun GuildHomeContent(
    state: GuildUiState,
    model: GuildViewModel,
    onOpenActivity: ((Int) -> Unit)?,
    canOpenActivity: (Int) -> Boolean,
    profileScroll: LazyListState,
    membersScroll: LazyListState,
    activitiesScroll: LazyListState,
    photosScroll: LazyListState,
    photoDetailScroll: LazyListState,
    actions: GuildActionRuntime?,
    modifier: Modifier,
) {
    when {
        state.isLoading && state.ownGuild == null -> GuildCentered(modifier) { CircularProgressIndicator() }
        state.error != null && state.ownGuild == null -> GuildCentered(modifier) {
            GuildError(state.error, model::retry)
        }
        state.hasNoGuild -> GuildCentered(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.guild_none), style = MaterialTheme.typography.titleMedium)
                state.error?.let { GuildInlineError(it, model::refresh) }
                TextButton(onClick = model::refresh) { Text(stringResource(R.string.guild_refresh)) }
            }
        }
        state.info != null -> Column(modifier) {
            val info = requireNotNull(state.info)
            if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { GuildInlineError(it, model::refresh) }
            GuildSectionTabs(state.section, model::selectSection)
            HorizontalDivider()
            when (state.section) {
                GuildSection.Profile -> GuildReadingPane { pane -> GuildProfile(info, pane, profileScroll, actions) }
                GuildSection.Members -> GuildReadingPane { pane ->
                    GuildMembersContent(state, model, pane, membersScroll)
                }
                GuildSection.Activities -> GuildReadingPane { pane ->
                    GuildActivitiesContent(state, model, onOpenActivity, canOpenActivity, pane, activitiesScroll)
                }
                GuildSection.Photos -> GuildPhotosContent(
                    state, model, Modifier.fillMaxSize(), photosScroll, photoDetailScroll,
                    actions,
                )
            }
        }
        else -> GuildCentered(modifier) { GuildError(state.error ?: GuildUiError.Failed, model::retry) }
    }
}

@Composable
private fun GuildReadingPane(content: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = 920.dp).fillMaxSize())
    }
}

@Composable
private fun GuildSectionTabs(selected: GuildSection, onSelect: (GuildSection) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GuildSection.entries.forEach { section ->
            FilterChip(
                selected = selected == section,
                onClick = { onSelect(section) },
                label = { Text(section.label()) },
                modifier = Modifier.testTag("guild-section-${section.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun GuildProfile(
    info: GuildInfo,
    modifier: Modifier,
    scrollState: LazyListState,
    actions: GuildActionRuntime?,
) {
    LazyColumn(
        modifier = modifier.testTag("guild-profile-content"),
        state = scrollState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (actions?.state?.canManageGuild == true && actions.model.canInteract) {
                    Button(
                        onClick = { actions.model.openManager(info.descriptionHtml.htmlPlainText()) },
                        modifier = Modifier.testTag("guild-manage-profile"),
                    ) { Text(stringResource(R.string.guild_manage)) }
                }
                info.imageUrl?.let { GuildRemoteImage(it, info.name, Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
                Text(info.name, style = MaterialTheme.typography.headlineSmall)
                Text("${info.tag} · ${info.areaName}/${info.groupName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                GuildChipRow(info.labels)
                GuildHtmlText(info.descriptionHtml)
                GuildKeyValue(
                    stringResource(R.string.guild_members),
                    stringResource(R.string.guild_member_counts, info.activeMemberCount, info.memberCount),
                )
                info.rank?.let { GuildKeyValue(stringResource(R.string.guild_rank), it.toString()) }
                GuildKeyValue(stringResource(R.string.guild_grand_company), info.grandCompanyName)
                info.createdAt?.let { GuildKeyValue(stringResource(R.string.guild_created_at), it) }
                info.weekdayActiveTime?.let { GuildKeyValue(stringResource(R.string.guild_weekday_time), it) }
                info.weekendActiveTime?.let { GuildKeyValue(stringResource(R.string.guild_weekend_time), it) }
                Text(stringResource(R.string.guild_housing), style = MaterialTheme.typography.titleMedium)
                when (info.housing.visibility) {
                    GuildHousingVisibility.Private -> Text(stringResource(R.string.guild_housing_private))
                    GuildHousingVisibility.Visible -> {
                        val description = info.housing.description?.takeIf(String::isNotBlank)
                        val remaining = info.housing.remainingDays?.takeIf(String::isNotBlank)
                        if (description == null && remaining == null) {
                            Text(stringResource(R.string.guild_housing_none))
                        } else {
                            description?.let { Text(it) }
                            remaining?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuildMembersContent(
    state: GuildUiState,
    model: GuildViewModel,
    modifier: Modifier,
    scrollState: LazyListState,
) {
    when {
        state.isLoadingMembers && state.members == null -> GuildCentered(modifier) { CircularProgressIndicator() }
        state.membersError != null && state.members == null -> GuildCentered(modifier) {
            GuildError(state.membersError, model::refreshSection)
        }
        else -> {
            val members = state.members ?: GuildMembers(emptyList(), emptyList())
            val registeredLabel = stringResource(R.string.guild_registered_members)
            val unregisteredLabel = stringResource(R.string.guild_unregistered_members)
            LazyColumn(
                modifier = modifier.testTag("guild-members-content"),
                state = scrollState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.isLoadingMembers) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.membersError?.let { item { GuildInlineError(it, model::refreshSection) } }
                if (members.registered.isEmpty() && members.unregistered.isEmpty()) {
                    item { Text(stringResource(R.string.guild_no_members)) }
                }
                memberGroup(
                    GuildMemberRegistration.Registered,
                    registeredLabel,
                    members.registered,
                )
                memberGroup(
                    GuildMemberRegistration.Unregistered,
                    unregisteredLabel,
                    members.unregistered,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memberGroup(
    registration: GuildMemberRegistration,
    label: String,
    members: List<GuildMember>,
) {
    if (members.isEmpty()) return
    item { Text(label, style = MaterialTheme.typography.titleMedium) }
    items(members, key = { "${registration.name}-${it.characterName}-${it.groupName}" }) { member ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                member.avatarUrl?.let { GuildRemoteImage(it, member.characterName, Modifier.width(56.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GuildAuthorName(member.characterName, member.authorUuid)
                    Text("${member.areaName}/${member.groupName}", style = MaterialTheme.typography.bodySmall)
                    GuildHtmlText(member.profile, maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun GuildActivitiesContent(
    state: GuildUiState,
    model: GuildViewModel,
    onOpenActivity: ((Int) -> Unit)?,
    canOpenActivity: (Int) -> Boolean,
    modifier: Modifier,
    scrollState: LazyListState,
) {
    when {
        state.isLoadingActivities && state.activities.isEmpty() -> GuildCentered(modifier) { CircularProgressIndicator() }
        state.activitiesError != null && state.activities.isEmpty() -> GuildCentered(modifier) {
            GuildError(state.activitiesError, model::refreshSection)
        }
        else -> LazyColumn(
            modifier = modifier.testTag("guild-activities-content"),
            state = scrollState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.activitiesError?.let { item { GuildInlineError(it, model::refreshSection) } }
            if (state.activities.isEmpty() && !state.isLoadingActivities) item { Text(stringResource(R.string.guild_no_activities)) }
            items(state.activities, key = GuildActivitySummary::id) { activity ->
                val open = onOpenActivity != null && canOpenActivity(activity.id)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().testTag("guild-activity-${activity.id}")
                        .then(if (open) Modifier.clickable(role = Role.Button) { onOpenActivity?.invoke(activity.id) } else Modifier),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuildAuthorName(activity.characterName, activity.authorUuid)
                        Text("${activity.areaName}/${activity.groupName}", style = MaterialTheme.typography.bodySmall)
                        GuildHtmlText(activity.contentHtml, maxLines = 6)
                        activity.imageUrls.firstOrNull()?.let {
                            GuildRemoteImage(it, activity.characterName, Modifier.fillMaxWidth().heightIn(max = 260.dp))
                        }
                        activity.createdAt?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            item {
                when {
                    state.isLoadingActivities -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.hasMoreActivities -> TextButton(onClick = model::loadMoreActivities) {
                        Text(stringResource(R.string.guild_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuildPhotosContent(
    state: GuildUiState,
    model: GuildViewModel,
    modifier: Modifier,
    photosScroll: LazyListState,
    detailScroll: LazyListState,
    actions: GuildActionRuntime?,
) {
    BoxWithConstraints(modifier) {
        val layoutWidth = maxWidth
        when (guildLayoutMode(layoutWidth.value.toInt())) {
            GuildLayoutMode.Compact -> {
                if (state.selectedPhotoId == null) {
                    GuildPhotoList(state, model, Modifier.fillMaxSize(), photosScroll, actions)
                } else {
                    GuildHomePhotoDetail(
                        state, model, Modifier.fillMaxSize(), showBack = true, scrollState = detailScroll,
                        actions = actions,
                    )
                }
            }
            GuildLayoutMode.Medium, GuildLayoutMode.Expanded -> Row(Modifier.fillMaxSize()) {
                GuildPhotoList(
                    state,
                    model,
                    Modifier.width(if (layoutWidth < 840.dp) 280.dp else 340.dp).fillMaxHeight(),
                    photosScroll,
                    actions,
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    GuildHomePhotoDetail(
                        state,
                        model,
                        Modifier.then(if (layoutWidth >= 840.dp) Modifier.widthIn(max = 840.dp) else Modifier).fillMaxSize(),
                        showBack = false,
                        scrollState = detailScroll,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuildPhotoList(
    state: GuildUiState,
    model: GuildViewModel,
    modifier: Modifier,
    scrollState: LazyListState,
    actions: GuildActionRuntime?,
) {
    when {
        state.isLoadingPhotos && state.photos.isEmpty() -> GuildCentered(modifier) { CircularProgressIndicator() }
        state.photosError != null && state.photos.isEmpty() -> GuildCentered(modifier) {
            GuildError(state.photosError, model::refreshSection)
        }
        else -> LazyColumn(
            modifier = modifier.testTag("guild-photo-list"),
            state = scrollState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (actions?.state?.canUploadAlbum == true && actions.model.canUploadImages) {
                item {
                    Button(
                        onClick = actions.launchers.album,
                        enabled = !actions.state.isUploadingAlbum,
                        modifier = Modifier.fillMaxWidth().testTag("guild-choose-album-images"),
                    ) { Text(stringResource(R.string.guild_upload_photos)) }
                }
            }
            state.photosError?.let { item { GuildInlineError(it, model::refreshSection) } }
            if (state.photos.isEmpty() && !state.isLoadingPhotos) item { Text(stringResource(R.string.guild_no_photos)) }
            items(state.photos, key = GuildPhotoSummary::id) { photo ->
                val likeResult = actions?.state?.likeResults?.get(photo.id)
                val shownLikeCount = when {
                    likeResult == GuildPhotoLikeResult.Liked && !photo.isLiked -> photo.likeCount + 1
                    likeResult == GuildPhotoLikeResult.Unliked && photo.isLiked ->
                        (photo.likeCount - 1).coerceAtLeast(0)
                    else -> photo.likeCount
                }
                ElevatedCard(
                    onClick = { model.selectPhoto(photo.id) },
                    modifier = Modifier.fillMaxWidth().testTag("guild-photo-${photo.id}"),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuildRemoteImage(photo.photoUrl, photo.characterName, Modifier.fillMaxWidth().heightIn(max = 220.dp))
                        Text(photo.characterName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.guild_photo_counts, shownLikeCount),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                when {
                    state.isLoadingPhotos -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.hasMorePhotos -> TextButton(onClick = model::loadMorePhotos) {
                        Text(stringResource(R.string.guild_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuildHomePhotoDetail(
    state: GuildUiState,
    model: GuildViewModel,
    modifier: Modifier,
    showBack: Boolean,
    scrollState: LazyListState,
    actions: GuildActionRuntime?,
) {
    GuildPhotoDetailContent(
        detail = state.photoDetail,
        isLoading = state.isLoadingPhotoDetail,
        error = state.photoDetailError,
        comments = state.comments,
        isLoadingComments = state.isLoadingComments,
        hasMoreComments = state.hasMoreComments,
        commentsError = state.commentsError,
        onRetryPhoto = model::retryPhoto,
        onRefreshComments = model::refreshComments,
        onMoreComments = model::loadMoreComments,
        onOpenReplies = model::openReplies,
        onBack = model::clearPhotoSelection.takeIf { showBack },
        modifier = modifier,
        scrollState = scrollState,
        actions = actions,
    )
}

@Composable
private fun GuildStandalonePhotoContent(
    state: GuildPhotoUiState,
    model: GuildPhotoViewModel,
    scrollState: LazyListState,
    actions: GuildActionRuntime?,
) {
    GuildPhotoDetailContent(
        detail = state.detail,
        isLoading = state.isLoading,
        error = state.error,
        comments = state.comments,
        isLoadingComments = state.isLoadingComments,
        hasMoreComments = state.hasMoreComments,
        commentsError = state.commentsError,
        onRetryPhoto = model::retryPhoto,
        onRefreshComments = model::refreshComments,
        onMoreComments = model::loadMoreComments,
        onOpenReplies = model::openReplies,
        onBack = null,
        modifier = Modifier.fillMaxSize(),
        scrollState = scrollState,
        actions = actions,
    )
}

@Composable
private fun GuildPhotoDetailContent(
    detail: GuildPhotoDetail?,
    isLoading: Boolean,
    error: GuildUiError?,
    comments: List<GuildPhotoComment>,
    isLoadingComments: Boolean,
    hasMoreComments: Boolean,
    commentsError: GuildUiError?,
    onRetryPhoto: () -> Unit,
    onRefreshComments: () -> Unit,
    onMoreComments: () -> Unit,
    onOpenReplies: (GuildPhotoComment) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    scrollState: LazyListState,
    actions: GuildActionRuntime?,
) {
    when {
        isLoading && detail == null -> GuildCentered(modifier) { CircularProgressIndicator() }
        error != null && detail == null -> GuildCentered(modifier) { GuildError(error, onRetryPhoto) }
        detail == null -> GuildCentered(modifier) { Text(stringResource(R.string.guild_select_photo)) }
        else -> {
            val likeResult = actions?.state?.likeResults?.get(detail.id)
            val shownLiked = when (likeResult) {
                GuildPhotoLikeResult.Liked -> true
                GuildPhotoLikeResult.Unliked -> false
                null -> detail.isLiked
            }
            val shownLikeCount = when {
                likeResult == GuildPhotoLikeResult.Liked && !detail.isLiked -> detail.likeCount + 1
                likeResult == GuildPhotoLikeResult.Unliked && detail.isLiked -> (detail.likeCount - 1).coerceAtLeast(0)
                else -> detail.likeCount
            }
            LazyColumn(
            modifier = modifier.testTag("guild-photo-content"),
            state = scrollState,
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    onBack?.let {
                        TextButton(onClick = it, modifier = Modifier.testTag("guild-photo-back")) {
                            Text(stringResource(R.string.guild_back_to_photos))
                        }
                    }
                    GuildRemoteImage(detail.photoUrl, detail.characterName, Modifier.fillMaxWidth().heightIn(max = 520.dp))
                    GuildAuthorName(detail.characterName, detail.authorUuid)
                    guildLocation(detail.areaName, detail.groupName)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.guild_photo_detail_counts, shownLikeCount, detail.commentCount))
                    actions?.takeIf { it.model.canInteract }?.let { runtime ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { runtime.model.togglePhotoLike(detail.id) },
                                enabled = !runtime.state.isLikingPhoto,
                                modifier = Modifier.testTag("guild-like-photo"),
                            ) {
                                Text(stringResource(if (shownLiked) R.string.guild_unlike else R.string.guild_like))
                            }
                            Button(
                                onClick = { runtime.model.openComment() },
                                enabled = !runtime.state.isSubmittingComment,
                                modifier = Modifier.testTag("guild-write-comment"),
                            ) { Text(stringResource(R.string.guild_write_comment)) }
                            }
                            if (runtime.state.canDeletePhoto(detail.id)) {
                                TextButton(
                                    onClick = { runtime.model.requestDeletePhoto(detail.id) },
                                    enabled = !runtime.state.isDeletingPhoto,
                                    modifier = Modifier.testTag("guild-delete-photo"),
                                ) { Text(stringResource(R.string.guild_delete)) }
                            }
                        }
                        runtime.state.error?.let { GuildActionErrorText(it) }
                    }
                    error?.let { GuildInlineError(it, onRetryPhoto) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.guild_comments), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onRefreshComments) { Text(stringResource(R.string.guild_refresh)) }
                    }
                }
            }
            commentsError?.let { item { GuildInlineError(it, onRefreshComments) } }
            if (comments.isEmpty() && !isLoadingComments && commentsError == null) {
                item { Text(stringResource(R.string.guild_no_comments)) }
            }
            items(comments, key = GuildPhotoComment::id) { comment ->
                GuildCommentCard(comment, onOpenReplies, actions) {
                    actions?.model?.openComment(comment.replyTarget())
                }
            }
            item {
                when {
                    isLoadingComments -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    hasMoreComments -> TextButton(onClick = onMoreComments) { Text(stringResource(R.string.guild_more)) }
                }
            }
            }
        }
    }
}

@Composable
private fun GuildCommentCard(
    comment: GuildPhotoComment,
    onOpenReplies: (GuildPhotoComment) -> Unit,
    actions: GuildActionRuntime?,
    onReply: () -> Unit,
) {
    ElevatedCard(Modifier.widthIn(max = 840.dp).fillMaxWidth().testTag("guild-comment-${comment.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GuildAuthorName(comment.characterName, comment.authorUuid)
            comment.replyToName?.let { Text(stringResource(R.string.guild_reply_to, it), style = MaterialTheme.typography.labelSmall) }
            GuildHtmlText(comment.contentHtml)
            comment.pictureUrl?.let { GuildRemoteImage(it, comment.characterName, Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
            comment.createdAt?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (comment.childCount > 0) {
                TextButton(
                    onClick = { onOpenReplies(comment) },
                    modifier = Modifier.testTag("guild-comment-replies-${comment.id}"),
                ) { Text(stringResource(R.string.guild_view_replies, comment.childCount)) }
            }
            actions?.takeIf { it.model.canInteract }?.let { runtime ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onReply,
                        modifier = Modifier.testTag("guild-reply-comment-${comment.id}"),
                    ) { Text(stringResource(R.string.guild_reply)) }
                    if (runtime.state.canDeleteComment(comment.id)) {
                        TextButton(
                            onClick = { runtime.model.requestDeleteComment(comment.id) },
                            enabled = comment.id !in runtime.state.deletingCommentIds,
                            modifier = Modifier.testTag("guild-delete-comment-${comment.id}"),
                        ) { Text(stringResource(R.string.guild_delete)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuildRepliesSheet(
    rootId: Int?,
    visible: Boolean,
    replies: List<GuildPhotoComment>,
    isLoading: Boolean,
    hasMore: Boolean,
    error: GuildUiError?,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    actions: GuildActionRuntime?,
    onReply: (GuildPhotoComment) -> Unit,
) {
    val scrollState = rememberSaveable(rootId, saver = LazyListState.Saver) { LazyListState() }
    // Returning from an author page must not collapse the viewport around the retained reply row.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (rootId == null || !visible || !lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.guild_replies), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.guild_close)) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag("guild-replies-content"),
                state = scrollState,
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(replies, key = GuildPhotoComment::id) { comment ->
                    GuildCommentCard(comment, {}, actions) { onReply(comment) }
                }
                item {
                    error?.let { GuildInlineError(it, onRetry) }
                    when {
                        isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        hasMore -> TextButton(onClick = onMore) { Text(stringResource(R.string.guild_more)) }
                        replies.isEmpty() && error == null -> Text(stringResource(R.string.guild_no_replies))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuildHtmlText(html: String, maxLines: Int = Int.MAX_VALUE) {
    val text = remember(html) { Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
    if (text.isNotEmpty()) Text(text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun GuildRemoteImage(url: String, description: String, modifier: Modifier) {
    AsyncImage(model = url, contentDescription = description, modifier = modifier)
}

private fun guildLocation(areaName: String, groupName: String): String? =
    listOf(areaName, groupName).filter(String::isNotBlank).joinToString("/").takeIf(String::isNotBlank)

private fun String.htmlPlainText(): String =
    Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString().trim()

private fun GuildPhotoComment.replyTarget(): GuildCommentTarget {
    val rootId = rootParentId.takeIf { it > 0 } ?: id
    return GuildCommentTarget(
        parentId = id,
        rootParentId = rootId,
        authorUuid = authorUuid,
        authorName = characterName,
    )
}

@Composable
private fun GuildChipRow(values: List<String>) {
    values.filter(String::isNotBlank).distinct().joinToString(" · ").takeIf(String::isNotBlank)?.let {
        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuildKeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value)
    }
}

@Composable
private fun GuildError(error: GuildUiError?, retry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.label(), color = MaterialTheme.colorScheme.error)
        Button(onClick = retry) { Text(stringResource(R.string.guild_retry)) }
    }
}

@Composable
private fun GuildInlineError(error: GuildUiError, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(error.label(), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = retry) { Text(stringResource(R.string.guild_retry)) }
    }
}

@Composable
private fun GuildCentered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun GuildSection.label(): String = stringResource(when (this) {
    GuildSection.Profile -> R.string.guild_section_profile
    GuildSection.Members -> R.string.guild_section_members
    GuildSection.Activities -> R.string.guild_section_activities
    GuildSection.Photos -> R.string.guild_section_photos
})

@Composable
private fun GuildUiError?.label(): String = stringResource(when (this) {
    GuildUiError.AuthenticationRequired -> R.string.guild_auth_required
    GuildUiError.Unavailable -> R.string.guild_unavailable
    GuildUiError.Failed, null -> R.string.guild_load_failed
})

@Composable
private fun GuildCapabilityBoundary(canRead: Boolean, onRevoked: () -> Unit) {
    var hadAccess by remember { mutableStateOf(canRead) }
    LaunchedEffect(canRead) {
        if (hadAccess && !canRead) onRevoked()
        hadAccess = canRead
    }
}
