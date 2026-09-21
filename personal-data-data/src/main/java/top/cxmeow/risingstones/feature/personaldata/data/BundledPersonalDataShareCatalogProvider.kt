package top.cxmeow.risingstones.feature.personaldata.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareImage
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareResourceService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataVanityCategory
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomShareJob
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomShareWeaponCategory
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateShareAchievement

/** Reviewed public share resources, read from the classpath without a session or character data. */
class BundledPersonalDataShareCatalogProvider : PersonalDataShareResourceService {
    private val supplementaryCatalogs = BundledPersonalDataSupplementaryCatalogProvider()
    private val snapshot: String by lazy {
        try {
            javaClass.getResourceAsStream(ShareCatalogResource)?.use { it.readBytes().decodeToString() }
                ?: throw PersonalDataException.MissingPayload
        } catch (_: IOException) {
            throw PersonalDataException.MissingPayload
        }
    }

    override suspend fun fetchShareCatalogs(): PersonalDataShareCatalogs = withContext(Dispatchers.IO) {
        ensureActive()
        val vanityCategories = supplementaryCatalogs.fetchSupplementaryCatalogs().vanityCategories
        ensureActive()
        decodePersonalDataShareCatalog(snapshot, vanityCategories).also { ensureActive() }
    }

    override fun sharePageUrl(kind: PersonalDataShareKind): String =
        "https://ff14risingstones.web.sdo.com/pc/index.html#/statistics/${kind.name.lowercase()}"

    override fun shareImageUrl(image: PersonalDataShareImage): String? = when (image) {
        is PersonalDataShareImage.Cover -> when (image.kind) {
            PersonalDataShareKind.Fishing -> StaticImageBase + "ff14_3d76515f2ed4ff71.png"
            PersonalDataShareKind.Glamour -> StaticImageBase + "ff14_83038ecea0a484cc.png"
            PersonalDataShareKind.Frontline -> StaticImageBase + "ff14_4a3bbd691f4c3875.png"
            PersonalDataShareKind.Occult -> StaticImageBase + "ff14_85a1d50894a27f02.png"
            PersonalDataShareKind.Savage -> "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/savage/default.png"
            PersonalDataShareKind.Ultimate -> personalDataUltimateCoverUrl(1363)
        }
        is PersonalDataShareImage.UltimateCover -> personalDataUltimateCoverUrl(image.territoryType)
        is PersonalDataShareImage.UltimateMedal -> personalDataUltimateMedalImageUrl(image.territoryType)
        is PersonalDataShareImage.RaidCover -> personalDataRaidImageUrl(image.imageId)
        is PersonalDataShareImage.ItemIcon -> personalDataItemIconUrl(image.iconId)
        is PersonalDataShareImage.AchievementIcon -> personalDataAchievementIconUrl(image.iconId)
        is PersonalDataShareImage.PhantomJobIcon -> phantomJobIconUrl(image.iconId)
        is PersonalDataShareImage.JobIcon -> personalDataUltimateJobIconUrl(image.jobName)
        PersonalDataShareImage.FrontlineBadge -> personalDataFrontlineAchievementImageUrl()
        PersonalDataShareImage.Logo -> StaticImageBase + "ff14_b09dfbeb911eabbb.png"
        PersonalDataShareImage.GameLogo -> StaticImageBase + "ff14_52f521799906ed0b.png"
    }

    private companion object {
        const val ShareCatalogResource = "/top/cxmeow/risingstones/feature/personaldata/data/official-share-catalog.json"
        const val StaticImageBase = "https://ff14risingstones.web.sdo.com/mob/static/images/"
    }
}

internal fun decodePersonalDataShareCatalog(
    text: String,
    vanityCategories: List<PersonalDataVanityCategory>,
): PersonalDataShareCatalogs {
    val document = try {
        Json { ignoreUnknownKeys = true }.decodeFromString<ShareCatalogDocument>(text)
    } catch (_: SerializationException) {
        throw PersonalDataException.MissingPayload
    } catch (_: IllegalArgumentException) {
        throw PersonalDataException.MissingPayload
    }
    return try {
        document.toDomain(vanityCategories)
    } catch (_: IllegalArgumentException) {
        throw PersonalDataException.MissingPayload
    }
}

private val ShareCatalogUltimateTerritoryTypes = setOf(733, 777, 887, 968, 1122, 1238, 1363)

@Serializable
private data class ShareCatalogDocument(
    val formatVersion: Int,
    val ultimateAchievements: List<ShareUltimateAchievement>,
    val phantomJobs: List<SharePhantomJob>,
    val weapons: List<ShareWeaponCategory>,
) {
    fun toDomain(vanityCategories: List<PersonalDataVanityCategory>): PersonalDataShareCatalogs {
        require(formatVersion == 1)
        require(ultimateAchievements.map { it.territoryType }.toSet() == ShareCatalogUltimateTerritoryTypes)
        require(ultimateAchievements.size == ShareCatalogUltimateTerritoryTypes.size)
        require(ultimateAchievements.map { it.achievementId }.distinct().size == ultimateAchievements.size)
        require(ultimateAchievements.all {
            it.achievementId > 0 && it.medalId > 0 && it.name.isNotBlank() && it.detail.isNotBlank()
        })
        require(phantomJobs.map { it.id } == (0..23).toList())
        require(phantomJobs.all { it.iconId > 0 && it.name.isNotBlank() && it.levelCap >= 0 })
        require(weapons.size == 44 && weapons.map { it.itemId }.distinct().size == weapons.size)
        require(weapons.all { it.itemId > 0 && it.itemUiCategoryId > 0 })

        val categoryById = vanityCategories.associateBy(PersonalDataVanityCategory::id)
        val weaponCategories = weapons.associate { row ->
            val category = requireNotNull(categoryById[row.itemUiCategoryId])
            row.itemId to PhantomShareWeaponCategory(category.id, category.name)
        }
        return PersonalDataShareCatalogs(
            ultimateAchievements = ultimateAchievements.map {
                UltimateShareAchievement(it.territoryType, it.achievementId, it.medalId, it.name, it.detail)
            },
            phantomJobs = phantomJobs.map { PhantomShareJob(it.id, it.name, it.iconId, it.levelCap) },
            weaponItemCategories = weaponCategories,
        )
    }
}

@Serializable
private data class ShareUltimateAchievement(
    val territoryType: Int,
    val achievementId: Int,
    val medalId: Int,
    val name: String,
    val detail: String,
)

@Serializable
private data class SharePhantomJob(val id: Int, val name: String, val iconId: Int, val levelCap: Int)

@Serializable
private data class ShareWeaponCategory(val itemId: Int, val itemUiCategoryId: Int)

/** 9da6.getPhantomJobIconUrl prefixes the numeric icon id with one ASCII zero before padding. */
private fun phantomJobIconUrl(iconId: Int): String? {
    if (iconId <= 0) return null
    val icon = ("0$iconId").padStart(6, '0')
    return "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult/job/${icon}_hr1.png"
}
