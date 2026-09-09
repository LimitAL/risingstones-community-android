package top.cxmeow.risingstones.feature.forum.data

import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock
import java.net.URI

internal object OfficialForumHtml {
    private val imageRegex = Regex("""<img[^>]+src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val dimensionRegex = Regex("""\b%s\s*=\s*[\"']?([0-9]+)""", RegexOption.IGNORE_CASE)
    private val styleDimensionRegex = Regex("""(?:^|;)\s*%s\s*:\s*([0-9.]+)\s*px""", RegexOption.IGNORE_CASE)
    private val anchorRegex = Regex(
        """<a\b[^>]*href\s*=\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val emojiRegex = Regex("""\[emo([1-9]|[1-3][0-9]|4[0-6])]""")
    private val breakRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val blockRegex = Regex(
        """</?(?:p|div|li|h[1-6]|blockquote|pre)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val tagRegex = Regex("""<[^>]+>""")
    private val openingParagraphRegex = Regex("""<(?:p|div|li|h[1-6]|blockquote|pre)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val closingParagraphRegex = Regex("""</(?:p|div|li|h[1-6]|blockquote|pre)\s*>""", RegexOption.IGNORE_CASE)
    private val tableRegex = Regex("""<table\b[^>]*>.*?</table\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val rowRegex = Regex("""<tr\b[^>]*>(.*?)</tr\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val cellRegex = Regex("""<t[hd]\b[^>]*>(.*?)</t[hd]\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val detailsRegex = Regex("""<details\b([^>]*)>(.*?)</details\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val summaryRegex = Regex("""<summary\b[^>]*>(.*?)</summary\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val iframeRegex = Regex("""<iframe\b[^>]*>.*?</iframe\s*>|<iframe\b[^>]*/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val sourceRegex = Regex("""\bsrc\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val entityRegex = Regex("""&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z][A-Za-z0-9]+);""")
    private val entities = mapOf(
        "amp" to "&", "apos" to "'", "bull" to "•", "copy" to "©", "deg" to "°",
        "gt" to ">", "hellip" to "…", "laquo" to "«", "ldquo" to "“", "lsquo" to "‘",
        "lt" to "<", "mdash" to "—", "middot" to "·", "nbsp" to " ", "ndash" to "–",
        "quot" to "\"", "raquo" to "»", "rdquo" to "”", "reg" to "®", "rsquo" to "’",
        "trade" to "™", "yen" to "¥",
    )

    fun imageUrls(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val htmlUrls = imageRegex.findAll(value).mapNotNull { remoteUrl(it.groupValues[1]) }.toList()
        return htmlUrls.ifEmpty {
            value.split(',').mapNotNull(::remoteUrl)
        }.distinct()
    }

    fun plainText(html: String): String = decodeEntities(
        tagRegex.replace(blockRegex.replace(breakRegex.replace(html, "\n"), "\n"), ""),
    ).lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n\n")

    fun richSegments(html: String): List<OfficialForumRichTextSegment> {
        val withBreaks = blockRegex.replace(breakRegex.replace(html, "\n"), "\n")
        val result = mutableListOf<OfficialForumRichTextSegment>()
        val tokens = Regex(
            """<img\b[^>]*>|<a\b[^>]*href\s*=\s*[\"'][^\"']+[\"'][^>]*>.*?</a\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        var start = 0
        tokens.findAll(withBreaks).forEach { match ->
            appendPlain(withBreaks.substring(start, match.range.first), result)
            if (match.value.startsWith("<img", ignoreCase = true)) {
                imageSegment(match.value)?.let(result::add)
            } else {
                val anchor = anchorRegex.matchEntire(match.value)
                val text = anchor?.groupValues?.getOrNull(2)?.let(::plainText).orEmpty()
                val url = anchor?.groupValues?.getOrNull(1)?.let(::decodeEntities)?.let(::remoteUrl)
                if (text.isNotEmpty() && url != null) result += OfficialForumRichTextSegment.Link(text, url)
                else appendPlain(match.value, result)
            }
            start = match.range.last + 1
        }
        appendPlain(withBreaks.substring(start), result)
        return result
    }

    fun postBlocks(html: String, fallbackSegments: List<OfficialForumRichTextSegment>): List<OfficialForumPostBodyBlock> {
        val blocks = mutableListOf<OfficialForumPostBodyBlock>()
        appendBlocks(html, blocks)
        return blocks.ifEmpty {
            fallbackSegments.takeIf { it.isNotEmpty() }
                ?.let { listOf(OfficialForumPostBodyBlock.Paragraph(it)) }.orEmpty()
        }
    }

    private fun appendBlocks(html: String, blocks: MutableList<OfficialForumPostBodyBlock>) {
        var remaining = html
        while (remaining.isNotBlank()) {
            val candidates = listOfNotNull(
                detailsRegex.find(remaining), tableRegex.find(remaining), iframeRegex.find(remaining),
                Regex("""<hr\b[^>]*>""", RegexOption.IGNORE_CASE).find(remaining),
            )
            val next = candidates.minByOrNull { it.range.first }
            if (next == null) { appendParagraphs(remaining, blocks); return }
            appendParagraphs(remaining.substring(0, next.range.first), blocks)
            val token = next.value
            when {
                token.startsWith("<details", true) -> appendDisclosure(token, blocks)
                token.startsWith("<table", true) -> appendTable(token, blocks)
                token.startsWith("<iframe", true) -> sourceRegex.find(token)?.groupValues?.getOrNull(1)?.let(::remoteUrl)?.let { source ->
                    val isBilibili = URI(source).host.equals("player.bilibili.com", true)
                    blocks += OfficialForumPostBodyBlock.VideoEmbed(
                        if (isBilibili) bilibiliDestination(source) ?: source else source,
                        isBilibili,
                    )
                }
                else -> blocks += OfficialForumPostBodyBlock.Divider
            }
            remaining = remaining.substring(next.range.last + 1)
        }
    }

    private fun appendDisclosure(token: String, blocks: MutableList<OfficialForumPostBodyBlock>) {
        val match = detailsRegex.matchEntire(token) ?: return
        val attributes = match.groupValues[1]
        val inner = match.groupValues[2]
        val summary = summaryRegex.find(inner)
        val title = summary?.groupValues?.getOrNull(1)?.let(::richSegments).orEmpty()
        val body = if (summary == null) inner else inner.removeRange(summary.range)
        val nested = mutableListOf<OfficialForumPostBodyBlock>(); appendBlocks(body, nested)
        blocks += OfficialForumPostBodyBlock.Disclosure(title, nested, Regex("""\bopen\b""").containsMatchIn(attributes))
    }

    private fun appendTable(token: String, blocks: MutableList<OfficialForumPostBodyBlock>) {
        val rows = rowRegex.findAll(token).map { row ->
            cellRegex.findAll(row.groupValues[1]).map { richSegments(it.groupValues[1]) }.toList()
        }.filter { it.isNotEmpty() }.toList()
        if (rows.isNotEmpty()) blocks += OfficialForumPostBodyBlock.Table(rows)
    }

    private fun appendParagraphs(html: String, blocks: MutableList<OfficialForumPostBodyBlock>) {
        val normalized = closingParagraphRegex.replace(
            openingParagraphRegex.replace(breakRegex.replace(html, "\n"), ""),
            "\n\n",
        )
        normalized.split(Regex("""\n\s*\n""")).forEach { fragment ->
            val segments = richSegments(fragment)
            if (segments.isEmpty()) return@forEach
            val textOnly = plainText(fragment).isNotBlank()
            val images = segments.filterIsInstance<OfficialForumRichTextSegment.Image>()
            if (images.isNotEmpty() && !textOnly && segments.size == images.size) {
                images.forEach { blocks += OfficialForumPostBodyBlock.Image(it.url, it.width, it.height) }
            } else {
                val isCallout = fragment.contains("collapse_btn", true)
                blocks += if (isCallout) OfficialForumPostBodyBlock.Callout(segments)
                else OfficialForumPostBodyBlock.Paragraph(segments)
            }
        }
    }

    private fun bilibiliDestination(playerUrl: String): String? {
        val query = URI(playerUrl).query.orEmpty().split('&').associate {
            val pair = it.split('=', limit = 2)
            pair.first().lowercase() to pair.getOrElse(1) { "" }
        }
        val bvid = query["bvid"]?.takeIf { Regex("""BV[0-9A-Za-z]+""").matches(it) }
        val aid = query["aid"]?.takeIf { it.all(Char::isDigit) }
        return when {
            bvid != null -> "https://www.bilibili.com/video/$bvid"
            aid != null -> "https://www.bilibili.com/video/av$aid"
            else -> null
        }
    }

    private fun imageSegment(tag: String): OfficialForumRichTextSegment.Image? {
        val url = imageRegex.find(tag)?.groupValues?.getOrNull(1)?.let(::remoteUrl) ?: return null
        return OfficialForumRichTextSegment.Image(url, imageDimension(tag, "width"), imageDimension(tag, "height"))
    }

    private fun imageDimension(tag: String, name: String): Int? {
        dimensionRegex.pattern.format(name).toRegex(RegexOption.IGNORE_CASE).find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) return it }
        val style = Regex("""\bstyle\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.getOrNull(1) ?: return null
        return styleDimensionRegex.pattern.format(name).toRegex(RegexOption.IGNORE_CASE).find(style)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 }?.toInt()
    }

    private fun appendPlain(html: String, result: MutableList<OfficialForumRichTextSegment>) {
        val text = plainText(html)
        var start = 0
        emojiRegex.findAll(text).forEach { match ->
            text.substring(start, match.range.first).takeIf(String::isNotEmpty)?.let {
                result += OfficialForumRichTextSegment.Text(it)
            }
            result += OfficialForumRichTextSegment.Emoji(match.groupValues[1].toInt())
            start = match.range.last + 1
        }
        text.substring(start).takeIf(String::isNotEmpty)?.let {
            result += OfficialForumRichTextSegment.Text(it)
        }
    }

    private fun decodeEntities(value: String): String = entityRegex.replace(value) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.codePoint()
            entity.startsWith('#') -> entity.drop(1).toIntOrNull()?.codePoint()
            else -> entities[entity] ?: entities[entity.lowercase()]
        } ?: match.value
    }

    private fun Int.codePoint(): String? = takeIf(Character::isValidCodePoint)
        ?.let { String(Character.toChars(it)) }

    private fun remoteUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null
        return runCatching { URI(trimmed) }.getOrNull()?.takeIf {
            it.scheme.equals("http", true) || it.scheme.equals("https", true)
        }?.toString()
    }
}

/**
 * Public entry point for clients that need to render official-forum rich text
 * outside the forum feature itself (for example recruitment reviews).
 */
object OfficialForumHtmlParser {
    fun richSegments(html: String): List<OfficialForumRichTextSegment> =
        OfficialForumHtml.richSegments(html)
}
