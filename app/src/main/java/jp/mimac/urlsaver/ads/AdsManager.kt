package jp.mimac.urlsaver.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AdsManager {
    private const val TAG = "AdsManager"
    private const val INTERSTITIAL_ACTION_THRESHOLD = 4
    private const val INTERSTITIAL_MIN_INTERVAL_MS = 2 * 60 * 1000L

    private val initialized = MutableStateFlow(false)
    private var isInitializing = false
    private var interstitialAd: InterstitialAd? = null
    private var actionCount: Int = 0
    private var lastInterstitialShownAt: Long = 0L

    fun initializedState(): StateFlow<Boolean> = initialized

    @Synchronized
    fun initialize(context: Context) {
        if (!AdsConfig.canInitializeSdk) {
            Log.i(TAG, "Ads disabled by build config. Skip MobileAds initialization.")
            initialized.value = false
            return
        }
        if (initialized.value || isInitializing) {
            return
        }

        isInitializing = true
        MobileAds.initialize(context) { status ->
            status.adapterStatusMap.forEach { (name, adapterStatus) ->
                Log.d(TAG, "Adapter $name => ${adapterStatus.initializationState} / ${adapterStatus.description}")
            }
            initialized.value = true
            isInitializing = false
            preloadInterstitial(context)
        }
    }

    fun preloadInterstitial(context: Context) {
        if (!initialized.value || !AdsConfig.canLoadInterstitial) return
        if (interstitialAd != null) return
        val adUnitId = AdsConfig.interstitialAdUnitId ?: return

        InterstitialAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    Log.d(TAG, "Interstitial load failed: ${error.code} ${error.message}")
                }
            },
        )
    }

    fun registerMeaningfulActionAndMaybeShow(context: Context) {
        if (!initialized.value || !AdsConfig.canLoadInterstitial) return

        actionCount += 1
        if (actionCount % INTERSTITIAL_ACTION_THRESHOLD != 0) {
            if (interstitialAd == null) {
                preloadInterstitial(context)
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastInterstitialShownAt < INTERSTITIAL_MIN_INTERVAL_MS) {
            if (interstitialAd == null) {
                preloadInterstitial(context)
            }
            return
        }

        val activity = context.findActivity()
        val ad = interstitialAd
        if (activity == null || ad == null) {
            preloadInterstitial(context)
            return
        }

        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                lastInterstitialShownAt = System.currentTimeMillis()
                preloadInterstitial(context)
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                Log.d(TAG, "Interstitial show failed: ${error.code} ${error.message}")
                preloadInterstitial(context)
            }
        }
        ad.show(activity)
    }

    private tailrec fun Context.findActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
