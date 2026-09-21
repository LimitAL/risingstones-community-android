package top.cxmeow.risingstones.feature.profile.ui.compose

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.profile.presentation.*
import kotlin.math.roundToInt

enum class ProfileLayoutMode { Compact, Medium, Expanded }
fun profileLayoutMode(widthDp: Int): ProfileLayoutMode = when {
    widthDp < 600 -> ProfileLayoutMode.Compact
    widthDp < 840 -> ProfileLayoutMode.Medium
    else -> ProfileLayoutMode.Expanded
}

// Compare physical pixels so density conversion cannot truncate an exact 840dp width to 839dp.
internal fun profileLayoutMode(widthPixels: Int, density: Float): ProfileLayoutMode = when {
    widthPixels < (600 * density).roundToInt() -> ProfileLayoutMode.Compact
    widthPixels < (840 * density).roundToInt() -> ProfileLayoutMode.Medium
    else -> ProfileLayoutMode.Expanded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenContent: ((ProfileContentTarget) -> Unit)? = null,
    canOpenContent: (ProfileContentTarget) -> Boolean = { true },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrolls = rememberSaveable(viewModel, saver = ProfileScrollStore.Saver) { ProfileScrollStore() }
    val ownerKey = when (val owner = state.owner) {
        ProfileOwner.Self -> "self"
        is ProfileOwner.User -> "user:${owner.uuid.length}:${owner.uuid}"
    }
    val contentScroll = scrolls.get("$ownerKey/content/${state.section}")
    val navigationScroll = scrolls.get("$ownerKey/navigation")
    val sectionScroll = scrolls.get("$ownerKey/sections")
    LaunchedEffect(viewModel) { viewModel.ensureLoaded() }
    val back = { if (!viewModel.navigateBack()) onNavigateBack() }
    BackHandler(onBack = back)
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.profile_title)) },
            navigationIcon = { TextButton(onClick = back, modifier = Modifier.testTag("profile-back")) {
                Text(stringResource(R.string.profile_back)) } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val mode = profileLayoutMode(constraints.maxWidth, LocalDensity.current.density)
            if (state.profileStatus in listOf(ProfileLoadStatus.AuthenticationRequired, ProfileLoadStatus.Unavailable)) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.profile_sign_in))
                }
            } else if (mode == ProfileLayoutMode.Compact) {
                ProfileContent(state, viewModel, onOpenContent, canOpenContent, true, Modifier.fillMaxSize(),
                    contentScroll, sectionScroll)
            } else Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(if (mode == ProfileLayoutMode.Medium) 220.dp else 280.dp).fillMaxHeight()
                    .testTag("profile-navigation"),
                    state = navigationScroll,
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Summary(state, viewModel) }
                    items(sections(state.owner)) { section ->
                        FilterChip(selected = state.section == section, onClick = { viewModel.selectSection(section) },
                            label = { Text(stringResource(section.label())) },
                            modifier = Modifier.fillMaxWidth().testTag("profile-section-${section.name}"))
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    ProfileContent(state, viewModel, onOpenContent, canOpenContent, false,
                        Modifier.widthIn(max = 840.dp).fillMaxSize(), contentScroll, sectionScroll)
                }
            }
        }
    }
}

@Composable
private fun Summary(state: ProfileUiState, model: ProfileViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.profileStatus == ProfileLoadStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.profileStatus == ProfileLoadStatus.Failed) Failure(model::refreshProfile)
        state.profile?.let { profile ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                profile.avatarUrl?.let { AsyncImage(it, null, Modifier.size(56.dp)) }
                Column {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(listOf(profile.areaName, profile.groupName).filter(String::isNotBlank).joinToString(" / "),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(profile.biography.ifBlank { stringResource(R.string.profile_no_bio) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.selectSection(ProfileSection.Following) }) {
                    Text(stringResource(R.string.profile_following_count, profile.followingCount))
                }
                TextButton(onClick = { model.selectSection(ProfileSection.Followers) }) {
                    Text(stringResource(R.string.profile_followers_count, profile.followerCount))
                }
            }
            Text(stringResource(R.string.profile_liked_count, profile.likedCount), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::refreshProfile) { Text(stringResource(R.string.profile_refresh_profile)) }
        }
    }
}

