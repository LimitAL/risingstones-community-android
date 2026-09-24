package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** Retains official inline emoji that ordinary HTML-to-text conversion would discard. */
@Composable
internal fun DynamicRichText(html: String, maxLines: Int = Int.MAX_VALUE) {
    val parsed = remember(html) { parseDynamicEmoji(html) }
    val labels = parsed.emojiNumbers.associateWith { stringResource(R.string.dynamic_emoji_number, it) }
    val text = remember(parsed, labels) {
        buildAnnotatedString {
            var cursor = 0
            parsed.markers.forEachIndexed { index, marker ->
                val position = parsed.text.text.indexOf(marker, cursor)
                if (position >= 0) {
                    append(parsed.text.subSequence(cursor, position))
                    val number = parsed.emojiNumbers[index]
                    appendInlineContent("emoji-$index", labels.getValue(number))
                    cursor = position + marker.length
                }
            }
            append(parsed.text.subSequence(cursor, parsed.text.length))
        }
    }
    val inline = parsed.emojiNumbers.mapIndexed { index, number ->
        "emoji-$index" to InlineTextContent(Placeholder(28.sp, 28.sp, PlaceholderVerticalAlign.TextCenter)) {
            val description = labels.getValue(number)
            val loader = LocalDynamicCommentEmojiLoader.current
            if (loader == null) {
                AsyncImage(dynamicEmojiUrl(number), description, Modifier.size(28.dp))
            } else {
                val bitmap by produceState<android.graphics.Bitmap?>(null, number, loader) { value = loader(number) }
                bitmap?.let { Image(it.asImageBitmap(), description, Modifier.size(28.dp)) }
            }
        }
    }.toMap()
    Text(text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline)
}

private data class DynamicEmojiText(val text: AnnotatedString, val markers: List<String>, val emojiNumbers: List<Int>)

private fun parseDynamicEmoji(html: String): DynamicEmojiText {
    var prefix = "\uE000dynamic-emoji-"
    val originalText = AnnotatedString.fromHtml(html).text
    while (originalText.contains(prefix)) prefix += "-"
    val markers = mutableListOf<String>()
    val numbers = mutableListOf<Int>()
    val replaced = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).replace(html) { tag ->
        val source = Regex("\\bsrc\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .find(tag.value)?.groupValues?.get(2)
        val number = (1..46).firstOrNull { source == dynamicEmojiUrl(it) }
        if (number == null) tag.value else {
            val marker = "$prefix${numbers.size}\uE001"
            markers += marker
            numbers += number
            marker
        }
    }
    return DynamicEmojiText(AnnotatedString.fromHtml(replaced), markers, numbers)
}
