package top.cxmeow.risingstones.feature.dynamic.ui.compose

import coil3.toBitmap
import coil3.request.allowHardware
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.text.style.TtsSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicCommentEditorState
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicCommentTextChange

internal val LocalDynamicCommentEmojiLoader = staticCompositionLocalOf<(suspend (Int) -> Bitmap?)?> { null }

internal fun dynamicEmojiUrl(number: Int): String {
    require(number in 1..46)
    return "https://static.web.sdo.com/jijiamobile/pic/ff14/2023ffstone/emo$number.png"
}

internal val DynamicCommentEmojiPattern = Regex("\\[emo([1-9]|[1-3][0-9]|4[0-6])]")

private fun DynamicCommentEditorState.emojiMatches(): List<MatchResult> {
    val protectedNames = mentions.filter { it.start >= 0 && it.end <= text.length && it.start < it.end &&
        text.substring(it.start, it.end) == "@${it.mention.characterName}" }
    return DynamicCommentEmojiPattern.findAll(text).filter { match -> protectedNames.none {
        it.start < match.range.last + 1 && it.end > match.range.first
    } }.toList()
}

/** One native Editable backs typing, IME, accessibility actions, spans and the controlled state. */
@Composable
internal fun DynamicNativeEditor(
    state: DynamicCommentEditorState,
    enabled: Boolean,
    placeholder: String? = null,
    onChange: (String, Int, Int, DynamicCommentTextChange?) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val imageLoader = remember(context) { coil3.ImageLoader.Builder(context).build() }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }
    val overrideLoader = LocalDynamicCommentEmojiLoader.current
    val loader: suspend (Int) -> Bitmap? = remember(imageLoader, overrideLoader) {
        overrideLoader ?: { number ->
            val request = coil3.request.ImageRequest.Builder(context)
                .data(dynamicEmojiUrl(number)).size(96).allowHardware(false).build()
            (imageLoader.execute(request) as? coil3.request.SuccessResult)?.image?.toBitmap()
        }
    }
    val numbers = remember(state.text, state.mentions) { state.emojiMatches().map { it.groupValues[1].toInt() }.toSet() }
    val images by produceState<Map<Int, Bitmap>>(emptyMap(), numbers, loader) {
        value = coroutineScope { numbers.map { number -> async { number to loader(number) } }.awaitAll()
            .mapNotNull { (number, image) -> image?.let { number to it } }.toMap() }
    }
    var field by remember { mutableStateOf<DynamicNativeCommentEditText?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    val currentChange by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val mentionColor = MaterialTheme.colorScheme.primary.toArgb()
    val mentionBackground = MaterialTheme.colorScheme.secondaryContainer.toArgb()
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val hint = placeholder ?: stringResource(R.string.dynamic_comment_hint)
    val emojiLabels = (1..46).associateWith { stringResource(R.string.dynamic_emoji_number, it) }
    Surface(shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 240.dp)
                .testTag("dynamic-comment-input").semantics {
                    editableText = AnnotatedString(state.text)
                    textSelectionRange = TextRange(state.selectionStart, state.selectionEnd)
                    focused = hasFocus
                    if (!enabled) disabled()
                    requestFocus { enabled && field?.requestFocus() == true }
                    setText { value -> field?.replaceText(value.text, replaceAll = true) == true }
                    insertTextAtCursor { value -> field?.replaceText(value.text, replaceAll = false) == true }
                    setSelection { start, end, _ -> field?.selectText(start, end) == true }
                },
            factory = { context -> DynamicNativeCommentEditText(context).also { editor ->
                field = editor
                editor.onEditorChange = { text, start, end, change -> currentChange(text, start, end, change) }
                editor.setOnFocusChangeListener { _, focused -> hasFocus = focused }
            } },
            update = { editor ->
                editor.isEnabled = enabled
                editor.hint = hint
                editor.setHintTextColor(hintColor)
                editor.setTextColor(textColor)
                editor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, with(density) { 16.sp.toPx() })
                val padding = with(density) { 12.dp.roundToPx() }
                editor.setPadding(padding, padding, padding, padding)
                editor.render(state, images, with(density) { 30.dp.roundToPx() }, emojiLabels,
                    mentionColor, mentionBackground, placeholderColor, textColor)
            },
            onRelease = { it.releaseEditor(); field = null },
        )
    }
}

