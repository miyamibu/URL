package jp.mimac.urlsaver.video

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.data.UrlEntryDao
import jp.mimac.urlsaver.data.VideoAssetDao
import jp.mimac.urlsaver.data.VideoDownloadDao
import jp.mimac.urlsaver.data.VideoDownloadEntity
import jp.mimac.urlsaver.util.AppClock
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

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
        val downloadUrl = asset.downloadUrl?.takeIf(::isAllowedDownloadUrl)
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
            return Result.success()
        }
        return runCatching {
            download(downloadId, asset.entryId, asset.provider, asset.providerAssetId, asset.mediaType, asset.mimeType, downloadUrl)
            Result.success()
        }.getOrElse { error ->
            videoDownloadDao.markFailed(downloadId, error.message ?: "メディアを保存できませんでした")
            Result.success()
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
        try {
            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,video/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error("HTTP_$responseCode")
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            temporaryFile.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = totalBytes?.let { ((downloaded * 100) / it).toInt().coerceIn(0, 99) } ?: 0
                        if (progress != lastProgress && (progress == 0 || progress - lastProgress >= 5)) {
                            videoDownloadDao.updateProgress(downloadId, progress, downloaded, totalBytes)
                            lastProgress = progress
                        }
                    }
                }
            }
            Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            videoDownloadDao.markSaved(
                downloadId = downloadId,
                localUri = AppMediaStore.localUri(entryId, fileName),
                fileName = fileName,
                savedAt = clock.nowEpochMillis(),
            )
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    companion object {
        const val KEY_VIDEO_ASSET_ID = "videoAssetId"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
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

        private fun isAllowedDownloadUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
            if (scheme == "https") return true
            if (scheme != "http" || !BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS) return false
            val host = uri.host?.lowercase(Locale.US) ?: return false
            return host == "localhost" ||
                host == "127.0.0.1" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                Regex("""172\.(1[6-9]|2[0-9]|3[0-1])\..+""").matches(host)
        }
    }
}
