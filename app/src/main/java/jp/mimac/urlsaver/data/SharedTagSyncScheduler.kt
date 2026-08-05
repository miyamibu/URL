package jp.mimac.urlsaver.data

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

interface SharedTagSyncScheduler {
    fun enqueue(authUserId: String)
    fun cancel(authUserId: String) = Unit

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
        val periodicRequest = PeriodicWorkRequestBuilder<jp.mimac.urlsaver.worker.SharedTagSyncWorker>(
            15,
            TimeUnit.MINUTES,
        )
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
            .build()
        workManager.enqueueUniquePeriodicWork(
            periodicWorkName(authUserId),
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest,
        )
    }

    override fun cancel(authUserId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(authUserId))
        workManager.cancelUniqueWork(periodicWorkName(authUserId))
    }

    private fun uniqueWorkName(authUserId: String): String = "shared-tag-sync:$authUserId"
    private fun periodicWorkName(authUserId: String): String = "shared-tag-sync-periodic:$authUserId"
}
