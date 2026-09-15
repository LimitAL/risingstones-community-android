package top.cxmeow.risingstones.feature.forum.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolves links that point back into an official Rising Stones post.
 *
 * Hosts can use this without sharing navigation or UI implementations.
 */
object OfficialForumLinkParser {
    private val postHosts = setOf(
        "ff14risingstones.web.sdo.com",
        "apiff14risingstones.web.sdo.com",
    )

    fun postId(rawUrl: String): Int? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host?.lowercase()?.trimEnd('.') !in postHosts) return null
        queryId(uri.rawQuery)?.let { return it }
        routeId(uri.path)?.let { return it }
        val fragment = uri.rawFragment ?: return null
        queryId(fragment.substringAfter('?', ""))?.let { return it }
        return routeId(fragment)
    }

    private fun queryId(query: String?): Int? =
        query?.split('&')?.firstNotNullOfOrNull { item ->
            val fields = item.split('=', limit = 2)
            val name = decode(fields.first()) ?: return@firstNotNullOfOrNull null
            val value = decode(fields.getOrElse(1) { "" })
                ?: return@firstNotNullOfOrNull null
            if (name == "id") value.toIntOrNull()?.takeIf { it > 0 } else null
        }

    // PC routes use `.../post/detail/<id>`; the mobile web client uses `.../tiedes/<id>`.
    private val routeIdSegments = setOf("detail", "tiedes")

    private fun routeId(route: String?): Int? {
        val parts = route.orEmpty().split('/').filter(String::isNotBlank)
        val markerIndex = parts.indexOfFirst { it in routeIdSegments }
        if (markerIndex < 0) return null
        return parts.getOrNull(markerIndex + 1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}

object OfficialForumResourceUrls {
    fun emojiImageUrl(number: Int): String =
        "https://static.web.sdo.com/jijiamobile/pic/ff14/2023ffstone/emo$number.png"
}
