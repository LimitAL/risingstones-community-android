package top.cxmeow.risingstones.feature.personaldata.domain

interface OccultCatalogService { suspend fun fetchCatalog(): OccultCatalog }
data class OccultCatalog(
    val version: Int,
    val schemaVersion: Int,
    val publishedAt: String,
    val itemIconBaseUrl: String,
    val supportJobs: List<OccultSupportJob>,
    val itemDirectory: List<OccultCatalogItem>,
    val weaponStages: List<OccultWeaponStage>,
    val phaseResources: OccultPhaseResources,
)
data class OccultSupportJob(
    val id: Int, val name: String, val nameEnglish: String, val description: String,
    val levelMax: Int, val jobIndex: Int, val iconId: Int,
)
data class OccultCatalogItem(val itemId: Int, val name: String, val iconId: Int, val itemUICategoryId: Int? = null)
data class OccultWeaponStage(val id: String, val name: String, val order: Int, val items: List<OccultCatalogItem>)
data class OccultPhaseResources(
    val penumbraeItems: List<OccultCatalogItem>,
    val halfSoulCrystalItemIds: List<Int>,
    val eclipticumCrystals: List<OccultCatalogItem>,
)
sealed class OccultCatalogException(message: String) : Exception(message) {
    class UnsupportedSchema(val version: Int) : OccultCatalogException("Unsupported Occult catalog schema")
    data object InvalidResponse : OccultCatalogException("Invalid Occult catalog")
}
