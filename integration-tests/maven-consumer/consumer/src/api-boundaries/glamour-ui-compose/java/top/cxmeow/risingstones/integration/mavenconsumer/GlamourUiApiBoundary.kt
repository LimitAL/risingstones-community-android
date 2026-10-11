package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import top.cxmeow.risingstones.feature.glamour.ui.compose.RisingStonesGlamourAuthorNavigation

@Composable
fun PublishedGlamourAuthorNavigation() {
    RisingStonesGlamourAuthorNavigation(null) { }
}

fun PublishedGlamourTypedFollowFailureCode(
    state: top.cxmeow.risingstones.feature.glamour.presentation.GlamourUiState =
        top.cxmeow.risingstones.feature.glamour.presentation.GlamourUiState(),
): Int? = (state.followFailure as?
    top.cxmeow.risingstones.feature.glamour.domain.GlamourException.Business)?.code
