package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountUiState
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel

class AccountPresentationApiBoundary(
    viewModel: RisingStonesAccountViewModel,
) {
    val lifecycleViewModel: ViewModel = viewModel
    val state: StateFlow<RisingStonesAccountUiState> = viewModel.state
}