@Composable
private fun ProfileContent(state: ProfileUiState, model: ProfileViewModel,
    onOpenContent: ((ProfileContentTarget) -> Unit)?, canOpenContent: (ProfileContentTarget) -> Boolean,
    compact: Boolean, modifier: Modifier, scroll: LazyListState, sectionScroll: LazyListState) {
    LazyColumn(modifier.testTag("profile-content"), state = scroll,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (compact) {
            item(key = "summary") { Summary(state, model) }
            item(key = "sections") {
                LazyRow(state = sectionScroll, modifier = Modifier.testTag("profile-sections"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sections(state.owner)) { section ->
                        FilterChip(selected = state.section == section, onClick = { model.selectSection(section) },
                            label = { Text(stringResource(section.label())) },
                            modifier = Modifier.testTag("profile-section-${section.name}"))
                    }
                }
            }
        }
        item(key = "heading") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(state.section.label()), style = MaterialTheme.typography.titleMedium)
                if (state.section != ProfileSection.Overview) TextButton(onClick = model::refreshSection) {
                    Text(stringResource(R.string.profile_refresh))
                }
            }
        }
        if (state.section == ProfileSection.Overview) {
            state.profile?.let { profile ->
                item(key = "game-data-notice") { Text(stringResource(R.string.profile_game_data_notice), style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(profile.facts, key = { index, _ -> "fact-$index" }) { _, fact ->
                    Column {
                        Text(stringResource(fact.kind.label()), style = MaterialTheme.typography.labelLarge)
                        Text(fact.value ?: stringResource(fact.visibility.label()))
                    }
                }
                item(key = "careers") { Text(stringResource(R.string.profile_careers), style = MaterialTheme.typography.titleMedium) }
                if (profile.careerVisibility != ProfileVisibility.Visible) item(key = "career-visibility") { Text(stringResource(profile.careerVisibility.label())) }
                else if (profile.careers.isEmpty()) item(key = "career-empty") { Text(stringResource(R.string.profile_no_data)) }
                else itemsIndexed(profile.careers, key = { index, _ -> "career-$index" }) { _, career -> Text(stringResource(R.string.profile_career_level, career.name, career.level)) }
                item(key = "achievements") { Text(stringResource(R.string.profile_achievements), style = MaterialTheme.typography.titleMedium) }
                if (profile.achievementVisibility != ProfileVisibility.Visible) item(key = "achievement-visibility") { Text(stringResource(profile.achievementVisibility.label())) }
                else if (profile.achievements.isEmpty()) item(key = "achievement-empty") { Text(stringResource(R.string.profile_no_data)) }
                else itemsIndexed(profile.achievements, key = { index, _ -> "achievement-$index" }) { _, achievement ->
                    Column {
                        Text(achievement.name, style = MaterialTheme.typography.titleSmall)
                        Text(achievement.description)
                        achievement.achievedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else {
            if (state.section == ProfileSection.Followers) item(key = "followers-notice") { Text(stringResource(R.string.profile_followers_notice), style = MaterialTheme.typography.bodySmall) }
            if (state.listStatus == ProfileLoadStatus.Loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.listStatus == ProfileLoadStatus.Failed || state.listStatus == ProfileLoadStatus.Idle) item(key = "failure") { Failure(model::refreshSection) }
            if (state.listStatus == ProfileLoadStatus.Loaded && state.items.isEmpty()) item(key = "empty") { Text(stringResource(R.string.profile_empty)) }
            items(state.items, key = { "entry:${it.key}" }) { entry ->
                when (entry) {
                    is ProfileListItem.Person -> Card(onClick = { model.open(ProfileOwner.User(entry.uuid)) },
                        modifier = Modifier.testTag("profile-person-${entry.uuid}")) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            entry.avatarUrl?.let { AsyncImage(it, null, Modifier.size(48.dp)) }
                            Column {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(entry.areaName, entry.groupName).filter(String::isNotBlank).joinToString(" / "))
                                if (entry.biography.isNotBlank()) Text(entry.biography, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    is ProfileListItem.Content -> Card(modifier = Modifier.testTag("profile-entry-${entry.key}")) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entry.title.isNotBlank()) Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            val text = remember(entry.summaryHtml) { Html.fromHtml(entry.summaryHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                            if (text.isNotBlank()) Text(text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            entry.imageUrls.firstOrNull()?.let { AsyncImage(it, null, Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
                            entry.createdAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (onOpenContent != null && canOpenContent(entry.target)) TextButton(onClick = { onOpenContent(entry.target) }) {
                                Text(stringResource(R.string.profile_open_content))
                            }
                        }
                    }
                }
            }
            if (state.hasMore) item(key = "more") {
                if (state.loadMoreFailed) Text(stringResource(R.string.profile_failed))
                TextButton(onClick = model::loadMore, enabled = !state.loadingMore && state.listStatus == ProfileLoadStatus.Loaded) {
                    Text(stringResource(if (state.loadingMore) R.string.profile_loading else R.string.profile_load_more))
                }
            }
        }
    }
}

/** One state per owner and section, shared by compact and split layouts and saved across recreation. */
private class ProfileScrollStore {
    private val states = mutableMapOf<String, LazyListState>()
    fun get(key: String) = states.getOrPut(key) { LazyListState() }

    companion object {
        val Saver = listSaver<ProfileScrollStore, Any>(
            save = { store -> store.states.flatMap { (key, state) ->
                listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            } },
            restore = { saved -> ProfileScrollStore().apply {
                saved.chunked(3).forEach { (key, index, offset) ->
                    states[key as String] = LazyListState(index as Int, offset as Int)
                }
            } },
        )
    }
}

@Composable private fun Failure(retry: () -> Unit) {
    Column { Text(stringResource(R.string.profile_failed)); TextButton(onClick = retry) { Text(stringResource(R.string.profile_retry)) } }
}
private fun sections(owner: ProfileOwner) = ProfileSection.entries.filter {
    owner == ProfileOwner.Self || it !in listOf(ProfileSection.FavoritePosts, ProfileSection.FavoriteGuides)
}
private fun ProfileSection.label() = when (this) {
    ProfileSection.Overview -> R.string.profile_overview
    ProfileSection.Posts -> R.string.profile_posts
    ProfileSection.Guides -> R.string.profile_guides
    ProfileSection.Dynamics -> R.string.profile_dynamics
    ProfileSection.FavoritePosts -> R.string.profile_favorite_posts
    ProfileSection.FavoriteGuides -> R.string.profile_favorite_guides
    ProfileSection.Following -> R.string.profile_following
    ProfileSection.Followers -> R.string.profile_followers
}
private fun ProfileVisibility.label() = if (this == ProfileVisibility.Private) R.string.profile_private else R.string.profile_no_data
private fun ProfileFactKind.label() = when (this) {
    ProfileFactKind.Guild -> R.string.profile_guild
    ProfileFactKind.CreatedAt -> R.string.profile_created
    ProfileFactKind.LastLogin -> R.string.profile_last_login
    ProfileFactKind.PlayTime -> R.string.profile_play_time
    ProfileFactKind.Housing -> R.string.profile_housing
    ProfileFactKind.FantasiaUses -> R.string.profile_fantasia
    ProfileFactKind.TreasureDungeonsCleared -> R.string.profile_treasure
    ProfileFactKind.FrontlineDefeats -> R.string.profile_enemies
    ProfileFactKind.IslandSanctuaryRank -> R.string.profile_island
    ProfileFactKind.CrystalRank -> R.string.profile_crystal
    ProfileFactKind.FishingCasts -> R.string.profile_fish
}
