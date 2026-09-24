package top.cxmeow.risingstones.feature.dynamic.presentation

import top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentMention

data class DynamicCommentMentionRange(val start: Int, val end: Int, val mention: DynamicCommentMention)
data class DynamicCommentTextChange(val start: Int, val end: Int)
data class DynamicCommentEditorState(
    val text: String = "", val selectionStart: Int = 0, val selectionEnd: Int = 0,
    val mentions: List<DynamicCommentMentionRange> = emptyList(),
    val emojiPickerOpen: Boolean = false, val mentionPickerOpen: Boolean = false,
)

internal fun DynamicCommentEditorState.edit(newText: String, start: Int, end: Int,
    change: DynamicCommentTextChange? = null): DynamicCommentEditorState {
    if (change == null && newText != text) return copy(text = newText,
        selectionStart = newText.safeOffset(start), selectionEnd = newText.safeOffset(end), mentions = emptyList())
    val delta = newText.length - text.length
    val replaced = change ?: DynamicCommentTextChange(0, 0)
    val valid = replaced.start in 0..text.length && replaced.end in replaced.start..text.length &&
        replaced.end - replaced.start + delta >= 0 && text.take(replaced.start) == newText.take(replaced.start) &&
        text.drop(replaced.end) == newText.drop(replaced.end + delta)
    val retained = if (!valid) emptyList() else mentions.mapNotNull { range ->
        val adjusted = when {
            replaced.start == replaced.end && delta == 0 -> range
            range.end <= replaced.start -> range
            range.start >= replaced.end -> range.copy(start = range.start + delta, end = range.end + delta)
            else -> null
        }
        adjusted?.takeIf { it.matches(newText) }
    }
    return copy(text = newText, selectionStart = newText.safeOffset(start),
        selectionEnd = newText.safeOffset(end), mentions = retained)
}

internal fun DynamicCommentEditorState.insert(value: String, mention: DynamicCommentMention? = null): DynamicCommentEditorState {
    val from = text.safeOffset(minOf(selectionStart, selectionEnd)); val to = text.safeOffset(maxOf(selectionStart, selectionEnd))
    val next = text.replaceRange(from, to, value); val cursor = from + value.length
    val updated = edit(next, cursor, cursor, DynamicCommentTextChange(from, to))
    val range = mention?.let { DynamicCommentMentionRange(from, from + 1 + it.characterName.length, it) }?.takeIf { it.matches(next) }
    return if (range == null) updated else updated.copy(mentions = (updated.mentions + range).sortedBy { it.start })
}

internal fun DynamicCommentMentionRange.matches(value: String) = start >= 0 && end in start..value.length &&
    value.substring(start, end) == "@${mention.characterName}"
internal data class PreparedDynamicComment(val html: String, val mentions: List<DynamicCommentMention>)

internal fun DynamicCommentEditorState.prepareComment(): PreparedDynamicComment = prepareContent(false)
internal fun DynamicCommentEditorState.preparePublishingContent(): PreparedDynamicComment = prepareContent(true)

private fun DynamicCommentEditorState.prepareContent(publishing: Boolean): PreparedDynamicComment {
    var priorEnd = 0
    val valid = mentions.sortedBy { it.start }.filter { range -> (range.start >= priorEnd && range.matches(text)).also { if (it) priorEnd = range.end } }
    val included = linkedSetOf<DynamicCommentMention>()
    val html = buildString {
        Regex("[^\\r\\n]+").findAll(text).forEach { line ->
            val first = line.value.indexOfFirst { !it.isWhitespace() }; if (first < 0) return@forEach
            val from = line.range.first + first
            val ordinaryEnd = line.range.first + line.value.indexOfLast { !it.isWhitespace() } + 1
            val to = valid.firstOrNull { it.start < ordinaryEnd && it.end > ordinaryEnd && it.end <= line.range.last + 1 }?.end ?: ordinaryEnd
            var cursor = from; append("<p>")
            valid.filter { it.start >= from && it.end <= to }.forEach { range ->
                append(text.substring(cursor, range.start).emojiHtml(publishing))
                append("<span class=\"at-text\" data-uuid=\"").append("${range.mention.uuid}#${range.mention.characterName}".escaped())
                append("\" contenteditable=\"false\">").append(text.substring(range.start, range.end).escaped()).append("</span>")
                included += range.mention; cursor = range.end
            }
            append(text.substring(cursor, to).emojiHtml(publishing)).append("</p>")
        }
    }
    return PreparedDynamicComment(html, included.toList())
}

private fun String.safeOffset(value: Int): Int { val offset = value.coerceIn(0, length); return if (offset in 1 until length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()) offset - 1 else offset }
private fun String.escaped() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun String.emojiHtml(publishing: Boolean): String = buildString {
    var cursor = 0
    Regex("\\[emo([1-9]|[1-3][0-9]|4[0-6])]").findAll(this@emojiHtml).forEach { match ->
        append(this@emojiHtml.substring(cursor, match.range.first).escaped())
        if (publishing) {
            append("<span class=\"at-emo\" contenteditable=\"false\">[emo")
            append(match.groupValues[1]).append("]</span>")
        } else {
            append("<img class=\"at-emo\" src=\"https://static.web.sdo.com/jijiamobile/pic/ff14/2023ffstone/emo")
            append(match.groupValues[1]).append(".png\">")
        }
        cursor = match.range.last + 1
    }
    append(this@emojiHtml.substring(cursor).escaped())
}
