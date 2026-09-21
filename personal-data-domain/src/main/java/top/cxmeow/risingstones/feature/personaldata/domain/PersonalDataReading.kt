package top.cxmeow.risingstones.feature.personaldata.domain

import java.time.Instant

/** Optional structured detail reads. Existing PersonalDataService implementations remain valid. */
interface PersonalDataReadingService : PersonalDataService {
    suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind): List<PersonalDataFishingRank>
    suspend fun fetchRaceUsage(): List<PersonalDataRaceUsage>
    suspend fun fetchGlamourSetRecords(): List<PersonalDataGlamourSetRecord>
    /** Optional official icon resource. Hosts without images may keep the default. */
    fun itemIconUrl(iconId: Int): String? = null
}

enum class PersonalDataFishingRankingKind { Fish, Bait }
enum class PersonalDataReadingPage { Fish, Baits, Races, Sets }

/** Category is official display text and may be absent on older records. No item ID is implied. */
data class PersonalDataFishingRank(val name: String, val count: Long, val category: String?)

/** Proportion is a fraction, not an already formatted percentage. Missing values stay unknown. */
data class PersonalDataRaceUsage(
    val race: String,
    val gender: String,
    val proportion: Double?,
    val days: Long?,
    val isCurrent: Boolean,
    val isMostUsed: Boolean,
)

/** Records are preserved individually even when several rows refer to the same set. */
data class PersonalDataGlamourSetRecord(
    val key: String,
    val setId: Int,
    val itemIds: Set<Int>,
    val recordedAt: Instant?,
    val hasInvalidItemIds: Boolean = false,
)
