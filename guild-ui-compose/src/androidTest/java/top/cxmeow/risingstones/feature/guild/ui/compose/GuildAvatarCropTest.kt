package top.cxmeow.risingstones.feature.guild.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput

class GuildAvatarCropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun panZoomAndRecreationPreserveSelectionUntilExplicitConfirmation() {
        val input = stripedImage()
        val restoration = StateRestorationTester(compose)
        var result: GuildAvatarCropSelection? = null
        restoration.setContent {
            MaterialTheme { GuildAvatarCropDialog(input, false, {}, { result = it }) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("guild-avatar-crop-preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("guild-avatar-crop-preview").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("guild-avatar-crop-zoom").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithTag("guild-confirm-avatar-crop").performClick()
        compose.runOnIdle {
            assertEquals(2f, result!!.zoom)
            assertTrue(result!!.centerX > 0.5f)
        }
    }

    @Test fun cancellingCropDoesNotReturnAnImage() {
        var confirms = 0
        var cancels = 0
        val input = stripedImage()
        compose.setContent { MaterialTheme { GuildAvatarCropDialog(input, false, { cancels++ }, { confirms++ }) } }
        compose.onNodeWithTag("guild-cancel-avatar-crop").performClick()
        compose.runOnIdle { assertEquals(0, confirms); assertEquals(1, cancels) }
    }

    @Test fun exportedPixelsMatchSelectedRegionAndRemainSquare() = runBlocking {
        val input = stripedImage()
        listOf(0.25f to Color.RED, 0.75f to Color.BLUE).forEach { (center, expected) ->
            val result = cropGuildAvatar(input, GuildAvatarCropSelection(center, 0.5f, 2f))
            val bytes = result.copyBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(100, bitmap.width)
            assertEquals(bitmap.width, bitmap.height)
            assertEquals(expected, bitmap.getPixel(50, 50))
            bitmap.recycle()
        }
    }

    @Test fun exifOrientationIsAppliedBeforeComputingPreviewAndExportRegion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("guild-crop-fixture", ".jpg", context.cacheDir)
        try {
            file.writeBytes(stripedImage(jpeg = true).copyBytes())
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val input = GuildImageUploadInput(file.readBytes(), "image/jpeg")
            val preview = decodeGuildImage(input, 1_024)
            assertEquals(200, preview.width)
            assertEquals(400, preview.height)
            preview.recycle()
            val bytes = cropGuildAvatar(input, GuildAvatarCropSelection(0.5f, 0.25f, 2f)).copyBytes()
            val export = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val pixel = export.getPixel(export.width / 2, export.height / 2)
            assertTrue(Color.red(pixel) > 240 && Color.blue(pixel) < 15)
            export.recycle()
        } finally { file.delete() }
    }
}

private fun stripedImage(jpeg: Boolean = false): GuildImageUploadInput {
    val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply { color = Color.RED }
    Canvas(bitmap).apply {
        drawRect(0f, 0f, 200f, 200f, paint)
        paint.color = Color.BLUE
        drawRect(200f, 0f, 400f, 200f, paint)
    }
    val bytes = ByteArrayOutputStream().use {
        bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }
    bitmap.recycle()
    return GuildImageUploadInput(bytes, if (jpeg) "image/jpeg" else "image/png")
}
