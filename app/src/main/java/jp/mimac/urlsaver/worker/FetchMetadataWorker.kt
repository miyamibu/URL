package jp.mimac.urlsaver.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.util.AppClock

class FetchMetadataWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val repository: UrlRepository,
    private val fetcher: MetadataFetcher,
    private val clock: AppClock,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(MetadataWorkScheduler.KEY_ENTRY_ID, -1L)
        if (entryId <= 0L) return Result.success()

        val entry = repository.loadEntry(entryId) ?: return Result.success()
        val canonicalFromUrl = if (entry.serviceType == ServiceType.X) {
            UrlRules.extractXStatusId(entry.openUrl)
        } else {
            null
        }
        val preservedCanonicalId = entry.canonicalId ?: canonicalFromUrl
        val fetchUrls = UrlRules.metadataFetchUrls(entry.openUrl, entry.serviceType)
        val outcome = resolveFetchOutcome(fetchUrls) { url -> fetcher.fetch(url) }

        return when (outcome) {
            is FetchOutcome.Ready -> {
                val resolvedNormalizedHost = if (entry.serviceType == ServiceType.X) {
                    entry.normalizedHost
                } else {
                    outcome.normalizedHost ?: entry.normalizedHost
                }
                val resolvedRawSourceHost = if (entry.serviceType == ServiceType.X) {
                    entry.rawSourceHost
                } else {
                    outcome.rawSourceHost ?: entry.rawSourceHost
                }
                repository.applyMetadataUpdate(
                    entryId,
                    MetadataUpdate(
                        fetchedTitle = outcome.fetchedTitle,
                        fetchedBody = outcome.fetchedBody ?: entry.fetchedBody,
                        fetchedBodyKind = outcome.fetchedBodyKind ?: entry.fetchedBodyKind,
                        bodySummary = outcome.bodySummary ?: entry.bodySummary,
                        description = outcome.description ?: entry.description,
                        thumbnailUrl = outcome.thumbnailUrl,
                        badgeImageUrl = outcome.badgeImageUrl ?: entry.badgeImageUrl,
                        metadataState = MetadataState.READY,
                        metadataFetchedAt = clock.nowEpochMillis(),
                        metadataError = null,
                        canonicalId = outcome.canonicalId ?: preservedCanonicalId,
                        normalizedHost = resolvedNormalizedHost,
                        rawSourceHost = resolvedRawSourceHost,
                    ),
                )
                Result.success()
            }

            is FetchOutcome.Unavailable -> {
                repository.applyMetadataUpdate(
                    entryId,
                    MetadataUpdate(
                        fetchedTitle = entry.fetchedTitle,
                        fetchedBody = entry.fetchedBody,
                        fetchedBodyKind = entry.fetchedBodyKind,
                        bodySummary = entry.bodySummary,
                        description = entry.description,
                        thumbnailUrl = entry.thumbnailUrl,
                        badgeImageUrl = entry.badgeImageUrl,
                        metadataState = MetadataState.UNAVAILABLE,
                        metadataFetchedAt = clock.nowEpochMillis(),
                        metadataError = outcome.error,
                        canonicalId = preservedCanonicalId,
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
                            fetchedBody = entry.fetchedBody,
                            fetchedBodyKind = entry.fetchedBodyKind,
                            bodySummary = entry.bodySummary,
                            description = entry.description,
                            thumbnailUrl = entry.thumbnailUrl,
                            badgeImageUrl = entry.badgeImageUrl,
                            metadataState = MetadataState.FAILED,
                            metadataFetchedAt = clock.nowEpochMillis(),
                            metadataError = outcome.error,
                            canonicalId = preservedCanonicalId,
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

internal fun resolveFetchOutcome(
    fetchUrls: List<String>,
    fetch: (String) -> FetchOutcome,
): FetchOutcome {
    var retryableOutcome: FetchOutcome.FailedRetryable? = null
    var unavailableOutcome: FetchOutcome.Unavailable? = null

    for (url in fetchUrls) {
        when (val outcome = fetch(url)) {
            is FetchOutcome.Ready -> return outcome
            is FetchOutcome.FailedRetryable -> {
                if (retryableOutcome == null) {
                    retryableOutcome = outcome
                }
            }
            is FetchOutcome.Unavailable -> {
                unavailableOutcome = outcome
            }
        }
    }

    return unavailableOutcome ?: retryableOutcome ?: FetchOutcome.Unavailable(
        error = MetadataError.PARSE_FAILED,
    )
}
