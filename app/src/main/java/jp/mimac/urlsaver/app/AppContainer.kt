package jp.mimac.urlsaver.app

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.DefaultUrlRepository
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.MetadataWorkScheduler
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.util.SystemAppClock

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)
    private val scheduler: MetadataScheduler by lazy {
        runCatching { MetadataWorkScheduler(WorkManager.getInstance(appContext)) }
            .getOrElse { error ->
                if (BuildConfig.DEBUG) {
                    throw IllegalStateException("WorkManager initialization failed; metadata scheduling is unavailable.", error)
                }
                Log.e(TAG, "WorkManager initialization failed. Metadata scheduling will report explicit failure.", error)
                UnavailableMetadataScheduler(error)
            }
    }

    val repository: UrlRepository by lazy {
        DefaultUrlRepository(
            database = database,
            dao = database.urlEntryDao(),
            clock = SystemAppClock,
            scheduler = scheduler,
        )
    }

    private class UnavailableMetadataScheduler(
        private val cause: Throwable,
    ) : MetadataScheduler {
        override fun enqueueMetadata(entryId: Long) {
            throw IllegalStateException("Metadata scheduling unavailable", cause)
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
