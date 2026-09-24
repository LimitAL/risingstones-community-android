package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadInput

internal enum class DynamicImageReadFailure { Unsupported, TooLarge, Unreadable }
internal class DynamicImageReadException(val failure: DynamicImageReadFailure) : Exception()

internal suspend fun readDynamicImage(context: Context, uri: Uri): DynamicImageUploadInput = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { readDynamicImageBytes(it) }
        ?: throw DynamicImageReadException(DynamicImageReadFailure.Unreadable)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val mime = bounds.outMimeType
    if (mime !in DynamicImageUploadInput.SupportedDynamicImageMimeTypes || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw DynamicImageReadException(DynamicImageReadFailure.Unsupported)
    }
    DynamicImageUploadInput(bytes, mime)
}

internal suspend fun readDynamicImageBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > DynamicImageUploadInput.MaximumDynamicImageBytes) {
            throw DynamicImageReadException(DynamicImageReadFailure.TooLarge)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun decodeDynamicImage(input: DynamicImageUploadInput, maximumDimension: Int): Bitmap {
    require(maximumDimension > 0)
    val bytes = input.copyBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DynamicImageReadException(DynamicImageReadFailure.Unsupported)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximumDimension) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw DynamicImageReadException(DynamicImageReadFailure.Unreadable)
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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
    return try {
        if (matrix.isIdentity) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        ).also { if (it !== decoded) decoded.recycle() }
    } catch (error: Exception) {
        decoded.recycle()
        throw error
    }
}
