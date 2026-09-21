package top.cxmeow.risingstones.feature.personaldata.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAchievementCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOceanFishCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataVanityCategory

/** Reviewed official public catalogs, loaded offline without Android Context or session access. */
class BundledPersonalDataSupplementaryCatalogProvider : PersonalDataSupplementaryCatalogProvider {
    private val snapshot: SupplementaryCatalogDocument by lazy {
        try {
            val bytes = BundledPersonalDataSupplementaryCatalogProvider::class.java
                .getResourceAsStream(ResourcePath)?.use { it.readBytes() }
                ?: throw PersonalDataException.MissingPayload
            CatalogJson.decodeFromString<SupplementaryCatalogDocument>(bytes.decodeToString())
                .also { if (it.formatVersion != 1) throw PersonalDataException.MissingPayload }
        } catch (_: IOException) {
            throw PersonalDataException.MissingPayload
        } catch (_: SerializationException) {
            throw PersonalDataException.MissingPayload
        }
    }

    override suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs = withContext(Dispatchers.IO) {
        ensureActive()
        val result = snapshot.toDomain()
        ensureActive()
        result
    }

    private companion object {
        const val ResourcePath = "/top/cxmeow/risingstones/feature/personaldata/data/official-supplementary-catalogs.json"
        val CatalogJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class SupplementaryCatalogDocument(
    val formatVersion: Int,
    val oceanFish: List<SupplementaryOceanFish>,
    val fishingAchievements: List<SupplementaryAchievement>,
    val frontlineAchievements: List<SupplementaryAchievement>,
    val vanityCategories: List<SupplementaryVanityCategory>,
) {
    fun toDomain() = PersonalDataSupplementaryCatalogs(
        oceanFish = oceanFish.map { PersonalDataOceanFishCatalogEntry(it.itemId, it.iconId, it.name) },
        fishingAchievements = fishingAchievements.map { it.toDomain() },
        frontlineAchievements = frontlineAchievements.map { it.toDomain() },
        vanityCategories = vanityCategories.map {
            PersonalDataVanityCategory(it.id, it.name, it.iconId, it.majorOrder, it.minorOrder, it.selectable)
        },
    )
}

@Serializable
private data class SupplementaryOceanFish(val itemId: Int, val iconId: Int, val name: String)

@Serializable
private data class SupplementaryAchievement(val achievementId: Int, val name: String, val detail: String, val iconId: Int?) {
    fun toDomain() = PersonalDataAchievementCatalogEntry(achievementId, name, detail, iconId)
}

@Serializable
private data class SupplementaryVanityCategory(
    val id: Int,
    val name: String,
    val iconId: Int,
    val majorOrder: Int,
    val minorOrder: Int,
    val selectable: Boolean,
)
