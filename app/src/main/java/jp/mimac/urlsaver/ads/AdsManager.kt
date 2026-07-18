package jp.mimac.urlsaver.ads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AdsManager {
    private val initialized = MutableStateFlow(false)

    fun initializedState(): StateFlow<Boolean> = initialized

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) {
        initialized.value = false
    }

    @Suppress("UNUSED_PARAMETER")
    fun preloadInterstitial(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun registerMeaningfulActionAndMaybeShow(context: Context) = Unit
}
