package jp.mimac.urlsaver.video

import android.content.Context
import android.os.storage.StorageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.data.UrlEntryDao
import jp.mimac.urlsaver.data.VideoAssetDao
import jp.mimac.urlsaver.data.VideoDownloadDao
import jp.mimac.urlsaver.data.VideoDownloadEntity
import jp.mimac.urlsaver.util.AppClock
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

class VideoDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val urlEntryDao: UrlEntryDao,
    private val videoAssetDao: VideoAssetDao,
    private val videoDownloadDao: VideoDownloadDao,
    private val clock: AppClock,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val assetId = inputData.getLong(KEY_VIDEO_ASSET_ID, 0L)
        if (assetId <= 0L) return Result.failure()
        val asset = videoAssetDao.findById(assetId) ?: return Result.failure()
        urlEntryDao.findById(asset.entryId) ?: return Result.success()
        val downloadUrl = asset.downloadUrl
            ?.let(::normalizeDownloadUrl)
            ?.takeIf {
                hasAllowedUrlShape(
                    url = it,
                    allowLoopbackHttp = BuildConfig.DEBUG && BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS,
                )
            }
        val downloadId = videoDownloadDao.insertOrUpdateDownload(
            VideoDownloadEntity(
                entryId = asset.entryId,
                videoAssetId = asset.id,
                status = "DOWNLOADING",
                progress = 0,
                bytesDownloaded = null,
                totalBytes = null,
                localUri = null,
                fileName = buildFileName(asset.provider, asset.providerAssetId, asset.mediaType, asset.mimeType, downloadUrl),
                startedAt = clock.nowEpochMillis(),
                savedAt = null,
                errorMessage = null,
            ),
        )
        if (downloadUrl == null) {
            videoDownloadDao.markFailed(downloadId, "メディアURLを取得できませんでした")
            return Result.failure()
        }
        return try {
            download(
                downloadId,
                asset.entryId,
                asset.provider,
                asset.providerAssetId,
                asset.mediaType,
                asset.mimeType,
                downloadUrl,
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            videoDownloadDao.markFailed(downloadId, error.message ?: "メディアを保存できませんでした")
            if (MediaNetworkPolicy.classifyForRetry(error) && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun download(
        downloadId: Long,
        entryId: Long,
        provider: String,
        providerAssetId: String,
        mediaType: String,
        mimeType: String?,
        downloadUrl: String,
    ) {
        val fileName = buildFileName(provider, providerAssetId, mediaType, mimeType, downloadUrl)
        val outputFile = AppMediaStore.fileFor(applicationContext, entryId, fileName)
        val temporaryFile = outputFile.resolveSibling("${outputFile.name}.download")
        outputFile.parentFile?.mkdirs()
        temporaryFile.delete()
        var outputInstalled = false
        try {
            val entryBytesBeforeDownload = outputFile.parentFile
                ?.listFiles()
                ?.filter { it.isFile && it != temporaryFile && it != outputFile }
                ?.sumOf { it.length().coerceAtLeast(0L) }
                ?: 0L
            if (entryBytesBeforeDownload >= MediaNetworkPolicy.MAX_ENTRY_MEDIA_BYTES) {
                throw MediaNetworkPolicy.QuotaExceededException()
            }
            var activeConnection: HttpURLConnection? = null
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                activeConnection?.disconnect()
            }
            try {
                val response = MediaNetworkPolicy.openGetResponse(
                    rawUrl = downloadUrl,
                    connectTimeoutMillis = 20_000,
                    readTimeoutMillis = 60_000,
                    allowLoopbackHttp = BuildConfig.DEBUG && BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS,
                    headers = mapOf(
                        "User-Agent" to DEFAULT_USER_AGENT,
                        "Accept" to "image/avif,image/webp,image/apng,image/*,video/*,*/*;q=0.8",
                        "Accept-Language" to "ja,en-US;q=0.9,en;q=0.8",
                    ),
                    onConnectionOpened = { activeConnection = it },
                )
                activeConnection = response.connection
                val connection = response.connection
                try {
                    currentCoroutineContext().ensureActive()
                    val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                    if (totalBytes != null && totalBytes > MediaNetworkPolicy.MAX_MEDIA_BYTES) {
                        throw MediaNetworkPolicy.QuotaExceededException()
                    }
                    if (totalBytes != null && !hasEnoughStorage(outputFile.parentFile, entryBytesBeforeDownload, totalBytes)) {
                        throw MediaNetworkPolicy.InsufficientStorageException()
                    }
                    MediaNetworkPolicy.validateContentType(mediaType, mimeType, connection.contentType)
                    val magicPrefix = ByteArray(MAGIC_PREFIX_BYTES)
                    var magicSize = 0
                    temporaryFile.outputStream().use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            var lastProgress = -1
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                if (downloaded > MediaNetworkPolicy.MAX_MEDIA_BYTES - read) {
                                    throw MediaNetworkPolicy.QuotaExceededException()
                                }
                                if (magicSize < magicPrefix.size) {
                                    val copied = minOf(read, magicPrefix.size - magicSize)
                                    buffer.copyInto(magicPrefix, magicSize, 0, copied)
                                    magicSize += copied
                                }
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (!hasEnoughStorage(outputFile.parentFile, entryBytesBeforeDownload, downloaded)) {
                                    throw MediaNetworkPolicy.InsufficientStorageException()
                                }
                                val progress = totalBytes?.let { ((downloaded * 100) / it).toInt().coerceIn(0, 99) } ?: 0
                                if (progress != lastProgress && (progress == 0 || progress - lastProgress >= 5)) {
                                    videoDownloadDao.updateProgress(downloadId, progress, downloaded, totalBytes)
                                    lastProgress = progress
                                }
                            }
                        }
                    }
                    if (magicSize == 0) throw MediaNetworkPolicy.InvalidResponseException("MEDIA_EMPTY")
                    MediaNetworkPolicy.validateMagic(mediaType, mimeType ?: connection.contentType, magicPrefix.copyOf(magicSize))
                    val downloadedBytes = temporaryFile.length()
                    if (downloadedBytes <= 0L || downloadedBytes > MediaNetworkPolicy.MAX_MEDIA_BYTES) {
                        throw MediaNetworkPolicy.InvalidResponseException("MEDIA_SIZE_INVALID")
                    }
                    if (!hasEnoughStorage(outputFile.parentFile, entryBytesBeforeDownload, downloadedBytes)) {
                        throw MediaNetworkPolicy.InsufficientStorageException()
                    }
                    currentCoroutineContext().ensureActive()
                    if (urlEntryDao.findById(entryId) == null) {
                        throw CancellationException("URL entry was deleted while downloading media")
                    }
                } finally {
                    connection.disconnect()
                }
            } finally {
                cancellationHandle?.dispose()
                activeConnection?.disconnect()
            }
            Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            outputInstalled = true
            currentCoroutineContext().ensureActive()
            if (urlEntryDao.findById(entryId) == null) {
                Files.deleteIfExists(outputFile.toPath())
                outputInstalled = false
                throw CancellationException("URL entry was deleted while saving media")
            }
            videoDownloadDao.markSaved(
                downloadId = downloadId,
                localUri = AppMediaStore.localUri(entryId, fileName),
                fileName = fileName,
                savedAt = clock.nowEpochMillis(),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                if (outputInstalled && urlEntryDao.findById(entryId) == null) {
                    runCatching { Files.deleteIfExists(outputFile.toPath()) }
                }
                temporaryFile.delete()
                throw error
            }
            if (!currentCoroutineContext().isActive) {
                temporaryFile.delete()
                if (outputInstalled && urlEntryDao.findById(entryId) == null) {
                    runCatching { Files.deleteIfExists(outputFile.toPath()) }
                }
                currentCoroutineContext().ensureActive()
            }
            temporaryFile.delete()
            throw error
        }
    }

    private fun hasEnoughStorage(directory: java.io.File?, entryBytes: Long, incomingBytes: Long): Boolean {
        if (directory == null) return false
        if (entryBytes > MediaNetworkPolicy.MAX_ENTRY_MEDIA_BYTES - incomingBytes) return false
        val allocatableBytes = applicationContext
            .getSystemService(StorageManager::class.java)
            ?.let { storageManager ->
                runCatching {
                    storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
                }.getOrNull()
            }
            ?: return false
        return allocatableBytes >= incomingBytes + MediaNetworkPolicy.MIN_FREE_BYTES
    }

    companion object {
        const val KEY_VIDEO_ASSET_ID = "videoAssetId"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val MAGIC_PREFIX_BYTES = 32
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"

        private fun buildFileName(
            provider: String,
            providerAssetId: String,
            mediaType: String,
            mimeType: String?,
            downloadUrl: String?,
        ): String {
            val extension = extensionFor(mimeType, downloadUrl, mediaType)
            val base = "${provider}_${providerAssetId}"
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_')
                .take(80)
                .ifBlank { "rinbam_media" }
            return "$base.$extension"
        }

        private fun extensionFor(mimeType: String?, downloadUrl: String?, mediaType: String): String {
            val fromMime = when (mimeType?.lowercase(Locale.US)?.substringBefore(';')) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "video/quicktime" -> "mov"
                "video/webm" -> "webm"
                "video/mp4" -> "mp4"
                else -> null
            }
            if (fromMime != null) return fromMime
            val fromUrl = downloadUrl
                ?.substringBefore('?')
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.US)
                ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "mp4", "mov", "webm") }
            return fromUrl ?: if (mediaType == "IMAGE") "jpg" else "mp4"
        }

        private fun hasAllowedUrlShape(url: String, allowLoopbackHttp: Boolean): Boolean {
            return MediaNetworkPolicy.hasAllowedUrlShape(url, allowLoopbackHttp)
        }

        private fun normalizeDownloadUrl(url: String): String {
            return url
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\u0025", "%")
                .replace("\\%", "%")
                .trim()
        }
    }
}
