package top.cxmeow.risingstones.feature.account.domain

data class RisingStonesCharacter(
    val areaName: String,
    val groupName: String,
    val characterName: String,
    val avatarUrl: String? = null,
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

/** A complete, self-profile array; null on the dashboard means unavailable, never confirmed empty. */
data class RisingStonesMedalWall(
    val character: RisingStonesCharacter?,
    val medals: List<RisingStonesMedal>,
    val sourceRows: Int,
    val rejectedRows: Int,
)
data class RisingStonesMedal(
    val achievementId: String,
    val name: String,
    val detail: String?,
    val typeId: String?,
    val typeName: String?,
    val achievedAt: String?,
)

data class RisingStonesAccountDashboard @JvmOverloads constructor(
    val character: RisingStonesCharacter?,
    val houseRemainDayText: String?,
    val signInCount: Int?,
    val signInLogs: List<RisingStonesSignInLog>,
    val rewards: List<RisingStonesReward>,
    val medalWall: RisingStonesMedalWall? = null,
) {
    /** Keep the original five-field JVM copy entry point for already compiled hosts. */
    fun copy(character: RisingStonesCharacter?, houseRemainDayText: String?, signInCount: Int?,
        signInLogs: List<RisingStonesSignInLog>, rewards: List<RisingStonesReward>): RisingStonesAccountDashboard =
        RisingStonesAccountDashboard(character, houseRemainDayText, signInCount, signInLogs, rewards, medalWall)

    companion object {
        /** Kotlin's original generated default-copy descriptor; keep its mask semantics unchanged. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun `copy$default`(source: RisingStonesAccountDashboard, character: RisingStonesCharacter?,
            houseRemainDayText: String?, signInCount: Int?, signInLogs: List<RisingStonesSignInLog>?,
            rewards: List<RisingStonesReward>?, mask: Int, marker: Any?): RisingStonesAccountDashboard =
            source.copy(if(mask and 1!=0) source.character else character,
                if(mask and 2!=0) source.houseRemainDayText else houseRemainDayText,
                if(mask and 4!=0) source.signInCount else signInCount,
                if(mask and 8!=0) source.signInLogs else requireNotNull(signInLogs),
                if(mask and 16!=0) source.rewards else requireNotNull(rewards))
    }
}

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
