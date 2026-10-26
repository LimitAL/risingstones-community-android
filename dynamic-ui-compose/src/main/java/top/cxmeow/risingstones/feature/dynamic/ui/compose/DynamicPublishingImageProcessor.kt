package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.core.graphics.scale
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadInput

/** 动态选择图片的临时读取：最多20 MiB，不取得持久图库授权，不写原图或输出文件。 */
suspend fun prepareDynamicPublishingImage(context:Context,uri:Uri):DynamicImageUploadInput=withContext(Dispatchers.IO) {
    val bytes=context.contentResolver.openInputStream(uri)?.use{input ->
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
            require(out.size().toLong()+n<=20L*1024*1024){"Image exceeds 20 MiB"};out.write(buffer,0,n)}
        out.toByteArray()
    } ?: error("Unreadable image")
    normalizeDynamicPublishingImage(bytes).also{currentCoroutineContext().ensureActive()}
}

/** 对齐iOS动态规格：归正方向、长边1920、透明PNG，其余JPEG按原始大小分档压缩，不保留原始元数据。 */
fun normalizeDynamicPublishingImage(bytes: ByteArray): DynamicImageUploadInput {
    if (bytes.size > 20 * 1_024 * 1_024) throw IllegalArgumentException("Image exceeds 20 MiB")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
        throw IllegalArgumentException("Unsupported image")
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Unreadable image")
    var sample = 1
    val longestDimension = maxOf(bounds.outWidth, bounds.outHeight).toLong()
    while ((longestDimension + sample - 1) / sample > 3_840) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalArgumentException("Unreadable image")
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
        if(maxOf(oriented.width,oriented.height)>1920) {
            val scale=1920.0/maxOf(oriented.width,oriented.height)
            val resized=oriented.scale((oriented.width*scale).toInt().coerceAtLeast(1),(oriented.height*scale).toInt().coerceAtLeast(1),true)
            if(oriented!==bitmap && resized!==oriented)oriented.recycle()
            oriented=resized
        }
        val isPng = oriented.hasAlpha()
        val output = ByteArrayOutputStream()
        val quality = dynamicPublishingJpegQuality(bytes.size)
        if (!oriented.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                quality, output)) throw IllegalArgumentException("Unreadable image")
        return DynamicImageUploadInput(output.toByteArray(), if (isPng) "image/png" else "image/jpeg")
    } finally {
        if (oriented !== bitmap) oriented.recycle()
        bitmap.recycle()
    }
}

internal fun dynamicPublishingJpegQuality(originalBytes:Int):Int { require(originalBytes>=0);return when { originalBytes < 5*1024*1024 -> 80; originalBytes < 10*1024*1024 -> 60;else ->40 } }
