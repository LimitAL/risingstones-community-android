package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadInput

class DynamicImageImportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localImportKeepsValidatedBytesAndPreviewRespectsDimensionLimit() = runBlocking {
        val image = Bitmap.createBitmap(2_000, 1_000, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val file = java.io.File.createTempFile("dynamic-image", ".png", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val input = readDynamicImage(context, Uri.fromFile(file))
            assertEquals("image/png", input.mimeType)
            assertArrayEquals(bytes, input.copyBytes())
            val preview = decodeDynamicImage(input, 500)
            assertEquals(500, preview.width)
            assertEquals(250, preview.height)
            preview.recycle()
        } finally { file.delete() }
    }

    @Test fun rotatedExifIsAppliedToSampledPreview() = runBlocking {
        val file = java.io.File.createTempFile("dynamic-image", ".jpg", context.cacheDir)
        try {
            val image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            image.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val preview = decodeDynamicImage(readDynamicImage(context, Uri.fromFile(file)), 200)
            assertEquals(100, preview.width)
            assertEquals(200, preview.height)
            preview.recycle()
        } finally { file.delete() }
    }

    @Test fun malformedImageAndOversizedStreamAreRejected() = runBlocking {
        val file = java.io.File.createTempFile("dynamic-invalid", ".png", context.cacheDir)
        try {
            file.writeText("not an image")
            assertTrue(runCatching { readDynamicImage(context, Uri.fromFile(file)) }.exceptionOrNull() is DynamicImageReadException)
            val oversized = ByteArrayInputStream(ByteArray(DynamicImageUploadInput.MaximumDynamicImageBytes + 1))
            val failure = runCatching { readDynamicImageBytes(oversized) }.exceptionOrNull() as? DynamicImageReadException
            assertEquals(DynamicImageReadFailure.TooLarge, failure?.failure)
        } finally { file.delete() }
    }
}
