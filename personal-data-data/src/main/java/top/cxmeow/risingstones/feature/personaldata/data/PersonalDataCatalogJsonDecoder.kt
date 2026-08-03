package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import top.cxmeow.risingstones.feature.personaldata.domain.FishKingCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogFashionAccessory
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSet
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSetItem
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogStain
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSummary
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.SavageRaidCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.SavageRaidCatalogSeries
import top.cxmeow.risingstones.feature.personaldata.domain.SavageRaidCatalogTier

/**
 * Decodes optional game-data enrichments without coupling the public feature to their transport.
 *
 * The standalone app uses no provider today. A host may fetch compatible schema-v1 documents from
 * its own source and pass only their response bodies to this decoder.
 */
class PersonalDataCatalogJsonDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    fun decode(
        fishDocument: ByteArray?,
        savageDocument: ByteArray?,
        glamourDocument: ByteArray?,
    ): PersonalDataOfficialCatalogs {
        val fishRows = document(fishDocument)?.objectValue("content")?.arrayValue("fish").orEmpty()
        val seriesRows = document(savageDocument)?.objectValue("content")?.arrayValue("series").orEmpty()
        val savageSeries = seriesRows.mapNotNull(JsonElement::toSavageSeries)
        val glamourContent = document(glamourDocument)?.objectValue("content")
        return PersonalDataOfficialCatalogs(
            fish = fishRows.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item.intValue("itemId", "item_id") ?: return@mapNotNull null
                id to FishKingCatalogEntry(
                    itemId = id,
                    iconId = item.intValue("iconId", "icon_id") ?: 0,
                    name = item.textValue("name").orEmpty(),
                    patch = item.textValue("patch").orEmpty(),
                )
            }.toMap(),
            savageRaids = savageSeries
                .flatMap(SavageRaidCatalogSeries::tiers)
                .flatMap(SavageRaidCatalogTier::raids)
                .associateBy(SavageRaidCatalogEntry::instanceId),
            glamour = glamourContent?.toGlamourCatalog(),
            savageSeries = savageSeries,
        )
    }

    private fun document(bytes: ByteArray?): JsonObject? {
        if (bytes == null) return null
        return runCatching {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }.getOrNull()?.takeIf { it.intValue("schemaVersion", "schema_version") == 1 }
    }
}

private fun JsonElement.toSavageSeries(): SavageRaidCatalogSeries? {
    val item = this as? JsonObject ?: return null
    return SavageRaidCatalogSeries(
        name = item.textValue("name").orEmpty(),
        abbreviation = item.textValue("abbreviation").orEmpty(),
        tiers = item.arrayValue("tiers").mapNotNull(JsonElement::toSavageTier),
    )
}

private fun JsonElement.toSavageTier(): SavageRaidCatalogTier? {
    val item = this as? JsonObject ?: return null
    return SavageRaidCatalogTier(
        nameEnglish = item.textValue("nameEnglish", "name_english").orEmpty(),
        nameChinese = item.textValue("nameChinese", "name_chinese").orEmpty(),
        achievementOnly = item.booleanValue("achievementOnly", "achievement_only") ?: false,
        achievementText = item.textValue("achievementText", "achievement_text"),
        raids = item.arrayValue("raids").mapNotNull(JsonElement::toSavageRaid),
    )
}

private fun JsonElement.toSavageRaid(): SavageRaidCatalogEntry? {
    val item = this as? JsonObject ?: return null
    return SavageRaidCatalogEntry(
        instanceId = item.intValue("instanceId", "instance_id") ?: return null,
        name = item.textValue("name").orEmpty(),
        imageId = item.intValue("imageId", "image_id"),
    )
}

private fun JsonObject.toGlamourCatalog(): GlamourCatalogSummary {
    val sets = arrayValue("sets")
    val accessories = arrayValue("fashionAccessories")
    val stains = arrayValue("stains")
    return GlamourCatalogSummary(
        setCount = sets.size,
        fashionAccessoryCount = accessories.size,
        stainCount = stains.size,
        sets = sets.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            GlamourCatalogSet(
                mirageSetId = item.intValue("mirageSetId", "mirage_set_id") ?: return@mapNotNull null,
                name = item.textValue("name").orEmpty(),
                iconId = item.intValue("iconId", "icon_id"),
                items = item.arrayValue("items").mapNotNull itemMap@ { value ->
                    val setItem = value as? JsonObject ?: return@itemMap null
                    GlamourCatalogSetItem(
                        slotIndex = setItem.intValue("slotIndex", "slot_index") ?: return@itemMap null,
                        itemId = setItem.intValue("itemId", "item_id") ?: return@itemMap null,
                        name = setItem.textValue("name").orEmpty(),
                        iconId = setItem.intValue("iconId", "icon_id") ?: 0,
                    )
                },
            )
        },
        fashionAccessories = accessories.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            GlamourCatalogFashionAccessory(
                id = item.intValue("id") ?: return@mapNotNull null,
                iconId = item.intValue("iconId", "icon_id") ?: 0,
                name = item.textValue("name"),
            )
        },
        stains = stains.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            GlamourCatalogStain(
                stainId = item.intValue("stainId", "stain_id") ?: return@mapNotNull null,
                name = item.textValue("name").orEmpty(),
                color = item.longValue("color") ?: 0,
                isMetallic = item.booleanValue("isMetallic", "is_metallic") ?: false,
            )
        },
    )
}

private fun JsonObject.value(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { this[it]?.takeUnless { value -> value is JsonNull } }
private fun JsonObject.textValue(vararg names: String): String? =
    (value(*names) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.intValue(vararg names: String): Int? =
    (value(*names) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
private fun JsonObject.longValue(vararg names: String): Long? =
    (value(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
private fun JsonObject.booleanValue(vararg names: String): Boolean? =
    (value(*names) as? JsonPrimitive)?.let {
        it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull()
    }
private fun JsonObject.objectValue(vararg names: String): JsonObject? = value(*names) as? JsonObject
private fun JsonObject.arrayValue(vararg names: String): List<JsonElement> =
    (value(*names) as? JsonArray).orEmpty()
