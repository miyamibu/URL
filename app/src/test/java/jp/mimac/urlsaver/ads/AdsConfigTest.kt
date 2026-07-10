package jp.mimac.urlsaver.ads

import jp.mimac.urlsaver.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Test

class AdsConfigTest {
    @Test
    fun trialLaunchGateKeepsAdsDisabled() {
        assertFalse(BuildConfig.ADS_TRIAL_LAUNCH_ADS_ENABLED)
        assertFalse(AdsConfig.adsEnabled)
        assertFalse(AdsConfig.canInitializeSdk)
        assertFalse(AdsConfig.canLoadBanner)
        assertFalse(AdsConfig.canLoadInterstitial)
    }
}
