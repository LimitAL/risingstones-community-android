package top.cxmeow.risingstones.feature.personaldata.data

import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.feature.personaldata.domain.*

/** Schema and icon contracts from iOS 611ccb7; the host supplies its own public transport. */
class OccultCatalogJsonDecoder(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun decode(bytes: ByteArray): OccultCatalog {
        val dto = try { json.decodeFromString<CatalogDto>(bytes.decodeToString()) }
        catch (_: IllegalArgumentException) { throw OccultCatalogException.InvalidResponse }
        if (dto.schemaVersion != 1) throw OccultCatalogException.UnsupportedSchema(dto.schemaVersion)
        val content = dto.content
        // Duplicate IDs are malformed catalog data, rather than silently changing lookup semantics.
        if (content.supportJobs.map { it.id }.distinct().size != content.supportJobs.size ||
            content.itemDirectory.map { it.itemId }.distinct().size != content.itemDirectory.size ||
            content.weaponStages.map { it.id }.distinct().size != content.weaponStages.size) {
            throw OccultCatalogException.InvalidResponse
        }
        return OccultCatalog(dto.version, dto.schemaVersion, dto.publishedAt,
            validatedOccultItemIconBaseUrl(content.itemIconBaseUrl) ?: DefaultOccultItemIconBaseUrl,
            content.supportJobs.map { OccultSupportJob(it.id, it.name, it.nameEnglish, it.description, it.levelMax, it.jobIndex, it.iconId) },
            content.itemDirectory.map { it.domain() },
            content.weaponStages.map { OccultWeaponStage(it.id, it.name, it.order, it.items.map { item -> item.domain() }) },
            OccultPhaseResources(content.phaseResources.penumbraeItems.map { it.domain() },
                content.phaseResources.halfSoulCrystalItemIds, content.phaseResources.eclipticumCrystals.map { it.domain() }))
    }
}

const val DefaultOccultItemIconBaseUrl = "https://ff14-eo.web.sdo.com/ffstones/item/icon/dcsvv4fowz2m"
fun validatedOccultItemIconBaseUrl(candidate: String?): String? {
    val uri = candidate?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (uri.scheme != "https" || uri.host != "ff14-eo.web.sdo.com" || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || !uri.path.startsWith("/ffstones/item/icon/")) return null
    val token = uri.path.removePrefix("/ffstones/item/icon/").trimStart('/')
    if (token.isEmpty() || token.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return null
    return uri.toString().trimEnd('/')
}
fun occultGameItemIconUrl(iconId: Int, baseUrl: String? = null): String? = iconId.takeIf { it >= 0 }?.let {
    val base = validatedOccultItemIconBaseUrl(baseUrl) ?: DefaultOccultItemIconBaseUrl
    "$base/${String.format(Locale.ROOT, "%06d", it / 1000 * 1000)}/${String.format(Locale.ROOT, "%06d", it)}_hr1.png"
}
enum class OccultStaticIconKind(val directory: String) { SupportJob("job/"), Item("item/"), PhaseResource("") }
fun occultStaticIconUrl(iconId: Int, kind: OccultStaticIconKind): String? = iconId.takeIf { it >= 0 }?.let {
    "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult/${kind.directory}${String.format(Locale.ROOT, "%06d", it)}_hr1.png"
}

@Serializable private data class CatalogDto(val version: Int, val schemaVersion: Int, val publishedAt: String, val content: ContentDto)
@Serializable private data class ContentDto(val itemIconBaseUrl: String? = null, val supportJobs: List<JobDto>, val itemDirectory: List<ItemDto>, val weaponStages: List<StageDto>, val phaseResources: ResourcesDto)
@Serializable private data class JobDto(val id: Int, val name: String, val nameEnglish: String, val description: String, val levelMax: Int, val jobIndex: Int, val iconId: Int)
@Serializable private data class ItemDto(val itemId: Int, val name: String, val iconId: Int, val itemUICategoryId: Int? = null) {
    fun domain() = OccultCatalogItem(itemId, name, iconId, itemUICategoryId)
}
@Serializable private data class StageDto(val id: String, val name: String, val order: Int, val items: List<ItemDto>)
@Serializable private data class ResourcesDto(val penumbraeItems: List<ItemDto>, val halfSoulCrystalItemIds: List<Int>, val eclipticumCrystals: List<ItemDto>)
