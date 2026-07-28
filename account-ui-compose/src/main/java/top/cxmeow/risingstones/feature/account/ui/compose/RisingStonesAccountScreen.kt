package top.cxmeow.risingstones.feature.account.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cxmeow.risingstones.feature.account.domain.RisingStonesReward
import top.cxmeow.risingstones.feature.account.domain.RisingStonesRewardStatus
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel

/**
 * Optional default account UI.
 *
 * Account data is reading-focused, so compact, medium, and expanded windows intentionally share a
 * constrained single-pane layout. Host apps may replace this UI while reusing domain, data, and
 * presentation modules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesAccountScreen(
    viewModel: RisingStonesAccountViewModel,
    sessionDisplayName: String?,
    canDailySignIn: Boolean,
    onManageSession: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.load()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rising_stones_account_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.rising_stones_account_back))
                    }
                },
                actions = {
                    TextButton(onClick = onManageSession) {
                        Text(stringResource(R.string.rising_stones_account_manage_session))
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.dashboard == null && state.isLoading -> {
                    CircularProgressIndicator(Modifier.padding(32.dp))
                }

                state.dashboard == null && state.error != null -> {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = viewModel::load) {
                            Text(stringResource(R.string.rising_stones_account_retry))
                        }
                    }
                }

                else -> {
                    val dashboard = state.dashboard
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = dashboard?.character?.characterName
                                            ?: sessionDisplayName.orEmpty(),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                    dashboard?.character?.let { character ->
                                        Text(
                                            text = listOf(
                                                character.areaName,
                                                character.groupName,
                                            ).filter(String::isNotBlank).joinToString(" · "),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    dashboard?.houseRemainDayText?.let {
                                        Text(
                                            text = "${stringResource(R.string.rising_stones_account_house)}：$it",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.rising_stones_account_sign_count,
                                            dashboard?.signInCount
                                                ?: dashboard?.signInLogs?.size
                                                ?: 0,
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (canDailySignIn) {
                                        Spacer(Modifier.height(4.dp))
                                        Button(
                                            onClick = viewModel::signIn,
                                            enabled = !state.isSigningIn,
                                        ) {
                                            if (state.isSigningIn) {
                                                CircularProgressIndicator()
                                            } else {
                                                Text(
                                                    stringResource(
                                                        R.string.rising_stones_account_sign_in,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        state.message?.let { message ->
                            item {
                                Text(
                                    text = message,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        state.error?.let { error ->
                            item {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        item {
                            Text(
                                text = stringResource(R.string.rising_stones_account_rewards),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        val rewards = dashboard?.rewards.orEmpty()
                        if (rewards.isEmpty()) {
                            item {
                                Text(stringResource(R.string.rising_stones_account_no_rewards))
                            }
                        } else {
                            items(rewards, key = RisingStonesReward::id) { reward ->
                                RewardCard(
                                    reward = reward,
                                    canClaim = canDailySignIn,
                                    isClaiming = reward.id in state.claimingRewardIds,
                                    onClaim = { viewModel.claimReward(reward.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RewardCard(
    reward: RisingStonesReward,
    canClaim: Boolean,
    isClaiming: Boolean,
    onClaim: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(reward.description, style = MaterialTheme.typography.titleMedium)
                reward.requiredDays?.let { days ->
                    Text(
                        text = stringResource(
                            R.string.rising_stones_account_required_days,
                            days,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when (reward.status) {
                RisingStonesRewardStatus.Claimable -> if (canClaim) {
                    Button(onClick = onClaim, enabled = !isClaiming) {
                        Text(stringResource(R.string.rising_stones_account_claim))
                    }
                } else {
                    Text(stringResource(R.string.rising_stones_account_not_qualified))
                }

                RisingStonesRewardStatus.Received ->
                    Text(stringResource(R.string.rising_stones_account_received))

                RisingStonesRewardStatus.NotQualified ->
                    Text(stringResource(R.string.rising_stones_account_not_qualified))
            }
        }
    }
}
