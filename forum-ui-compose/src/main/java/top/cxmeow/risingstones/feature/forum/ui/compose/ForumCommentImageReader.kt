package top.cxmeow.risingstones.feature.forum.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload

internal const val ForumCommentImageMaximumBytes = 22_020_096

internal enum class ForumImageReadFailure { Unsupported, TooLarge, Unreadable }
internal class ForumImageReadException(val failure: ForumImageReadFailure) : Exception()

internal suspend fun readForumCommentImage(context: Context, uri: Uri): OfficialForumCommentImageUpload =
    withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readForumImageBytes)
            ?: throw ForumImageReadException(ForumImageReadFailure.Unreadable)
        normalizeForumCommentImage(bytes)
    }

internal fun readForumImageBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > ForumCommentImageMaximumBytes) {
            throw ForumImageReadException(ForumImageReadFailure.TooLarge)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun normalizeForumCommentImage(bytes: ByteArray): OfficialForumCommentImageUpload {
    if (bytes.size > ForumCommentImageMaximumBytes) throw ForumImageReadException(ForumImageReadFailure.TooLarge)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
        throw ForumImageReadException(ForumImageReadFailure.Unsupported)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ForumImageReadException(ForumImageReadFailure.Unreadable)
    var sample = 1
    val longestDimension = maxOf(bounds.outWidth, bounds.outHeight).toLong()
    while ((longestDimension + sample - 1) / sample > 1_920) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw ForumImageReadException(ForumImageReadFailure.Unreadable)
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    var oriented = bitmap
    try {
        oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0,
            bitmap.width, bitmap.height, matrix, true)
        val isPng = oriented.hasAlpha()
        val output = ByteArrayOutputStream()
        val quality = when { bytes.size <= 1_048_576 -> 80; bytes.size <= 5_242_880 -> 60; else -> 40 }
        if (!oriented.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                quality, output)) throw ForumImageReadException(ForumImageReadFailure.Unreadable)
        return OfficialForumCommentImageUpload(output.toByteArray(), if (isPng) "image/png" else "image/jpeg")
    } finally {
        if (oriented !== bitmap) oriented.recycle()
        bitmap.recycle()
    }
}
