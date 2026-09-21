package top.cxmeow.risingstones.feature.account.domain

/** Real first-use writes, never probes. Invoke only after the user confirms the specific action. */
interface RisingStonesAccountActionVerificationService {
    val canVerifyDailySignIn: Boolean
    suspend fun verifyDailySignIn(): RisingStonesDailySignInResult
    suspend fun verifyClaimReward(id: Int): String
}
