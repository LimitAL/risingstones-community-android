package top.cxmeow.risingstones.app

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PngShareControllerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var controller: PngShareController? = null

    @After
    fun clearShareCache() {
        controller?.clear()
        PngShareFileProvider.clearRegisteredUrisForTesting()
    }

    @Test
    fun prepareCreatesOneReadOnlyPngShareIntent() {
        val intent = controller().prepare(PngBytes)
        val uri = intent.streamUri()

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.share", uri.authority)
        assertEquals(PngBytes.toList(), context.contentResolver.openInputStream(uri)?.use { it.readBytes().toList() })
    }

    @Test
    fun clearRevokesTheExactUriAndDeletesItsPrivateFile() {
        val uri = controller().prepare(PngBytes).streamUri()
        controller!!.clear()

        assertUnreadable(uri)
        assertTrue(context.cacheDir.resolve("share").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun preparingAgainRevokesAndDeletesThePreviousPng() {
        val first = controller().prepare(PngBytes).streamUri()
        val second = controller!!.prepare(PngBytes).streamUri()

        assertNotEquals(first, second)
        assertUnreadable(first)
        assertEquals(PngBytes.toList(), context.contentResolver.openInputStream(second)?.use { it.readBytes().toList() })
    }

    @Test
    fun anUnregisteredUriCannotBeReadAfterProcessStateIsLost() {
        val uri = controller().prepare(PngBytes).streamUri()
        PngShareFileProvider.clearRegisteredUrisForTesting()

        assertUnreadable(uri)
    }

    @Test
    fun providerRejectsWritesAndUrisOtherThanThePreparedFile() {
        val uri = controller().prepare(PngBytes).streamUri()

        assertNotWritable(uri)
        assertUnreadable(uri.buildUpon().appendPath("not-the-prepared-file").build())
    }

    @Test
    fun prepareRejectsNonPngDataWithoutWritingACacheFile() {
        try {
            controller().prepare(byteArrayOf(1, 2, 3))
            fail("Non-PNG input must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertTrue(context.cacheDir.resolve("share").listFiles().orEmpty().isEmpty())
    }

    private fun controller(): PngShareController = controller ?: PngShareController(context).also {
        controller = it
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri = requireNotNull(getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

    private fun assertUnreadable(uri: Uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            fail("Inactive shared URI must not be readable")
        } catch (_: FileNotFoundException) {
            // Expected.
        }
    }

    private fun assertNotWritable(uri: Uri) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.close()
            fail("Shared URI must be read-only")
        } catch (_: FileNotFoundException) {
            // Expected.
        }
    }

    private companion object {
        val PngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
