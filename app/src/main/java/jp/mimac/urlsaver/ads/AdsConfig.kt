package jp.mimac.urlsaver.ads

import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.domain.AdPolicy
import jp.mimac.urlsaver.domain.LaunchStandardPlan

object AdsConfig {
    // Trial launch switch. Set this back to true when AdMob display should return.
    private const val SHOW_ADS_FOR_TRIAL_LAUNCH = false

    private val runningUnderTest: Boolean by lazy {
        isClassAvailable("org.robolectric.RuntimeEnvironment") ||
            runCatching {
                val clazz = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
                val method = clazz.getMethod("getInstrumentation")
                method.invoke(null) != null
            }.getOrDefault(false)
    }

    val adsEnabled: Boolean = SHOW_ADS_FOR_TRIAL_LAUNCH &&
        BuildConfig.ADS_ENABLED &&
        AdPolicy.shouldShowAds(LaunchStandardPlan.entitlements) &&
        !runningUnderTest

    val appId: String = BuildConfig.ADMOB_APP_ID.trim()
    val bannerAdUnitId: String? = BuildConfig.ADMOB_BANNER_AD_UNIT_ID.trim().takeIf { it.isNotBlank() }
    val interstitialAdUnitId: String? = BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID.trim().takeIf { it.isNotBlank() }

    val canInitializeSdk: Boolean = adsEnabled
    val canLoadBanner: Boolean = adsEnabled && !bannerAdUnitId.isNullOrBlank()
    val canLoadInterstitial: Boolean = adsEnabled && !interstitialAdUnitId.isNullOrBlank()

    private fun isClassAvailable(name: String): Boolean {
        return runCatching { Class.forName(name) }.isSuccess
    }
}
