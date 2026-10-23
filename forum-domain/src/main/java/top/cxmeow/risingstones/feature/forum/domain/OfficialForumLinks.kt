package top.cxmeow.risingstones.feature.forum.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Official site entities; deliberately independent of a host application's navigation types. */
enum class OfficialForumLinkKind { Post, Glamour, Dynamic, GuildRecruitment, RolePlayRecruitment }

data class OfficialForumLinkTarget(val kind: OfficialForumLinkKind, val id: Int)

/** Resolves only the existing official HTTPS hosts. Unknown pages stay external. */
object OfficialForumLinkParser {
    private val postHosts = setOf(
        "ff14risingstones.web.sdo.com",
        "apiff14risingstones.web.sdo.com",
    )
    private val patterns = listOf(
        listOf("post", "detail") to OfficialForumLinkKind.Post,
        listOf("posts", "detail") to OfficialForumLinkKind.Post, // existing host compatibility
        listOf("strat", "detail") to OfficialForumLinkKind.Post,
        listOf("tiedes") to OfficialForumLinkKind.Post,
        listOf("wikides") to OfficialForumLinkKind.Post,
        listOf("glamour", "detail") to OfficialForumLinkKind.Glamour,
        listOf("dynamic", "detail") to OfficialForumLinkKind.Dynamic,
        listOf("recruit", "guild", "detail") to OfficialForumLinkKind.GuildRecruitment,
        listOf("recruit", "roleplay", "detail") to OfficialForumLinkKind.RolePlayRecruitment,
    )

    /** Compatibility API: a recognized non-post must never become a post ID. */
    fun postId(rawUrl: String): Int? = target(rawUrl)?.takeIf { it.kind == OfficialForumLinkKind.Post }?.id

    fun target(rawUrl: String): OfficialForumLinkTarget? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host?.lowercase()?.trimEnd('.') !in postHosts) return null
        val path = segments(uri.rawPath) ?: return null
        route(path)?.let { return it }
        val fragment = uri.rawFragment
        val fragmentPath = fragment?.substringBefore('?')?.let(::segments) ?: if (fragment == null) emptyList() else return null
        // Entity routes take precedence over unrelated query IDs.
        route(fragmentPath)?.let { return it }
        // Keep legacy post query forms, without treating an unknown/non-post page as a post.
        val fragmentIsRoute = fragment != null && (fragment.startsWith('/') || '/' in fragment || '?' in fragment)
        val query = if (fragmentIsRoute && fragmentPath.isNotEmpty()) {
            if (!legacyPostQuery(fragmentPath)) return null
            fragment.substringAfter('?', "").takeIf(String::isNotEmpty) ?: uri.rawQuery
        } else {
            if (!legacyPostQuery(path)) return null
            uri.rawQuery
        }
        return queryId(query)?.let { OfficialForumLinkTarget(OfficialForumLinkKind.Post, it) }
    }

    private fun route(parts: List<String>): OfficialForumLinkTarget? {
        for ((pattern, kind) in patterns) {
            if (parts.size <= pattern.size) continue
            for (start in 0 until parts.size - pattern.size) {
                if (pattern.indices.all { parts[start + it].equals(pattern[it], ignoreCase = true) }) {
                    val id = positiveId(parts[start + pattern.size]) ?: continue
                    return OfficialForumLinkTarget(kind, id)
                }
            }
        }
        // Historical /detail/<id> is accepted only as a complete root route, not any detail marker.
        if (parts.firstOrNull()?.equals("detail", ignoreCase = true) == true) {
            positiveId(parts.getOrNull(1))?.let { return OfficialForumLinkTarget(OfficialForumLinkKind.Post, it) }
        }
        return null
    }

    private fun legacyPostQuery(parts: List<String>): Boolean = parts.map(String::lowercase) in listOf(
        emptyList(), listOf("post"), listOf("posts"), listOf("detail"),
        listOf("post", "detail"), listOf("posts", "detail"),
        listOf("pc", "index.html"), listOf("mob", "index.html"),
    )

    private fun segments(raw: String?): List<String>? {
        val result = mutableListOf<String>()
        for (part in raw.orEmpty().split('/').filter(String::isNotBlank)) {
            // Decode each segment once; encoded separators do not create new route components.
            val decoded = decode(part.replace("+", "%2B")) ?: return null
            if (decoded.any { it == '/' || it == '\\' || it == '?' || it == '#' }) return null
            result += decoded
        }
        return result
    }

    private fun positiveId(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }

    private fun queryId(query: String?): Int? =
        query?.split('&')?.firstNotNullOfOrNull { item ->
            val fields = item.split('=', limit = 2)
            val name = decode(fields.first()) ?: return@firstNotNullOfOrNull null
            val value = decode(fields.getOrElse(1) { "" }) ?: return@firstNotNullOfOrNull null
            if (name == "id") positiveId(value) else null
        }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}

object OfficialForumResourceUrls {
    fun emojiImageUrl(number: Int): String =
        "https://static.web.sdo.com/jijiamobile/pic/ff14/2023ffstone/emo$number.png"
}
