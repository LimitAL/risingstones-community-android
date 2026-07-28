package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictHandler
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictState

class ForumDomainApiBoundary(
    handler: OfficialForumIdentityConflictHandler,
) {
    val state: StateFlow<OfficialForumIdentityConflictState> = handler.identityConflictState
}
