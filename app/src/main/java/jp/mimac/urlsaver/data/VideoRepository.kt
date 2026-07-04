package jp.mimac.urlsaver.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import jp.mimac.urlsaver.util.AppClock
import jp.mimac.urlsaver.video.AppMediaStore
import jp.mimac.urlsaver.video.VideoDownloadWorker
import jp.mimac.urlsaver.video.VideoResolveWorker
import java.io.File
import kotlinx.coroutines.flow.Flow

interface VideoRepository {
    fun observeAssetsForEntry(entryId: Long): Flow<List<VideoAssetEntity>>
    fun observePreferredAssetForEntry(entryId: Long): Flow<VideoAssetEntity?>
    fun observeLatestDownloadForEntry(entryId: Long): Flow<VideoDownloadEntity?>
    fun observeSavedDownloadsForEntry(entryId: Long): Flow<List<VideoDownloadEntity>>
    fun enqueueResolve(entryId: Long, autoDownload: Boolean = false)
    fun enqueueDownloads(videoAssetIds: List<Long>)
    suspend fun repairSavedDownloadsForEntry(entryId: Long): Int
}

class DefaultVideoRepository(
    private val appContext: Context,
    private val videoAssetDao: VideoAssetDao,
    private val videoDownloadDao: VideoDownloadDao,
    private val workManager: WorkManager,
    private val clock: AppClock,
) : VideoRepository {
    override fun observeAssetsForEntry(entryId: Long): Flow<List<VideoAssetEntity>> =
        videoAssetDao.observeAssetsForEntry(entryId)

    override fun observePreferredAssetForEntry(entryId: Long): Flow<VideoAssetEntity?> =
        videoAssetDao.observePreferredAssetForEntry(entryId)

    override fun observeLatestDownloadForEntry(entryId: Long): Flow<VideoDownloadEntity?> =
        videoDownloadDao.observeLatestDownloadForEntry(entryId)

    override fun observeSavedDownloadsForEntry(entryId: Long): Flow<List<VideoDownloadEntity>> =
        videoDownloadDao.observeSavedDownloadsForEntry(entryId)

    override fun enqueueResolve(entryId: Long, autoDownload: Boolean) {
        val request = OneTimeWorkRequestBuilder<VideoResolveWorker>()
            .setInputData(
                workDataOf(
                    VideoResolveWorker.KEY_ENTRY_ID to entryId,
                    VideoResolveWorker.KEY_AUTO_DOWNLOAD to autoDownload,
                ),
            )
            .build()
        workManager.enqueueUniqueWork("video-resolve:$entryId", ExistingWorkPolicy.REPLACE, request)
    }

    override fun enqueueDownloads(videoAssetIds: List<Long>) {
        videoAssetIds.distinct().forEach { assetId ->
            val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ASSET_ID to assetId))
                .build()
            workManager.enqueueUniqueWork("video-download:$assetId", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun repairSavedDownloadsForEntry(entryId: Long): Int {
        val files = AppMediaStore.fileFor(appContext, entryId, ".probe").parentFile
            ?.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".download") }
            .orEmpty()
        if (files.isEmpty()) return 0
        val assets = videoAssetDao.loadAssetsForEntries(listOf(entryId)).ifEmpty {
            files.mapIndexed { index, file ->
                val mediaType = mediaTypeFor(file)
                localFileAsset(
                    entryId = entryId,
                    file = file,
                    index = index,
                    mediaType = mediaType,
                ).copy(
                    id = videoAssetDao.insertAsset(
                        localFileAsset(
                            entryId = entryId,
                            file = file,
                            index = index,
                            mediaType = mediaType,
                        ),
                    ),
                )
            }
        }
        val existingUris = videoDownloadDao.loadDownloadsForEntries(listOf(entryId)).mapNotNull { it.localUri }.toSet()
        var repaired = 0
        files.forEachIndexed { index, file ->
            val localUri = AppMediaStore.localUri(entryId, file.name)
            if (localUri in existingUris) return@forEachIndexed
            val asset = assets.getOrNull(index) ?: assets.first()
            val timestamp = file.lastModified().takeIf { it > 0L } ?: clock.nowEpochMillis()
            videoDownloadDao.insertOrUpdateDownload(
                VideoDownloadEntity(
                    entryId = entryId,
                    videoAssetId = asset.id,
                    status = "SAVED",
                    progress = 100,
                    bytesDownloaded = file.length().takeIf { it > 0L },
                    totalBytes = file.length().takeIf { it > 0L },
                    localUri = localUri,
                    fileName = file.name,
                    startedAt = timestamp,
                    savedAt = timestamp,
                    errorMessage = null,
                ),
            )
            repaired += 1
        }
        return repaired
    }

    private fun localFileAsset(
        entryId: Long,
        file: File,
        index: Int,
        mediaType: String,
    ): VideoAssetEntity {
        val timestamp = file.lastModified().takeIf { it > 0L } ?: clock.nowEpochMillis()
        return VideoAssetEntity(
            entryId = entryId,
            provider = "local",
            providerAssetId = file.name,
            sourceUrl = AppMediaStore.localUri(entryId, file.name),
            canonicalPostUrl = null,
            authorName = null,
            title = file.name,
            bodyText = null,
            thumbnailUrl = null,
            durationMs = null,
            mediaType = mediaType,
            hasVideo = if (mediaType == "VIDEO") "YES" else "NO",
            resolveStatus = "AVAILABLE",
            downloadUrl = null,
            requestHeadersJson = null,
            mimeType = mimeTypeFor(file, mediaType),
            qualityLabel = null,
            width = null,
            height = null,
            bitrate = null,
            isPreferred = index == 0,
            checkedAt = timestamp,
            expiresAt = null,
            errorReason = null,
        )
    }

    private fun mediaTypeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif" -> "IMAGE"
            else -> "VIDEO"
        }
    }

    private fun mimeTypeFor(file: File, mediaType: String): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> if (mediaType == "IMAGE") "image/jpeg" else "video/mp4"
        }
    }
}
