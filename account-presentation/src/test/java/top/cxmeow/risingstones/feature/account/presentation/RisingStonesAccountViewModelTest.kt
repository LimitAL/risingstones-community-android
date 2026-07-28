package top.cxmeow.risingstones.feature.account.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountDashboard
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

        viewModel.claimReward(7)
        advanceUntilIdle()
        assertEquals("claimed", viewModel.state.value.message)
        assertEquals(3, viewModel.state.value.dashboard?.signInCount)
        assertTrue(viewModel.state.value.claimingRewardIds.isEmpty())
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
}

private class FakeAccountService : RisingStonesAccountService {
    var dashboardFetches = 0
    var fail = false

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

    override suspend fun signIn() = RisingStonesDailySignInResult(
        message = "signed",
        isAlreadyCheckedIn = false,
        continuousDays = 1,
        totalDays = 1,
        communityExperience = null,
        shopExperience = null,
    )

    override suspend fun claimReward(id: Int): String = "claimed"
}
