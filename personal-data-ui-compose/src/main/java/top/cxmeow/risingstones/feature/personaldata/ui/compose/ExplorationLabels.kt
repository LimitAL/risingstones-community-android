package top.cxmeow.risingstones.feature.personaldata.ui.compose

import top.cxmeow.risingstones.feature.personaldata.domain.*

internal fun ExplorationSectionKind.label(): Int = when (this) {
    ExplorationSectionKind.Overview -> R.string.exploration_overview
    ExplorationSectionKind.PhantomJobs -> R.string.exploration_phantomjobs
    ExplorationSectionKind.ItemUsage -> R.string.exploration_itemusage
    ExplorationSectionKind.AcquiredItems -> R.string.exploration_acquireditems
    ExplorationSectionKind.TreasureChests -> R.string.exploration_treasurechests
    ExplorationSectionKind.Achievements -> R.string.exploration_achievements
    ExplorationSectionKind.Aether -> R.string.exploration_aether
    ExplorationSectionKind.Challenges -> R.string.exploration_challenges
    ExplorationSectionKind.ChallengeJobs -> R.string.exploration_challengejobs
    ExplorationSectionKind.FailureFloors -> R.string.exploration_failurefloors
    ExplorationSectionKind.SpecialBattle -> R.string.exploration_specialbattle
    ExplorationSectionKind.SpecialBattleJobs -> R.string.exploration_specialbattlejobs
    ExplorationSectionKind.DeathLocations -> R.string.exploration_deathlocations
    ExplorationSectionKind.FirstClearTeam -> R.string.exploration_firstclearteam
    ExplorationSectionKind.TreasureHistory -> R.string.exploration_treasurehistory
    ExplorationSectionKind.RelicHistory -> R.string.exploration_relichistory
    ExplorationSectionKind.ItemHistory -> R.string.exploration_itemhistory
}

internal fun ExplorationFieldKind.label(): Int = when (this) {
    ExplorationFieldKind.KnowledgeLevel -> R.string.exploration_knowledgelevel
    ExplorationFieldKind.Fates -> R.string.exploration_fates
    ExplorationFieldKind.CriticalEncounters -> R.string.exploration_criticalencounters
    ExplorationFieldKind.SilverCoins -> R.string.exploration_silvercoins
    ExplorationFieldKind.GoldCoins -> R.string.exploration_goldcoins
    ExplorationFieldKind.WhiteSilverCoins -> R.string.exploration_whitesilvercoins
    ExplorationFieldKind.WhiteGoldCoins -> R.string.exploration_whitegoldcoins
    ExplorationFieldKind.PhantomJob -> R.string.exploration_phantomjob
    ExplorationFieldKind.Level -> R.string.exploration_level
    ExplorationFieldKind.Quantity -> R.string.exploration_quantity
    ExplorationFieldKind.ItemName -> R.string.exploration_itemname
    ExplorationFieldKind.ItemCategory -> R.string.exploration_itemcategory
    ExplorationFieldKind.FirstAcquiredAt -> R.string.exploration_firstacquiredat
    ExplorationFieldKind.Uses -> R.string.exploration_uses
    ExplorationFieldKind.BoxType -> R.string.exploration_boxtype
    ExplorationFieldKind.BoxGrade -> R.string.exploration_boxgrade
    ExplorationFieldKind.AetherColor -> R.string.exploration_aethercolor
    ExplorationFieldKind.AetherPoints -> R.string.exploration_aetherpoints
    ExplorationFieldKind.AchievementName -> R.string.exploration_achievementname
    ExplorationFieldKind.RecordedAt -> R.string.exploration_recordedat
    ExplorationFieldKind.Solo -> R.string.exploration_solo
    ExplorationFieldKind.WeaponLevel -> R.string.exploration_weaponlevel
    ExplorationFieldKind.ArmorLevel -> R.string.exploration_armorlevel
    ExplorationFieldKind.Clears -> R.string.exploration_clears
    ExplorationFieldKind.FirstClearAt -> R.string.exploration_firstclearat
    ExplorationFieldKind.ClearDuration -> R.string.exploration_clearduration
    ExplorationFieldKind.Attempts -> R.string.exploration_attempts
    ExplorationFieldKind.Deaths -> R.string.exploration_deaths
    ExplorationFieldKind.Wipes -> R.string.exploration_wipes
    ExplorationFieldKind.ClassJob -> R.string.exploration_classjob
    ExplorationFieldKind.CharacterName -> R.string.exploration_charactername
    ExplorationFieldKind.World -> R.string.exploration_world
    ExplorationFieldKind.Area -> R.string.exploration_area
    ExplorationFieldKind.Floor -> R.string.exploration_floor
    ExplorationFieldKind.X -> R.string.exploration_x
    ExplorationFieldKind.Y -> R.string.exploration_y
}

internal val phantomJobLabels = listOf(
    R.string.exploration_phantom_0,
    R.string.exploration_phantom_1,
    R.string.exploration_phantom_2,
    R.string.exploration_phantom_3,
    R.string.exploration_phantom_4,
    R.string.exploration_phantom_5,
    R.string.exploration_phantom_6,
    R.string.exploration_phantom_7,
    R.string.exploration_phantom_8,
    R.string.exploration_phantom_9,
    R.string.exploration_phantom_10,
    R.string.exploration_phantom_11,
    R.string.exploration_phantom_12,
    R.string.exploration_phantom_13,
    R.string.exploration_phantom_14,
    R.string.exploration_phantom_15,
    R.string.exploration_phantom_16,
    R.string.exploration_phantom_17,
    R.string.exploration_phantom_18,
    R.string.exploration_phantom_19,
    R.string.exploration_phantom_20,
    R.string.exploration_phantom_21,
    R.string.exploration_phantom_22,
    R.string.exploration_phantom_23,
)

internal val classJobLabels = mapOf(
    19 to R.string.exploration_job_19,
    20 to R.string.exploration_job_20,
    21 to R.string.exploration_job_21,
    22 to R.string.exploration_job_22,
    23 to R.string.exploration_job_23,
    24 to R.string.exploration_job_24,
    25 to R.string.exploration_job_25,
    26 to R.string.exploration_job_26,
    27 to R.string.exploration_job_27,
    28 to R.string.exploration_job_28,
    29 to R.string.exploration_job_29,
    30 to R.string.exploration_job_30,
    31 to R.string.exploration_job_31,
    32 to R.string.exploration_job_32,
    33 to R.string.exploration_job_33,
    34 to R.string.exploration_job_34,
    35 to R.string.exploration_job_35,
    36 to R.string.exploration_job_36,
    37 to R.string.exploration_job_37,
    38 to R.string.exploration_job_38,
    39 to R.string.exploration_job_39,
    40 to R.string.exploration_job_40,
    41 to R.string.exploration_job_41,
    42 to R.string.exploration_job_42,
)
