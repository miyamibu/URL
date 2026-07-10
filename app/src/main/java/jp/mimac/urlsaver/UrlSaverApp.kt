package jp.mimac.urlsaver

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import jp.mimac.urlsaver.ads.AdsManager
import jp.mimac.urlsaver.app.AppContainer

class UrlSaverApp : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .setWorkerFactory(container.workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        container
        AdsManager.initialize(this)
    }
}
