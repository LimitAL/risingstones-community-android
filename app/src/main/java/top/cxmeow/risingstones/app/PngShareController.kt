package top.cxmeow.risingstones.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Owns the one temporary PNG which may be offered to another application.
 *
 * The caller deliberately launches the returned intent after its own preview/confirmation flow.
 * Nothing outside [shareDirectory] is ever accepted or exposed.
 */
internal class PngShareController(context: Context) {
    private val applicationContext = context.applicationContext
    private val shareDirectory = File(applicationContext.cacheDir, ShareDirectoryName)
    private var active: SharedPng? = null

    init {
        requireShareDirectory()
        removeStaleFiles()
    }

    /** Creates a one-image, read-only share intent without launching a chooser. */
    @Synchronized
    fun prepare(png: ByteArray): Intent {
        validatePng(png)
        clear()

        val file = File(requireShareDirectory(), "share-${UUID.randomUUID()}.png")
        var uri: Uri? = null
        try {
            writeAtomically(file, png)
            uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.share",
                file,
            )
            PngShareFileProvider.register(uri)
            active = SharedPng(file, uri)
            return Intent(Intent.ACTION_SEND).apply {
                type = PngMimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("shared-png", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            uri?.let(PngShareFileProvider::unregister)
            deleteOwnedFile(file)
            throw error
        }
    }

    /** Revokes future reads and deletes the currently prepared PNG. */
    @Synchronized
    fun clear() {
        val shared = active ?: return
        active = null
        PngShareFileProvider.unregister(shared.uri)
        applicationContext.revokeUriPermission(shared.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        deleteOwnedFile(shared.file)
    }

    private fun validatePng(png: ByteArray) {
        require(png.size in PngSignature.size..MaxPngBytes) { "Shared PNG has an invalid size" }
        require(png.indices.take(PngSignature.size).all { png[it] == PngSignature[it] }) {
            "Shared data is not a PNG"
        }
    }

    private fun requireShareDirectory(): File {
        if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
            throw IOException("Unable to create the private share cache directory")
        }
        check(shareDirectory.isDirectory) { "The private share cache path is not a directory" }
        return shareDirectory
    }

    private fun writeAtomically(file: File, png: ByteArray) {
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(png)
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun removeStaleFiles() {
        val directory = requireShareDirectory()
        directory.listFiles()?.forEach(::deleteOwnedFile)
    }

    private fun deleteOwnedFile(file: File) {
        val directory = requireShareDirectory().canonicalFile
        val candidate = file.canonicalFile
        if (candidate.parentFile != directory) return
        candidate.delete()
        File(candidate.path + ".new").delete()
        File(candidate.path + ".bak").delete()
    }

    private data class SharedPng(val file: File, val uri: Uri)

    private companion object {
        const val ShareDirectoryName = "share"
        const val PngMimeType = "image/png"
        const val MaxPngBytes = 10 * 1024 * 1024
        val PngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}

/**
 * A FileProvider which serves only the URI registered by the live controller.
 *
 * The registry is intentionally process-local. After process restart, a stale URI grant has no
 * corresponding registration and cannot open a leftover cache file.
 */
class PngShareFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        requireActiveRead(uri, mode)
        return super.openFile(uri, mode)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        requireActiveRead(uri, "r")
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    override fun getType(uri: Uri): String? {
        requireActiveRead(uri, "r")
        return super.getType(uri)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("Shared PNG provider is read-only")
    }

    override fun insert(uri: Uri, values: ContentValues): Uri {
        throw SecurityException("Shared PNG provider is read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        throw SecurityException("Shared PNG provider is read-only")
    }

    private fun requireActiveRead(uri: Uri, mode: String) {
        if (mode != "r" || !isRegistered(uri)) {
            throw FileNotFoundException("Shared PNG is no longer available")
        }
    }

    internal companion object {
        private val activeUris = mutableSetOf<String>()

        @Synchronized
        fun register(uri: Uri) {
            activeUris += uri.toString()
        }

        @Synchronized
        fun unregister(uri: Uri) {
            activeUris -= uri.toString()
        }

        @Synchronized
        fun clearRegisteredUrisForTesting() {
            activeUris.clear()
        }

        @Synchronized
        private fun isRegistered(uri: Uri): Boolean = uri.toString() in activeUris
    }
}
