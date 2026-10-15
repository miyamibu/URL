package jp.mimac.urlsaver

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import jp.mimac.urlsaver.domain.BillingPeriod
import jp.mimac.urlsaver.domain.LinkedIdentityProvider
import jp.mimac.urlsaver.domain.LinkedIdentityProviderState
import jp.mimac.urlsaver.domain.LinkedIdentityProvidersSnapshot
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.ui.ChatGptAuthProviderButton
import jp.mimac.urlsaver.ui.LinkedIdentityProviderAccountRow
import jp.mimac.urlsaver.ui.PaidCourseSection
import jp.mimac.urlsaver.ui.ProfileAccountAndShareCard
import jp.mimac.urlsaver.ui.theme.UrlSaverTheme

class RemainingStatesVisualReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
        setContent {
            UrlSaverTheme {
                RemainingStatesVisualReviewScreen(onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemainingStatesVisualReviewScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("プロフィール") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("UI検証用の固定fixture", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "認証・ネットワーク・購入処理はありません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ProfileAccountAndShareCard(
                    cloudState = SharedTagCloudState(isConfigured = true, isSignedIn = true),
                    expanded = true,
                    accountMessage = null,
                    onToggleAccountDetails = {},
                    emailExpanded = false,
                    onToggleEmail = {},
                    emailDetails = {},
                    accountManagementExpanded = false,
                    onToggleAccountManagement = {},
                    accountManagementDetails = {},
                    planDetailsExpanded = true,
                    onTogglePlanDetails = {},
                    accountDetails = {
                        val linkedState = remember {
                            LinkedIdentityProviderState.Verified(
                                LinkedIdentityProvidersSnapshot(
                                    authUserId = "ui-review-fixture",
                                    providers = setOf(LinkedIdentityProvider.GOOGLE, LinkedIdentityProvider.APPLE),
                                    verifiedAtEpochMillis = 0L,
                                ),
                            )
                        }
                        listOf(LinkedIdentityProvider.GOOGLE, LinkedIdentityProvider.APPLE).forEach { provider ->
                            LinkedIdentityProviderAccountRow(
                                provider = provider,
                                state = linkedState,
                                isSubmitting = false,
                                onLink = {},
                            )
                        }
                        ChatGptAuthProviderButton(testTag = "auth_link_chatgpt_ui_review")
                    },
                    planDetails = {
                        PaidCourseSection(
                            isEnabled = true,
                            isPurchasing = false,
                            showMonthlyOptions = true,
                            onPurchase = { _: PlanType, _: BillingPeriod -> },
                        )
                    },
                    showChatGptContent = false,
                    chatGptContent = {},
                )
            }
        }
    }
}
