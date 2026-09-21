package top.cxmeow.risingstones.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicService
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel
import top.cxmeow.risingstones.feature.dynamic.ui.compose.*
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.ui.compose.*
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.ui.compose.*
import top.cxmeow.risingstones.feature.message.ui.compose.RisingStonesMessageAuthorNavigation
import top.cxmeow.risingstones.feature.message.domain.MessageAuthorTarget
import top.cxmeow.risingstones.feature.profile.domain.ProfileOwner
import top.cxmeow.risingstones.feature.profile.domain.ProfileService
import top.cxmeow.risingstones.feature.profile.presentation.ProfileViewModel
import top.cxmeow.risingstones.feature.profile.ui.compose.RisingStonesProfileScreen
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentService
import top.cxmeow.risingstones.feature.recruitment.ui.compose.*
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.domain.GuildActionService
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService
import top.cxmeow.risingstones.feature.guild.ui.compose.*

internal class CommunityReadingServices(
    val profile: ProfileService,
    val forum: OfficialForumService,
    val dynamic: DynamicService,
    val glamour: GlamourService,
    val recruitment: DutyRecruitmentService,
    val guild: GuildService,
    val guildActions: GuildActionService? = guild as? GuildActionService,
    val guildImages: GuildImageUploadService? = null,
)

@Composable
internal fun CommunityReadingHost(
    navigation: CommunityReadingNavigation,
    access: CommunityReadingAccess,
    services: CommunityReadingServices,
    content: @Composable () -> Unit,
) {
    val author: ((String) -> Unit)? = if (access.profile) navigation::openAuthor else null
    RisingStonesGuildAuthorNavigation(author) {
        RisingStonesForumAuthorNavigation(author) {
            RisingStonesDynamicAuthorNavigation(author) {
                RisingStonesGlamourAuthorNavigation(author) {
                    RisingStonesRecruitmentAuthorNavigation(author) {
                        RisingStonesMessageAuthorNavigation(if (access.profile) { target ->
                            when (target) {
                                MessageAuthorTarget.Self -> navigation.open(CommunityDestination.Profile(ProfileOwner.Self))
                                is MessageAuthorTarget.Community -> navigation.openAuthor(target.uuid)
                            }
                        } else null) {
                            CommunityReadingStack(navigation, content) { entry ->
                                CommunityDestinationScreen(entry.destination, navigation, access, services)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keeps covered pages composed, but unplaced and stopped, so only the top page handles input/back. */
@Composable
internal fun CommunityReadingStack(
    navigation: CommunityReadingNavigation,
    content: @Composable () -> Unit,
    destinationContent: @Composable (CommunityReadingEntry) -> Unit,
) {
    val entries by navigation.entries.collectAsStateWithLifecycle()
    val savedState = rememberSaveableStateHolder()
    var previousKeys by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(entries) {
        val keys = entries.map { it.key }.toSet()
        (previousKeys - keys).forEach(savedState::removeState)
        previousKeys = keys
    }
    ReadingLayer(visible = entries.isEmpty(), content = content)
    if (entries.isNotEmpty()) {
        val density = LocalDensity.current
        val uriHandler = LocalUriHandler.current
        Dialog(onDismissRequest = navigation::back,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            CompositionLocalProvider(LocalDensity provides density, LocalUriHandler provides uriHandler) {
                Surface(Modifier.fillMaxSize().testTag("community-reading-stack")) {
                    Box(Modifier.fillMaxSize()) {
                        entries.forEachIndexed { index, entry ->
                            key(entry.key) {
                                savedState.SaveableStateProvider(entry.key) {
                                    CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                                        ReadingLayer(visible = index == entries.lastIndex) { destinationContent(entry) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingLayer(visible: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { ReadingLifecycleOwner() }
    val currentVisible by rememberUpdatedState(visible)
    fun synchronize() {
        val state = parent.lifecycle.currentState
        owner.registry.currentState = if (currentVisible || state < Lifecycle.State.CREATED) state else Lifecycle.State.CREATED
    }
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> synchronize() }
        parent.lifecycle.addObserver(observer)
        synchronize()
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
    SideEffect { synchronize() }
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (visible) placeables.forEach { it.placeRelative(0, 0) }
            }
        }
    }
}

private class ReadingLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
}

@Composable
private fun CommunityDestinationScreen(destination: CommunityDestination, navigation: CommunityReadingNavigation,
    access: CommunityReadingAccess, services: CommunityReadingServices) {
    when (destination) {
        CommunityDestination.Guild -> RisingStonesGuildActionProvider(services.guildActions, services.guildImages) {
            RisingStonesGuildScreen(services.guild, navigation::back,
                onOpenActivity = { navigation.open(CommunityDestination.Dynamic(it)) },
                canOpenActivity = { access.allows(CommunityDestination.Dynamic(it)) })
        }
        is CommunityDestination.GuildPhoto -> RisingStonesGuildActionProvider(services.guildActions, services.guildImages) {
            RisingStonesGuildPhotoScreen(services.guild, destination.id, navigation::back)
        }
        is CommunityDestination.Profile -> {
            val model = viewModel<ProfileViewModel>(factory = viewModelFactory {
                initializer { ProfileViewModel(services.profile).apply { openRoot(destination.owner) } }
            })
            RisingStonesProfileScreen(model, navigation::back,
                canOpenContent = { access.allows(it.destination()) },
                onOpenContent = { navigation.open(it.destination()) })
        }
        is CommunityDestination.Post -> RisingStonesForumPostScreen(services.forum, destination.id, navigation::back)
        is CommunityDestination.Glamour -> RisingStonesGlamourDetailScreen(services.glamour, destination.id, navigation::back)
        is CommunityDestination.Recruitment -> RisingStonesRecruitmentDetailScreen(services.recruitment,
            destination.id, destination.board, navigation::back)
        is CommunityDestination.Dynamic -> {
            val model = viewModel<DynamicViewModel>(factory = viewModelFactory {
                initializer { DynamicViewModel(services.dynamic).apply { select(destination.id) } }
            })
            RisingStonesDynamicDetailScreen(model, navigation::back,
                canOpenReference = { it.destination()?.let(access::allows) == true },
                onOpenReference = { it.destination()?.let(navigation::open) })
        }
    }
}
