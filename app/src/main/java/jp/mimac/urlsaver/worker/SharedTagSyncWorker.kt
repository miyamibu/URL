package jp.mimac.urlsaver.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.SharedTagSyncScheduler

class SharedTagSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val coordinator: SharedTagSyncCoordinator,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val authUserId = inputData.getString(SharedTagSyncScheduler.KEY_AUTH_USER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        return if (coordinator.syncForAuthUser(authUserId)) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
