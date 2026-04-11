package jp.mimac.urlsaver.data

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import jp.mimac.urlsaver.worker.FetchMetadataWorker
import jp.mimac.urlsaver.worker.ResolveWorker
import java.util.concurrent.TimeUnit

class MetadataWorkScheduler(
    private val workManager: WorkManager,
) : MetadataScheduler {
    override fun enqueueMetadata(entryId: Long) {
        val resolve = OneTimeWorkRequestBuilder<ResolveWorker>()
            .setInputData(workDataOf(KEY_ENTRY_ID to entryId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        val fetch = OneTimeWorkRequestBuilder<FetchMetadataWorker>()
            .setInputData(workDataOf(KEY_ENTRY_ID to entryId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        workManager.beginUniqueWork(
            uniqueWorkName(entryId),
            ExistingWorkPolicy.KEEP,
            resolve,
        ).then(fetch).enqueue()
    }

    companion object {
        const val KEY_ENTRY_ID = "entry_id"
        fun uniqueWorkName(entryId: Long): String = "metadata:$entryId"
    }
}

interface MetadataScheduler {
    fun enqueueMetadata(entryId: Long)
}
