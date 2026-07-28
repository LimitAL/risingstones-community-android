package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.feature.forum.data.OfficialForumApiService
import top.cxmeow.risingstones.feature.glamour.ui.compose.RisingStonesGlamourLayoutMode
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataViewModel
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataLayoutMode

/**
 * Compilation of this source proves that a separate Android build can resolve public APIs and
 * their transitive dependencies from the generated Maven repository without composite builds.
 */
class PublishedArtifactsSmoke(
    val sessionProvider: RisingStonesWebCookieSessionProvider,
    val forumService: OfficialForumApiService,
    val glamourLayoutMode: RisingStonesGlamourLayoutMode,
    val personalDataViewModel: PersonalDataViewModel,
    val personalDataLayoutMode: RisingStonesPersonalDataLayoutMode,
)
