package top.cxmeow.risingstones.auth.webview

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RisingStonesCookieStore {
    suspend fun read(): RisingStonesCookieCredential?
    suspend fun write(credential: RisingStonesCookieCredential)
    suspend fun clear()
}

/**
 * Stores only the captured Rising Stones cookie and matching WebView User-Agent.
 *
 * Ciphertext lives in the app's no-backup directory; the non-exportable AES key lives in
 * AndroidKeyStore. Writes are atomic, and legacy private SharedPreferences are removed after
 * migration. This store never reads or clears the process-wide WebView cookie jar.
 */
class KeystoreRisingStonesCookieStore(
    context: Context,
    private val keyAlias: String = DefaultKeyAlias,
) : RisingStonesCookieStore {
    private val applicationContext = context.applicationContext
    private val credentialFile = AtomicFile(
        File(applicationContext.noBackupFilesDir, CredentialFileName),
    )
    private val legacyPreferences = applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override suspend fun read(): RisingStonesCookieCredential? = withContext(Dispatchers.IO) {
        val encryptedCredential = runCatching {
            readEncryptedCredential()?.also {
                clearLegacyPreferences()
            } ?: readLegacyEncryptedCredential()?.also { legacy ->
                writeEncryptedCredential(legacy)
                clearLegacyPreferences()
            }
        }.getOrElse {
            clearStoredCredential()
            return@withContext null
        }
        encryptedCredential ?: return@withContext null
        runCatching {
            RisingStonesCookieCredential(
                cookie = decrypt(encryptedCredential.cookie),
                userAgent = decrypt(encryptedCredential.userAgent),
            )
        }.getOrElse {
            clearStoredCredential()
            null
        }
    }

    override suspend fun write(credential: RisingStonesCookieCredential) =
        withContext(Dispatchers.IO) {
            writeEncryptedCredential(
                EncryptedCredential(
                    cookie = encrypt(credential.cookie),
                    userAgent = encrypt(credential.userAgent),
                ),
            )
            clearLegacyPreferences()
        }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        clearStoredCredential()
    }

    private fun readEncryptedCredential(): EncryptedCredential? {
        if (!credentialFile.baseFile.isFile) return null
        credentialFile.openRead().use { fileInput ->
            val input = DataInputStream(fileInput)
            val encrypted = EncryptedCredential(
                cookie = input.readUTF(),
                userAgent = input.readUTF(),
            )
            require(input.read() == -1) { "Invalid encrypted credential file" }
            return encrypted
        }
    }

    private fun readLegacyEncryptedCredential(): EncryptedCredential? {
        val encryptedCookie = legacyPreferences.getString(CookieKey, null)
        val encryptedUserAgent = legacyPreferences.getString(UserAgentKey, null)
        if (encryptedCookie == null && encryptedUserAgent == null) return null
        require(encryptedCookie != null && encryptedUserAgent != null) {
            "Incomplete legacy encrypted credential"
        }
        return EncryptedCredential(
            cookie = encryptedCookie,
            userAgent = encryptedUserAgent,
        )
    }

    private fun writeEncryptedCredential(credential: EncryptedCredential) {
        val output = credentialFile.startWrite()
        try {
            DataOutputStream(output).apply {
                writeUTF(credential.cookie)
                writeUTF(credential.userAgent)
                flush()
            }
            credentialFile.finishWrite(output)
        } catch (error: Exception) {
            credentialFile.failWrite(output)
            throw IllegalStateException("Unable to persist Rising Stones credential", error)
        }
    }

    private fun clearStoredCredential() {
        credentialFile.delete()
        clearLegacyPreferences()
    }

    private fun clearLegacyPreferences() {
        check(legacyPreferences.edit().clear().commit()) {
            "Unable to clear Rising Stones credential"
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(EncryptedValueSeparator)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(EncryptedValueSeparator, limit = 2)
        require(parts.size == 2) { "Invalid encrypted credential" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagLengthBits, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        data class EncryptedCredential(
            val cookie: String,
            val userAgent: String,
        )

        const val AndroidKeyStore = "AndroidKeyStore"
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val GcmTagLengthBits = 128
        const val EncryptedValueSeparator = ":"
        const val CredentialFileName = "rising_stones_web_cookie.bin"
        const val PreferencesName = "rising_stones_web_cookie"
        const val CookieKey = "cookie"
        const val UserAgentKey = "user_agent"
        const val DefaultKeyAlias = "top.cxmeow.risingstones.web-cookie"
    }
}
