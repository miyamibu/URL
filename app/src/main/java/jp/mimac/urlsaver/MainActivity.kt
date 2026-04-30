package jp.mimac.urlsaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jp.mimac.urlsaver.ui.MainActivityViewModel
import jp.mimac.urlsaver.ui.SimpleFactory
import jp.mimac.urlsaver.ui.UrlSaverRoot
import jp.mimac.urlsaver.ui.theme.AppThemeMode
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme
import jp.mimac.urlsaver.util.SystemAppClock

class MainActivity : ComponentActivity() {

    private val activityViewModel: MainActivityViewModel by viewModels {
        SimpleFactory {
            MainActivityViewModel(
                repository = (application as UrlSaverApp).container.repository,
                clock = SystemAppClock,
                tagRepository = (application as UrlSaverApp).container.tagRepository,
            )
        }
    }

    private var currentRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIncomingIntent(intent)

        setContent {
            val themePreferences = remember {
                getSharedPreferences("app_theme_preferences", MODE_PRIVATE)
            }
            var themeMode by remember {
                mutableStateOf(
                    AppThemeMode.fromStorageValue(themePreferences.getString("theme_mode", null)),
                )
            }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            UrlSaverTheme(darkTheme = darkTheme) {
                UrlSaverRoot(
                    activityViewModel = activityViewModel,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        themePreferences.edit().putString("theme_mode", mode.storageValue).apply()
                    },
                    onRouteChanged = { route -> currentRoute = route },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        activityViewModel.cleanupOnStart()
        activityViewModel.enqueueForegroundSharedTagSyncIfNeeded()
    }

    fun consumeIncomingIntentForTest(intent: Intent) {
        consumeIncomingIntent(intent)
    }

    private fun consumeIncomingIntent(intent: Intent) {
        activityViewModel.consumeShareResult(intent, currentRoute)
        activityViewModel.consumeTagImportResult(intent)
        activityViewModel.consumeDeepLinkIntent(intent)
    }
}
