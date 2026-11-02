package top.cxmeow.risingstones.feature.dynamic.data

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

/**
 * 动态图片导入失败；只区分可确定的大小限制与无法读取/解码，界面不使用 URI 或底层异常文本。
 * 不改变上传输出格式，也不代表已获取上传资格。
 */
class DynamicPublishingImagePreparationException(
    /** 是否因原始输入超过 20 MiB 而拒绝。 */
    val tooLarge: Boolean,
    cause: Throwable? = null,
) : IllegalArgumentException(if (tooLarge) "Image exceeds 20 MiB" else "Unreadable image", cause)

/** 动态选择图片的临时读取：最多20 MiB，不取得持久图库授权，不写原图或输出文件。 */
suspend fun prepareDynamicPublishingImage(context:Context,uri:Uri):DynamicImageUploadInput=withContext(Dispatchers.IO) {
    try {
    val bytes=context.contentResolver.openInputStream(uri)?.use{input ->
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
            if(out.size().toLong()+n>20L*1024*1024)throw DynamicPublishingImagePreparationException(true);out.write(buffer,0,n)}
        out.toByteArray()
    } ?: error("Unreadable image")
    normalizeDynamicPublishingImage(bytes).also{currentCoroutineContext().ensureActive()}
    } catch(cancel:CancellationException) { throw cancel
    } catch(error:DynamicPublishingImagePreparationException) { throw error
    } catch(error:Exception) { throw DynamicPublishingImagePreparationException(false,error) }
}

/**
 * 对齐 iOS 动态规格：归正方向、长边 1920、透明 PNG，其余 JPEG 按原始大小分档压缩，不保留原始元数据。
 * HEIF 输入依赖平台解码能力；不能解码时拒绝，不将原始 HEIF 作为上传输出。
 */
fun normalizeDynamicPublishingImage(bytes: ByteArray): DynamicImageUploadInput {
    if (bytes.size > 20 * 1_024 * 1_024) throw DynamicPublishingImagePreparationException(true)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heif")) {
        throw DynamicPublishingImagePreparationException(false)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DynamicPublishingImagePreparationException(false)
    var sample = 1
    val longestDimension = maxOf(bounds.outWidth, bounds.outHeight).toLong()
    while ((longestDimension + sample - 1) / sample > 3_840) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw DynamicPublishingImagePreparationException(false)
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
            val resized=Bitmap.createScaledBitmap(oriented,(oriented.width*scale).toInt().coerceAtLeast(1),(oriented.height*scale).toInt().coerceAtLeast(1),true)
            if(oriented!==bitmap && resized!==oriented)oriented.recycle()
            oriented=resized
        }
        val isPng = oriented.hasAlpha()
        val output = ByteArrayOutputStream()
        val quality = dynamicPublishingJpegQuality(bytes.size)
        if (!oriented.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                quality, output)) throw DynamicPublishingImagePreparationException(false)
        return DynamicImageUploadInput(output.toByteArray(), if (isPng) "image/png" else "image/jpeg")
    } finally {
        if (oriented !== bitmap) oriented.recycle()
        bitmap.recycle()
    }
}

internal fun dynamicPublishingJpegQuality(originalBytes:Int):Int { require(originalBytes>=0);return when { originalBytes < 5*1024*1024 -> 80; originalBytes < 10*1024*1024 -> 60;else ->40 } }
