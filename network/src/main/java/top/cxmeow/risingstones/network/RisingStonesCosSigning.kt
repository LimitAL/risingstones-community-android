package top.cxmeow.risingstones.network

import java.net.URI
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Shared signing for the reviewed official image store. Inputs and results must never be logged. */
object RisingStonesCosSigning {
    fun putAuthorization(
        url: String,
        contentLength: Int,
        secretId: String,
        secretKey: String,
        startTime: Long,
        expiredTime: Long,
    ): String {
        val uri = try { URI(url) } catch (_: Exception) { invalid() }
        if (uri.scheme != "https" || uri.host != "ff14risingstones.gcloud.com.cn" ||
            uri.port !in setOf(-1, 443) || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || uri.rawPath.isNullOrBlank() || uri.rawPath == "/" ||
            contentLength < 0 || secretId.isBlank() || secretKey.isBlank() ||
            secretId.any { it.isISOControl() || it in "&;=?" } || secretKey.any(Char::isISOControl) ||
            startTime <= 0 || startTime >= expiredTime
        ) invalid()
        val keyTime = "$startTime;$expiredTime"
        val httpString = "put\n${uri.rawPath}\n\ncontent-length=$contentLength\n"
        val stringToSign = "sha1\n$keyTime\n${sha1(httpString)}\n"
        val signature = hmacSha1(hmacSha1(secretKey, keyTime), stringToSign)
        return listOf(
            "q-sign-algorithm=sha1", "q-ak=$secretId", "q-sign-time=$keyTime",
            "q-key-time=$keyTime", "q-header-list=content-length", "q-url-param-list=",
            "q-signature=$signature",
        ).joinToString("&")
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid official object upload signing input")

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.encodeToByteArray()).toHex()

    private fun hmacSha1(key: String, value: String): String = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA1"))
        doFinal(value.encodeToByteArray()).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
