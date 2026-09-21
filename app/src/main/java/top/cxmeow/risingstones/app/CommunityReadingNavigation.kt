package top.cxmeow.risingstones.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicOrigin
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicReference
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

internal sealed interface CommunityDestination {
    data class Profile(val owner: ProfileOwner) : CommunityDestination
    data class Post(val id: Int) : CommunityDestination
    data class Dynamic(val id: Int) : CommunityDestination
    data class Glamour(val id: Int) : CommunityDestination
    data class Recruitment(val id: Int, val board: RecruitmentBoardKind) : CommunityDestination
    data object Guild : CommunityDestination
    data class GuildPhoto(val id: Int) : CommunityDestination
}

internal data class CommunityReadingAccess(
    val profile: Boolean = false,
    val dynamic: Boolean = false,
    val glamour: Boolean = false,
    val guildRecruitment: Boolean = false,
    val guild: Boolean = false,
) {
    fun allows(destination: CommunityDestination): Boolean = when (destination) {
        is CommunityDestination.Profile -> profile && (destination.owner !is ProfileOwner.User ||
            destination.owner.uuid.isNotBlank())
        is CommunityDestination.Post -> destination.id > 0
        is CommunityDestination.Dynamic -> dynamic && destination.id > 0
        is CommunityDestination.Glamour -> glamour && destination.id > 0
        is CommunityDestination.Recruitment -> destination.id > 0 &&
            (destination.board != RecruitmentBoardKind.Guild || guildRecruitment)
        CommunityDestination.Guild -> guild
        is CommunityDestination.GuildPhoto -> guild && destination.id > 0
    }
}

/** Stores live reading destinations only; identity and content are not written into saved state. */
internal class CommunityReadingEntry(val key: Long, val destination: CommunityDestination) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

internal class CommunityReadingNavigation : ViewModel() {
    private val mutableEntries = MutableStateFlow<List<CommunityReadingEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private var access = CommunityReadingAccess()
    private var revision: Long? = null
    private var nextKey = 0L

    fun synchronize(access: CommunityReadingAccess, protectedRevision: Long) {
        if ((revision != null && revision != protectedRevision) ||
            entries.value.any { !access.allows(it.destination) }) clear()
        this.access = access
        revision = protectedRevision
    }

    fun open(destination: CommunityDestination) {
        if (!access.allows(destination) || entries.value.lastOrNull()?.destination == destination) return
        mutableEntries.value = entries.value + CommunityReadingEntry(++nextKey, destination)
    }

    fun openAuthor(uuid: String) {
        if (uuid.isNotBlank()) open(CommunityDestination.Profile(ProfileOwner.User(uuid)))
    }

    fun back() {
        val current = entries.value
        val removed = current.lastOrNull() ?: return
        mutableEntries.value = current.dropLast(1)
        removed.viewModelStore.clear()
    }

    fun clear() {
        val old = entries.value
        mutableEntries.value = emptyList()
        old.forEach { it.viewModelStore.clear() }
    }

    override fun onCleared() { clear() }
}

internal fun ProfileContentTarget.destination(): CommunityDestination = when (kind) {
    ProfileContentKind.Post, ProfileContentKind.Guide -> CommunityDestination.Post(id)
    ProfileContentKind.Dynamic -> CommunityDestination.Dynamic(id)
}

internal fun DynamicReference.destination(): CommunityDestination? {
    val id = id.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return when (origin) {
        DynamicOrigin.Post, DynamicOrigin.Guide -> CommunityDestination.Post(id)
        DynamicOrigin.Dynamic -> CommunityDestination.Dynamic(id)
        DynamicOrigin.Glamour -> CommunityDestination.Glamour(id)
        DynamicOrigin.BeginnerRecruitment -> CommunityDestination.Recruitment(id, RecruitmentBoardKind.Beginner)
        DynamicOrigin.DutyRecruitment -> CommunityDestination.Recruitment(id, RecruitmentBoardKind.Duty)
        DynamicOrigin.GuildRecruitment -> CommunityDestination.Recruitment(id, RecruitmentBoardKind.Guild)
        DynamicOrigin.RolePlayRecruitment -> CommunityDestination.Recruitment(id, RecruitmentBoardKind.RolePlay)
        DynamicOrigin.OtherRecruitment -> CommunityDestination.Recruitment(id, RecruitmentBoardKind.Other)
        DynamicOrigin.Original, DynamicOrigin.Unknown -> null
    }
}

internal fun MessageTarget.destination(): CommunityDestination? = when (kind) {
    MessageTargetKind.Post, MessageTargetKind.Guide -> CommunityDestination.Post(id)
    MessageTargetKind.Dynamic -> CommunityDestination.Dynamic(id)
    MessageTargetKind.Glamour -> CommunityDestination.Glamour(id)
    MessageTargetKind.RolePlayRecruitment -> CommunityDestination.Recruitment(id, RecruitmentBoardKind.RolePlay)
    MessageTargetKind.Recruitment -> CommunityDestination.Recruitment(id, when (recruitmentChannel) {
        MessageRecruitmentChannel.Beginner -> RecruitmentBoardKind.Beginner
        MessageRecruitmentChannel.Guild -> RecruitmentBoardKind.Guild
        MessageRecruitmentChannel.Other -> RecruitmentBoardKind.Other
        MessageRecruitmentChannel.Duty, null -> RecruitmentBoardKind.Duty
    })
    MessageTargetKind.GuildPhoto -> CommunityDestination.GuildPhoto(id)
}
