package top.cxmeow.risingstones.feature.dynamic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicPublishingPreparationContractTest {
    @Test fun jpegThresholdsPreserveOriginalByteRules() {
        for ((bytes, quality) in listOf(0 to 80, (5 * 1024 * 1024 - 1) to 80,
            5 * 1024 * 1024 to 60, (10 * 1024 * 1024 - 1) to 60,
            10 * 1024 * 1024 to 40, 20 * 1024 * 1024 to 40)) {
            assertEquals(quality, dynamicPublishingJpegQuality(bytes))
        }
    }
    @Test fun oversizeRejectedBeforePlatformDecode() {
        val error = runCatching { normalizeDynamicPublishingImage(ByteArray(20 * 1024 * 1024 + 1)) }.exceptionOrNull()
        assertTrue(error is DynamicPublishingImagePreparationException)
        assertTrue((error as DynamicPublishingImagePreparationException).tooLarge)
    }
    @Test fun failureRetainsCategoryAndCause() {
        val cause = IllegalStateException("synthetic")
        val error = DynamicPublishingImagePreparationException(false, cause)
        assertFalse(error.tooLarge)
        assertSame(cause, error.cause)
        assertEquals("Unreadable image", error.message)
    }
}
