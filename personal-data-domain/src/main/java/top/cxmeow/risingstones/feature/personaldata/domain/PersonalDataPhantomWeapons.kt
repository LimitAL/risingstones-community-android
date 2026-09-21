package top.cxmeow.risingstones.feature.personaldata.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Optional typed extension of the same exploration read, without additional business requests. */
interface PersonalDataPhantomWeaponService : PersonalDataExplorationService {
    suspend fun fetchPhantomWeaponExploration(): PhantomWeaponExplorationSnapshot
    suspend fun fetchPhantomWeaponCatalog(): PhantomWeaponCatalog
    fun phantomWeaponItemIconUrl(iconId: Int): String? = null
    fun phantomWeaponElementIconUrl(element: PhantomWeaponElement): String? = null
    /** Official lens images: steps 1..4 are incomplete; step 5 is complete. */
    fun phantomWeaponLensImageUrl(step: Int): String? = null
}

enum class PhantomWeaponStage(val order: Int) {
    Penumbrae(1), Umbrae(2), Obscurum(3), Eclipticum(4), Occultum(5),
}

enum class PhantomWeaponElement { Yellow, Red, Blue, Green }

data class PhantomWeaponDefinition(
    val stage: PhantomWeaponStage,
    val itemId: Int,
    val name: String,
    val iconId: Int,
)

data class PhantomWeaponMaterialDefinition(val itemId: Int, val name: String, val iconId: Int)

data class PhantomWeaponCatalog(
    val weapons: List<PhantomWeaponDefinition>,
    val soulCrystals: List<PhantomWeaponMaterialDefinition>,
    val demiatma: List<PhantomWeaponMaterialDefinition>,
)

sealed interface PhantomWeaponRecordTime {
    data class CalendarDate(val value: LocalDate) : PhantomWeaponRecordTime
    data class LocalTime(val value: LocalDateTime) : PhantomWeaponRecordTime
    data class OffsetTime(val value: Instant) : PhantomWeaponRecordTime
}

/** All observed categories remain available; unknown quantities do not imply a visible stage. */
data class PhantomWeaponItemRecord(
    val itemId: Int?,
    val category: String?,
    val quantity: Long?,
    val firstAcquiredAt: PhantomWeaponRecordTime?,
    val name: String?,
)

data class PhantomWeaponAetherRecord(val color: String?, val points: Long?)

data class PhantomWeaponItemSection(
    val records: List<PhantomWeaponItemRecord> = emptyList(),
    val failure: ExplorationFailure? = null,
)

data class PhantomWeaponAetherSection(
    val records: List<PhantomWeaponAetherRecord> = emptyList(),
    val failure: ExplorationFailure? = null,
)

/** The legacy overview and typed weapon inputs come from the same request batch. */
data class PhantomWeaponExplorationSnapshot(
    val overview: ExplorationOverview,
    val items: PhantomWeaponItemSection?,
    val aether: PhantomWeaponAetherSection?,
)
