package top.cxmeow.risingstones.feature.account.data

import kotlinx.serialization.json.*
import top.cxmeow.risingstones.feature.account.domain.*

/** Only the direct self-profile fields identify this wall. Nested unrelated characters are not owners. */
internal fun JsonObject.selfMedalWall(): RisingStonesMedalWall? {
    val rows = this["achieveInfo"] as? JsonArray ?: return null
    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    fun JsonObject.id(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.longOrNull != null }?.content?.trim()?.takeIf { it.isNotEmpty() }
    val character = text("character_name")?.let { name ->
        text("group_name")?.let { RisingStonesCharacter(text("area_name").orEmpty(), it, name) }
    }
    val medals = rows.mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        RisingStonesMedal(row.id("achieve_id") ?: return@mapNotNull null,
            row.text("achieve_name") ?: return@mapNotNull null, row.text("achieve_detail"),
            row.id("medal_type_id"), row.text("medal_type"), row.text("achieve_time"))
    }
    return RisingStonesMedalWall(character, medals, rows.size, rows.size - medals.size)
}
