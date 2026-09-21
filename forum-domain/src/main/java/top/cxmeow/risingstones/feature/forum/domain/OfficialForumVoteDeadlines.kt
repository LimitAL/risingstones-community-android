package top.cxmeow.risingstones.feature.forum.domain

import java.time.Instant
import kotlin.math.abs
import kotlin.math.truncate

/** Uses the official numeric timestamp rule; textual calendar dates are not accepted. */
fun OfficialForumPostVote.deadline(): Instant? {
    val text = endDateText?.trim { it.isWhitespace() || it == '\uFEFF' }
        ?.takeIf(String::isNotEmpty) ?: return null
    val number = text.timestampNumber() ?: return null
    if (!number.isFinite()) return null
    // The official rule includes the minus sign in the truncated number's length.
    val milliseconds = if (truncate(number).toLong().toString().length == 10) number * 1_000 else number
    if (!milliseconds.isFinite() || abs(milliseconds) > 8_640_000_000_000_000.0) return null
    return Instant.ofEpochMilli(milliseconds.toLong())
}

private fun String.timestampNumber(): Double? {
    val radix = when {
        startsWith("0x", ignoreCase = true) -> 16
        startsWith("0o", ignoreCase = true) -> 8
        startsWith("0b", ignoreCase = true) -> 2
        else -> null
    }
    if (radix != null) {
        val digits = substring(2)
        if (digits.isEmpty() || digits.any { it.digitToIntOrNull(radix) == null }) return null
        return digits.toBigIntegerOrNull(radix)?.toDouble()
    }
    if (!DecimalTimestamp.matches(this)) return null
    return toDoubleOrNull()
}

private val DecimalTimestamp = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
