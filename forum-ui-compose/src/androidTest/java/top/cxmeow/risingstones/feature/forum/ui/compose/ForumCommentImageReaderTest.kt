package top.cxmeow.risingstones.feature.forum.ui.compose

import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ForumCommentImageReaderTest {
    @Test fun rejectsAnOversizedStreamWithoutReadingTheWholeSource() {
        val input = CountingImageStream(100L * 1024 * 1024)
        val failure = assertThrows(ForumImageReadException::class.java) { readForumImageBytes(input) }
        assertEquals(ForumImageReadFailure.TooLarge, failure.failure)
        assertTrue(input.bytesRead > 21L * 1024 * 1024)
        assertTrue("Only a bounded read beyond 21 MiB is allowed", input.bytesRead <= 21L * 1024 * 1024 + 8192)
        assertTrue(input.bytesRead < input.length)
    }

    @Test fun rejectsUnsupportedDataAndPreservesValidSmallImageBytesDuringRead() {
        val failure = assertThrows(ForumImageReadException::class.java) {
            normalizeForumCommentImage("fixture-not-an-image".toByteArray())
        }
        assertEquals(ForumImageReadFailure.Unsupported, failure.failure)
        val image = syntheticForumImage()
        assertArrayEquals(image.bytes, readForumImageBytes(ByteArrayInputStream(image.bytes)))
        val normalized = normalizeForumCommentImage(image.bytes)
        assertTrue(normalized.mimeType in setOf("image/png", "image/jpeg"))
        val bitmap = BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.bytes.size)
        assertNotNull(bitmap)
        try { assertEquals(16, bitmap.width); assertEquals(16, bitmap.height) }
        finally { bitmap.recycle() }
    }

    @Test fun normalizationBoundsTheLongestDimensionWhileKeepingAspectRatio() {
        // A narrow generated bitmap exercises downsampling without allocating a large photograph.
        val source = syntheticForumImage(width = 3841, height = 64)
        val image = normalizeForumCommentImage(source.bytes)
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        assertNotNull(decoded)
        try {
            assertTrue(decoded.width <= 1920)
            assertTrue(decoded.height <= 1920)
            assertEquals(60f, decoded.width.toFloat() / decoded.height, 0.1f)
            assertTrue(decoded.width > 0 && decoded.height > 0)
        } finally { decoded.recycle() }
    }
}

private class CountingImageStream(val length: Long) : InputStream() {
    var bytesRead = 0L
        private set

    override fun read(): Int = if (bytesRead == length) -1 else { bytesRead++; 0 }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRead == this.length) return -1
        val count = minOf(length.toLong(), this.length - bytesRead).toInt()
        buffer.fill(0, offset, offset + count)
        bytesRead += count
        return count
    }
}
