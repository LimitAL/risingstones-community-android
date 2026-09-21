package top.cxmeow.risingstones.feature.personaldata.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponCatalog
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponDefinition
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponMaterialDefinition
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponStage

/** Reviewed public snapshot, read offline from the AAR/JVM classpath without Context or a session. */
class BundledPhantomWeaponCatalogProvider {
    private val snapshot by lazy {
        try {
            javaClass.getResourceAsStream(PhantomCatalogResource)?.use { it.readBytes().decodeToString() }
                ?: throw PersonalDataException.MissingPayload
        } catch (_: IOException) {
            throw PersonalDataException.MissingPayload
        }
    }

    suspend fun fetchPhantomWeaponCatalog(): PhantomWeaponCatalog = withContext(Dispatchers.IO) {
        ensureActive()
        val catalog = decodePhantomWeaponCatalog(snapshot)
        ensureActive()
        catalog
    }
}

internal const val PhantomCatalogResource = "/top/cxmeow/risingstones/feature/personaldata/data/official-phantom-weapons.json"

/** Only the bundled, independently versioned snapshot schema is accepted here. */
internal fun decodePhantomWeaponCatalog(text: String): PhantomWeaponCatalog {
    val document = try {
        Json { ignoreUnknownKeys = true }.decodeFromString<PhantomCatalogDocument>(text)
    } catch (_: SerializationException) {
        throw PersonalDataException.MissingPayload
    } catch (_: IllegalArgumentException) {
        throw PersonalDataException.MissingPayload
    }
    if (document.formatVersion != 1) throw PersonalDataException.MissingPayload
    val weapons = document.weapons.map {
        val stage = when (it.stage) {
            "penumbrae" -> PhantomWeaponStage.Penumbrae
            "umbrae" -> PhantomWeaponStage.Umbrae
            "obscurum" -> PhantomWeaponStage.Obscurum
            "eclipticum" -> PhantomWeaponStage.Eclipticum
            "occultum" -> PhantomWeaponStage.Occultum
            else -> throw PersonalDataException.MissingPayload
        }
        if (it.itemId <= 0 || it.iconId <= 0 || it.name.isBlank()) throw PersonalDataException.MissingPayload
        PhantomWeaponDefinition(stage, it.itemId, it.name, it.iconId)
    }
    val ranges = mapOf(
        PhantomWeaponStage.Penumbrae to 47869..47890,
        PhantomWeaponStage.Umbrae to 47006..47027,
        PhantomWeaponStage.Obscurum to 50032..50053,
        PhantomWeaponStage.Eclipticum to 50978..50999,
        PhantomWeaponStage.Occultum to 51000..51021,
    )
    if (weapons.size != 110 || ranges.any { (stage, range) ->
            weapons.filter { it.stage == stage }.map { it.itemId } != range.toList()
        }) throw PersonalDataException.MissingPayload
    val soul = document.soulCrystals.map { it.toDomain() }
    val demi = document.demiatma.map { it.toDomain() }
    if (soul.map { it.itemId } != (47744..47749).toList() || demi.map { it.itemId } != listOf(50974, 50975, 50976)) {
        throw PersonalDataException.MissingPayload
    }
    return PhantomWeaponCatalog(weapons, soul, demi)
}

@Serializable
private data class PhantomCatalogDocument(
    val formatVersion: Int,
    val weapons: List<PhantomCatalogWeapon>,
    val soulCrystals: List<PhantomCatalogMaterial>,
    val demiatma: List<PhantomCatalogMaterial>,
)

@Serializable
private data class PhantomCatalogWeapon(val stage: String, val itemId: Int, val name: String, val iconId: Int)

@Serializable
private data class PhantomCatalogMaterial(val itemId: Int, val name: String, val iconId: Int) {
    fun toDomain(): PhantomWeaponMaterialDefinition {
        if (itemId <= 0 || iconId <= 0 || name.isBlank()) throw PersonalDataException.MissingPayload
        return PhantomWeaponMaterialDefinition(itemId, name, iconId)
    }
}
