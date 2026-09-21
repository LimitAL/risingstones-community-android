package top.cxmeow.risingstones.feature.account.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountDashboard
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountActionVerificationService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesDailySignInResult
import top.cxmeow.risingstones.feature.account.domain.RisingStonesReward
import top.cxmeow.risingstones.feature.account.domain.RisingStonesRewardStatus
import top.cxmeow.risingstones.feature.account.domain.RisingStonesSignInSummary

@OptIn(ExperimentalCoroutinesApi::class)
class RisingStonesAccountViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadSignInAndClaimRefreshTheSharedDashboard() = runTest {
        val service = FakeAccountService()
        val viewModel = RisingStonesAccountViewModel(service)

        viewModel.load()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.dashboard?.signInCount)

        viewModel.signIn()
        advanceUntilIdle()
        assertEquals("signed", viewModel.state.value.message)
        assertEquals(2, viewModel.state.value.dashboard?.signInCount)
        assertTrue(viewModel.hasVerifiedDailySignIn)
        viewModel.signIn()
        advanceUntilIdle()
        assertEquals(1, service.signInCalls)

        viewModel.claimReward(7)
        advanceUntilIdle()
        assertEquals("claimed", viewModel.state.value.message)
        assertEquals(3, viewModel.state.value.dashboard?.signInCount)
        assertTrue(viewModel.state.value.claimingRewardIds.isEmpty())
        assertTrue(viewModel.hasVerifiedClaimReward(7))
        viewModel.claimReward(7)
        advanceUntilIdle()
        assertEquals(1, service.claimCalls)
    }

    @Test
    fun refreshFailurePreservesConfirmedDashboard() = runTest {
        val service = FakeAccountService()
        val viewModel = RisingStonesAccountViewModel(service)
        viewModel.load()
        advanceUntilIdle()
        service.fail = true

        viewModel.load()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.dashboard?.signInCount)
        assertEquals("account failed", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun firstUseSignInIsEligibleOnlyForOptionalServiceAndSubmitsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = VerifyingAccountService(signInGate = gate)
        val viewModel = RisingStonesAccountViewModel(service)

        assertTrue(viewModel.canVerifyDailySignIn)
        assertTrue(viewModel.canVerifyClaimReward(7))
        viewModel.verifyDailySignIn()
        viewModel.verifyDailySignIn()
        viewModel.signIn()
        assertTrue(viewModel.state.value.isSigningIn)
        advanceUntilIdle()
        assertEquals(1, service.verifySignInCalls)
        assertEquals(0, service.signInCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, service.verifySignInCalls)
        assertEquals("verified sign-in", viewModel.state.value.message)
        assertTrue(viewModel.hasVerifiedDailySignIn)
        assertFalse(viewModel.state.value.isSigningIn)
        viewModel.verifyDailySignIn()
        advanceUntilIdle()
        assertEquals(1, service.verifySignInCalls)
    }

    @Test
    fun firstUseRewardUsesSharedBusyStateAndSubmitsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = VerifyingAccountService(rewardGate = gate)
        val viewModel = RisingStonesAccountViewModel(service)

        viewModel.verifyClaimReward(7)
        viewModel.verifyClaimReward(7)
        viewModel.claimReward(7)
        assertTrue(7 in viewModel.state.value.claimingRewardIds)
        advanceUntilIdle()
        assertEquals(1, service.verifyClaimCalls)
        assertEquals(0, service.claimCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(7 in viewModel.state.value.claimingRewardIds)
        assertTrue(viewModel.hasVerifiedClaimReward(7))
        viewModel.verifyClaimReward(7)
        advanceUntilIdle()
        assertEquals(1, service.verifyClaimCalls)
    }

    @Test
    fun completedFirstUseWriteKeepsNoticeAndDashboardWhenRefreshFails() = runTest {
        val service = VerifyingAccountService()
        val viewModel = RisingStonesAccountViewModel(service)
        viewModel.load()
        advanceUntilIdle()
        val confirmedDashboard = viewModel.state.value.dashboard
        service.failDashboard = true

        viewModel.verifyClaimReward(7)
        advanceUntilIdle()

        assertEquals(1, service.verifyClaimCalls)
        assertEquals("verified reward", viewModel.state.value.message)
        assertTrue(viewModel.hasVerifiedClaimReward(7))
        assertEquals(confirmedDashboard, viewModel.state.value.dashboard)
        assertEquals("refresh failed", viewModel.state.value.error)
        assertFalse(7 in viewModel.state.value.claimingRewardIds)
        viewModel.verifyClaimReward(7)
        advanceUntilIdle()
        assertEquals(1, service.verifyClaimCalls)
    }

    @Test
    fun completedFirstUseSignInKeepsNoticeAndDashboardWhenRefreshFails() = runTest {
        val service = VerifyingAccountService()
        val viewModel = RisingStonesAccountViewModel(service)
        viewModel.load()
        advanceUntilIdle()
        val confirmedDashboard = viewModel.state.value.dashboard
        service.failDashboard = true

        viewModel.verifyDailySignIn()
        advanceUntilIdle()

        assertEquals(1, service.verifySignInCalls)
        assertEquals("verified sign-in", viewModel.state.value.message)
        assertTrue(viewModel.hasVerifiedDailySignIn)
        assertEquals(confirmedDashboard, viewModel.state.value.dashboard)
        assertEquals("refresh failed", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isSigningIn)
    }

    @Test
    fun cancellationReleasesFirstUseBusyState() = runTest {
        val service = VerifyingAccountService(cancelFirstSignIn = true)
        val viewModel = RisingStonesAccountViewModel(service)

        viewModel.verifyDailySignIn()
        advanceUntilIdle()

        assertEquals(1, service.verifySignInCalls)
        assertFalse(viewModel.state.value.isSigningIn)
        assertFalse(viewModel.hasVerifiedDailySignIn)

        viewModel.verifyDailySignIn()
        advanceUntilIdle()

        assertEquals(2, service.verifySignInCalls)
        assertTrue(viewModel.hasVerifiedDailySignIn)
    }

    @Test
    fun clearedViewModelIgnoresLateFirstUseSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = VerifyingAccountService(lateGate = gate)
        val viewModel = RisingStonesAccountViewModel(service)

        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.dashboard != null)
        viewModel.verifyDailySignIn()
        advanceUntilIdle()
        assertEquals(1, service.verifySignInCalls)
        clearViewModel(viewModel)
        assertEquals(RisingStonesAccountUiState(), viewModel.state.value)
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.hasVerifiedDailySignIn)
        assertEquals(RisingStonesAccountUiState(), viewModel.state.value)
    }

    @Test
    fun completedActionsExpireByShanghaiDayAndMonth() = runTest {
        var date = LocalDate.of(2026, 9, 20)
        val service = VerifyingAccountService()
        val viewModel = RisingStonesAccountViewModel(service) { date }

        viewModel.verifyDailySignIn()
        viewModel.verifyClaimReward(7)
        advanceUntilIdle()
        assertTrue(viewModel.hasVerifiedDailySignIn)
        assertTrue(viewModel.hasVerifiedClaimReward(7))
        assertFalse(viewModel.canVerifyDailySignIn)
        assertFalse(viewModel.canVerifyClaimReward(7))

        date = LocalDate.of(2026, 9, 21)
        assertFalse(viewModel.hasVerifiedDailySignIn)
        assertTrue(viewModel.canVerifyDailySignIn)
        assertTrue(viewModel.hasVerifiedClaimReward(7))
        assertFalse(viewModel.canVerifyClaimReward(7))

        date = LocalDate.of(2026, 10, 1)
        assertFalse(viewModel.hasVerifiedClaimReward(7))
        assertTrue(viewModel.canVerifyClaimReward(7))
    }

    @Test
    fun lateSuccessKeepsTheActionPeriodRatherThanTheResponsePeriod() = runTest {
        var date = LocalDate.of(2026, 9, 30)
        val gate = CompletableDeferred<Unit>()
        val service = VerifyingAccountService(signInGate = gate, rewardGate = gate)
        val viewModel = RisingStonesAccountViewModel(service) { date }
        viewModel.verifyDailySignIn()
        viewModel.verifyClaimReward(7)
        advanceUntilIdle()
        date = LocalDate.of(2026, 10, 1)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.hasVerifiedDailySignIn)
        assertFalse(viewModel.hasVerifiedClaimReward(7))
        assertTrue(viewModel.canVerifyDailySignIn)
        assertTrue(viewModel.canVerifyClaimReward(7))
    }

    @Test
    fun serviceWithoutFirstUseContractIsNotEligible() = runTest {
        val viewModel = RisingStonesAccountViewModel(FakeAccountService())

        assertFalse(viewModel.canVerifyDailySignIn)
        assertFalse(viewModel.canVerifyClaimReward(7))
        viewModel.verifyDailySignIn()
        viewModel.verifyClaimReward(7)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isSigningIn)
        assertTrue(viewModel.state.value.claimingRewardIds.isEmpty())
    }
}