internal class DynamicNativeCommentEditText(context: Context) : EditText(context) {
    var onEditorChange: ((String, Int, Int, DynamicCommentTextChange?) -> Unit)? = null
    private var applying = false
    private var textChanging = false
    private var pendingChange: DynamicCommentTextChange? = null
    private val decorations = mutableListOf<Any>()
    private var hasRendered = false
    private var needsInitialFocus = true
    private val restoreInitialFocus = Runnable {
        if (needsInitialFocus && hasRendered && isAttachedToWindow && isLaidOut && hasWindowFocus() && isEnabled) {
            val start = selectionStart
            val end = selectionEnd
            val previousApplying = applying
            val previousShowKeyboard = showSoftInputOnFocus
            applying = true
            showSoftInputOnFocus = false
            try {
                if (requestFocus()) {
                    needsInitialFocus = false
                    if (start >= 0 && end >= 0) setSelection(start, end)
                }
            } finally {
                showSoftInputOnFocus = previousShowKeyboard
                applying = previousApplying
            }
        }
    }

    init {
        tag = "dynamic-comment-native-input"
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isFocusableInTouchMode = true
        minLines = 3
        maxLines = 8
        isSaveEnabled = false // The retained presentation model owns the draft and selection.
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!applying) { textChanging = true; pendingChange = DynamicCommentTextChange(start, start + count) }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!applying) {
                    textChanging = false
                    emitChange(pendingChange)
                    pendingChange = null
                }
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleInitialFocus()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        scheduleInitialFocus()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) scheduleInitialFocus()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(restoreInitialFocus)
        super.onDetachedFromWindow()
    }

    private fun scheduleInitialFocus() {
        if (needsInitialFocus && isAttachedToWindow) {
            removeCallbacks(restoreInitialFocus)
            post(restoreInitialFocus)
        }
    }

    fun releaseEditor() {
        needsInitialFocus = false
        removeCallbacks(restoreInitialFocus)
        onEditorChange = null
        clearFocus()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applying && !textChanging && selStart >= 0 && selEnd >= 0) {
            val editable = text ?: return
            var start = minOf(selStart, selEnd)
            var end = maxOf(selStart, selEnd)
            editable.getSpans(0, editable.length, ImageSpan::class.java).forEach { span ->
                val from = editable.getSpanStart(span)
                val to = editable.getSpanEnd(span)
                if (start == end && start in (from + 1) until to) {
                    start = if (start - from < to - start) from else to
                    end = start
                } else {
                    if (start in (from + 1) until to) start = from
                    if (end in (from + 1) until to) end = to
                }
            }
            if (start != minOf(selStart, selEnd) || end != maxOf(selStart, selEnd)) {
                setSelection(if (selStart <= selEnd) start else end, if (selStart <= selEnd) end else start)
            } else emitChange(null)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                deleteAroundSelection(beforeLength, afterLength, false) || super.deleteSurroundingText(beforeLength, afterLength)
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                deleteAroundSelection(beforeLength, afterLength, true) || super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            if (selectionStart != selectionEnd) {
                if (deleteSelectedEmoji()) return true
            } else if (deleteAroundSelection(if (keyCode == KeyEvent.KEYCODE_DEL) 1 else 0,
                    if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) 1 else 0, true, protectComposing = false)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Image tokens are one visible object, including for IMEs that delete one UTF-16 unit. */
    private fun deleteAroundSelection(before: Int, after: Int, codePoints: Boolean, protectComposing: Boolean = true): Boolean {
        if (!isEnabled || before < 0 || after < 0 || selectionStart < 0 || selectionEnd < 0) return false
        val editable = text ?: return false
        val originalStart = selectionStart
        val originalEnd = selectionEnd
        var first = minOf(originalStart, originalEnd)
        var last = maxOf(originalStart, originalEnd)
        val composingStart = BaseInputConnection.getComposingSpanStart(editable)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)
        if (protectComposing && composingStart >= 0 && composingEnd >= 0) {
            first = minOf(first, composingStart, composingEnd)
            last = maxOf(last, composingStart, composingEnd)
        }
        val start = if (codePoints) Character.offsetByCodePoints(editable, first,
            -minOf(before, Character.codePointCount(editable, 0, first))) else (first - before).coerceAtLeast(0)
        val end = if (codePoints) Character.offsetByCodePoints(editable, last,
            minOf(after, Character.codePointCount(editable, last, editable.length))) else (last.toLong() + after).coerceAtMost(editable.length.toLong()).toInt()
        val beforeRange = expandedEmojiRange(editable, start, first)
        val afterRange = expandedEmojiRange(editable, last, end)
        if (beforeRange == null && afterRange == null) return false
        val from = beforeRange?.first ?: start
        val to = afterRange?.second ?: end
        // InputConnection deletes around a selection; the selected text itself must survive.
        beginBatchEdit()
        try {
            editable.delete(last, to)
            editable.delete(from, first)
            val shift = first - from
            setSelection(originalStart - shift, originalEnd - shift)
        } finally { endBatchEdit() }
        return true
    }

    private fun deleteSelectedEmoji(): Boolean {
        if (!isEnabled || selectionStart < 0 || selectionEnd < 0) return false
        val editable = text ?: return false
        val range = expandedEmojiRange(editable, minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)) ?: return false
        editable.delete(range.first, range.second)
        setSelection(range.first)
        return true
    }

    private fun expandedEmojiRange(editable: Editable, start: Int, end: Int): Pair<Int, Int>? {
        if (start == end) return null
        val spans = editable.getSpans(start, end, ImageSpan::class.java).filter {
            editable.getSpanStart(it) < end && editable.getSpanEnd(it) > start
        }
        if (spans.isEmpty()) return null
        return minOf(start, spans.minOf(editable::getSpanStart)) to maxOf(end, spans.maxOf(editable::getSpanEnd))
    }

    private fun emitChange(change: DynamicCommentTextChange?) {
        onEditorChange?.invoke(text?.toString().orEmpty(), selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0), change)
    }

    fun replaceText(value: String, replaceAll: Boolean): Boolean {
        if (!isEnabled) return false
        val editable = text ?: return false
        val start = if (replaceAll) 0 else minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = if (replaceAll) editable.length else maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
        editable.replace(start, end, value)
        setSelection(start + value.length)
        return true
    }

    fun selectText(start: Int, end: Int): Boolean {
        if (!isEnabled || start !in 0..length() || end !in 0..length()) return false
        setSelection(start, end)
        return true
    }

    fun render(state: DynamicCommentEditorState, images: Map<Int, Bitmap>, emojiSize: Int,
        labels: Map<Int, String>, mentionColor: Int, mentionBackground: Int, placeholderColor: Int, foreground: Int) {
        applying = true
        try {
            val editable = text ?: return
            if (editable.toString() != state.text) editable.replace(0, editable.length, state.text)
            decorations.forEach(editable::removeSpan)
            decorations.clear()
            fun decorate(span: Any, start: Int, end: Int) {
                editable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                decorations += span
            }
            state.emojiMatches().forEach { match ->
                val number = match.groupValues[1].toInt()
                val drawable = images[number]?.let { BitmapDrawable(resources, it) }
                    ?: DynamicEmojiPlaceholder(number, placeholderColor, foreground)
                drawable.setBounds(0, 0, emojiSize, emojiSize)
                val span = ImageSpan(drawable, labels.getValue(number), ImageSpan.ALIGN_BOTTOM)
                if (Build.VERSION.SDK_INT >= 30) span.contentDescription = labels.getValue(number)
                decorate(span, match.range.first, match.range.last + 1)
                decorate(TtsSpan.TextBuilder(labels.getValue(number)).build(), match.range.first, match.range.last + 1)
            }
            state.mentions.forEach { mention ->
                if (mention.start >= 0 && mention.end <= editable.length && mention.end > mention.start) {
                    decorate(ForegroundColorSpan(mentionColor), mention.start, mention.end)
                    decorate(BackgroundColorSpan(mentionBackground), mention.start, mention.end)
                    decorate(StyleSpan(Typeface.BOLD), mention.start, mention.end)
                }
            }
            if (selectionStart != state.selectionStart || selectionEnd != state.selectionEnd) {
                setSelection(state.selectionStart.coerceIn(0, editable.length), state.selectionEnd.coerceIn(0, editable.length))
            }
        } finally { applying = false }
        hasRendered = true
        scheduleInitialFocus()
    }
}

private class DynamicEmojiPlaceholder(private val number: Int, private val background: Int, private val foreground: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        paint.color = background
        canvas.drawRoundRect(android.graphics.RectF(bounds), 4f, 4f, paint)
        paint.color = foreground
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = bounds.height() * 0.48f
        canvas.drawText(number.toString(), bounds.exactCenterX(), bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
