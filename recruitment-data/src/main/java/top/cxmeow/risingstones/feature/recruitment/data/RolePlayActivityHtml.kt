package top.cxmeow.risingstones.feature.recruitment.data

import java.net.URI
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivityBodyBlock

/** Basic native rendering; original markup remains available on the detail model. */
internal object RolePlayActivityHtml {
    data class Parsed(val blocks: List<RolePlayActivityBodyBlock>, val hasUnsupportedContent: Boolean)

    private data class OpenTag(val sourceName: String, val renderedName: String, val opening: String, val linkUrl: String?)
    private val tokens = Regex("""<!--.*?-->|<(?:"[^"]*"|'[^']*'|[^'">])*>""", RegexOption.DOT_MATCHES_ALL)
    private val tagName = Regex("""^<\s*(/?)\s*([A-Za-z][A-Za-z0-9:-]*)\b""")
    private val attributes = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
    private val basicTags = setOf(
        "p", "div", "br", "b", "strong", "i", "em", "u", "s", "strike", "del", "ul", "ol", "li",
        "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "code", "span", "font", "a", "hr",
    )
    private val hiddenTags = setOf("script", "style", "iframe", "object", "svg", "math", "video", "audio")
    private val color = Regex("""#[0-9a-fA-F]{3,8}|[A-Za-z]{1,20}|rgba?\([0-9.,% ]+\)""")

    fun httpsUrl(value: String?): String? {
        val decoded = value?.let(::decodeEntities)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (decoded.any { it.isISOControl() || it.isWhitespace() } || '\\' in decoded) return null
        return runCatching { URI(decoded) }.getOrNull()?.takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() &&
                it.rawUserInfo == null && (it.port == -1 || it.port == 443)
        }?.toASCIIString()
    }

    fun parse(html: String): Parsed {
        val blocks = mutableListOf<RolePlayActivityBodyBlock>()
        val open = mutableListOf<OpenTag>()
        val hidden = mutableListOf<String>()
        val fragment = StringBuilder()
        var unsupported = false
        var start = 0
        fun flush() {
            val complete = fragment.toString() + open.asReversed().joinToString("") { "</${it.renderedName}>" }
            if (tokens.replace(complete, "").isNotBlank()) blocks += RolePlayActivityBodyBlock.Html(complete)
            fragment.clear()
            open.forEach { fragment.append(it.opening) }
        }
        for (token in tokens.findAll(html)) {
            if (hidden.isEmpty()) fragment.append(escapeLooseText(html.substring(start, token.range.first)))
            start = token.range.last + 1
            val match = tagName.find(token.value) ?: continue
            val closing = match.groupValues[1].isNotEmpty()
            val name = match.groupValues[2].lowercase()
            if (hidden.isNotEmpty()) {
                if (!closing && name in hiddenTags) hidden += name
                else if (closing && hidden.last() == name) hidden.removeAt(hidden.lastIndex)
                continue
            }
            if (name in hiddenTags) {
                unsupported = true
                if (!closing && !token.value.endsWith("/>")) hidden += name
                continue
            }
            if (name == "img" && !closing) {
                val attrs = attributeValues(token.value)
                val url = httpsUrl(attrs["src"])
                val alternative = attrs["alt"]?.let(::decodeEntities)?.takeIf(String::isNotBlank)
                if (url != null) {
                    flush()
                    blocks += RolePlayActivityBodyBlock.Image(url, alternative, open.lastOrNull { it.linkUrl != null }?.linkUrl)
                } else {
                    unsupported = true
                    alternative?.let { fragment.append(escape(it)) }
                }
                continue
            }
            if (name !in basicTags) {
                unsupported = true
                if (name in setOf("table", "tr", "td", "th", "details", "summary")) fragment.append("<br>")
                continue
            }
            if (closing) {
                val index = open.indexOfLast { it.sourceName == name }
                if (index >= 0) {
                    while (open.size > index) fragment.append("</${open.removeAt(open.lastIndex).renderedName}>")
                }
                continue
            }
            if (name == "br" || name == "hr") {
                fragment.append("<br>")
                continue
            }
            val attrs = attributeValues(token.value)
            val href = if (name == "a") httpsUrl(attrs["href"]) else null
            val rendered = when (name) {
                "a" -> if (href != null) "a" else "span"
                "s", "del" -> "strike"
                else -> name
            }
            val safeAttributes = buildString {
                href?.let { append(" href=\"").append(escape(it)).append('"') }
                if (name == "font") attrs["color"]?.takeIf(color::matches)?.let {
                    append(" color=\"").append(escape(it)).append('"')
                }
                safeStyle(attrs["style"]).takeIf(String::isNotEmpty)?.let {
                    append(" style=\"").append(escape(it)).append('"')
                }
            }
            if (name == "a" && attrs["href"] != null && href == null) unsupported = true
            val opening = "<$rendered$safeAttributes>"
            fragment.append(opening)
            if (!token.value.endsWith("/>")) open += OpenTag(name, rendered, opening, href)
            else fragment.append("</$rendered>")
        }
        if (hidden.isEmpty()) fragment.append(escapeLooseText(html.substring(start)))
        flush()
        return Parsed(blocks, unsupported)
    }

    private fun attributeValues(tag: String): Map<String, String> = attributes.findAll(tag)
        .map { match ->
            match.groupValues[1].lowercase() to match.groups.drop(2).firstNotNullOf { it?.value }
        }.toMap()

    private fun safeStyle(value: String?): String = value.orEmpty().split(';').mapNotNull { declaration ->
        val parts = declaration.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[0].trim().lowercase()
        val content = parts[1].trim()
        when {
            name in setOf("color", "background-color") && color.matches(content) -> "$name:$content"
            name == "font-size" && Regex("""(?:[1-9][0-9]?)(?:px|pt)""").matches(content) -> "$name:$content"
            name == "text-decoration" && content in setOf("underline", "line-through") -> "$name:$content"
            else -> null
        }
    }.joinToString(";")

    private val entities = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);""", RegexOption.IGNORE_CASE)
    private fun decodeEntities(value: String): String = entities.replace(value) { match ->
        val entity = match.groupValues[1]
        val numeric = when {
            entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)
            entity.startsWith('#') -> entity.drop(1).toIntOrNull()
            else -> null
        }
        if (numeric != null && Character.isValidCodePoint(numeric)) String(Character.toChars(numeric))
        else when (entity.lowercase()) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
            else -> match.value
        }
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeLooseText(value: String): String = value.replace("<", "&lt;").replace(">", "&gt;")
}
