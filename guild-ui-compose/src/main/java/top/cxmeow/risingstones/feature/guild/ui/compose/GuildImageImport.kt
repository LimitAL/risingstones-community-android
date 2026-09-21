package top.cxmeow.risingstones.feature.guild.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput
import top.cxmeow.risingstones.feature.guild.presentation.GuildActionViewModel

enum class GuildImageReadFailure { Unsupported, TooLarge, Unreadable, TooMany }

internal data class GuildImageImportState(
    val isReading: Boolean = false,
    val failure: GuildImageReadFailure? = null,
    val pendingAvatar: GuildImageUploadInput? = null,
    val albumPreviewUris: List<Uri> = emptyList(),
)

internal class GuildImageReadException(val failure: GuildImageReadFailure) : Exception()

/** Holds picker and local decoding work across configuration changes; it never uploads. */
internal class GuildImageImportViewModel : ViewModel() {
    val singlePickerKey = "guild-single-image-${UUID.randomUUID()}"
    val albumPickerKey = "guild-album-images-${UUID.randomUUID()}"
    private val mutableState = MutableStateFlow(GuildImageImportState())
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null
    private var generation = 0L
    private var pickerRevision: Long? = null
    private var pickerPurpose: GuildImagePurpose? = null
    private var activeRevision: Long? = null

    fun beginPicker(model: GuildActionViewModel, purpose: GuildImagePurpose): Boolean {
        model.synchronizeEligibility()
        if (!model.canUploadImages || mutableState.value.isReading) return false
        pickerRevision = model.state.value.draftRevision
        pickerPurpose = purpose
        return true
    }

    fun acceptPickerResult(model: GuildActionViewModel): GuildImagePurpose? {
        val revision = pickerRevision
        val purpose = pickerPurpose
        pickerRevision = null
        pickerPurpose = null
        return purpose?.takeIf {
            revision != null && revision == model.state.value.draftRevision && model.canUploadImages
        }
    }

    fun read(
        model: GuildActionViewModel,
        purpose: GuildImagePurpose,
        uris: List<Uri>,
        reader: suspend (Uri) -> GuildImageUploadInput,
    ) {
        if (uris.isEmpty() || mutableState.value.isReading || !model.canUploadImages) return
        if (uris.size > purpose.maximumSelectionCount) {
            mutableState.value = mutableState.value.copy(failure = GuildImageReadFailure.TooMany)
            return
        }
        val requestGeneration = ++generation
        val revision = model.state.value.draftRevision
        activeRevision = revision
        mutableState.value = GuildImageImportState(isReading = true)
        readJob = viewModelScope.launch {
            try {
                val images = uris.map { reader(it) }
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation || revision != model.state.value.draftRevision ||
                    !model.canUploadImages) return@launch
                when (purpose) {
                    GuildImagePurpose.Avatar -> mutableState.value = GuildImageImportState(pendingAvatar = images.single())
                    GuildImagePurpose.Album -> {
                        model.setAlbumImages(images)
                        mutableState.value = GuildImageImportState()
                    }
                    GuildImagePurpose.Comment -> {
                        model.setCommentImage(images.single())
                        mutableState.value = GuildImageImportState()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == generation && revision == model.state.value.draftRevision) {
                    mutableState.value = GuildImageImportState(failure =
                        (error as? GuildImageReadException)?.failure ?: GuildImageReadFailure.Unreadable)
                }
            } finally {
                if (requestGeneration == generation) mutableState.value = mutableState.value.copy(isReading = false)
            }
        }
    }

    fun selectAlbumSources(
        model: GuildActionViewModel,
        context: Context,
        uris: List<Uri>,
    ) {
        if (uris.isEmpty() || mutableState.value.isReading || !model.canUploadImages) return
        if (uris.size > GuildImagePurpose.Album.maximumSelectionCount) {
            mutableState.value = mutableState.value.copy(failure = GuildImageReadFailure.TooMany)
            return
        }
        val applicationContext = context.applicationContext
        model.setAlbumImageSources(uris.map { uri ->
            top.cxmeow.risingstones.feature.guild.presentation.GuildAlbumImageSource {
                readGuildImage(applicationContext, uri)
            }
        })
        activeRevision = model.state.value.draftRevision
        mutableState.value = GuildImageImportState(albumPreviewUris = uris.toList())
    }

