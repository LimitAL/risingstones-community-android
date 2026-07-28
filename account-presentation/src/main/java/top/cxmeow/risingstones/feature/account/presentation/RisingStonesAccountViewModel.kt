package top.cxmeow.risingstones.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountDashboard
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountService

data class RisingStonesAccountUiState(
    val dashboard: RisingStonesAccountDashboard? = null,
    val isLoading: Boolean = false,
    val isSigningIn: Boolean = false,
    val claimingRewardIds: Set<Int> = emptySet(),
    val message: String? = null,
    val error: String? = null,
)

class RisingStonesAccountViewModel(
    private val service: RisingStonesAccountService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RisingStonesAccountUiState())
    val state: StateFlow<RisingStonesAccountUiState> = mutableState.asStateFlow()

    fun load() {
        if (mutableState.value.isLoading) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                mutableState.update {
                    it.copy(
                        dashboard = service.fetchDashboard(),
                        isLoading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isLoading = false, error = error.message)
                }
            }
        }
    }

    fun signIn() {
        if (mutableState.value.isSigningIn) return
        viewModelScope.launch {
            mutableState.update { it.copy(isSigningIn = true, error = null, message = null) }
            try {
                val result = service.signIn()
                val dashboard = service.fetchDashboard()
                mutableState.update {
                    it.copy(
                        dashboard = dashboard,
                        isSigningIn = false,
                        message = result.message,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isSigningIn = false, error = error.message)
                }
            }
        }
    }

    fun claimReward(id: Int) {
        if (id in mutableState.value.claimingRewardIds) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    claimingRewardIds = it.claimingRewardIds + id,
                    error = null,
                    message = null,
                )
            }
            try {
                val message = service.claimReward(id)
                val dashboard = service.fetchDashboard()
                mutableState.update {
                    it.copy(
                        dashboard = dashboard,
                        claimingRewardIds = it.claimingRewardIds - id,
                        message = message,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        claimingRewardIds = it.claimingRewardIds - id,
                        error = error.message,
                    )
                }
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null, error = null) }
    }
}
