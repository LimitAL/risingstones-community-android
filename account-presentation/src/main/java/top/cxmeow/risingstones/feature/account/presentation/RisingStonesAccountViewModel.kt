package top.cxmeow.risingstones.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountActionVerificationService
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
    private var currentDate: () -> LocalDate = { LocalDate.now(ActionZone) }
    private val mutableState = MutableStateFlow(RisingStonesAccountUiState())
    val state: StateFlow<RisingStonesAccountUiState> = mutableState.asStateFlow()
    @Volatile private var cleared = false
    private var verifiedDailySignInDate: LocalDate? = null
    private val verifiedRewardMonths = mutableMapOf<Int, YearMonth>()

    internal constructor(
        service: RisingStonesAccountService,
        currentDate: () -> LocalDate,
    ) : this(service) {
        this.currentDate = currentDate
    }

    val canVerifyDailySignIn: Boolean
        get() = !hasVerifiedDailySignIn && verifier()?.canVerifyDailySignIn == true

    fun canVerifyClaimReward(id: Int): Boolean =
        id > 0 && !hasVerifiedClaimReward(id) && verifier()?.canVerifyDailySignIn == true

    val hasVerifiedDailySignIn: Boolean
        get() = verifiedDailySignInDate == currentDate()

    fun hasVerifiedClaimReward(id: Int): Boolean =
        verifiedRewardMonths[id] == YearMonth.from(currentDate())

    fun load() {
        if (mutableState.value.isLoading) return
        updateState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val dashboard = service.fetchDashboard()
                ensureActive()
                updateState {
                    it.copy(
                        dashboard = dashboard,
                        isLoading = false,
                    )
                }
            } catch (error: CancellationException) {
                updateState { it.copy(isLoading = false) }
                throw error
            } catch (error: Throwable) {
                updateState {
                    it.copy(isLoading = false, error = error.message)
                }
            }
        }
    }

    fun signIn() {
        if (mutableState.value.isSigningIn || hasVerifiedDailySignIn) return
        updateState { it.copy(isSigningIn = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val actionDate = currentDate()
                val result = service.signIn()
                ensureActive()
                verifiedDailySignInDate = actionDate
                updateState { it.copy(message = result.message) }
                val dashboard = service.fetchDashboard()
                ensureActive()
                updateState {
                    it.copy(
                        dashboard = dashboard,
                        isSigningIn = false,
                    )
                }
            } catch (error: CancellationException) {
                updateState { it.copy(isSigningIn = false) }
                throw error
            } catch (error: Throwable) {
                updateState {
                    it.copy(isSigningIn = false, error = error.message)
                }
            }
        }
    }

    fun claimReward(id: Int) {
        if (id <= 0 || id in mutableState.value.claimingRewardIds || hasVerifiedClaimReward(id)) return
        updateState {
            it.copy(
                claimingRewardIds = it.claimingRewardIds + id,
                error = null,
                message = null,
            )
        }
        viewModelScope.launch {
            try {
                val actionDate = currentDate()
                val message = service.claimReward(id)
                ensureActive()
                verifiedRewardMonths[id] = YearMonth.from(actionDate)
                updateState { it.copy(message = message) }
                val dashboard = service.fetchDashboard()
                ensureActive()
                updateState {
                    it.copy(
                        dashboard = dashboard,
                        claimingRewardIds = it.claimingRewardIds - id,
                    )
                }
            } catch (error: CancellationException) {
                updateState { it.copy(claimingRewardIds = it.claimingRewardIds - id) }
                throw error
            } catch (error: Throwable) {
                updateState {
                    it.copy(
                        claimingRewardIds = it.claimingRewardIds - id,
                        error = error.message,
                    )
                }
            }
        }
    }

    /** Performs one real sign-in only after the UI has received an explicit confirmation. */
    fun verifyDailySignIn() {
        val verifier = verifier() ?: return
        if (!verifier.canVerifyDailySignIn || hasVerifiedDailySignIn || mutableState.value.isSigningIn) return
        updateState { it.copy(isSigningIn = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val actionDate = currentDate()
                val result = verifier.verifyDailySignIn()
                ensureActive()
                if (cleared) return@launch
                verifiedDailySignInDate = actionDate
                updateState { it.copy(message = result.message) }
                val dashboard = service.fetchDashboard()
                ensureActive()
                updateState { it.copy(dashboard = dashboard, isSigningIn = false) }
            } catch (error: CancellationException) {
                updateState { it.copy(isSigningIn = false) }
                throw error
            } catch (error: Throwable) {
                updateState { it.copy(isSigningIn = false, error = error.message) }
            }
        }
    }

    /** Performs one real reward claim only after the UI has received an explicit confirmation. */
    fun verifyClaimReward(id: Int) {
        val verifier = verifier() ?: return
        if (!verifier.canVerifyDailySignIn || id <= 0 || id in mutableState.value.claimingRewardIds ||
            hasVerifiedClaimReward(id)) return
        updateState {
            it.copy(
                claimingRewardIds = it.claimingRewardIds + id,
                error = null,
                message = null,
            )
        }
        viewModelScope.launch {
            try {
                val actionDate = currentDate()
                val message = verifier.verifyClaimReward(id)
                ensureActive()
                if (cleared) return@launch
                verifiedRewardMonths[id] = YearMonth.from(actionDate)
                updateState { it.copy(message = message) }
                val dashboard = service.fetchDashboard()
                ensureActive()
                updateState {
                    it.copy(dashboard = dashboard, claimingRewardIds = it.claimingRewardIds - id)
                }
            } catch (error: CancellationException) {
                updateState { it.copy(claimingRewardIds = it.claimingRewardIds - id) }
                throw error
            } catch (error: Throwable) {
                updateState {
                    it.copy(claimingRewardIds = it.claimingRewardIds - id, error = error.message)
                }
            }
        }
    }

    fun clearMessage() {
        updateState { it.copy(message = null, error = null) }
    }

    override fun onCleared() {
        cleared = true
        verifiedDailySignInDate = null
        verifiedRewardMonths.clear()
        mutableState.value = RisingStonesAccountUiState()
        super.onCleared()
    }

    private inline fun updateState(transform: (RisingStonesAccountUiState) -> RisingStonesAccountUiState) {
        if (!cleared) mutableState.update(transform)
    }

    private fun verifier(): RisingStonesAccountActionVerificationService? =
        service as? RisingStonesAccountActionVerificationService

    private companion object {
        val ActionZone: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
