package top.cxmeow.risingstones.feature.forum.ui.compose

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

@Composable
internal fun RisingStonesRemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable () -> Unit,
) {
    val state by produceState<RemoteImageState>(
        initialValue = RemoteImageState.Loading,
        key1 = url,
    ) {
        value = RemoteImageState.Loading
        val image = url?.let { RisingStonesForumImageLoader.load(it) }
        value = image?.let(RemoteImageState::Loaded) ?: RemoteImageState.Failed
    }
    when (val current = state) {
        RemoteImageState.Loading,
        RemoteImageState.Failed,
        -> fallback()

        is RemoteImageState.Loaded -> Image(
            bitmap = current.image,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

private sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data object Failed : RemoteImageState
    data class Loaded(val image: ImageBitmap) : RemoteImageState
}

private object RisingStonesForumImageLoader {
    private const val MaxResponseBytes = 12L * 1024L * 1024L
    private const val MaxDecodedDimension = 1600
    private val memory = LruCache<String, ImageBitmap>(64)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun load(url: String): ImageBitmap? {
        synchronized(memory) { memory.get(url) }?.let { return it }
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        return try {
            val bytes = fetchBytes(parsed.toString()) ?: return null
            val image = withContext(Dispatchers.Default) { decode(bytes) } ?: return null
            synchronized(memory) { memory.put(url, image) }
            image
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MaxDecodedDimension ||
            bounds.outHeight / sampleSize > MaxDecodedDimension
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private suspend fun fetchBytes(url: String): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = response.use { value ->
                        val declaredLength = value.body.contentLength()
                        if (!value.isSuccessful ||
                            declaredLength > MaxResponseBytes
                        ) {
                            null
                        } else {
                            runCatching { value.body.bytes() }.getOrNull()
                                ?.takeIf { it.size <= MaxResponseBytes }
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                }
            })
        }
}
