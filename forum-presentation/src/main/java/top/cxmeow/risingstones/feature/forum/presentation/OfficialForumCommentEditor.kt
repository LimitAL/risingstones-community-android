package top.cxmeow.risingstones.feature.forum.presentation

import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentMention
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumMentionCandidate

/** UTF-16 offsets of a selected, identity-bearing mention in the plain editor backing text. */
data class OfficialForumCommentMentionRange(
    val start: Int,
    val end: Int,
    val mention: OfficialForumCommentMention,
)

/** Half-open range replaced in the previous text, including replacements with identical text. */
data class OfficialForumCommentTextChange(val start: Int, val end: Int)

data class OfficialForumCommentEditorState(
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val mentions: List<OfficialForumCommentMentionRange> = emptyList(),
    val isEmojiPickerOpen: Boolean = false,
    val isMentionPickerOpen: Boolean = false,
    val candidates: List<OfficialForumMentionCandidate> = emptyList(),
    val candidateQuery: String = "",
    val candidateStatus: OfficialForumLoadStatus = OfficialForumLoadStatus.Idle,
    val candidateError: OfficialForumInteractionError? = null,
)

internal fun OfficialForumCommentEditorState.edit(
    newText: String,
    selectionStart: Int,
    selectionEnd: Int,
    change: OfficialForumCommentTextChange? = null,
): OfficialForumCommentEditorState {
    // Identical display names can carry different identities. Text alone cannot identify which
    // occurrence was edited; preserve identities only for selection changes or explicit edit ranges.
    if (change == null && newText != text) return copy(text = newText,
        selectionStart = newText.safeOffset(selectionStart), selectionEnd = newText.safeOffset(selectionEnd),
        mentions = emptyList())
    val delta = newText.length - text.length
    val changed = change ?: OfficialForumCommentTextChange(0, 0)
    val validChange = changed.start in 0..text.length && changed.end in changed.start..text.length &&
        changed.end - changed.start + delta >= 0 &&
        text.take(changed.start) == newText.take(changed.start) &&
        text.drop(changed.end) == newText.drop(changed.end + delta)
    val retained = if (!validChange) emptyList() else mentions.mapNotNull { range ->
        val adjusted = when {
            changed.start == changed.end && delta == 0 -> range
            range.end <= changed.start -> range
            range.start >= changed.end -> range.copy(start = range.start + delta, end = range.end + delta)
            else -> null // Editing any part of a mention removes its notification identity.
        }
        adjusted?.takeIf { it.matches(newText) }
    }
    return copy(text = newText, selectionStart = newText.safeOffset(selectionStart),
        selectionEnd = newText.safeOffset(selectionEnd), mentions = retained)
}

internal fun OfficialForumCommentEditorState.insert(
    replacement: String,
    mention: OfficialForumCommentMention? = null,
): OfficialForumCommentEditorState {
    val start = text.safeOffset(minOf(selectionStart, selectionEnd))
    val end = text.safeOffset(maxOf(selectionStart, selectionEnd))
    val next = text.replaceRange(start, end, replacement)
    val cursor = start + replacement.length
    val updated = edit(next, cursor, cursor, OfficialForumCommentTextChange(start, end))
    val added = mention?.let { OfficialForumCommentMentionRange(start, start + 1 + it.name.length, it) }
        ?.takeIf { it.matches(next) }
    return if (added == null) updated else updated.copy(mentions = (updated.mentions + added).sortedBy { it.start })
}

internal fun OfficialForumCommentMentionRange.matches(text: String): Boolean =
    mention.isEncodable() && start >= 0 && end in start..text.length &&
        text.substring(start, end) == "@${mention.name}"

internal fun OfficialForumCommentMention.isEncodable(): Boolean =
    listOf(uuid, name).all { it.isNotBlank() && it.none { char ->
        char == '#' || char.isISOControl() || char == '\u2028' || char == '\u2029'
    } }

internal data class OfficialForumPreparedComment(
    val html: String,
    val mentions: List<OfficialForumCommentMention>,
)

/** Derive notification metadata only from unchanged selected ranges that are actually rendered. */
internal fun OfficialForumCommentEditorState.prepareComment(): OfficialForumPreparedComment {
    var previousEnd = 0
    val validRanges = mentions.sortedBy { it.start }.filter { range ->
        (range.start >= previousEnd && range.matches(text)).also { valid ->
            if (valid) previousEnd = range.end
        }
    }
    val included = linkedSetOf<OfficialForumCommentMention>()
    val html = buildString {
        Regex("[^\\r\\n]+").findAll(text).forEach { line ->
            val first = line.value.indexOfFirst { !it.isWhitespace() }
            if (first < 0) return@forEach
            val start = line.range.first + first
            val trimmedEnd = line.range.first + line.value.indexOfLast { !it.isWhitespace() } + 1
            // A selected name can contain trailing spaces. Keep the full visible identity even
            // though ordinary paragraph whitespace follows the legacy trimming behavior.
            val end = validRanges.firstOrNull { it.start < trimmedEnd && it.end > trimmedEnd &&
                it.end <= line.range.last + 1 }?.end ?: trimmedEnd
            var cursor = start
            append("<p>")
            validRanges.filter { it.start >= start && it.end <= end }.forEach { range ->
                append(text.substring(cursor, range.start).officialForumEmojiHtml())
                append("<span class=\"at-text\" data-uuid=\"")
                append("${range.mention.uuid}#${range.mention.name}".officialForumHtmlEscaped())
                append("\" contenteditable=\"false\">")
                append(text.substring(range.start, range.end).officialForumHtmlEscaped())
                append("</span>")
                included += range.mention
                cursor = range.end
            }
            append(text.substring(cursor, end).officialForumEmojiHtml())
            append("</p>")
        }
    }
    return OfficialForumPreparedComment(html, included.toList())
}

private fun String.safeOffset(value: Int): Int {
    val offset = value.coerceIn(0, length)
    return if (offset in 1 until length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())
        offset - 1 else offset
}
