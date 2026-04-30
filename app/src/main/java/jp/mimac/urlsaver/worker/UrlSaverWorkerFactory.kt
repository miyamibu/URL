package jp.mimac.urlsaver.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.util.AppClock

class UrlSaverWorkerFactory(
    private val repositoryProvider: () -> UrlRepository,
    private val metadataFetcherProvider: () -> MetadataFetcher,
    private val clockProvider: () -> AppClock,
    private val sharedTagSyncCoordinatorProvider: () -> SharedTagSyncCoordinator,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            FetchMetadataWorker::class.qualifiedName -> {
                FetchMetadataWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    repository = repositoryProvider(),
                    fetcher = metadataFetcherProvider(),
                    clock = clockProvider(),
                )
            }

            SharedTagSyncWorker::class.qualifiedName -> {
                SharedTagSyncWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    coordinator = sharedTagSyncCoordinatorProvider(),
                )
            }

            else -> null
        }
    }
}
