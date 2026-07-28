package top.cxmeow.risingstones.feature.account.domain

data class RisingStonesCharacter(
    val areaName: String,
    val groupName: String,
    val characterName: String,
)

enum class RisingStonesRewardStatus {
    NotQualified,
    Claimable,
    Received,
}

data class RisingStonesReward(
    val id: Int,
    val description: String,
    val itemName: String?,
    val requiredDays: Int?,
    val status: RisingStonesRewardStatus,
)

data class RisingStonesSignInLog(
    val id: String,
    val signTime: String,
    val platform: Int?,
    val location: String?,
)

data class RisingStonesSignInSummary(
    val signInCount: Int?,
    val signInLogs: List<RisingStonesSignInLog>,
    val rewards: List<RisingStonesReward>,
)

data class RisingStonesAccountDashboard(
    val character: RisingStonesCharacter?,
    val houseRemainDayText: String?,
    val signInCount: Int?,
    val signInLogs: List<RisingStonesSignInLog>,
    val rewards: List<RisingStonesReward>,
)

data class RisingStonesDailySignInResult(
    val message: String,
    val isAlreadyCheckedIn: Boolean,
    val continuousDays: Int?,
    val totalDays: Int?,
    val communityExperience: Int?,
    val shopExperience: Int?,
)

sealed class RisingStonesAccountException(message: String) : Exception(message) {
    data object AuthenticationRequired :
        RisingStonesAccountException("Rising Stones authentication is required")

    data object MissingPayload :
        RisingStonesAccountException("Rising Stones account response is missing required data")

    class Business(
        val code: Int?,
        val serverMessage: String?,
    ) : RisingStonesAccountException(
        serverMessage?.takeIf(String::isNotBlank)
            ?: "Rising Stones account request failed${code?.let { " ($it)" }.orEmpty()}",
    )
}

interface RisingStonesAccountService {
    suspend fun fetchSignInSummary(): RisingStonesSignInSummary

    suspend fun fetchDashboard(): RisingStonesAccountDashboard

    suspend fun signIn(): RisingStonesDailySignInResult

    suspend fun claimReward(id: Int): String
}