private fun clearViewModel(viewModel: RisingStonesAccountViewModel) {
    ViewModelStore().apply { put("account", viewModel) }.clear()
}

private class FakeAccountService : RisingStonesAccountService {
    var dashboardFetches = 0
    var fail = false
    var signInCalls = 0
    var claimCalls = 0

    override suspend fun fetchSignInSummary() = RisingStonesSignInSummary(
        signInCount = dashboardFetches,
        signInLogs = emptyList(),
        rewards = emptyList(),
    )

    override suspend fun fetchDashboard(): RisingStonesAccountDashboard {
        if (fail) error("account failed")
        dashboardFetches += 1
        return RisingStonesAccountDashboard(
            character = null,
            houseRemainDayText = null,
            signInCount = dashboardFetches,
            signInLogs = emptyList(),
            rewards = listOf(
                RisingStonesReward(
                    id = 7,
                    description = "Reward",
                    itemName = null,
                    requiredDays = 1,
                    status = RisingStonesRewardStatus.Claimable,
                ),
            ),
        )
    }

    override suspend fun signIn(): RisingStonesDailySignInResult {
        signInCalls += 1
        return RisingStonesDailySignInResult(
            message = "signed",
            isAlreadyCheckedIn = false,
            continuousDays = 1,
            totalDays = 1,
            communityExperience = null,
            shopExperience = null,
        )
    }

