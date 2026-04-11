package jp.mimac.urlsaver.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.UrlSaverApp
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.util.SystemAppClock

class FetchMetadataWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val fetcher = MetadataFetcher()

    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(MetadataWorkScheduler.KEY_ENTRY_ID, -1L)
        if (entryId <= 0L) return Result.success()

        val repository = (applicationContext as UrlSaverApp).container.repository
        val entry = repository.loadEntry(entryId) ?: return Result.success()

        return when (val outcome = fetcher.fetch(entry.openUrl)) {
            is FetchOutcome.Ready -> {
                repository.applyMetadataUpdate(
                    entryId,
                    MetadataUpdate(
                        fetchedTitle = outcome.fetchedTitle,
                        thumbnailUrl = outcome.thumbnailUrl,
                        metadataState = MetadataState.READY,
                        metadataFetchedAt = SystemAppClock.nowEpochMillis(),
                        metadataError = null,
                        canonicalId = outcome.canonicalId ?: entry.canonicalId,
                        normalizedHost = outcome.normalizedHost,
                        rawSourceHost = outcome.rawSourceHost,
                    ),
                )
                Result.success()
            }

            is FetchOutcome.Unavailable -> {
                repository.applyMetadataUpdate(
                    entryId,
                    MetadataUpdate(
                        fetchedTitle = entry.fetchedTitle,
                        thumbnailUrl = entry.thumbnailUrl,
                        metadataState = MetadataState.UNAVAILABLE,
                        metadataFetchedAt = SystemAppClock.nowEpochMillis(),
                        metadataError = outcome.error,
                        canonicalId = entry.canonicalId,
                        normalizedHost = entry.normalizedHost,
                        rawSourceHost = entry.rawSourceHost,
                    ),
                )
                Result.success()
            }

            is FetchOutcome.FailedRetryable -> {
                val shouldRetry = runAttemptCount < 2
                if (shouldRetry) {
                    Result.retry()
                } else {
                    repository.applyMetadataUpdate(
                        entryId,
                        MetadataUpdate(
                            fetchedTitle = entry.fetchedTitle,
                            thumbnailUrl = entry.thumbnailUrl,
                            metadataState = MetadataState.FAILED,
                            metadataFetchedAt = SystemAppClock.nowEpochMillis(),
                            metadataError = outcome.error,
                            canonicalId = entry.canonicalId,
                            normalizedHost = entry.normalizedHost,
                            rawSourceHost = entry.rawSourceHost,
                        ),
                    )
                    Result.success()
                }
            }
        }
    }
}
