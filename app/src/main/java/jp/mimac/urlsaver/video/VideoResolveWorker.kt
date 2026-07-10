package jp.mimac.urlsaver.video

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import jp.mimac.urlsaver.data.UrlEntryDao
import jp.mimac.urlsaver.data.VideoAssetDao
import jp.mimac.urlsaver.data.VideoAssetEntity
import jp.mimac.urlsaver.util.AppClock

class VideoResolveWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val urlEntryDao: UrlEntryDao,
    private val videoAssetDao: VideoAssetDao,
    private val resolver: VideoResolver,
    private val clock: AppClock,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(KEY_ENTRY_ID, 0L)
        if (entryId <= 0L) return Result.failure()
        val entry = urlEntryDao.findById(entryId) ?: return Result.success()
        val resolved = runCatching { resolver.resolve(entry) }.getOrElse { error ->
            VideoResolveResult(
                provider = entry.serviceType.name.lowercase(),
                assets = emptyList(),
                hasVideo = "UNKNOWN",
                resolveStatus = "FAILED",
                errorReason = error.message,
            )
        }
        val now = clock.nowEpochMillis()
        val assetsToPersist = resolved.assets.ifEmpty {
            listOf(
                ResolvedVideoAsset(
                    providerAssetId = "${resolved.provider}:unavailable:$entryId",
                    sourceUrl = entry.openUrl,
                    canonicalPostUrl = entry.openUrl,
                    authorName = entry.fetchedAuthorName,
                    title = entry.fetchedTitle ?: entry.userTitle,
                    bodyText = entry.fetchedBody,
                    thumbnailUrl = entry.thumbnailUrl,
                    durationMs = null,
                    mediaType = "VIDEO",
                    downloadUrl = null,
                    requestHeadersJson = null,
                    mimeType = null,
                    qualityLabel = null,
                    width = null,
                    height = null,
                    bitrate = null,
                    sortIndex = 0,
                    isPreferred = true,
                    expiresAt = null,
                    errorReason = resolved.errorReason,
                ),
            )
        }
        videoAssetDao.deleteAssetsForEntry(entryId)
        videoAssetDao.upsertAssets(
            assetsToPersist.map { asset ->
                VideoAssetEntity(
                    entryId = entryId,
                    provider = resolved.provider,
                    providerAssetId = asset.providerAssetId,
                    sourceUrl = asset.sourceUrl,
                    canonicalPostUrl = asset.canonicalPostUrl,
                    authorName = asset.authorName,
                    title = asset.title,
                    bodyText = asset.bodyText,
                    thumbnailUrl = asset.thumbnailUrl,
                    durationMs = asset.durationMs,
                    mediaType = asset.mediaType,
                    hasVideo = resolved.hasVideo,
                    resolveStatus = resolved.resolveStatus,
                    downloadUrl = asset.downloadUrl,
                    requestHeadersJson = asset.requestHeadersJson,
                    mimeType = asset.mimeType,
                    qualityLabel = asset.qualityLabel,
                    width = asset.width,
                    height = asset.height,
                    bitrate = asset.bitrate,
                    sortIndex = asset.sortIndex,
                    isPreferred = asset.isPreferred,
                    checkedAt = now,
                    expiresAt = asset.expiresAt,
                    errorReason = asset.errorReason ?: resolved.errorReason,
                )
            },
        )
        if (inputData.getBoolean(KEY_AUTO_DOWNLOAD, false)) {
            videoAssetDao.loadAssetsForEntries(listOf(entryId))
                .filter { it.resolveStatus == "AVAILABLE" && !it.downloadUrl.isNullOrBlank() }
                .forEach { asset ->
                    val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                        .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ASSET_ID to asset.id))
                        .build()
                    WorkManager.getInstance(applicationContext)
                        .enqueueUniqueWork("video-download:${asset.id}", ExistingWorkPolicy.REPLACE, request)
                }
        }
        return Result.success()
    }

    companion object {
        const val KEY_ENTRY_ID = "entryId"
        const val KEY_AUTO_DOWNLOAD = "autoDownload"
    }
}
