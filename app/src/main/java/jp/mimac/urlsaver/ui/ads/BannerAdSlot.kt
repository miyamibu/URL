package jp.mimac.urlsaver.ui.ads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import jp.mimac.urlsaver.ads.AdsConfig
import jp.mimac.urlsaver.ads.AdsManager

@Composable
fun BannerAdSlot(
    modifier: Modifier = Modifier,
) {
    if (!AdsConfig.canLoadBanner) {
        return
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val initialized by AdsManager.initializedState().collectAsStateWithLifecycle()

    val adUnitId = AdsConfig.bannerAdUnitId ?: return
    val adWidthDp = (configuration.screenWidthDp - 32).coerceAtLeast(320)

    if (!initialized) {
        Spacer(modifier = modifier.fillMaxWidth().height(52.dp))
        return
    }

    key(adUnitId, adWidthDp) {
        val adView = remember(adUnitId, adWidthDp) {
            AdView(context).apply {
                this.adUnitId = adUnitId
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp))
                loadAd(AdRequest.Builder().build())
            }
        }

        DisposableEffect(adView) {
            onDispose {
                adView.destroy()
            }
        }

        Box(modifier = modifier.fillMaxWidth()) {
            AndroidView(
                factory = { adView },
                update = { view ->
                    if (view.adUnitId != adUnitId) {
                        view.adUnitId = adUnitId
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
