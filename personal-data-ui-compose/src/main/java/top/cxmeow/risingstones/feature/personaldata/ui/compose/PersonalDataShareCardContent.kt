package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.content.Context
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import top.cxmeow.risingstones.feature.personaldata.domain.*

internal data class ShareCardRow(val title: String, val body: String = "", val image: PersonalDataShareImage? = null)
internal data class ShareCardSection(val title: String, val rows: List<ShareCardRow>, val columns: Int = 1)
internal data class ShareCardContent(val title: String, val cover: PersonalDataShareImage?,
    val metrics: List<Pair<String, String>>, val sections: List<ShareCardSection>, val radar: FrontlineRanks? = null)

internal fun PersonalDataShareKind.titleResource(): Int = when (this) {
    PersonalDataShareKind.Fishing -> R.string.personal_data_board_fishing
    PersonalDataShareKind.Savage -> R.string.personal_data_board_savage
    PersonalDataShareKind.Glamour -> R.string.personal_data_board_glamour
    PersonalDataShareKind.Frontline -> R.string.personal_data_board_frontline
    PersonalDataShareKind.Ultimate -> R.string.personal_data_board_ultimate
    PersonalDataShareKind.Occult -> R.string.exploration_occult
}

internal class ShareCardContentBuilder(private val context: Context, private val zone: ZoneId) {
    private val locale = context.resources.configuration.locales[0]
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun number(value: Number?): String = value?.let { NumberFormat.getNumberInstance(locale).format(it) } ?: s(R.string.pdr_unknown)
    private fun percent(value: Double?) = value?.let { s(R.string.pdd_percent, it * 100) } ?: s(R.string.pdr_unknown)
    private fun date(value: Instant?) = value?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(it) } ?: s(R.string.pdr_unknown)
    private fun metric(id: Int, value: Number?) = s(id) to number(value)
    private fun unknown(value: String?) = value?.takeIf(String::isNotBlank) ?: s(R.string.pdr_unknown)
    private fun <T> optional(value: PersonalDataShareOptional<T>, row: (T) -> ShareCardRow): ShareCardRow = when (value) {
        is PersonalDataShareOptional.Known -> row(value.value)
        PersonalDataShareOptional.Empty -> ShareCardRow(s(R.string.pdr_empty))
        PersonalDataShareOptional.Failed -> ShareCardRow(s(R.string.pds_failed_section))
    }
    fun build(content: PersonalDataShareContent): ShareCardContent {
        val sections = mutableListOf<ShareCardSection>()
        var cover: PersonalDataShareImage? = PersonalDataShareImage.Cover(content.kind)
        var radar: FrontlineRanks? = null
        val metrics = when (content) {
            is FishingShareContent -> {
                sections += ShareCardSection(s(R.string.pds_recent_fish), content.recentBigFish.map {
                    ShareCardRow(it.record.name, date(it.record.caughtAt), it.iconId?.let(PersonalDataShareImage::ItemIcon))
                })
                sections += ShareCardSection(s(R.string.pds_recent_achievement), listOf(optional(content.latestAchievement) {
                    ShareCardRow(it.catalog?.name ?: unknown(it.record.name), listOfNotNull(it.catalog?.detail ?: it.record.detail,
                        date(it.record.obtainedAt)).joinToString("\n"), it.catalog?.iconId?.let(PersonalDataShareImage::AchievementIcon))
                }))
                with(content.overview) { listOf(metric(R.string.personal_data_metric_total_catches, casts), s(R.string.personal_data_metric_success_rate) to percent(successRate),
                    metric(R.string.personal_data_metric_ocean_trips, seaTrips), metric(R.string.personal_data_metric_ocean_score, highestSeaScore)) }
            }
            is GlamourShareContent -> {
                sections += ShareCardSection(s(R.string.pds_recent_sets), content.latestSets.map {
                    ShareCardRow(it.catalog.name, date(it.record.recordedAt), it.catalog.iconId?.let(PersonalDataShareImage::ItemIcon))
                }.ifEmpty { listOf(ShareCardRow(s(R.string.pdr_empty))) })
                val favorites = listOf(R.string.pdd_weapons to content.favorites.weapon, R.string.pdd_armor to content.favorites.gear,
                    R.string.pdd_accessories to content.favorites.jewelry).map { (title, value) ->
                    val row = optional(value) { ShareCardRow(unknown(it.record.name), number(it.record.count), it.record.iconId?.let(PersonalDataShareImage::ItemIcon)) }
                    row.copy(title = "${s(title)} · ${row.title}")
                } + optional(content.favorites.fashionAccessory) {
                    ShareCardRow(it.catalog?.name ?: s(R.string.pdd_accessory_number, it.record.accessoryId), number(it.record.count), it.catalog?.iconId?.let(PersonalDataShareImage::ItemIcon))
                }.let { it.copy(title = "${s(R.string.personal_data_section_ornaments)} · ${it.title}") }
                sections += ShareCardSection(s(R.string.pds_favorites), favorites)
                with(content.overview) { listOf(metric(R.string.personal_data_metric_washings, fantasiaUses), s(R.string.pds_collection_rate) to
                    (content.setCollectionRatePercent?.let { s(R.string.pdd_percent, it) } ?: s(R.string.pdr_unknown)),
                    metric(R.string.personal_data_metric_dyes, dyesUsed), metric(R.string.personal_data_metric_glamours, projections)) }
            }
            is SavageShareContent -> {
                val latest = content.latestClear?.territoryId
                cover = content.series.flatMap { it.tiers }.flatMap { it.raids }.firstOrNull { it.catalog.instanceId == latest }?.catalog?.imageId?.let(PersonalDataShareImage::RaidCover) ?: cover
                content.series.forEach { series ->
                    sections += ShareCardSection(series.catalog.name, series.tiers.flatMap { tier ->
                        listOf(ShareCardRow(if (locale.language == "zh") tier.catalog.nameChinese else tier.catalog.nameEnglish)) + tier.raids.map { raid ->
                            val status = if (raid.records.isEmpty()) s(R.string.pds_no_clear) else raid.records.first().let {
                                val mode = when(it.supportsUnrestricted) { true -> s(R.string.pds_unrestricted); false -> s(R.string.pds_restricted); null -> s(R.string.pdr_unknown) }
                                "${s(R.string.pds_clear)} · $mode"
                            }
                            ShareCardRow(raid.catalog.name, status)
                        }
                    })
                }
                with(content.overview) { listOf(metric(R.string.personal_data_metric_raids, territoriesCleared), metric(R.string.personal_data_metric_entries, entries),
                    metric(R.string.personal_data_metric_clears, clears), s(R.string.personal_data_metric_elapsed) to (elapsedHours?.let { s(R.string.pdd_hours, it) } ?: s(R.string.pdr_unknown))) }
            }
            is FrontlineShareContent -> {
                radar = content.radar
                sections += ShareCardSection(s(R.string.pds_common_jobs), content.commonJobs.map { ShareCardRow(it.jobName,
                    "${s(R.string.personal_data_metric_battles)} ${number(it.battles)} · ${s(R.string.personal_data_metric_win_rate)} ${percent(it.winRate)}", PersonalDataShareImage.JobIcon(it.jobName)) }.ifEmpty { listOf(ShareCardRow(s(R.string.pdr_empty))) })
                sections += ShareCardSection(s(R.string.pds_best_kills), listOf(best(content.bestKills)))
                sections += ShareCardSection(s(R.string.pds_best_assists), listOf(optional(content.bestAssists, ::best)))
                sections += ShareCardSection(s(R.string.pds_recent_achievement), listOf(optional(content.latestAchievement) {
                    ShareCardRow(it.catalog?.name ?: unknown(it.record.name), listOfNotNull(it.catalog?.detail ?: it.record.detail,
                        frontlineDateText(it.record.obtainedAt, zone, locale)).joinToString("\n"), PersonalDataShareImage.FrontlineBadge)
                }))
                with(content.overall) { listOf(metric(R.string.personal_data_metric_battles, battles), metric(R.string.personal_data_metric_kda, kda),
                    metric(R.string.personal_data_metric_wins, wins), s(R.string.personal_data_metric_win_rate) to percent(winRate)) }
            }
            is UltimateShareContent -> {
                cover = content.headerTerritoryType?.let(PersonalDataShareImage::UltimateCover) ?: cover
                sections += ShareCardSection(s(R.string.pds_progress), content.progress.map { ShareCardRow(
                    it.achievement?.name ?: it.encounter?.title ?: s(R.string.pdd_raid_number, it.territoryType),
                    s(if(it.record == null) R.string.pds_no_clear else R.string.pds_clear)) })
                sections += ShareCardSection(s(R.string.pds_first_clears), content.timeline.map {
                    ShareCardRow(it.achievement?.name ?: it.encounter?.title ?: s(R.string.pdd_raid_number, it.record.territoryType),
                        listOfNotNull(ultimateDateText(it.record.firstClearAt, zone, locale) ?: s(R.string.pdr_unknown), it.record.firstClearJob, it.achievement?.detail).joinToString("\n"),
                        if (content.singleClearMedalId != null && content.timeline.size == 1) PersonalDataShareImage.UltimateMedal(it.record.territoryType)
                        else it.record.firstClearJob?.let(PersonalDataShareImage::JobIcon))
                })
                if (content.allCleared) sections += ShareCardSection(s(R.string.pds_all_cleared), emptyList())
                emptyList()
            }
            is OccultShareContent -> {
                sections += ShareCardSection(s(R.string.exploration_phantomjobs), content.supportJobs.map {
                    ShareCardRow(phantomJobLabels.getOrNull(it.catalog.id)?.let(::s) ?: it.catalog.name,
                        if (it.catalog.id == 0) (it.level?.let { level -> s(R.string.exploration_mastered, level) } ?: s(R.string.pdr_unknown))
                        else "${number(it.level)} / ${number(it.catalog.levelCap)}", PersonalDataShareImage.PhantomJobIcon(it.catalog.iconId))
                }, columns = 4)
                sections += ShareCardSection(s(R.string.exploration_treasurechests), listOf(optional(content.treasure) {
                    ShareCardRow(number(it.total), listOf(s(R.string.exploration_copper) + " ${number(it.bronze)} · ${percent(it.bronzeFraction)}",
                        s(R.string.exploration_silver) + " ${number(it.silver)} · ${percent(it.silverFraction)}",
                        s(R.string.exploration_gold) + " ${number(it.gold)} · ${percent(it.goldFraction)}").joinToString("\n"))
                }))
                sections += ShareCardSection(s(R.string.pds_recent_weapon), listOf(optional(content.recentWeapon) {
                    ShareCardRow(it.definition.name, listOf(unknown(it.category?.name), phantomDateText(it.record.firstAcquiredAt, zone, locale) ?: s(R.string.pdr_unknown)).joinToString("\n"),
                        PersonalDataShareImage.ItemIcon(it.definition.iconId))
                }))
                listOf(ExplorationFieldKind.KnowledgeLevel, ExplorationFieldKind.Fates, ExplorationFieldKind.CriticalEncounters).map { field ->
                    s(field.label()) to (content.overview.metrics.firstOrNull { it.kind == field }?.value ?: s(R.string.pdr_unknown))
                }
            }
        }
        return ShareCardContent(s(content.kind.titleResource()), cover, metrics, sections, radar)
    }
    private fun best(value: FrontlineBestRecord) = ShareCardRow(unknown(value.mapName), listOf(
        unknown(value.jobName), s(R.string.pds_placement, value.placement?.let { (it.ordinal + 1).toString() } ?: s(R.string.pdr_unknown)),
        "${s(R.string.personal_data_metric_kills)} ${number(value.kills)} · ${s(R.string.personal_data_metric_deaths)} ${number(value.deaths)} · ${s(R.string.personal_data_metric_assists)} ${number(value.assists)}"
    ).joinToString("\n"), value.jobName?.let(PersonalDataShareImage::JobIcon))
}
