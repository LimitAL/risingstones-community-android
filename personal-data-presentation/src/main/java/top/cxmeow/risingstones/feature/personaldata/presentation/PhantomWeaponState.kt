package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.ZoneId
import top.cxmeow.risingstones.feature.personaldata.domain.*

data class PhantomWeaponRow(val definition: PhantomWeaponDefinition, val record: PhantomWeaponItemRecord?) {
    val isObtained: Boolean get() = record != null
}

data class PhantomWeaponMaterialRow(
    val definition: PhantomWeaponMaterialDefinition,
    val count: Long?,
    val record: PhantomWeaponItemRecord?,
    val target: Long? = null,
) {
    val fraction: Double? get() = target?.takeIf { it > 0 }?.let { maximum -> count?.let { (it.toDouble() / maximum).coerceIn(0.0, 1.0) } }
}
data class PhantomWeaponAetherRow(val element: PhantomWeaponElement, val points: Long?, val target: Long = 10_000) {
    val fraction: Double? get() = points?.let { (it.toDouble() / target).coerceIn(0.0, 1.0) }
}
data class PhantomWeaponLensProgress(val cumulative: Long?, val step: Int?, val current: Long?, val target: Long?, val isComplete: Boolean) {
    val fraction: Double? get() = target?.takeIf { it > 0 }?.let { maximum -> current?.let { (it.toDouble() / maximum).coerceIn(0.0, 1.0) } }
}
sealed interface PhantomWeaponMaterials {
    data class SoulCrystals(val rows: List<PhantomWeaponMaterialRow>) : PhantomWeaponMaterials
    data class Aether(val rows: List<PhantomWeaponAetherRow>) : PhantomWeaponMaterials
    data class Lens(val progress: PhantomWeaponLensProgress) : PhantomWeaponMaterials
    data class DemiAtma(val rows: List<PhantomWeaponMaterialRow>) : PhantomWeaponMaterials
    data object None : PhantomWeaponMaterials
}

data class PhantomWeaponUiState(
    val isOpen: Boolean = false,
    val returnFromHistory: Boolean = false,
    val selectedStage: PhantomWeaponStage? = null,
    val query: String = "",
    val obtainedOnly: Boolean = false,
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val items: List<PhantomWeaponItemRecord>? = null,
    val aether: List<PhantomWeaponAetherRecord>? = null,
    val itemError: ExplorationError? = null,
    val aetherError: ExplorationError? = null,
    val catalog: PhantomWeaponCatalog? = null,
    val isLoadingCatalog: Boolean = false,
    val catalogError: ExplorationError? = null,
    val contentGeneration: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val hasUnknownProgress: Boolean get() = items == null || aether == null || catalog == null ||
        items.any { it.category in listOf("半魂晶", "水晶混合黏土", "消幻晶") && (it.quantity?.let { count -> count < 0 } ?: true) } ||
        aether.any { it.points?.let { points -> points < 0 } ?: true }
    val maximumStage: PhantomWeaponStage? get() = PhantomWeaponViews.maximumStage(items, aether, catalog)
    val availableStages: List<PhantomWeaponStage> get() = maximumStage?.let { maximum -> PhantomWeaponStage.entries.filter { it.order <= maximum.order } }.orEmpty()
    val weapons: List<PhantomWeaponRow> get() = PhantomWeaponViews.weapons(items, catalog, selectedStage, zone)
    val acquiredCount: Int get() = weapons.count { it.isObtained }
    val rawAcquiredCount: Int get() = PhantomWeaponViews.rawAcquiredCount(items, catalog, selectedStage)
    val recentWeapon: PhantomWeaponRow? get() = weapons.firstOrNull { it.isObtained }
    val filteredWeapons: List<PhantomWeaponRow> get() = weapons.filter { (!obtainedOnly || it.isObtained) && it.definition.name.contains(query.trim(), ignoreCase = true) }
    val visibleWeapons: List<PhantomWeaponRow> get() = if (isExpanded) filteredWeapons else filteredWeapons.take(PhantomWeaponViews.collapsedLimit(rawAcquiredCount))
    val hasMoreWeapons: Boolean get() = visibleWeapons.size < filteredWeapons.size
    val materials: PhantomWeaponMaterials get() = PhantomWeaponViews.materials(selectedStage, items, aether, catalog)
}
