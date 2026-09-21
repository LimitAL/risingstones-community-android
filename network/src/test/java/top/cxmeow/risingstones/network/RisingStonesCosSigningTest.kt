package top.cxmeow.risingstones.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class RisingStonesCosSigningTest {
    @Test fun matchesTheEstablishedOfficialPutSignature() {
        assertEquals(
            "q-sign-algorithm=sha1&q-ak=AKIDTEST&q-sign-time=1700000000;1700001800&" +
                "q-key-time=1700000000;1700001800&q-header-list=content-length&" +
                "q-url-param-list=&q-signature=b65fab15b01f7bb92c66dae6318aa4c59cdb2111",
            sign("https://ff14risingstones.gcloud.com.cn/posts/20260722/10015973/test/1700000000123_abc1700000000123.png"),
        )
    }

    @Test fun onlyTheReviewedHttpsObjectStoreCanBeSignedAndErrorsAreSanitized() {
        listOf(
            "http://ff14risingstones.gcloud.com.cn/image.png",
            "https://ff14risingstones.gcloud.com.cn.evil.invalid/image.png",
            "https://ff14risingstones.gcloud.com.cn:8443/image.png",
            "https://private-value@ff14risingstones.gcloud.com.cn/image.png",
            "https://ff14risingstones.gcloud.com.cn/image.png?private-value=1",
            "https://ff14risingstones.gcloud.com.cn/image.png#private-value",
            "https://ff14risingstones.gcloud.com.cn/",
        ).forEach { url ->
            try { sign(url); fail("Unreviewed signing target accepted") }
            catch (error: IllegalArgumentException) {
                assertFalse(error.message.orEmpty().contains("private-value"))
                assertEquals(null, error.cause)
            }
        }
    }

    private fun sign(url: String) = RisingStonesCosSigning.putAuthorization(
        url, 5, "AKIDTEST", "test-secret", 1_700_000_000, 1_700_001_800,
    )
}
