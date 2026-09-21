package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import top.cxmeow.risingstones.feature.personaldata.domain.FishKingCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogFashionAccessory
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSet
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSetItem
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogStain
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSummary
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.SavageRaidCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.SavageRaidCatalogSeries
import top.cxmeow.risingstones.feature.personaldata.domain.SavageRaidCatalogTier

/**
 * Offline snapshot of the official public mobile catalog, reviewed on 2026-09-20.
 *
 * Source URLs and hashes are included in the packaged resource. Loading uses the classpath, so
 * Android Context, credentials, network access and JavaScript execution are unnecessary.
 * This snapshot is independent of the host-supplied schema accepted by [PersonalDataCatalogJsonDecoder].
 */
class BundledPersonalDataCatalogProvider : PersonalDataCatalogProvider, PersonalDataSupplementaryCatalogProvider {
    private val supplementaryProvider = BundledPersonalDataSupplementaryCatalogProvider()

    override suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs =
        supplementaryProvider.fetchSupplementaryCatalogs()

    private val snapshot: BundledCatalogDocument by lazy {
        try {
            val bytes = BundledPersonalDataCatalogProvider::class.java.getResourceAsStream(ResourcePath)
                ?.use { it.readBytes() } ?: throw PersonalDataException.MissingPayload
            CatalogJson.decodeFromString<BundledCatalogDocument>(bytes.decodeToString())
                .also { if (it.formatVersion != 1) throw PersonalDataException.MissingPayload }
        } catch (_: IOException) {
            throw PersonalDataException.MissingPayload
        } catch (_: SerializationException) {
            throw PersonalDataException.MissingPayload
        }
    }

    override suspend fun fetchCatalogs(): PersonalDataOfficialCatalogs = withContext(Dispatchers.IO) {
        ensureActive()
        val result = snapshot.toDomain()
        ensureActive()
        result
    }

    private companion object {
        const val ResourcePath = "/top/cxmeow/risingstones/feature/personaldata/data/official-catalogs.json"
        val CatalogJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class BundledCatalogDocument(
    val formatVersion: Int,
    val fish: List<BundledFish>,
    val savageSeries: List<BundledSavageSeries>,
    val sets: List<BundledGlamourSet>,
    val fashionAccessories: List<BundledAccessory>,
    val stains: List<BundledStain>,
) {
    fun toDomain(): PersonalDataOfficialCatalogs {
        val series = savageSeries.map { row ->
            SavageRaidCatalogSeries(row.name, row.abbreviation, row.tiers.map { tier ->
                SavageRaidCatalogTier(tier.nameEnglish, tier.nameChinese, tier.achievementOnly,
                    tier.achievementText, tier.raids.map { raid ->
                        SavageRaidCatalogEntry(raid.instanceId, raid.name, raid.imageId)
                    })
            })
        }
        return PersonalDataOfficialCatalogs(
            fish = fish.associate { it.itemId to FishKingCatalogEntry(it.itemId, it.iconId, it.name, it.patch) },
            savageRaids = series.flatMap { it.tiers }.flatMap { it.raids }.associateBy { it.instanceId },
            glamour = GlamourCatalogSummary(
                setCount = sets.size, fashionAccessoryCount = fashionAccessories.size, stainCount = stains.size,
                sets = sets.map { row ->
                    GlamourCatalogSet(row.mirageSetId, row.name, row.iconId, row.items.map { item ->
                        GlamourCatalogSetItem(item.slotIndex, item.itemId, item.name, item.iconId)
                    })
                },
                fashionAccessories = fashionAccessories.map { GlamourCatalogFashionAccessory(it.id, it.iconId, it.name) },
                stains = stains.map { GlamourCatalogStain(it.stainId, it.name, it.color, it.isMetallic) },
            ),
            savageSeries = series,
        )
    }
}

@Serializable
private data class BundledFish(val itemId: Int, val iconId: Int, val name: String, val patch: String)

@Serializable
private data class BundledSavageSeries(val name: String, val abbreviation: String, val tiers: List<BundledSavageTier>)

@Serializable
private data class BundledSavageTier(
    val nameEnglish: String,
    val nameChinese: String,
    val achievementOnly: Boolean,
    val achievementText: String?,
    val raids: List<BundledSavageRaid>,
)

@Serializable
private data class BundledSavageRaid(val instanceId: Int, val name: String, val imageId: Int?)

@Serializable
private data class BundledGlamourSet(
    val mirageSetId: Int, val name: String, val iconId: Int, val items: List<BundledGlamourSetItem>,
)

@Serializable
private data class BundledGlamourSetItem(val slotIndex: Int, val itemId: Int, val name: String, val iconId: Int)

@Serializable
private data class BundledAccessory(val id: Int, val iconId: Int, val name: String)

@Serializable
private data class BundledStain(val stainId: Int, val name: String, val color: Long, val isMetallic: Boolean)
