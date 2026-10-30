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
    change: DynamicCommentTextChange? = null, atomicDeletion: Boolean = true): DynamicCommentEditorState {
    if (change == null && newText != text) return copy(text = newText,
        selectionStart = newText.safeOffset(start), selectionEnd = newText.safeOffset(end), mentions = emptyList())
    val delta = newText.length - text.length
    var replaced = change ?: DynamicCommentTextChange(0, 0)
    val valid = replaced.start in 0..text.length && replaced.end in replaced.start..text.length &&
        replaced.end - replaced.start + delta >= 0 && text.take(replaced.start) == newText.take(replaced.start) &&
        text.drop(replaced.end) == newText.drop(replaced.end + delta)
    if (valid && atomicDeletion && replaced.end > replaced.start && replaced.end + delta == replaced.start) {
        // Only verified selected spans may expand a user's deletion; stale metadata is never authority.
        var priorEnd = 0
        val selected = mentions.sortedBy { it.start }.filter { range ->
            (range.start >= priorEnd && range.matches(text)).also { if (it) priorEnd = range.end }
        }
        val length = replaced.end - replaced.start
        fun touches(at: Int) = selected.any { it.start < at + length && at < it.end }
        if (touches(replaced.start)) {
            // A repeated character can make the same deletion appear inside a token. Prefer the
            // equivalent deletion outside it (e.g. @@Name -> @Name), as the reference editor does.
            var candidate = replaced.start
            while (candidate > 0 && text[candidate - 1] == text[candidate - 1 + length]) {
                candidate--
                if (!touches(candidate)) {
                    replaced = DynamicCommentTextChange(candidate, candidate + length)
                    break
                }
            }
        }
        val leftovers = buildList<Pair<Int, Int>> {
            for (range in selected) if (range.start < replaced.end && replaced.start < range.end) {
                if (range.start < replaced.start) add(range.start to replaced.start)
                if (range.end > replaced.end) add(replaced.start to range.end + delta)
            }
        }
        if (leftovers.isNotEmpty()) {
            val base = copy(mentions = selected).edit(newText, start, end, replaced, atomicDeletion = false)
            var result = newText
            for ((from, to) in leftovers.sortedByDescending { it.first }) result = result.removeRange(from, to)
            fun removedBefore(offset: Int) = leftovers.filter { it.second <= offset }.sumOf { it.second - it.first }
            val cursor = result.safeOffset(replaced.start - removedBefore(replaced.start))
            val remaining = base.mentions.map { it.copy(start = it.start - removedBefore(it.start), end = it.end - removedBefore(it.end)) }
                .filter { it.matches(result) }
            return base.copy(text = result, selectionStart = cursor, selectionEnd = cursor, mentions = remaining)
        }
    }
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
    val updated = edit(next, cursor, cursor, DynamicCommentTextChange(from, to), atomicDeletion = false)
    val range = mention?.let { DynamicCommentMentionRange(from, from + 1 + it.characterName.length, it) }?.takeIf { it.matches(next) }
    return if (range == null) updated else updated.copy(mentions = (updated.mentions + range).sortedBy { it.start })
}

/**
 * 只读预览表情插入后的编辑状态；编号不在1…46时返回null，不修改原值、不发请求。
 * 折叠光标在有效已选提及内部时，移到整段末尾及一个后随ASCII空格之后；非折叠选区仍按原规则替换。
 * 调用方仍负责当前身份、配额和生命周期；本方法不提供认证或提交能力。
 */
fun DynamicCommentEditorState.previewEmojiInsertion(number: Int): DynamicCommentEditorState? {
    if (number !in 1..46) return null
    val from = text.safeOffset(minOf(selectionStart, selectionEnd))
    val to = text.safeOffset(maxOf(selectionStart, selectionEnd))
    if (from != to) return insert("[emo$number]")
    var priorEnd = 0
    val selected = mentions.sortedBy { it.start }.filter { range ->
        (range.start >= priorEnd && range.matches(text)).also { if (it) priorEnd = range.end }
    }.firstOrNull { it.start < from && from < it.end }
    val point = selected?.end?.let { if (text.getOrNull(it) == ' ') it + 1 else it } ?: from
    return copy(selectionStart = point, selectionEnd = point).insert("[emo$number]")
}

/** Remove only validated selected spans of this UUID, plus one following ASCII space, as iOS does. */
internal fun DynamicCommentEditorState.removeMention(uuid: String): DynamicCommentEditorState {
    if (uuid.isBlank()) return this
    var priorEnd = 0
    val valid = mentions.sortedBy { it.start }.filter { range ->
        (range.start >= priorEnd && range.matches(text)).also { if (it) priorEnd = range.end }
    }
    val removed = valid.filter { it.mention.uuid == uuid }
    if (removed.isEmpty()) return copy(mentions = valid)
    var result = copy(mentions = valid)
    for (range in removed.asReversed()) {
        val end = range.end + if (text.getOrNull(range.end) == ' ') 1 else 0
        result = result.edit(result.text.removeRange(range.start, end), range.start, range.start,
            DynamicCommentTextChange(range.start, end), atomicDeletion = false)
    }
    return result.copy(selectionStart = removed.first().start, selectionEnd = removed.first().start)
}

internal fun DynamicCommentMentionRange.matches(value: String) = start >= 0 && end in start..value.length &&
    value.substring(start, end) == "@${mention.characterName}"
internal data class PreparedDynamicComment(val html: String, val mentions: List<DynamicCommentMention>)

internal fun DynamicCommentEditorState.prepareComment(): PreparedDynamicComment = prepareContent(false)
internal fun DynamicCommentEditorState.preparePublishingContent(): PreparedDynamicComment = prepareContent(true)

private fun DynamicCommentEditorState.prepareContent(publishing: Boolean): PreparedDynamicComment {
    var priorEnd = 0
    val valid = mentions.sortedBy { it.start }.filter { range -> (range.start >= priorEnd && range.matches(text)).also { if (it) priorEnd = range.end } }
    // Notification identity is the UUID; visible spans keep their own selected names.
    val included = linkedMapOf<String, DynamicCommentMention>()
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
                included.putIfAbsent(range.mention.uuid, range.mention); cursor = range.end
            }
            append(text.substring(cursor, to).emojiHtml(publishing)).append("</p>")
        }
    }
    return PreparedDynamicComment(html, included.values.toList())
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
