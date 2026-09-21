package top.cxmeow.risingstones.feature.personaldata.presentation

import top.cxmeow.risingstones.feature.personaldata.domain.*

data class DashboardFishRow(
    val key: String,
    val itemId: Int?,
    val iconId: Int?,
    val name: String,
    val patch: String?,
    val record: PersonalDataFishCatch?,
)

data class DashboardAchievementRow(
    val achievementId: Int,
    val catalog: PersonalDataAchievementCatalogEntry?,
    val record: PersonalDataAchievementRecord?,
)

data class DashboardSavageRaid(
    val catalog: SavageRaidCatalogEntry,
    val records: List<PersonalDataSavageClear>,
)
data class DashboardSavageTier(val catalog: SavageRaidCatalogTier, val raids: List<DashboardSavageRaid>) {
    val hasRecords: Boolean get() = raids.any { it.records.isNotEmpty() }
    val isComplete: Boolean get() = !catalog.achievementOnly && raids.isNotEmpty() && raids.all { it.records.isNotEmpty() }
}
data class DashboardSavageSeries(val catalog: SavageRaidCatalogSeries, val tiers: List<DashboardSavageTier>)

/** Joins only audited catalog keys; unknown records are retained separately instead of reclassified. */
object PersonalDataDashboardCatalogViews {
    fun fishRows(
        records: List<PersonalDataFishCatch>,
        catalogs: PersonalDataOfficialCatalogs?,
        supplementary: PersonalDataSupplementaryCatalogs?,
        ocean: Boolean,
        patch: String?,
        includeUnobtained: Boolean,
        countFirst: Boolean,
        query: String,
    ): List<DashboardFishRow> {
        val recordsByName = records.withIndex().groupBy { it.value.name }
        val definitions = if (ocean) supplementary?.oceanFish.orEmpty().map {
            DashboardFishRow("catalog:${it.itemId}", it.itemId, it.iconId, it.name, null, null)
        } else catalogs?.fish.orEmpty().values.map {
            DashboardFishRow("catalog:${it.itemId}", it.itemId, it.iconId, it.name, it.patch, null)
        }
        val knownNames = catalogs?.fish.orEmpty().values.map { it.name }.toSet() +
            supplementary?.oceanFish.orEmpty().map { it.name }
        val joined = definitions.distinctBy { it.itemId }.filter { ocean || patch == null || it.patch == patch }.flatMap { definition ->
            val matches = recordsByName[definition.name].orEmpty()
            if (matches.isEmpty()) listOfNotNull(definition.takeIf { includeUnobtained })
            else matches.map { definition.copy(key = "record:${it.index}:${definition.itemId}", record = it.value) }
        }
        // An unmapped name cannot be claimed to be an ocean fish or a member of a particular patch.
        val unknown = if (!ocean && patch == null) records.withIndex().filter { it.value.name !in knownNames }.map {
            DashboardFishRow("unknown:${it.index}", null, null, it.value.name, null, it.value)
        } else emptyList()
        return (joined + unknown).filter { it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<DashboardFishRow> { it.record != null }.thenComparator { a, b ->
                val compared = if (countFirst) compareValues(b.record?.count, a.record?.count)
                    else compareValues(b.record?.caughtAt, a.record?.caughtAt)
                if (compared != 0) compared else compareValues(a.itemId ?: Int.MAX_VALUE, b.itemId ?: Int.MAX_VALUE)
            })
    }

    fun achievementRows(
        records: List<PersonalDataAchievementRecord>,
        catalog: List<PersonalDataAchievementCatalogEntry>,
        includeUnobtained: Boolean,
        query: String,
    ): List<DashboardAchievementRow> {
        val definitions = catalog.associateBy { it.achievementId }
        val obtainedIds = records.map { it.achievementId }.toSet()
        val rows = records.map { DashboardAchievementRow(it.achievementId, definitions[it.achievementId], it) } +
            if (includeUnobtained) catalog.distinctBy { it.achievementId }.filter { it.achievementId !in obtainedIds }
                .map { DashboardAchievementRow(it.achievementId, it, null) } else emptyList()
        return rows.filter {
            listOf(it.record?.name, it.catalog?.name, it.record?.detail, it.catalog?.detail, it.achievementId.toString())
                .filterNotNull().any { value -> value.contains(query.trim(), ignoreCase = true) }
        }.sortedWith(compareByDescending<DashboardAchievementRow> { it.record != null }
            .thenByDescending { it.record?.obtainedAt })
    }

