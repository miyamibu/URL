package jp.mimac.urlsaver.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import jp.mimac.urlsaver.video.MediaNetworkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
fun SafeRemoteURLImage(
    model: String?,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {},
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = model) {
        value = withContext(Dispatchers.IO) {
            loadBitmap(model)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier) {
            fallback()
        }
    }
}

private suspend fun loadBitmap(rawUrl: String?): Bitmap? {
    if (rawUrl.isNullOrBlank()) return null
    return try {
        val response = MediaNetworkPolicy.openGetResponse(
            rawUrl = rawUrl,
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = 20_000,
            headers = mapOf(
                "User-Agent" to "Rinbam Android",
                "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            ),
        )
        val connection = response.connection
        try {
            val totalBytes = connection.contentLengthLong
            if (totalBytes > MediaNetworkPolicy.MAX_IMAGE_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (total > MediaNetworkPolicy.MAX_IMAGE_BYTES - read) return null
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
            MediaNetworkPolicy.validateContentType("IMAGE", null, connection.contentType)
            MediaNetworkPolicy.validateMagic("IMAGE", connection.contentType, bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            connection.disconnect()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }
}
