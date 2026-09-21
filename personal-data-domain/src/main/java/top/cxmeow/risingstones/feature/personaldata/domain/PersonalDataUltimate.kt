package top.cxmeow.risingstones.feature.personaldata.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Optional complete Ultimate reader; the existing dashboard/detail contracts remain compatible. */
interface PersonalDataUltimateService : PersonalDataService {
    suspend fun fetchUltimateRecords(): List<PersonalDataUltimateRecord>
    suspend fun fetchUltimateSection(territoryType: Int, section: PersonalDataUltimateSection): PersonalDataUltimateData
    fun ultimateCoverUrl(territoryType: Int): String? = null
    fun ultimateJobIconUrl(jobName: String): String? = null
    /** Official role order (defence, healing, attack); equal roles preserve input order, unknown jobs return null. */
    fun ultimateJobOrder(jobName: String): Int? = null
    fun ultimateMedalImageUrl(territoryType: Int): String? = null
}

sealed interface UltimateRecordTime {
    data class CalendarDate(val value: LocalDate) : UltimateRecordTime
    data class LocalTime(val value: LocalDateTime) : UltimateRecordTime
    data class OffsetTime(val value: Instant) : UltimateRecordTime
}

data class PersonalDataUltimateRecord(
    val territoryType: Int,
    val clearCount: Long?,
    val entriesBeforeFirstClear: Long?,
    val firstClearJob: String?,
    val firstClearAt: UltimateRecordTime?,
    val firstClearDurationSeconds: Long?,
    val deathsBeforeFirstClear: Long?,
)

enum class PersonalDataUltimateSection { Party, Jobs, Partners, Phases, Deaths }
sealed interface PersonalDataUltimateData {
    data class Party(val rows: List<UltimatePartyMember>) : PersonalDataUltimateData
    data class Jobs(val rows: List<UltimateJobUsage>) : PersonalDataUltimateData
    data class Partners(val rows: List<UltimateCompanion>) : PersonalDataUltimateData
    data class Phases(val rows: List<UltimatePhaseRecord>) : PersonalDataUltimateData
    data class Deaths(val rows: List<UltimateDeathRecord>) : PersonalDataUltimateData
}

data class UltimatePartyMember(
    val characterName: String,
    val areaName: String?,
    val groupName: String?,
    val jobName: String?,
)
/** Number of clears with this job, not entries or time spent. */
data class UltimateJobUsage(val jobName: String, val times: Long?)
data class UltimateCompanion(
    val characterName: String,
    val areaName: String?,
    val groupName: String?,
    val jointEntries: Long?,
)
data class UltimatePhaseRecord(val phase: String, val reachedAt: UltimateRecordTime?)
/** Original server coordinates. Coordinate transforms belong to the presentation projection. */
data class UltimateDeathRecord(val x: Double?, val y: Double?)
