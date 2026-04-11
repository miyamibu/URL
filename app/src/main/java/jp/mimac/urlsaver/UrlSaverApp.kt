package jp.mimac.urlsaver

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import jp.mimac.urlsaver.app.AppContainer

class UrlSaverApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
