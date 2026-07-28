package top.cxmeow.risingstones.auth.webview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreRisingStonesCookieStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = KeystoreRisingStonesCookieStore(
        context = context,
        keyAlias = "top.cxmeow.risingstones.web-cookie.instrumented-test",
    )

    @Before
    fun clearBeforeTest() = runBlocking {
        store.clear()
    }

    @After
    fun clearAfterTest() = runBlocking {
        store.clear()
    }

    @Test
    fun encryptsInNoBackupDirectoryAndRestoresOnlyCookieAndMatchingUserAgent() = runBlocking {
        val credential = RisingStonesCookieCredential(
            cookie = "instrumented-cookie-value",
            userAgent = "InstrumentedWebView/1.0",
        )

        store.write(credential)

        val legacyPreferences = context.getSharedPreferences(
            "rising_stones_web_cookie",
            Context.MODE_PRIVATE,
        )
        val credentialFile = credentialFile()
        val rawCiphertext = credentialFile.readBytes().decodeToString()
        assertTrue(credentialFile.isFile)
        assertTrue(credentialFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertTrue(legacyPreferences.all.isEmpty())
        assertFalse(rawCiphertext.contains("instrumented-cookie-value"))
        assertFalse(rawCiphertext.contains("InstrumentedWebView/1.0"))

        val restored = store.read()
        assertEquals("instrumented-cookie-value", restored?.cookie)
        assertEquals("InstrumentedWebView/1.0", restored?.userAgent)
        assertTrue(restored.toString().contains("redacted"))

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun clearsCorruptEncryptedCredentialInsteadOfLeavingOrphanedState() = runBlocking {
        store.write(
            RisingStonesCookieCredential(
                cookie = "incomplete-cookie-value",
                userAgent = "IncompleteWebView/1.0",
            ),
        )
        val credentialFile = credentialFile()
        credentialFile.writeText("corrupt")

        assertNull(store.read())
        assertFalse(credentialFile.exists())
    }

    @Test
    fun migratesLegacyEncryptedPreferencesIntoNoBackupStorage() = runBlocking {
        store.write(
            RisingStonesCookieCredential(
                cookie = "legacy-cookie-value",
                userAgent = "LegacyWebView/1.0",
            ),
        )
        val credentialFile = credentialFile()
        val encryptedValues = DataInputStream(credentialFile.inputStream()).use { input ->
            input.readUTF() to input.readUTF()
        }
        assertTrue(credentialFile.delete())
        val legacyPreferences = context.getSharedPreferences(
            "rising_stones_web_cookie",
            Context.MODE_PRIVATE,
        )
        assertTrue(
            legacyPreferences.edit()
                .putString("cookie", encryptedValues.first)
                .putString("user_agent", encryptedValues.second)
                .commit(),
        )

        val restored = store.read()

        assertEquals("legacy-cookie-value", restored?.cookie)
        assertEquals("LegacyWebView/1.0", restored?.userAgent)
        assertTrue(credentialFile.isFile)
        assertTrue(legacyPreferences.all.isEmpty())
    }

    private fun credentialFile(): File =
        File(context.noBackupFilesDir, "rising_stones_web_cookie.bin")
}
