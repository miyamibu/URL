package jp.mimac.urlsaver.video

import android.content.Context
import androidx.work.WorkManager
import jp.mimac.urlsaver.data.PendingDeleteMediaCleanup

interface EntryWorkCanceller {
    suspend fun cancel(entryId: Long, downloadAssetIds: List<Long>)
}

interface EntryMediaFileStore {
    fun deleteFiles(entryId: Long)
}

class WorkManagerEntryWorkCanceller(
    private val workManager: WorkManager,
) : EntryWorkCanceller {
    override suspend fun cancel(entryId: Long, downloadAssetIds: List<Long>) {
        workManager.cancelUniqueWork(VideoResolveWorker.uniqueWorkName(entryId))
        downloadAssetIds.distinct().forEach { assetId ->
            workManager.cancelUniqueWork(VideoDownloadWorker.uniqueWorkName(assetId))
        }
    }
}

class AndroidEntryMediaFileStore(
    private val appContext: Context,
) : EntryMediaFileStore {
    override fun deleteFiles(entryId: Long) {
        AppMediaStore.deleteFilesForEntry(appContext, entryId)
    }
}

class AndroidPendingDeleteMediaCleanup(
    private val workCanceller: EntryWorkCanceller,
    private val fileStore: EntryMediaFileStore,
) : PendingDeleteMediaCleanup {
    override suspend fun cleanup(entryId: Long, downloadAssetIds: List<Long>) {
        workCanceller.cancel(entryId, downloadAssetIds)
        fileStore.deleteFiles(entryId)
    }

    companion object {
        fun create(context: Context, workManager: WorkManager): AndroidPendingDeleteMediaCleanup {
            return AndroidPendingDeleteMediaCleanup(
                workCanceller = WorkManagerEntryWorkCanceller(workManager),
                fileStore = AndroidEntryMediaFileStore(context.applicationContext),
            )
        }
    }
}