    fun savageSeries(
        records: List<PersonalDataSavageClear>,
        catalogs: PersonalDataOfficialCatalogs?,
        query: String,
    ): List<DashboardSavageSeries> {
        val byTerritory = records.groupBy { it.territoryId }
        return catalogs?.savageSeries.orEmpty().mapNotNull { series ->
            if (series.tiers.none { tier -> tier.raids.any { byTerritory[it.instanceId].orEmpty().isNotEmpty() } }) return@mapNotNull null
            val tiers = series.tiers.mapNotNull tierMap@ { tier ->
                val raids = tier.raids.map { DashboardSavageRaid(it, byTerritory[it.instanceId].orEmpty()) }
                val hasRecords = raids.any { it.records.isNotEmpty() }
                if (!hasRecords && !tier.achievementOnly) return@tierMap null
                DashboardSavageTier(tier, raids)
            }
            val words = listOf(series.name, series.abbreviation) + tiers.flatMap { tier ->
                listOf(tier.catalog.nameChinese, tier.catalog.nameEnglish) + tier.raids.map { it.catalog.name }
            }
            if (words.none { it.contains(query.trim(), ignoreCase = true) }) null else DashboardSavageSeries(series, tiers)
        }
    }

    fun unmappedSavageRecords(records: List<PersonalDataSavageClear>, catalogs: PersonalDataOfficialCatalogs?) =
        records.filter { row -> catalogs?.savageSeries.orEmpty().none { series ->
            series.tiers.any { tier -> tier.raids.any { it.instanceId == row.territoryId } }
        } }

    fun vanityCategories(
        records: List<PersonalDataVanityUsage>,
        catalog: List<PersonalDataVanityCategory>,
        period: PersonalDataVanityPeriod,
        major: Int,
    ): List<PersonalDataVanityCategory> {
        val used = records.filter { it.period == period }.mapNotNull { it.categoryId }.toSet()
        return catalog.filter { it.majorOrder == major && it.selectable && (records.isEmpty() || it.id in used) }
            .distinctBy { it.id }.sortedBy { it.minorOrder }
    }

    fun vanityRows(
        records: List<PersonalDataVanityUsage>,
        catalog: List<PersonalDataVanityCategory>,
        period: PersonalDataVanityPeriod,
        major: Int,
        categoryId: Int?,
        query: String,
    ): List<PersonalDataVanityUsage> {
        val definitions = catalog.associateBy { it.id }
        return records.filter {
            it.period == period && it.count != 0L &&
                (if (categoryId != null) it.categoryId == categoryId && definitions[categoryId]?.majorOrder == major
                 else definitions[it.categoryId]?.majorOrder == major) &&
                (it.name.orEmpty().contains(query.trim(), ignoreCase = true) || it.itemId?.toString()?.contains(query.trim()) == true)
        }.sortedByDescending { it.count }
    }

    fun unmappedVanityRows(
        records: List<PersonalDataVanityUsage>,
        catalog: List<PersonalDataVanityCategory>,
        period: PersonalDataVanityPeriod,
        query: String,
    ): List<PersonalDataVanityUsage> {
        val ids = catalog.map { it.id }.toSet()
        return records.filter { it.period == period && it.count != 0L && it.categoryId !in ids &&
            (it.name.orEmpty().contains(query.trim(), ignoreCase = true) || it.itemId?.toString()?.contains(query.trim()) == true) }
            .sortedByDescending { it.count }
    }
}
