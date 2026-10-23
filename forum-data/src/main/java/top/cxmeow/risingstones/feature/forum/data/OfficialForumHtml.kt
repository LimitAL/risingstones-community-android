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

    private val flowBreakRegex = Regex(
        """<br\b[^>]*>|</?(?:p|div|li|h[1-6]|blockquote|pre|ul|ol)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val escapedAnchorPattern = """&lt;a\b(?:(?!&gt;).)*&gt;.*?&lt;/a\s*&gt;"""
    private val literalAnchorRegex = Regex("""<a\b.*?</a\s*>|$escapedAnchorPattern""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val nestedAnchorRegex = Regex("""(?:<|&lt;)a\b""", RegexOption.IGNORE_CASE)
    private val bareUrlRegex = Regex("""(?<![A-Za-z0-9_:/="'@])https?://[A-Za-z0-9\-._~:/?#@!$&'*+,;=%()]+""", RegexOption.IGNORE_CASE)
    private val richFlowRegex = Regex(
        """$escapedAnchorPattern|<img\b[^>]*>|<a\b[^>]*href\s*=\s*["'][^"']+["'][^>]*>.*?</a\s*>|${flowBreakRegex.pattern}""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val htmlPresenceRegex = Regex("""<[A-Za-z/!][^>]*>""")
    private val sourceNewlineRegex = Regex("""\r\n?|\n""")
    private val tagNameRegex = Regex("""^</?([A-Za-z][A-Za-z0-9]*)""")

    fun plainText(html: String): String = normalizedContent(html, rich = false)
        .filterIsInstance<OfficialForumRichTextSegment.Text>().joinToString("") { it.value }

    fun richSegments(html: String): List<OfficialForumRichTextSegment> = buildList {
        normalizedContent(html, rich = true).forEach { segment ->
            if (segment is OfficialForumRichTextSegment.Text) appendLinkedText(segment.value, this)
            else add(segment)
        }
    }

    /** Passive token flow, not a DOM/execution engine. Breaks are normalized once across inline atoms. */
    private fun normalizedContent(html: String, rich: Boolean, entitiesDecoded: Boolean = false): List<OfficialForumRichTextSegment> {
        val result = mutableListOf<OfficialForumRichTextSegment>()
        val outputText = StringBuilder()
        val line = mutableListOf<OfficialForumRichTextSegment>()
        val lineText = StringBuilder()
        val hasHtml = htmlPresenceRegex.containsMatchIn(html)
        var preDepth = 0
        var pendingBreaks = 0
        var hasOutput = false

        fun emit(segment: OfficialForumRichTextSegment) {
            if (segment is OfficialForumRichTextSegment.Text) outputText.append(segment.value)
            else {
                if (outputText.isNotEmpty()) { result += OfficialForumRichTextSegment.Text(outputText.toString()); outputText.clear() }
                result += segment
            }
        }
        fun finishText() {
            if (lineText.isNotEmpty()) { line += OfficialForumRichTextSegment.Text(lineText.toString()); lineText.clear() }
        }
        fun flushLine(): Boolean {
            finishText()
            while (line.firstOrNull() is OfficialForumRichTextSegment.Text) {
                val value = (line.first() as OfficialForumRichTextSegment.Text).value.trimStart()
                if (value.isEmpty()) line.removeAt(0) else { line[0] = OfficialForumRichTextSegment.Text(value); break }
            }
            while (line.lastOrNull() is OfficialForumRichTextSegment.Text) {
                val value = (line.last() as OfficialForumRichTextSegment.Text).value.trimEnd()
                if (value.isEmpty()) line.removeAt(line.lastIndex) else { line[line.lastIndex] = OfficialForumRichTextSegment.Text(value); break }
            }
            if (line.isEmpty()) return false
            if (hasOutput && pendingBreaks > 0) outputText.append("\n".repeat(pendingBreaks))
            line.forEach(::emit)
            line.clear()
            hasOutput = true
            pendingBreaks = 0
            return true
        }
        fun appendDecoded(value: String) {
            for (character in sourceNewlineRegex.replace(value, "\n")) {
                if (character == '\n') {
                    val visibleLine = flushLine()
                    pendingBreaks = if (visibleLine) 1 else pendingBreaks + 1
                } else lineText.append(character)
            }
        }
        fun appendHtml(value: String) {
            val source = sourceNewlineRegex.replace(value, if (hasHtml && preDepth == 0) " " else "\n")
            val text = tagRegex.replace(source, "")
            appendDecoded(if (entitiesDecoded) text else decodeEntities(text))
        }
        fun boundary(minimum: Int) {
            flushLine()
            pendingBreaks = maxOf(pendingBreaks, minimum)
        }
        var cursor = 0
        val tokens = if (rich) richFlowRegex else flowBreakRegex
        for (match in tokens.findAll(html)) {
            appendHtml(html.substring(cursor, match.range.first))
            val escaped = match.value.startsWith("&lt;", true)
            val token = if (escaped) decodeEntities(match.value) else match.value
            val name = tagNameRegex.find(token)?.groupValues?.get(1)?.lowercase().orEmpty()
            when (name) {
                "br" -> { val visibleLine = flushLine(); pendingBreaks = if (visibleLine) 1 else pendingBreaks + 1 }
                "img" -> imageSegment(token)?.let { finishText(); line += it }
                "a" -> {
                    val anchor = anchorRegex.matchEntire(token)
                    val body = anchor?.groupValues?.get(2).orEmpty()
                    val text = normalizedContent(sourceNewlineRegex.replace(body, if (preDepth == 0) " " else "\n"), false, entitiesDecoded = escaped)
                        .filterIsInstance<OfficialForumRichTextSegment.Text>().joinToString("") { it.value }
                    val href = anchor?.groupValues?.get(1)?.let { if (escaped) it else decodeEntities(it) }
                    val url = href?.let { if (escaped) clickableUrl(it) else remoteUrl(it) }
                    if (!nestedAnchorRegex.containsMatchIn(body) && text.isNotEmpty() && url != null) { finishText(); line += OfficialForumRichTextSegment.Link(text, url) }
                    else if (escaped) appendDecoded(token) // Invalid escaped markup stays literal, never executed.
                    else appendDecoded(text)
                }
                "div", "li" -> boundary(1)
                else -> {
                    boundary(2)
                    if (name == "pre") preDepth = if (token.startsWith("</")) maxOf(0, preDepth - 1) else preDepth + 1
                }
            }
            cursor = match.range.last + 1
        }
        appendHtml(html.substring(cursor))
        flushLine()
        if (outputText.isNotEmpty()) result += OfficialForumRichTextSegment.Text(outputText.toString())
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
        // Keep opening-tag context and explicit BR tokens until the one-pass formatter.
        val normalized = closingParagraphRegex.replace(html, "\n\n")
        normalized.split(Regex("""\n\s*\n""")).forEach { fragment ->
            val segments = richSegments(fragment)
            if (segments.isEmpty()) return@forEach
            val textOnly = plainText(fragment).isNotBlank()
            val images = segments.filterIsInstance<OfficialForumRichTextSegment.Image>()
            if (images.isNotEmpty() && !textOnly && segments.size == images.size) {
                images.forEach { blocks += OfficialForumPostBodyBlock.Image(it.url, it.width, it.height) }
            } else {
                val isCallout = openingParagraphRegex.replace(fragment, "").contains("collapse_btn", true)
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

    /** Decode has already happened once. Never auto-link inside literal escaped markup. */
    private fun appendLinkedText(text: String, result: MutableList<OfficialForumRichTextSegment>) {
        fun appendBare(value: String) {
            var cursor = 0
            for (match in bareUrlRegex.findAll(value)) {
                var candidate = match.value
                while (candidate.isNotEmpty()) {
                    val last = candidate.last()
                    if (last in ".,;:!?\"'" || (last == ')' && candidate.count { it == ')' } > candidate.count { it == '(' })) candidate = candidate.dropLast(1)
                    else break
                }
                val url = clickableUrl(candidate) ?: continue
                appendEmojiText(value.substring(cursor, match.range.first), result)
                result += OfficialForumRichTextSegment.Link(candidate, url)
                cursor = match.range.first + candidate.length
            }
            appendEmojiText(value.substring(cursor), result)
        }
        var cursor = 0
        for (match in literalAnchorRegex.findAll(text)) {
            appendBare(text.substring(cursor, match.range.first))
            appendEmojiText(match.value, result)
            cursor = match.range.last + 1
        }
        appendBare(text.substring(cursor))
    }

    private fun clickableUrl(value: String): String? = remoteUrl(value)?.takeIf {
        !runCatching { URI(it).host }.getOrNull().isNullOrBlank()
    }

    private fun appendEmojiText(text: String, result: MutableList<OfficialForumRichTextSegment>) {
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
