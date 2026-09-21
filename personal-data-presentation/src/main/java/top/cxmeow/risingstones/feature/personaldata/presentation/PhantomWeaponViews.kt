package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import top.cxmeow.risingstones.feature.personaldata.domain.*

object PhantomWeaponViews {
    fun maximumStage(items: List<PhantomWeaponItemRecord>?, aether: List<PhantomWeaponAetherRecord>?, catalog: PhantomWeaponCatalog?): PhantomWeaponStage? {
        if (items == null || aether == null || catalog == null) return null
        val demiatma = items.filter { it.category == "消幻晶" }
        val eclipticumIds = catalog.weapons.filter { it.stage == PhantomWeaponStage.Eclipticum }.map { it.itemId }.toSet()
        return when {
            demiatma.count { (it.quantity ?: -1) >= 100 } >= 3 && items.any { it.itemId in eclipticumIds } -> PhantomWeaponStage.Occultum
            demiatma.any { (it.quantity ?: 0) > 0 } -> PhantomWeaponStage.Eclipticum
            items.any { it.category == "水晶混合黏土" && (it.quantity ?: 0) > 0 } -> PhantomWeaponStage.Obscurum
            aether.any { (it.points ?: 0) > 0 } -> PhantomWeaponStage.Umbrae
            items.any { it.category == "半魂晶" && (it.quantity ?: 0) > 0 } -> PhantomWeaponStage.Penumbrae
            else -> null
        }
    }

    fun weapons(items: List<PhantomWeaponItemRecord>?, catalog: PhantomWeaponCatalog?, stage: PhantomWeaponStage?, zone: ZoneId = ZoneId.systemDefault()): List<PhantomWeaponRow> {
        if (items == null || catalog == null || stage == null) return emptyList()
        val firstRecords = items.filter { it.category == "幻境武器" }.distinctBy { it.itemId }.associateBy { it.itemId }
        return catalog.weapons.filter { it.stage == stage }.distinctBy { it.itemId }.map { PhantomWeaponRow(it, firstRecords[it.itemId]) }
            .sortedWith(compareByDescending<PhantomWeaponRow> { it.isObtained }
                .thenByDescending { timeInstant(it.record?.firstAcquiredAt, zone) }
                .thenBy { if (it.isObtained) 0 else it.definition.itemId })
    }

    fun rawAcquiredCount(items: List<PhantomWeaponItemRecord>?, catalog: PhantomWeaponCatalog?, stage: PhantomWeaponStage?): Int {
        val ids = catalog?.weapons.orEmpty().filter { it.stage == stage }.map { it.itemId }.toSet()
        return items.orEmpty().count { it.category == "幻境武器" && it.itemId in ids }
    }

    fun collapsedLimit(rawCount: Int): Int = ((rawCount.coerceAtLeast(0).toLong() + 1) / 2 * 2).coerceIn(2, 22).toInt()

    /** The explicit display zone is used only for comparison; source precision stays in the original record. */
    fun timeInstant(value: PhantomWeaponRecordTime?, zone: ZoneId): Instant? = try {
        when (value) {
            is PhantomWeaponRecordTime.CalendarDate -> value.value.atStartOfDay(zone).toInstant()
            is PhantomWeaponRecordTime.LocalTime -> value.value.atZone(zone).toInstant()
            is PhantomWeaponRecordTime.OffsetTime -> value.value
            null -> null
        }
    } catch (_: DateTimeException) { null }

    fun materials(stage: PhantomWeaponStage?, items: List<PhantomWeaponItemRecord>?, aether: List<PhantomWeaponAetherRecord>?, catalog: PhantomWeaponCatalog?): PhantomWeaponMaterials = when (stage) {
        PhantomWeaponStage.Penumbrae -> PhantomWeaponMaterials.SoulCrystals(soulCrystals(items, catalog))
        PhantomWeaponStage.Umbrae -> PhantomWeaponMaterials.Aether(PhantomWeaponElement.entries.map { element ->
            val record = aether?.firstOrNull { it.color == element.name.lowercase() }
            PhantomWeaponAetherRow(element, if (record == null) aether?.let { 0L } else record.points?.takeIf { it >= 0 })
        })
        PhantomWeaponStage.Obscurum -> PhantomWeaponMaterials.Lens(lens(items))
        PhantomWeaponStage.Eclipticum -> PhantomWeaponMaterials.DemiAtma(listOf(50974, 50975, 50976).mapNotNull { id ->
            val definition = catalog?.demiatma?.firstOrNull { it.itemId == id } ?: return@mapNotNull null
            val record = items?.firstOrNull { it.itemId == id }
            PhantomWeaponMaterialRow(definition, if (record == null) items?.let { 0L } else record.quantity?.takeIf { it >= 0 }, record, 100)
        })
        PhantomWeaponStage.Occultum, null -> PhantomWeaponMaterials.None
    }

    private fun soulCrystals(items: List<PhantomWeaponItemRecord>?, catalog: PhantomWeaponCatalog?): List<PhantomWeaponMaterialRow> {
        val definitions = catalog?.soulCrystals.orEmpty().distinctBy { it.itemId }
        if (items == null) return definitions.map { PhantomWeaponMaterialRow(it, null, null) }
        val observed = items.filter { it.category == "半魂晶" }.map { record ->
            val definition = definitions.firstOrNull { it.itemId == record.itemId }
                ?: PhantomWeaponMaterialDefinition(record.itemId ?: 0, record.name.orEmpty(), 0)
            PhantomWeaponMaterialRow(definition, record.quantity?.takeIf { count -> count >= 0 }, record)
        }.sortedWith(compareBy<PhantomWeaponMaterialRow> { it.count == null }.thenBy { it.count }.thenBy { it.definition.itemId })
        val observedIds = observed.map { it.definition.itemId }.toSet()
        return observed + definitions.filter { it.itemId !in observedIds }.map { PhantomWeaponMaterialRow(it, 0, null) }
    }

    fun lens(items: List<PhantomWeaponItemRecord>?): PhantomWeaponLensProgress {
        val clay = items?.filter { it.category == "水晶混合黏土" }
        val firstCandidate = clay?.firstOrNull { it.quantity != 0L }
        val cumulative = if (firstCandidate != null) firstCandidate.quantity?.takeIf { it >= 0 } else clay?.let { 0L }
        if (cumulative == null) return PhantomWeaponLensProgress(null, null, null, null, false)
        return when {
            cumulative < 100 -> PhantomWeaponLensProgress(cumulative, 1, cumulative, 100, false)
            cumulative < 300 -> PhantomWeaponLensProgress(cumulative, 2, cumulative - 100, 200, false)
            cumulative < 600 -> PhantomWeaponLensProgress(cumulative, 3, cumulative - 300, 300, false)
            cumulative < 1200 -> PhantomWeaponLensProgress(cumulative, 4, cumulative - 600, 600, false)
            else -> PhantomWeaponLensProgress(cumulative, 5, 1200, 1200, true)
        }
    }
}
