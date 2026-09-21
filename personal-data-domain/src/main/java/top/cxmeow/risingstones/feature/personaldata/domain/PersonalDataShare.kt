package top.cxmeow.risingstones.feature.personaldata.domain

enum class PersonalDataShareKind { Fishing, Savage, Glamour, Frontline, Ultimate, Occult }

sealed interface PersonalDataShareOptional<out T> {
    data class Known<T>(val value: T) : PersonalDataShareOptional<T>
    data object Empty : PersonalDataShareOptional<Nothing>
    data object Failed : PersonalDataShareOptional<Nothing>
}

data class PersonalDataShareDocument(
    val identity: PersonalDataIdentity,
    val content: PersonalDataShareContent,
)

sealed interface PersonalDataShareContent {
    val kind: PersonalDataShareKind
}

data class FishingShareCatch(
    val record: PersonalDataFishCatch,
    val iconId: Int?,
)

data class FishingShareAchievement(
    val record: PersonalDataAchievementRecord,
    val catalog: PersonalDataAchievementCatalogEntry?,
)

data class FishingShareContent(
    val overview: FishingOverview,
    val recentBigFish: List<FishingShareCatch>,
    val latestAchievement: PersonalDataShareOptional<FishingShareAchievement>,
) : PersonalDataShareContent {
    override val kind = PersonalDataShareKind.Fishing
}

data class GlamourShareSet(
    val record: PersonalDataGlamourSetRecord,
    val catalog: GlamourCatalogSet,
)

data class GlamourShareVanityFavorite(
    val record: PersonalDataVanityUsage,
    val category: PersonalDataVanityCategory,
)

data class GlamourShareAccessoryFavorite(
    val record: PersonalDataAccessoryUsage,
    val catalog: GlamourCatalogFashionAccessory?,
)

data class GlamourShareFavorites(
    val weapon: PersonalDataShareOptional<GlamourShareVanityFavorite>,
    val gear: PersonalDataShareOptional<GlamourShareVanityFavorite>,
    val jewelry: PersonalDataShareOptional<GlamourShareVanityFavorite>,
    val fashionAccessory: PersonalDataShareOptional<GlamourShareAccessoryFavorite>,
)

data class GlamourShareContent(
    val overview: GlamourOverview,
    /** The official one-decimal percentage, or null when any required set membership is unknown. */
    val setCollectionRatePercent: Double?,
    val latestSets: List<GlamourShareSet>,
    val favorites: GlamourShareFavorites,
) : PersonalDataShareContent {
    override val kind = PersonalDataShareKind.Glamour
}

data class SavageShareRaid(
    val catalog: SavageRaidCatalogEntry,
    val records: List<PersonalDataSavageClear>,
)

data class SavageShareTier(
    val catalog: SavageRaidCatalogTier,
    val raids: List<SavageShareRaid>,
)

data class SavageShareSeries(
    val catalog: SavageRaidCatalogSeries,
    val tiers: List<SavageShareTier>,
)

data class SavageShareContent(
    val overview: SavageOverview,
    val series: List<SavageShareSeries>,
    /** The latest known timestamp selects the header cover; null retains an unknown cover. */
    val latestClear: PersonalDataSavageClear?,
) : PersonalDataShareContent {
    override val kind = PersonalDataShareKind.Savage
}

data class FrontlineShareAchievement(
    val record: FrontlineAchievementRecord,
    val catalog: PersonalDataAchievementCatalogEntry?,
)

data class FrontlineShareContent(
    val overall: FrontlineOverviewRecord,
    /** The six 5.1-era scores retain nullable 0..100 values independently. */
    val radar: FrontlineRanks,
    /** Descending raw usage rows; duplicates and unrecognised periods remain observable. */
    val commonJobs: List<FrontlineJobRecord>,
    val bestKills: FrontlineBestRecord,
    val bestAssists: PersonalDataShareOptional<FrontlineBestRecord>,
    val latestAchievement: PersonalDataShareOptional<FrontlineShareAchievement>,
) : PersonalDataShareContent {
    override val kind = PersonalDataShareKind.Frontline
}

data class UltimateShareAchievement(
    val territoryType: Int,
    val achievementId: Int,
    val medalId: Int,
    val name: String,
    val detail: String,
)

data class UltimateShareProgress(
    val territoryType: Int,
    val encounter: UltimateEncounter?,
    val achievement: UltimateShareAchievement?,
    val record: PersonalDataUltimateRecord?,
)

data class UltimateShareTimelineEntry(
    val record: PersonalDataUltimateRecord,
    val encounter: UltimateEncounter?,
    val achievement: UltimateShareAchievement?,
)

data class UltimateShareContent(
    /** Fixed official encounter order, including uncleared encounters. */
    val progress: List<UltimateShareProgress>,
    /** Descending known first-clear time; unknown times remain last and retain source order. */
    val timeline: List<UltimateShareTimelineEntry>,
    val headerTerritoryType: Int?,
    val singleClearMedalId: Int?,
    val allCleared: Boolean,
) : PersonalDataShareContent {
    override val kind = PersonalDataShareKind.Ultimate
}

data class PhantomShareJob(
    val id: Int,
    val name: String,
    val iconId: Int,
    val levelCap: Int,
)

data class OccultShareJob(
    val catalog: PhantomShareJob,
    val level: Int?,
)

data class OccultShareTreasure(
    val total: Long,
    val bronze: Long,
    val silver: Long,
    val gold: Long,
    val bronzeFraction: Double?,
    val silverFraction: Double?,
    val goldFraction: Double?,
)

data class PhantomShareWeaponCategory(
    val itemUiCategoryId: Int,
    val name: String?,
)

data class OccultShareWeapon(
    val definition: PhantomWeaponDefinition,
    val record: PhantomWeaponItemRecord,
    val category: PhantomShareWeaponCategory?,
)

data class OccultShareContent(
    val overview: ExplorationOverview,
    /** Fixed catalog order 0..23, even when individual levels are unknown. */
    val supportJobs: List<OccultShareJob>,
    val treasure: PersonalDataShareOptional<OccultShareTreasure>,
    val recentWeapon: PersonalDataShareOptional<OccultShareWeapon>,
) : PersonalDataShareContent {
    override val kind = PersonalDataShareKind.Occult
}

data class PersonalDataShareCatalogs(
    val ultimateAchievements: List<UltimateShareAchievement> = emptyList(),
    val phantomJobs: List<PhantomShareJob> = emptyList(),
    /** Keyed by weapon item id; the cached definition names the source field ItemUICategory. */
    val weaponItemCategories: Map<Int, PhantomShareWeaponCategory> = emptyMap(),
)
