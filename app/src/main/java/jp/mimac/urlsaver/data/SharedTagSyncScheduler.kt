package jp.mimac.urlsaver.data

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class SharedTagSyncScheduler(
    private val workManager: WorkManager,
) {
    fun enqueue(authUserId: String) {
        val request = OneTimeWorkRequestBuilder<jp.mimac.urlsaver.worker.SharedTagSyncWorker>()
            .setInputData(
                workDataOf(
                    KEY_AUTH_USER_ID to authUserId,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(authUserId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun uniqueWorkName(authUserId: String): String = "shared-tag-sync:$authUserId"

    companion object {
        const val KEY_AUTH_USER_ID = "shared_tag_sync_auth_user_id"
    }
}
