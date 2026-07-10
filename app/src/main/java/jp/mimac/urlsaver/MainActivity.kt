package jp.mimac.urlsaver

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
                val navigationBarColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp).toArgb()
                SideEffect {
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = if (darkTheme) {
                            SystemBarStyle.dark(navigationBarColor)
                        } else {
                            SystemBarStyle.light(navigationBarColor, navigationBarColor)
                        },
                    )
                    window.setBackgroundDrawable(ColorDrawable(navigationBarColor))
                    window.navigationBarColor = navigationBarColor
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                    insetsController.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insetsController.hide(WindowInsetsCompat.Type.navigationBars())
                }
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
        activityViewModel.consumeAuthCallback(intent)
    }
}
