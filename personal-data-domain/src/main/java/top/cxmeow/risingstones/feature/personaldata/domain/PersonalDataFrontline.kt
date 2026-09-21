package top.cxmeow.risingstones.feature.personaldata.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Optional semantic Frontline reader. All selections operate on the returned complete arrays. */
interface PersonalDataFrontlineService : PersonalDataService {
    suspend fun fetchFrontlineSection(section: PersonalDataFrontlineSection): PersonalDataFrontlineData
    suspend fun fetchFrontlineCatalogs(): PersonalDataFrontlineCatalogs = PersonalDataFrontlineCatalogs()
    fun frontlineJobIconUrl(jobName: String, hollow: Boolean = true): String? = null
    fun frontlineCompanyFlagUrl(companyName: String): String? = null
    fun frontlineAchievementImageUrl(): String? = null
}

enum class PersonalDataFrontlineSection { Overview, Weekly, Jobs, Best, Maps, MapJobs, Achievements }
sealed interface PersonalDataFrontlineData {
    data class Overview(val rows: List<FrontlineOverviewRecord>) : PersonalDataFrontlineData
    data class Weekly(val rows: List<FrontlineDayRecord>) : PersonalDataFrontlineData
    data class Jobs(val rows: List<FrontlineJobRecord>) : PersonalDataFrontlineData
    data class Best(val rows: List<FrontlineBestRecord>) : PersonalDataFrontlineData
    data class Maps(val rows: List<FrontlineMapRecord>) : PersonalDataFrontlineData
    data class MapJobs(val rows: List<FrontlineMapJobRecord>) : PersonalDataFrontlineData
    data class Achievements(val rows: List<FrontlineAchievementRecord>) : PersonalDataFrontlineData
}

/** Nullable period means an unrecognised period, never an all-time record. Rates are fractions. */
data class FrontlineOverviewRecord(
    val period: FrontlinePeriodKind?,
    val battles: Long?,
    val wins: Long?,
    val kills: Long?,
    val winRate: Double?,
    val kda: Double?,
    val companyName: String?,
    val pvpRank: Long?,
    val seriesLevel: Long?,
    val elapsedHours: Double?,
    val occupiedObjectives: Long?,
    val averages: FrontlineAverages,
    val ranks: FrontlineRanks,
)

data class FrontlineAverages(
    val kills: Double? = null,
    val assists: Double? = null,
    val deaths: Double? = null,
    val damage: Double? = null,
    val healing: Double? = null,
    val damageTaken: Double? = null,
)
/** These six scores are on a 0–100 scale, unlike win/use rates. */
data class FrontlineRanks(
    val kills: Double?, val healing: Double?, val damageTaken: Double?,
    val damage: Double?, val survival: Double?, val assists: Double?,
)

/** Keeps a calendar date separate from a timestamp; the caller supplies the display time zone. */
sealed interface FrontlineDayStamp {
    data class CalendarDate(val value: LocalDate) : FrontlineDayStamp
    data class LocalTime(val value: LocalDateTime) : FrontlineDayStamp
    data class OffsetTime(val value: Instant) : FrontlineDayStamp
}
data class FrontlineDayRecord(
    val day: FrontlineDayStamp?,
    val battles: Long?, val wins: Long?, val kills: Long?, val deaths: Long?, val assists: Long?,
)

data class FrontlineJobRecord(
    val period: FrontlinePeriodKind?,
    val jobName: String,
    val battles: Long?,
    val useRate: Double?,
    val kills: Long?,
    val winRate: Double?,
    val kda: Double?,
    val kdaPercentile: Double?,
    val limitBreaks: Long?,
    val averages: FrontlineAverages,
)

enum class FrontlineBestKind { Kills, Assists, Damage, DamageTaken, Healing, Unknown }
enum class FrontlinePlacement { First, Second, Third }
/** The official chart orders teams 3, 2, 1; numbers do not imply an unverified company mapping. */
data class FrontlineTeamScore(val teamNumber: Int, val points: Long?)
data class FrontlineBestRecord(
    val kind: FrontlineBestKind,
    val mapName: String?,
    val recordedAt: FrontlineDayStamp?,
    val jobName: String?,
    val placement: FrontlinePlacement?,
    val kills: Long?, val deaths: Long?, val assists: Long?,
    val damage: Long?, val damageTaken: Long?, val healing: Long?,
    val scores: List<FrontlineTeamScore>,
)

data class FrontlineMapRecord(
    val mapName: String,
    val battles: Long?, val wins: Long?, val kills: Long?, val winRate: Double?,
)
data class FrontlineAchievementRecord(
    val achievementId: Int,
    val name: String?,
    val detail: String?,
    val obtainedAt: FrontlineDayStamp?,
)
data class FrontlineMapJobRecord(
    val mapName: String,
    val jobName: String,
    val battles: Long?, val wins: Long?, val kills: Long?, val winRate: Double?,
)
data class PersonalDataFrontlineCatalogs(
    val mapNames: List<String> = emptyList(),
    val achievements: List<PersonalDataAchievementCatalogEntry> = emptyList(),
)
