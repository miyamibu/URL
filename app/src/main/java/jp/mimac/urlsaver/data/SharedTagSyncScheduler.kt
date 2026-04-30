package jp.mimac.urlsaver.data

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

interface SharedTagSyncScheduler {
    fun enqueue(authUserId: String)

    companion object {
        const val KEY_AUTH_USER_ID = "shared_tag_sync_auth_user_id"
    }
}

class WorkManagerSharedTagSyncScheduler(
    private val workManager: WorkManager,
) : SharedTagSyncScheduler {
    override fun enqueue(authUserId: String) {
        val request = OneTimeWorkRequestBuilder<jp.mimac.urlsaver.worker.SharedTagSyncWorker>()
            .setInputData(
                workDataOf(
                    SharedTagSyncScheduler.KEY_AUTH_USER_ID to authUserId,
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
}