    override suspend fun claimReward(id: Int): String {
        claimCalls += 1
        return "claimed"
    }
}

private class VerifyingAccountService(
    private val cancelFirstSignIn: Boolean = false,
    private val lateGate: CompletableDeferred<Unit>? = null,
    private val signInGate: CompletableDeferred<Unit>? = null,
    private val rewardGate: CompletableDeferred<Unit>? = null,
) : RisingStonesAccountService, RisingStonesAccountActionVerificationService {
    var verifySignInCalls = 0
    var verifyClaimCalls = 0
    var signInCalls = 0
    var claimCalls = 0
    var dashboardFetches = 0
    var failDashboard = false

    override val canVerifyDailySignIn: Boolean = true

    override suspend fun fetchSignInSummary() = RisingStonesSignInSummary(
        signInCount = dashboardFetches,
        signInLogs = emptyList(),
        rewards = emptyList(),
    )

    override suspend fun fetchDashboard(): RisingStonesAccountDashboard {
        if (failDashboard) error("refresh failed")
        dashboardFetches += 1
        return RisingStonesAccountDashboard(
            character = null,
            houseRemainDayText = null,
            signInCount = dashboardFetches,
            signInLogs = emptyList(),
            rewards = emptyList(),
        )
    }

    override suspend fun signIn(): RisingStonesDailySignInResult {
        signInCalls += 1
        return RisingStonesDailySignInResult("unused", false, null, null, null, null)
    }

    override suspend fun claimReward(id: Int): String {
        claimCalls += 1
        return "unused"
    }

    override suspend fun verifyDailySignIn(): RisingStonesDailySignInResult {
        verifySignInCalls += 1
        if (cancelFirstSignIn && verifySignInCalls == 1) throw CancellationException("cancelled")
        lateGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        signInGate?.await()
        return RisingStonesDailySignInResult("verified sign-in", false, null, null, null, null)
    }

    override suspend fun verifyClaimReward(id: Int): String {
        verifyClaimCalls += 1
        rewardGate?.await()
        return "verified reward"
    }
}
