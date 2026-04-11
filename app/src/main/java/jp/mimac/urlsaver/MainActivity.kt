package jp.mimac.urlsaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import jp.mimac.urlsaver.ui.MainActivityViewModel
import jp.mimac.urlsaver.ui.SimpleFactory
import jp.mimac.urlsaver.ui.UrlSaverRoot
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme
import jp.mimac.urlsaver.util.SystemAppClock

class MainActivity : ComponentActivity() {

    private val activityViewModel: MainActivityViewModel by viewModels {
        SimpleFactory {
            MainActivityViewModel(
                repository = (application as UrlSaverApp).container.repository,
                clock = SystemAppClock,
            )
        }
    }

    private var currentRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityViewModel.consumeShareResult(intent, currentRoute)

        setContent {
            UrlSaverTheme {
                UrlSaverRoot(
                    activityViewModel = activityViewModel,
                    onRouteChanged = { route -> currentRoute = route },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activityViewModel.consumeShareResult(intent, currentRoute)
    }

    override fun onStart() {
        super.onStart()
        activityViewModel.cleanupOnStart()
    }
}
