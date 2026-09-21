package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.feature.forum.data.OfficialForumApiService
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailInteractionState
import top.cxmeow.risingstones.feature.glamour.ui.compose.RisingStonesGlamourLayoutMode
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataViewModel
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataLayoutMode

/**
 * Compilation of this source proves that a separate Android build can resolve public APIs and
 * their transitive dependencies from the generated Maven repository without composite builds.
 */
class PublishedArtifactsSmoke(
    val profileViewModel: top.cxmeow.risingstones.feature.profile.presentation.ProfileViewModel,
    val profileLayoutMode: top.cxmeow.risingstones.feature.profile.ui.compose.ProfileLayoutMode,
    val messageViewModel: top.cxmeow.risingstones.feature.message.presentation.MessageViewModel,
    val messageLayoutMode: top.cxmeow.risingstones.feature.message.ui.compose.MessageLayoutMode,
    val dynamicViewModel: top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel,
    val dynamicLayoutMode: top.cxmeow.risingstones.feature.dynamic.ui.compose.DynamicLayoutMode,
    val sessionProvider: RisingStonesWebCookieSessionProvider,
    val forumService: OfficialForumApiService,
    val glamourLayoutMode: RisingStonesGlamourLayoutMode,
    val personalDataViewModel: PersonalDataViewModel,
    val personalDataLayoutMode: RisingStonesPersonalDataLayoutMode,
)

fun resumePublishedForumDraft(model: OfficialForumDetailViewModel): OfficialForumDetailInteractionState {
    model.resumeCommentComposer()
    return model.interactionState.value
}

fun editPublishedForumDraft(model: OfficialForumDetailViewModel, text: String):
    top.cxmeow.risingstones.feature.forum.presentation.OfficialForumCommentEditorState {
    model.updateCommentEditor(text, text.length, text.length,
        top.cxmeow.risingstones.feature.forum.presentation.OfficialForumCommentTextChange(
            0, model.commentEditorState.value.text.length))
    return model.commentEditorState.value
}

fun publishedForumAuthoring(service: OfficialForumApiService):
    top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentAuthoringService = service

fun publishedRecruitmentState(
    model: top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentViewModel,
): Pair<top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentInteractionState,
    top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBrowsingState> =
    model.interactionState.value to model.browsingState.value

fun publishedRolePlayDirectoryState(
    model: top.cxmeow.risingstones.feature.recruitment.presentation.RolePlayDirectoryViewModel,
): top.cxmeow.risingstones.feature.recruitment.presentation.RolePlayDirectoryState = model.state.value

fun openPublishedProfileRoot(model: top.cxmeow.risingstones.feature.profile.presentation.ProfileViewModel,
    owner: top.cxmeow.risingstones.feature.profile.domain.ProfileOwner) = model.openRoot(owner)

fun publishedAuthorStates(message: top.cxmeow.risingstones.feature.message.presentation.MessageViewModel,
    recruitment: top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentViewModel) =
    message.authorState.value to recruitment.authorState.value
