package jp.mimac.urlsaver.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.UrlEntryDao
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.data.VideoAssetDao
import jp.mimac.urlsaver.data.VideoDownloadDao
import jp.mimac.urlsaver.util.AppClock
import jp.mimac.urlsaver.video.VideoDownloadWorker
import jp.mimac.urlsaver.video.VideoResolveWorker
import jp.mimac.urlsaver.video.VideoResolver

class UrlSaverWorkerFactory(
    private val repositoryProvider: () -> UrlRepository,
    private val metadataFetcherProvider: () -> MetadataFetcher,
    private val clockProvider: () -> AppClock,
    private val sharedTagSyncCoordinatorProvider: () -> SharedTagSyncCoordinator,
    private val urlEntryDaoProvider: () -> UrlEntryDao,
    private val videoAssetDaoProvider: () -> VideoAssetDao,
    private val videoDownloadDaoProvider: () -> VideoDownloadDao,
    private val videoResolverProvider: () -> VideoResolver,
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

            VideoResolveWorker::class.qualifiedName -> {
                VideoResolveWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    urlEntryDao = urlEntryDaoProvider(),
                    videoAssetDao = videoAssetDaoProvider(),
                    resolver = videoResolverProvider(),
                    clock = clockProvider(),
                )
            }

            VideoDownloadWorker::class.qualifiedName -> {
                VideoDownloadWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    urlEntryDao = urlEntryDaoProvider(),
                    videoAssetDao = videoAssetDaoProvider(),
                    videoDownloadDao = videoDownloadDaoProvider(),
                    clock = clockProvider(),
                )
            }

            else -> null
        }
    }
}