    fun confirmAvatarCrop(model: GuildActionViewModel, selection: GuildAvatarCropSelection) {
        val pending = mutableState.value.pendingAvatar ?: return
        if (!model.canUploadImages || mutableState.value.isReading) return
        val requestGeneration = ++generation
        val revision = model.state.value.draftRevision
        activeRevision = revision
        mutableState.value = mutableState.value.copy(isReading = true, failure = null)
        readJob = viewModelScope.launch {
            try {
                val cropped = cropGuildAvatar(pending, selection)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation || revision != model.state.value.draftRevision ||
                    !model.canUploadImages) return@launch
                model.setAvatarDraft(cropped)
                mutableState.value = GuildImageImportState()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (requestGeneration == generation) mutableState.value = GuildImageImportState(
                    pendingAvatar = pending,
                    failure = (error as? GuildImageReadException)?.failure ?: GuildImageReadFailure.Unreadable,
                )
            } finally {
                if (requestGeneration == generation) mutableState.value = mutableState.value.copy(isReading = false)
            }
        }
    }

    fun cancelAvatarCrop() { mutableState.value = GuildImageImportState() }
    fun clearError() { mutableState.value = mutableState.value.copy(failure = null) }

    fun synchronizeDraft(revision: Long) {
        if (activeRevision != null && activeRevision != revision || pickerRevision != null && pickerRevision != revision) {
            cancelAll()
        }
    }

    fun cancelAll() {
        ++generation
        readJob?.cancel()
        readJob = null
        activeRevision = null
        pickerRevision = null
        pickerPurpose = null
        mutableState.value = GuildImageImportState()
    }

    override fun onCleared() {
        cancelAll()
        super.onCleared()
    }
}

internal suspend fun readGuildImage(context: Context, uri: Uri): GuildImageUploadInput = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use(::readGuildImageBytes)
        ?: throw GuildImageReadException(GuildImageReadFailure.Unreadable)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val mime = bounds.outMimeType
    if (mime !in GuildImageUploadInput.SupportedGuildImageMimeTypes || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw GuildImageReadException(GuildImageReadFailure.Unsupported)
    }
    GuildImageUploadInput(bytes, mime)
}

internal fun readGuildImageBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > GuildImageUploadInput.MaximumGuildImageBytes) {
            throw GuildImageReadException(GuildImageReadFailure.TooLarge)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun decodeGuildImage(input: GuildImageUploadInput, maximumDimension: Int): Bitmap {
    val bytes = input.copyBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximumDimension) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw GuildImageReadException(GuildImageReadFailure.Unreadable)
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    return try {
        if (matrix.isIdentity) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        ).also { if (it !== decoded) decoded.recycle() }
    } catch (error: Exception) {
        decoded.recycle()
        throw error
    }
}

internal suspend fun cropGuildAvatar(
    input: GuildImageUploadInput,
    selection: GuildAvatarCropSelection,
): GuildImageUploadInput = withContext(Dispatchers.Default) {
    val oriented = decodeGuildImage(input, 2_048)
    var square: Bitmap? = null
    try {
        val rect = selection.rectangle(oriented.width, oriented.height)
        square = Bitmap.createBitmap(oriented, rect.left, rect.top, rect.side, rect.side)
        val output = ByteArrayOutputStream()
        val hasAlpha = square.hasAlpha()
        if (!square.compress(if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, output)) {
            throw GuildImageReadException(GuildImageReadFailure.Unreadable)
        }
        GuildImageUploadInput(output.toByteArray(), if (hasAlpha) "image/png" else "image/jpeg")
    } finally {
        square?.takeIf { it !== oriented }?.recycle()
        oriented.recycle()
    }
}
