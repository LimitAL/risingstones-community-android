package top.cxmeow.risingstones.feature.personaldata.presentation

import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationFieldKind.*

/** iOS 611ccb7 outcomes, independent of the standalone client's official-web weapon policy. */
object NativeOccultProjection {
    fun jobs(catalog: OccultCatalog, section: ExplorationSection?): NativeOccultJobs {
        val levels = section?.records.orEmpty().mapNotNull { record ->
            val id = record.field(PhantomJob)?.toIntOrNull() ?: return@mapNotNull null
            val level = record.field(Level)?.toLongOrNull() ?: return@mapNotNull null
            id to level
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
        val regular = catalog.supportJobs.filter { it.id != 0 }.sortedBy { it.id }
        val mastered = regular.count { (levels[it.id] ?: Long.MIN_VALUE) >= it.levelMax }
        return NativeOccultJobs(
            catalog.supportJobs.firstOrNull { it.id == 0 }?.let { NativeOccultJob(it, if (section.hasSnapshot()) mastered.toLong() else null, section.hasSnapshot()) },
            regular.map { NativeOccultJob(it, levels[it.id], section.hasSnapshot()) },
            regular.count { levels[it.id] != null }, mastered, section.hasSnapshot())
    }
    fun boxes(section: ExplorationSection?): NativeOccultBoxes {
        var treasure = 0L
        var bunny = 0L
        val counts = mutableMapOf<String, Long>()
        section?.records.orEmpty().forEach { record ->
            val count = record.field(Quantity).count().coerceAtLeast(0)
            when (record.field(BoxType)) {
                "撒娇罐" -> treasure = treasure.plusCount(count)
                "幸福兔" -> bunny = bunny.plusCount(count)
            }
            val grade = normalizedGrade(record.field(BoxGrade))
            counts[grade] = (counts[grade] ?: 0L).plusCount(count)
        }
        val known = listOf("copper", "silver", "gold")
        val order = known + counts.keys.filterNot { it in known }.sorted()
        return NativeOccultBoxes(treasure, bunny, counts.values.fold(0L) { sum, value -> sum.plusCount(value) },
            order.map { NativeOccultBoxGrade(it, counts[it] ?: 0) }, section.hasSnapshot())
    }
    fun drops(catalog: OccultCatalog, section: ExplorationSection?): List<NativeOccultDrop> {
        val counts = itemCounts(section?.records.orEmpty(), clamp = true)
        return catalog.itemDirectory.map { NativeOccultDrop(it, counts[it.itemId] ?: 0, section.hasSnapshot()) }
            .sortedWith(compareByDescending<NativeOccultDrop> { it.count }.thenBy { it.item.itemId })
    }
    fun history(catalog: OccultCatalog?, section: ExplorationSection?): List<NativeOccultHistoryRecord> {
        val items = catalog?.itemDirectory.orEmpty().associateBy { it.itemId }
        return section?.records.orEmpty().mapNotNull { record ->
            val item = record.itemId?.let { items[it] }
            val name = record.field(ItemName) ?: item?.name ?: record.title.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NativeOccultHistoryRecord(record.key, record.itemId, name, item?.iconId, record.field(RecordedAt) ?: "—")
        }.sortedByDescending { it.logTime }.take(200)
    }
    fun weapons(catalog: OccultCatalog, items: ExplorationSection?, light: ExplorationSection?): NativeOccultWeapons {
        // Last duplicate weapon wins, including timestamp; the web policy takes the first instead.
        val records = items?.records.orEmpty()
        val weapons = records.filter { it.field(ItemCategory) == "幻境武器" && it.itemId != null }.associateBy { it.itemId }
        val counts = itemCounts(records, clamp = false)
        val stages = catalog.weaponStages.sortedBy { it.order }.map { stage ->
            val rows = stage.items.map { item ->
                val record = weapons[item.itemId]
                NativeOccultWeapon(item, items.hasSnapshot(), items.hasSnapshot() && record != null, record?.field(FirstAcquiredAt))
            }.sortedWith(compareByDescending<NativeOccultWeapon> { it.isObtained }.thenByDescending { it.obtainedAt.orEmpty() }.thenBy { it.item.itemId })
            val progress = when (stage.id) {
                "penumbrae" -> NativeOccultProgress.SoulCrystals(catalog.phaseResources.halfSoulCrystalItemIds.mapNotNull { id ->
                    catalog.phaseResources.penumbraeItems.firstOrNull { it.itemId == id }?.let { NativeOccultResource(it, counts[id] ?: 0, null, items.hasSnapshot()) }
                })
                "umbrae" -> NativeOccultProgress.MagicBoard(listOf("green", "blue", "red", "yellow").map { color ->
                    NativeOccultSphere(color, light?.records?.firstOrNull { it.field(AetherColor) == color }?.field(AetherPoints).count().coerceAtLeast(0))
                }, light.hasSnapshot())
                "obscurum" -> NativeOccultProgress.Clay(clay(records.filter { it.field(ItemCategory) == "水晶混合黏土" }
                    .mapNotNull { it.field(Quantity)?.toLongOrNull() }.maxOrNull() ?: 0, items.hasSnapshot()))
                "eclipticum" -> NativeOccultProgress.Crystals(catalog.phaseResources.eclipticumCrystals.map {
                    NativeOccultResource(it, counts[it.itemId] ?: 0, 100, items.hasSnapshot())
                })
                else -> NativeOccultProgress.BattleMemory
            }
            NativeOccultStage(stage.id, stage.name, stage.order, rows, progress)
        }
        val occultum = stages.firstOrNull { it.id == "occultum" }
        val eclipticum = stages.firstOrNull { it.id == "eclipticum" }
        val crystals = (eclipticum?.progress as? NativeOccultProgress.Crystals)?.rows.orEmpty()
        val suggested = when {
            occultum != null && occultum.obtainedCount > 0 -> occultum.id
            occultum != null && eclipticum != null && eclipticum.obtainedCount > 0 && crystals.isNotEmpty() && crystals.all { it.value >= (it.maximum ?: 0) } -> occultum.id
            else -> stages.lastOrNull { it.obtainedCount > 0 }?.id ?: stages.lastOrNull { it.progress.hasProgress }?.id ?: stages.firstOrNull()?.id
        }
        return NativeOccultWeapons(stages, suggested)
    }
    fun clay(rawTotal: Long, hasSnapshot: Boolean): NativeOccultClay {
        val total = rawTotal.coerceIn(0, 1200)
        return when {
            total < 100 -> NativeOccultClay(total, 1, total, 100, hasSnapshot)
            total < 300 -> NativeOccultClay(total, 2, total - 100, 200, hasSnapshot)
            total < 600 -> NativeOccultClay(total, 3, total - 300, 300, hasSnapshot)
            else -> NativeOccultClay(total, 4, total - 600, 600, hasSnapshot)
        }
    }
    private fun normalizedGrade(value: String?): String = when (val normalized = value?.trim()?.lowercase().orEmpty()) {
        "copper", "bronze", "铜", "銅" -> "copper"
        "silver", "银", "銀" -> "silver"
        "gold", "金" -> "gold"
        else -> normalized.ifEmpty { "unknown" }
    }
    private fun itemCounts(records: List<ExplorationRecord>, clamp: Boolean): Map<Int, Long> = records.mapNotNull { record ->
        val id = record.itemId ?: return@mapNotNull null
        val count = if (clamp) record.field(Quantity).count().coerceAtLeast(0) else record.field(Quantity)?.toLongOrNull() ?: return@mapNotNull null
        id to count
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
}
private fun ExplorationRecord.field(kind: ExplorationFieldKind) = fields.firstOrNull { it.kind == kind }?.value
private fun ExplorationSection?.hasSnapshot() = this != null && hasSnapshot
private fun String?.count() = this?.toLongOrNull() ?: 0L
private fun Long.plusCount(value: Long) = if (Long.MAX_VALUE - this < value) Long.MAX_VALUE else this + value
data class NativeOccultJob(val job: OccultSupportJob, val currentLevel: Long?, val hasSnapshot: Boolean) {
    val isLearned: Boolean get() = hasSnapshot && currentLevel != null
    val isMastered: Boolean get() = hasSnapshot && currentLevel != null && currentLevel >= job.levelMax
}
data class NativeOccultJobs(val freelancer: NativeOccultJob?, val jobs: List<NativeOccultJob>, val learnedCount: Int, val masteredCount: Int, val hasSnapshot: Boolean)
data class NativeOccultBoxGrade(val id: String, val count: Long)
data class NativeOccultBoxes(val treasureCount: Long, val bunnyCount: Long, val totalCount: Long, val grades: List<NativeOccultBoxGrade>, val hasSnapshot: Boolean)
data class NativeOccultDrop(val item: OccultCatalogItem, val count: Long, val hasSnapshot: Boolean) { val isObtained: Boolean get() = hasSnapshot && count > 0 }
data class NativeOccultHistoryRecord(val id: String, val itemId: Int?, val name: String, val iconId: Int?, val logTime: String)
data class NativeOccultWeapon(val item: OccultCatalogItem, val hasSnapshot: Boolean, val isObtained: Boolean, val obtainedAt: String?)
data class NativeOccultResource(val item: OccultCatalogItem, val value: Long, val maximum: Long?, val hasSnapshot: Boolean)
data class NativeOccultSphere(val color: String, val value: Long)
data class NativeOccultClay(val total: Long, val step: Int, val current: Long, val maximum: Long, val hasSnapshot: Boolean)
sealed interface NativeOccultProgress {
    data class SoulCrystals(val rows: List<NativeOccultResource>) : NativeOccultProgress
    data class MagicBoard(val spheres: List<NativeOccultSphere>, val hasSnapshot: Boolean) : NativeOccultProgress
    data class Clay(val value: NativeOccultClay) : NativeOccultProgress
    data class Crystals(val rows: List<NativeOccultResource>) : NativeOccultProgress
    data object BattleMemory : NativeOccultProgress
    val hasProgress: Boolean get() = when (this) {
        is SoulCrystals -> rows.any { it.value > 0 }
        is MagicBoard -> spheres.any { it.value > 0 }
        is Clay -> value.total > 0
        is Crystals -> rows.any { it.value > 0 }
        BattleMemory -> false
    }
}
data class NativeOccultStage(val id: String, val name: String, val order: Int, val weapons: List<NativeOccultWeapon>, val progress: NativeOccultProgress) { val obtainedCount: Int get() = weapons.count { it.isObtained } }
data class NativeOccultWeapons(val stages: List<NativeOccultStage>, val suggestedStageId: String?)
